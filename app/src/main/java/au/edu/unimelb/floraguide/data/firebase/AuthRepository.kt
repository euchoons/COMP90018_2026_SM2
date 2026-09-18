//package au.edu.unimelb.floraguide.data.firebase
//
//import android.util.Log
//import com.google.firebase.auth.FirebaseAuth
//import com.google.firebase.auth.FirebaseAuthException
//import com.google.firebase.auth.FirebaseUser
//import kotlinx.coroutines.channels.awaitClose
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.callbackFlow
//import kotlinx.coroutines.tasks.await
//
//sealed interface AuthResult {
//    data class Success(val user: FirebaseUser) : AuthResult
//    data class Failure(val errorCode: String, val message: String, val cause: Throwable?) : AuthResult
//}
//
//class AuthRepository(
//    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
//) {
//    private val tag = "FloraGuide-Auth"
//
//    val currentUser: FirebaseUser? get() = auth.currentUser
//
//    val authStateFlow: Flow<FirebaseUser?> = callbackFlow {
//        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
//            trySend(firebaseAuth.currentUser)
//        }
//        auth.addAuthStateListener(listener)
//        awaitClose { auth.removeAuthStateListener(listener) }
//    }
//
//    suspend fun registerWithEmail(email: String, pass: String): AuthResult = try {
//        Log.i(tag, "Attempting user registration for email=$email")
//        val result = auth.createUserWithEmailAndPassword(email, pass).await()
//        val user = result.user ?: throw IllegalStateException("Registration succeeded but user object was null.")
//        Log.i(tag, "Registration successful uid=${user.uid}")
//        AuthResult.Success(user)
//    } catch (e: FirebaseAuthException) {
//        Log.e(tag, "Registration failed code=${e.errorCode} message=${e.message}", e)
//        AuthResult.Failure(e.errorCode, mapAuthError(e), e)
//    } catch (e: Exception) {
//        Log.e(tag, "Registration failed with unexpected error", e)
//        AuthResult.Failure("UNKNOWN_ERROR", e.message ?: "An unexpected error occurred during registration.", e)
//    }
//
//    suspend fun signInWithEmail(email: String, pass: String): AuthResult = try {
//        Log.i(tag, "Attempting sign-in for email=$email")
//        val result = auth.signInWithEmailAndPassword(email, pass).await()
//        val user = result.user ?: throw IllegalStateException("Sign-in succeeded but user object was null.")
//        Log.i(tag, "Sign-in successful uid=${user.uid}")
//        AuthResult.Success(user)
//    } catch (e: FirebaseAuthException) {
//        Log.e(tag, "Sign-in failed code=${e.errorCode} message=${e.message}", e)
//        AuthResult.Failure(e.errorCode, mapAuthError(e), e)
//    } catch (e: Exception) {
//        Log.e(tag, "Sign-in failed with unexpected error", e)
//        AuthResult.Failure("UNKNOWN_ERROR", e.message ?: "An unexpected error occurred during sign-in.", e)
//    }
//
//    fun signOut() {
//        Log.i(tag, "Signing out current user uid=${auth.currentUser?.uid}")
//        auth.signOut()
//    }
//
//    private fun mapAuthError(e: FirebaseAuthException): String = when (e.errorCode) {
//        "ERROR_INVALID_EMAIL" -> "The email address is badly formatted."
//        "ERROR_WRONG_PASSWORD" -> "The password is invalid or the user does not have a password."
//        "ERROR_USER_NOT_FOUND" -> "No user record corresponding to this identifier exists."
//        "ERROR_EMAIL_ALREADY_IN_USE" -> "The email address is already in use by another account."
//        "ERROR_WEAK_PASSWORD" -> "The password must be 6 characters long or more."
//        "ERROR_NETWORK_REQUEST_FAILED" -> "Network error. Check your connection and try again."
//        else -> e.localizedMessage ?: "Authentication operation failed."
//    }
//}
