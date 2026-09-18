package au.edu.unimelb.floraguide.data.firebase

import android.content.Context
import android.util.Log
import au.edu.unimelb.floraguide.domain.repository.AuthRepository
import au.edu.unimelb.floraguide.domain.repository.AuthState
import au.edu.unimelb.floraguide.domain.repository.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant

private const val TAG = "FloraGuide-Auth"

class FirebaseAuthRepository(
    private val context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val sessionLogs = mutableListOf<String>()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                val profile = user.toDomain()
                _authState.value = AuthState.Authenticated(profile)
                logSession("AUTH_STATE_CHANGED: Signed in as ${user.uid} (${if (user.isAnonymous) "Anonymous" else user.email})")
            } else {
                _authState.value = AuthState.Unauthenticated
                logSession("AUTH_STATE_CHANGED: Signed out")
            }
        }
    }

    override fun getCurrentUser(): UserProfile? = auth.currentUser?.toDomain()

    override suspend fun signInAnonymously(): Result<UserProfile> = runCatching {
        _authState.value = AuthState.Authenticating
        logSession("ATTEMPT: Anonymous Sign-In")
        val result = auth.signInAnonymously().await()
        val user = result.user ?: throw Exception("Firebase anonymous auth returned null user.")
        logSession("SUCCESS: Anonymous Sign-In (UID: ${user.uid})")
        user.toDomain()
    }.onFailure { logFailure("Anonymous Sign-In Failed", it) }

    override suspend fun signInWithEmail(email: String, pass: String): Result<UserProfile> = runCatching {
        _authState.value = AuthState.Authenticating
        logSession("ATTEMPT: Email Sign-In for $email")
        val result = auth.signInWithEmailAndPassword(email, pass).await()
        val user = result.user ?: throw Exception("Firebase Auth returned null user.")
        logSession("SUCCESS: Email Sign-In (UID: ${user.uid})")
        user.toDomain()
    }.onFailure { logFailure("Email Sign-In Failed", it) }

    override suspend fun registerWithEmail(email: String, pass: String, displayName: String): Result<UserProfile> = runCatching {
        _authState.value = AuthState.Authenticating
        logSession("ATTEMPT: Registration for $email")
        val result = auth.createUserWithEmailAndPassword(email, pass).await()
        val user = result.user ?: throw Exception("Firebase Registration returned null user.")

        val profileUpdates = UserProfileChangeRequest.Builder()
            .setDisplayName(displayName)
            .build()
        user.updateProfile(profileUpdates).await()

        logSession("SUCCESS: User Registered (UID: ${user.uid})")
        user.toDomain()
    }.onFailure { logFailure("Registration Failed", it) }

    override suspend fun signOut() {
        val uid = auth.currentUser?.uid
        auth.signOut()
        logSession("SUCCESS: Signed Out user $uid")
        _authState.value = AuthState.Unauthenticated
    }

    override fun getSessionLogs(): List<String> = sessionLogs.toList()

    private fun logSession(message: String) {
        val logEntry = "[${Instant.now()}] $message"
        sessionLogs.add(logEntry)
        Log.i(TAG, logEntry)
    }

    private fun logFailure(action: String, error: Throwable) {
        val logEntry = "[${Instant.now()}] FAILURE: $action - ${error.localizedMessage}"
        sessionLogs.add(logEntry)
        Log.e(TAG, logEntry, error)
        _authState.value = AuthState.Error(error.localizedMessage ?: action)
    }

    private fun FirebaseUser.toDomain() = UserProfile(
        uid = uid,
        email = email,
        displayName = displayName,
        isAnonymous = isAnonymous
    )
}
