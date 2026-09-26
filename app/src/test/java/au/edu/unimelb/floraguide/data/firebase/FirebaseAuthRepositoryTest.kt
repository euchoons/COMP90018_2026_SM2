package au.edu.unimelb.floraguide.data.firebase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import au.edu.unimelb.floraguide.domain.repository.AuthState
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.*
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class FirebaseAuthRepositoryTest {
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun user(id: String, anonymous: Boolean): FirebaseUser = mockk<FirebaseUser>(relaxed = true).also {
        every { it.uid } returns id
        every { it.isAnonymous } returns anonymous
        every { it.email } returns "user@example.test"
        every { it.displayName } returns "Test User"
    }

    @Test
    fun `successful sign in writes session log`() = runTest {
        val u = user("user-1", false)
        val result = mockk<AuthResult> { every { user } returns u }
        every { auth.currentUser } returns u
        every { auth.signInWithEmailAndPassword(any(), any()) } returns Tasks.forResult(result)

        val repository = FirebaseAuthRepository(app, auth)
        val res = repository.signInWithEmail("user@example.test", "password123")

        assertTrue(res.isSuccess)
        val logs = repository.getSessionLogs()
        assertFalse(logs.isEmpty())
        assertTrue(logs.any { it.contains("EMAIL_SIGN_IN") && it.contains("SUCCESS") })
    }

    @Test
    fun `failed sign in records failure state in session logs`() = runTest {
        every { auth.currentUser } returns null
        every { auth.signInWithEmailAndPassword(any(), any()) } returns Tasks.forException(
            FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "Invalid credentials")
        )

        val repository = FirebaseAuthRepository(app, auth)
        val res = repository.signInWithEmail("user@example.test", "wrongpass")

        assertTrue(res.isFailure)
        val logs = repository.getSessionLogs()
        assertFalse(logs.isEmpty())
        assertTrue(logs.any { it.contains("EMAIL_SIGN_IN") && it.contains("FAILURE") })
    }
}
