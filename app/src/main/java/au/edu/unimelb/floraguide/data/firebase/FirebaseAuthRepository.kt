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

class FirebaseAuthRepository(
    private val context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val logger: AuthSessionLogger = AuthSessionLogger(context)
) : AuthRepository {
    private val state = MutableStateFlow<AuthState>(currentState())
    override val authState: StateFlow<AuthState> = state.asStateFlow()
    private val operations = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val currentUser = firebaseAuth.currentUser
            if (currentUser != null || state.value != AuthState.OfflineGuest) {
                val newState = currentState()
                state.value = newState
                scope.launch {
                    if (currentUser != null) {
                        logger.logEvent(
                            eventType = "SESSION_RESTORED",
                            status = "SUCCESS",
                            userUid = currentUser.uid,
                            detail = "Auth state listener synced user (Anonymous: ${currentUser.isAnonymous})"
                        )
                    }
                }
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
        try {
            user.delete().await()
            state.value = AuthState.Unauthenticated
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.value = auth.currentUser?.let { AuthState.Authenticated(it.toDomain()) }
                ?: AuthState.Error(error.localizedMessage ?: "Account deletion failed.")
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
            val failMsg = error.localizedMessage ?: "Authentication failed."
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

    private fun FirebaseUser.toDomain() = UserProfile(uid, email, displayName, isAnonymous)
}
