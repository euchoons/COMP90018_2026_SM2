package au.edu.unimelb.floraguide.data.firebase

import au.edu.unimelb.floraguide.domain.repository.AuthState
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FirebaseAuthRepositoryTest {
    private val auth = mockk<FirebaseAuth>(relaxed = true)

    private fun user(id: String, anonymous: Boolean): FirebaseUser = mockk<FirebaseUser>(relaxed = true).also {
        every { it.uid } returns id
        every { it.isAnonymous } returns anonymous
        every { it.email } returns null
        every { it.displayName } returns null
    }

    @Test fun `anonymous registration links credentials and retains UID`() = runTest {
        val guest = user("guest-uid", true)
        val linked = user("guest-uid", false)
        val result = mockk<AuthResult> { every { user } returns linked }
        every { auth.currentUser } returns guest
        every { guest.linkWithCredential(any()) } answers {
            every { auth.currentUser } returns linked
            Tasks.forResult(result)
        }
        every { linked.updateProfile(any()) } returns Tasks.forResult(null)
        val repository = FirebaseAuthRepository(auth)
        assertEquals("guest-uid", repository.registerWithEmail("new@example.test", "password", "Gardener").getOrThrow().uid)
        verify(exactly = 1) { guest.linkWithCredential(any()) }
        verify(exactly = 0) { auth.createUserWithEmailAndPassword(any(), any()) }
        assertFalse((repository.authState.value as AuthState.Authenticated).user.isAnonymous)
        assertTrue(repository.getSessionLogs().isEmpty())
    }

    @Test fun `cancelled sign in propagates cancellation and restores offline mode`() = runTest {
        every { auth.currentUser } returns null
        every { auth.signInWithEmailAndPassword(any(), any()) } returns TaskCompletionSource<AuthResult>().task
        val repository = FirebaseAuthRepository(auth)
        repository.continueOffline()
        var swallowed = false
        val pending = launch { repository.signInWithEmail("test@example.test", "password"); swallowed = true }
        runCurrent()
        assertEquals(AuthState.Authenticating, repository.authState.value)
        pending.cancelAndJoin()
        assertFalse(swallowed)
        assertEquals(AuthState.OfflineGuest, repository.authState.value)
    }

    @Test fun `failed guest upgrade keeps the guest signed in`() = runTest {
        val guest = user("guest-uid", true)
        every { auth.currentUser } returns guest
        every { guest.linkWithCredential(any()) } returns Tasks.forException(IllegalStateException("Email already in use"))
        val repository = FirebaseAuthRepository(auth)
        assertTrue(repository.registerWithEmail("existing@example.test", "password", "Gardener").isFailure)
        assertEquals("guest-uid", (repository.authState.value as AuthState.Authenticated).user.uid)
        verify(exactly = 0) { auth.signOut() }
    }

    @Test fun `offline entry never calls Firebase anonymous sign in`() = runTest {
        every { auth.currentUser } returns null
        val repository = FirebaseAuthRepository(auth)
        repository.continueOffline()
        assertEquals(AuthState.OfflineGuest, repository.authState.value)
        verify(exactly = 0) { auth.signInAnonymously() }
    }

    @Test fun `guest action cannot silently reuse an existing real account`() = runTest {
        every { auth.currentUser } returns user("A", false)
        val repository = FirebaseAuthRepository(auth)
        assertTrue(repository.signInAnonymously().isFailure)
        assertEquals("A", (repository.authState.value as AuthState.Authenticated).user.uid)
        verify(exactly = 0) { auth.signInAnonymously() }
    }
}
