package au.edu.unimelb.floraguide.domain.repository

import kotlinx.coroutines.flow.StateFlow

data class UserProfile(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val isAnonymous: Boolean
)

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Authenticating : AuthState()
    object OfflineGuest : AuthState()
    data class Authenticated(val user: UserProfile) : AuthState()
    data class Error(val message: String) : AuthState()
}

interface AuthRepository {
    val authState: StateFlow<AuthState>
    fun getCurrentUser(): UserProfile?
    suspend fun signInAnonymously(): Result<UserProfile>
    suspend fun signInWithEmail(email: String, pass: String): Result<UserProfile>
    suspend fun registerWithEmail(email: String, pass: String, displayName: String): Result<UserProfile>
    suspend fun signOut()
    suspend fun continueOffline()
    fun getSessionLogs(): List<String>
}
