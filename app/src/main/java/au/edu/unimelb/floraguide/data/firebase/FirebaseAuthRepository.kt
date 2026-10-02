package au.edu.unimelb.floraguide.data.firebase

import android.content.Context
import au.edu.unimelb.floraguide.domain.repository.AuthRepository
import au.edu.unimelb.floraguide.domain.repository.AuthState
import au.edu.unimelb.floraguide.domain.repository.UserProfile
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import androidx.work.WorkManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import au.edu.unimelb.floraguide.data.local.FloraGuideDatabase
import au.edu.unimelb.floraguide.data.local.ObservationDao

class FirebaseAuthRepository(
    private val context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val logger: AuthSessionLogger = AuthSessionLogger(context),
    private val workManager: WorkManager = WorkManager.getInstance(context),
    private val observationDao: ObservationDao? = null
) : AuthRepository {
    private val state = MutableStateFlow<AuthState>(currentState())
    override val authState: StateFlow<AuthState> = state.asStateFlow()
    private val operations = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isInitialVerification = true // Track the cold-start check

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val currentUser = firebaseAuth.currentUser
            if (currentUser != null || state.value != AuthState.OfflineGuest) {
                state.value = currentState()
            }
            val restoredSession = isInitialVerification && currentUser != null
            isInitialVerification = false

            if (restoredSession) scope.launch {
                // Only log SESSION_RESTORED if this is the very first check upon app launch
                logger.logEvent(
                    eventType = "SESSION_RESTORED",
                    status = "SUCCESS",
                    userUid = currentUser.uid,
                    detail = "Cold-start session reconciliation synced user."
                )
            }
        }
    }


    override fun getCurrentUser(): UserProfile? = auth.currentUser?.toDomain()

    override suspend fun signInAnonymously(): Result<UserProfile> = authenticate("ANONYMOUS_SIGN_IN") {
        check(auth.currentUser?.isAnonymous != false) { "Sign out before starting a cloud guest session." }
        val user = auth.currentUser ?: requireNotNull(auth.signInAnonymously().await().user)
        user
    }

    override suspend fun signInWithEmail(email: String, pass: String): Result<UserProfile> = authenticate("EMAIL_SIGN_IN") {
        require(email.isNotBlank() && pass.isNotBlank()) { "Enter your email address and password." }
        requireNotNull(auth.signInWithEmailAndPassword(email.trim(), pass).await().user)
    }

    override suspend fun registerWithEmail(email: String, pass: String, displayName: String): Result<UserProfile> = authenticate("EMAIL_REGISTER") {
        require(email.isNotBlank() && pass.length >= 6 && displayName.isNotBlank()) {
            "Enter a name, email address and a password of at least six characters."
        }
        val guest = auth.currentUser?.takeIf { it.isAnonymous }
        val result = if (guest != null) {
            guest.linkWithCredential(EmailAuthProvider.getCredential(email.trim(), pass)).await()
        } else {
            auth.createUserWithEmailAndPassword(email.trim(), pass).await()
        }
        val user = requireNotNull(result.user)
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(displayName.trim()).build()).await()
        user
    }

    override suspend fun continueOffline() = operations.withLock {
        check(auth.currentUser == null) { "Sign out before starting a local guest session." }
        state.value = AuthState.OfflineGuest
        logger.logEvent(
            eventType = "OFFLINE_GUEST",
            status = "SUCCESS",
            detail = "Local offline guest mode activated."
        )
    }

    override suspend fun signOut() = operations.withLock {
        val prevUid = auth.currentUser?.uid
        auth.signOut()
        state.value = AuthState.Unauthenticated
        logger.logEvent(
            eventType = "SIGN_OUT",
            status = "SUCCESS",
            userUid = prevUid,
            detail = "User explicitly signed out."
        )
    }


    // Global lock to prevent background sync or in-flight uploads from writing data during/after erasure
    private val isAccountErasureActive = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isErasureActive(): Boolean = isAccountErasureActive.get()

    override suspend fun deleteAccount(): Result<Unit> = operations.withLock {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("No authenticated user to delete."))
        val uid = user.uid

        // Persistent erasure state tracker in SharedPreferences
        val erasurePrefs = context.getSharedPreferences("floraguide_erasure_state", Context.MODE_PRIVATE)

        try {
            // 1. Pre-flight reauthentication validation
            val token = user.getIdToken(false).await()
            val authTime = (token.claims["auth_time"] as? Number)?.toLong() ?: 0L
            val now = System.currentTimeMillis() / 1000L
            if (now - authTime > 300L) {
                throw com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException(
                    "ERROR_REQUIRES_RECENT_LOGIN",
                    "Account deletion requires a recent login. Please sign out and sign in again before retrying."
                )
            }

            state.value = AuthState.Authenticating
            isAccountErasureActive.set(true)
            erasurePrefs.edit().putString("erasure_status", "IN_PROGRESS").putString("erasure_uid", uid).apply()

            // 2. Coordinate observation listeners, in-flight uploads, and pending WorkManager jobs
            workManager.cancelUniqueWork("observation-sync-$uid")
            workManager.cancelUniqueWork("pending-scan-cleanup-$uid")

            val firestore = FirebaseFirestore.getInstance()
            val storage = FirebaseStorage.getInstance()

            // 3. Define complete account-erasure inventory & ownership verification boundaries
            val expectedPrefixPath = "plant_photos/$uid/"
            val storageRef = storage.reference.child("plant_photos/$uid")

            // Sweep all storage objects (covering orphan photos and non-observation media)
            val listResult = storageRef.listAll().await()
            for (item in listResult.items) {
                val normalizedPath = item.path.trimStart('/')
                require(normalizedPath.startsWith(expectedPrefixPath)) {
                    "Ownership boundary violation: Attempted to purge unowned storage object at ${item.path}"
                }
                try {
                    item.delete().await()
                } catch (e: StorageException) {
                    if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND && e.errorCode != 404) {
                        throw e
                    }
                }
            }
            // Also handle nested prefixes if any
            for (prefix in listResult.prefixes) {
                val nestedList = prefix.listAll().await()
                for (item in nestedList.items) {
                    val normalizedPath = item.path.trimStart('/')
                    require(normalizedPath.startsWith(expectedPrefixPath)) {
                        "Ownership boundary violation: Attempted to purge unowned nested storage object."
                    }
                    try {
                        item.delete().await()
                    } catch (e: StorageException) {
                        if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND && e.errorCode != 404) {
                            throw e
                        }
                    }
                }
            }

            // 4. Enumerate and verify observation documents ownership
            val observationsRef = firestore.collection("users").document(uid).collection("observations")
            val snapshot = observationsRef.get(Source.SERVER).await()
            for (doc in snapshot.documents) {
                val docUserId = doc.getString("userId") ?: uid
                require(docUserId == uid) {
                    "Ownership boundary violation: Firestore observation document does not belong to target user."
                }
                doc.reference.delete().await()
            }

            // Purge root user document
            firestore.collection("users").document(uid).delete().await()

            // 5. Clean up account-owned local records, photos, and diagnostics
            val effectiveDao = observationDao ?: FloraGuideDatabase.getInstance(context).observationDao()
            effectiveDao.deleteAllForUser(uid)
            AuthSessionLogger(context).clearLogs()

            // 6. Verify that NO owned data remains before concluding erasure
            val remainingObs = effectiveDao.getAllForUser(uid)
            check(remainingObs.isEmpty()) { "Erasure incomplete: Local observations remain." }

            // 7. Delete Auth credentials
            user.delete().await()

            // Mark persistent erasure state as fully complete
            erasurePrefs.edit().putString("erasure_status", "COMPLETE").remove("erasure_uid").apply()
            isAccountErasureActive.set(false)

            logger.logEvent(
                eventType = "DELETE_ACCOUNT",
                status = "SUCCESS",
                userUid = uid,
                detail = "Account erasure completed successfully."
            )

            state.value = AuthState.Unauthenticated
            Result.success(Unit)

        } catch (cancelled: CancellationException) {
            isAccountErasureActive.set(false)
            erasurePrefs.edit().putString("erasure_status", "INTERRUPTED").apply()
            state.value = currentState()
            throw cancelled
        } catch (error: Exception) {
            isAccountErasureActive.set(false)
            erasurePrefs.edit().putString("erasure_status", "INTERRUPTED").apply()
            val failMsg = error.localizedMessage ?: "Account deletion failed."
            logger.logEvent(
                eventType = "DELETE_ACCOUNT",
                status = "FAILURE",
                userUid = uid,
                detail = failMsg
            )
            state.value = auth.currentUser?.let { AuthState.Authenticated(it.toDomain()) } ?: AuthState.Error(failMsg)
            Result.failure(error)
        }
    }

    override fun getSessionLogs(): List<String> = kotlinx.coroutines.runBlocking {
        logger.getLogs()
    }

    private suspend fun authenticate(
        actionName: String,
        action: suspend () -> FirebaseUser
    ): Result<UserProfile> = operations.withLock {
        val before = state.value
        state.value = AuthState.Authenticating
        try {
            val user = action().toDomain()
            state.value = AuthState.Authenticated(user)
            logger.logEvent(
                eventType = actionName,
                status = "SUCCESS",
                userUid = user.uid,
                detail = "Authentication succeeded."
            )
            Result.success(user)
        } catch (cancelled: CancellationException) {
            state.value = if (auth.currentUser == null && before == AuthState.OfflineGuest) before else currentState()
            logger.logEvent(
                eventType = actionName,
                status = "CANCELLED",
                detail = "Authentication cancelled by user navigation."
            )
            throw cancelled
        } catch (error: Exception) {
            val failMsg = sanitizeAuthError(error)
            state.value = auth.currentUser?.let { AuthState.Authenticated(it.toDomain()) }
                ?: AuthState.Error(failMsg)
            logger.logEvent(
                eventType = actionName,
                status = "FAILURE",
                userUid = auth.currentUser?.uid,
                detail = failMsg
            )
            Result.failure(error)
        }
    }

    private fun currentState(): AuthState = auth.currentUser?.let { AuthState.Authenticated(it.toDomain()) }
        ?: AuthState.Unauthenticated

    // UI needs the real identity; only AuthSessionLogger hashes persisted identifiers.
    private fun FirebaseUser.toDomain() = UserProfile(
        uid = uid,
        email = email, // Pass to domain for UI, but DO NOT log this object raw
        displayName = displayName,
        isAnonymous = isAnonymous
    )

    // If capturing Firebase Exceptions for logs, sanitize the message:
    private fun sanitizeAuthError(error: Exception): String {
        val rawMessage = error.localizedMessage ?: "Unknown auth error"
        // Regex to strip out email addresses from Firebase exception messages
        return rawMessage.replace(Regex("[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "[REDACTED_EMAIL]")
    }

    companion object {
        @Volatile private var globalErasureActive = false

        fun isErasureActive(context: Context): Boolean {
            if (globalErasureActive) return true
            val prefs = context.getSharedPreferences("floraguide_erasure_state", Context.MODE_PRIVATE)
            return prefs.getString("erasure_status", null) == "IN_PROGRESS"
        }
    }
}
