package au.edu.unimelb.floraguide.data.firebase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import au.edu.unimelb.floraguide.domain.repository.AuthState
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
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

    // Helper configured to support all test requirements (handles nulls and custom profiles)
    private fun user(
        id: String,
        anonymous: Boolean,
        email: String? = null,
        displayName: String? = null
    ): FirebaseUser = mockk<FirebaseUser>(relaxed = true).also {
        every { it.uid } returns id
        every { it.isAnonymous } returns anonymous
        every { it.email } returns email
        every { it.displayName } returns displayName
    }

    // --- TESTS FROM FILE 1 ---

    @Test
    fun `anonymous registration links credentials and retains UID`() = runTest {
        val guest = user("guest-uid", true)
        val linked = user("guest-uid", false)
        val result = mockk<AuthResult> { every { user } returns linked }
        every { auth.currentUser } returns guest
        every { guest.linkWithCredential(any()) } answers {
            every { auth.currentUser } returns linked
            Tasks.forResult(result)
        }
        every { linked.updateProfile(any()) } returns Tasks.forResult(null)

        val repository = FirebaseAuthRepository(app, auth)
        assertEquals("guest-uid", repository.registerWithEmail("new@example.test", "password", "Gardener").getOrThrow().uid)
        verify(exactly = 1) { guest.linkWithCredential(any()) }
        verify(exactly = 0) { auth.createUserWithEmailAndPassword(any(), any()) }
        assertFalse((repository.authState.value as AuthState.Authenticated).user.isAnonymous)
        assertFalse(repository.getSessionLogs().isEmpty())
    }

    @Test
    fun `cancelled sign in propagates cancellation and restores offline mode`() = runTest {
        every { auth.currentUser } returns null
        every { auth.signInWithEmailAndPassword(any(), any()) } returns TaskCompletionSource<AuthResult>().task

        val repository = FirebaseAuthRepository(app, auth)
        repository.continueOffline()
        var swallowed = false
        val pending = launch { repository.signInWithEmail("test@example.test", "password"); swallowed = true }
        runCurrent()
        assertEquals(AuthState.Authenticating, repository.authState.value)
        pending.cancelAndJoin()
        assertFalse(swallowed)
        assertEquals(AuthState.OfflineGuest, repository.authState.value)
    }

    @Test
    fun `failed guest upgrade keeps the guest signed in`() = runTest {
        val guest = user("guest-uid", true)
        every { auth.currentUser } returns guest
        every { guest.linkWithCredential(any()) } returns Tasks.forException(IllegalStateException("Email already in use"))

        val repository = FirebaseAuthRepository(app, auth)
        assertTrue(repository.registerWithEmail("existing@example.test", "password", "Gardener").isFailure)
        assertEquals("guest-uid", (repository.authState.value as AuthState.Authenticated).user.uid)
        verify(exactly = 0) { auth.signOut() }
    }

    @Test
    fun `offline entry never calls Firebase anonymous sign in`() = runTest {
        every { auth.currentUser } returns null
        val repository = FirebaseAuthRepository(app, auth)
        repository.continueOffline()
        assertEquals(AuthState.OfflineGuest, repository.authState.value)
        verify(exactly = 0) { auth.signInAnonymously() }
    }

    @Test
    fun `guest action cannot silently reuse an existing real account`() = runTest {
        every { auth.currentUser } returns user("A", false)
        val repository = FirebaseAuthRepository(app, auth)
        assertTrue(repository.signInAnonymously().isFailure)
        assertEquals("A", (repository.authState.value as AuthState.Authenticated).user.uid)
        verify(exactly = 0) { auth.signInAnonymously() }
    }

    // --- TESTS FROM FILE 2 ---

    @Test
    fun `successful sign in writes session log`() = runTest {
        val u = user("user-1", false, "user@example.test", "Test User")
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
