package au.edu.unimelb.floraguide.data.firebase

import au.edu.unimelb.floraguide.domain.repository.AuthRepository
import au.edu.unimelb.floraguide.domain.repository.AuthState
import au.edu.unimelb.floraguide.domain.repository.UserProfile
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class FirebaseAuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) : AuthRepository {
    private val state = MutableStateFlow<AuthState>(currentState())
    override val authState: StateFlow<AuthState> = state.asStateFlow()
    private val operations = Mutex()

    init {
        auth.addAuthStateListener {
            // Keep explicit local-only access across redundant signed-out callbacks.
            if (it.currentUser != null || state.value != AuthState.OfflineGuest) state.value = currentState()
        }
    }

    override fun getCurrentUser(): UserProfile? = auth.currentUser?.toDomain()

    override suspend fun signInAnonymously(): Result<UserProfile> = authenticate {
        check(auth.currentUser?.isAnonymous != false) { "Sign out before starting a cloud guest session." }
        auth.currentUser ?: requireNotNull(auth.signInAnonymously().await().user)
    }

    override suspend fun signInWithEmail(email: String, pass: String): Result<UserProfile> = authenticate {
        require(email.isNotBlank() && pass.isNotBlank()) { "Enter your email address and password." }
        requireNotNull(auth.signInWithEmailAndPassword(email.trim(), pass).await().user)
    }

    override suspend fun registerWithEmail(email: String, pass: String, displayName: String): Result<UserProfile> = authenticate {
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
    }

    override suspend fun signOut() = operations.withLock {
        auth.signOut()
        state.value = AuthState.Unauthenticated
    }

    override fun getSessionLogs(): List<String> = emptyList() // Do not retain account identifiers or emails in logs.

    private suspend fun authenticate(action: suspend () -> FirebaseUser): Result<UserProfile> = operations.withLock {
        val before = state.value
        state.value = AuthState.Authenticating
        try {
            val user = action().toDomain()
            state.value = AuthState.Authenticated(user)
            Result.success(user)
        } catch (cancelled: CancellationException) {
            state.value = if (auth.currentUser == null && before == AuthState.OfflineGuest) before else currentState()
            throw cancelled
        } catch (error: Exception) {
            state.value = auth.currentUser?.let { AuthState.Authenticated(it.toDomain()) }
                ?: AuthState.Error(error.localizedMessage ?: "Authentication failed.")
            Result.failure(error)
        }
    }

    private fun currentState(): AuthState = auth.currentUser?.let { AuthState.Authenticated(it.toDomain()) }
        ?: AuthState.Unauthenticated

    private fun FirebaseUser.toDomain() = UserProfile(uid, email, displayName, isAnonymous)
}
