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


    override suspend fun deleteAccount(): Result<Unit> = operations.withLock {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("No authenticated user to delete."))
        val uid = user.uid

        try {
            // 1. Pre-flight check: Ensure recent authentication to prevent late-stage rejection
            val token = user.getIdToken(false).await()
            val authTime = (token.claims["auth_time"] as? Number)?.toLong() ?: 0L
            val now = System.currentTimeMillis() / 1000L

            // Firebase classifies sessions older than 5 minutes as stale for sensitive operations
            if (now - authTime > 300L) {
                throw com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException(
                    "ERROR_REQUIRES_RECENT_LOGIN",
                    "Account deletion requires a recent login. Please sign out and sign in again before retrying."
                )
            }

            state.value = AuthState.Authenticating

            // 2. Coordinate concurrent processes: Halt sync and pending uploads
            workManager.cancelUniqueWork("observation-sync-$uid")
            workManager.cancelUniqueWork("pending-scan-cleanup-$uid")

            val firestore = FirebaseFirestore.getInstance()
            val storage = FirebaseStorage.getInstance()

            // 3. Purge all account-owned cloud objects (including orphan photos)
            // This is a multi-step idempotent cascade, not an atomic transaction.
            val storageRef = storage.reference.child("plant_photos/$uid")
            try {
                val items = storageRef.listAll().await().items
                for (item in items) {
                    try {
                        item.delete().await()
                    } catch (e: com.google.firebase.storage.StorageException) {
                        if (!isMissingStorageObject(e.errorCode)) throw e
                    }
                }
            } catch (e: com.google.firebase.storage.StorageException) {
                if (!isMissingStorageObject(e.errorCode)) throw e
            }

            // 4. Purge server observation documents
            val observationsRef = firestore.collection("users").document(uid).collection("observations")
            val snapshot = observationsRef.get(Source.SERVER).await()
            for (doc in snapshot.documents) {
                try {
                    doc.reference.delete().await()
                } catch (e: Exception) {
                    // Ignore missing documents on retry
                }
            }

            // 5. Purge the user's root Firestore document
            try {
                firestore.collection("users").document(uid).delete().await()
            } catch (e: Exception) {
                // Ignore missing root document on retry
            }

            // 6. Local cleanup: Purge account-owned local records and diagnostics
            val dao = observationDao ?: FloraGuideDatabase.getInstance(context).observationDao()
            dao.deleteAllForUser(uid)
            logger.clearLogs()

            // 7. Finally, remove the Auth account credentials
            user.delete().await()

            state.value = AuthState.Unauthenticated
            logger.logEvent("DELETE_ACCOUNT", "SUCCESS", uid, "Account and associated data completely removed.")
            Result.success(Unit)

        } catch (cancelled: CancellationException) {
            state.value = currentState()
            throw cancelled
        } catch (error: Exception) {
            val failMsg = sanitizeAuthError(error)
            state.value = auth.currentUser?.let { AuthState.Authenticated(it.toDomain()) }
                ?: AuthState.Error(failMsg)
            logger.logEvent("DELETE_ACCOUNT", "FAILURE", uid, failMsg)
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
}
