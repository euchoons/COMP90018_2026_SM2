package au.edu.unimelb.floraguide.data.firebase

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import au.edu.unimelb.floraguide.data.local.ObservationDao
import au.edu.unimelb.floraguide.domain.repository.AuthState
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.*
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.ListResult
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
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
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val dao = mockk<ObservationDao>(relaxed = true)
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun repository(context: Context = app) = FirebaseAuthRepository(
        context, auth, AuthSessionLogger(context.getSharedPreferences("test_auth_logs", Context.MODE_PRIVATE)), workManager, dao, UnconfinedTestDispatcher()
    )

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

        val repository = repository()
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

        val repository = repository()
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

        val repository = repository()
        assertTrue(repository.registerWithEmail("existing@example.test", "password", "Gardener").isFailure)
        assertEquals("guest-uid", (repository.authState.value as AuthState.Authenticated).user.uid)
        verify(exactly = 0) { auth.signOut() }
    }

    @Test
    fun `offline entry never calls Firebase anonymous sign in`() = runTest {
        every { auth.currentUser } returns null
        val repository = repository()
        repository.continueOffline()
        assertEquals(AuthState.OfflineGuest, repository.authState.value)
        verify(exactly = 0) { auth.signInAnonymously() }
    }

    @Test
    fun `delayed auth callback preserves offline guest mode`() = runTest {
        every { auth.currentUser } returns null
        val listener = slot<FirebaseAuth.AuthStateListener>()
        every { auth.addAuthStateListener(capture(listener)) } just Runs
        val repository = repository()
        repository.continueOffline()
        listener.captured.onAuthStateChanged(auth)
        assertEquals(AuthState.OfflineGuest, repository.authState.value)
    }

    @Test
    fun `guest action cannot silently reuse an existing real account`() = runTest {
        every { auth.currentUser } returns user("A", false)
        val repository = repository()
        assertTrue(repository.signInAnonymously().isFailure)
        assertEquals("A", (repository.authState.value as AuthState.Authenticated).user.uid)
        verify(exactly = 0) { auth.signInAnonymously() }
    }

    @Test
    fun `successful sign in writes session log`() = runTest {
        val u = user("user-1", false, "user@example.test", "Test User")
        val result = mockk<AuthResult> { every { user } returns u }
        every { auth.currentUser } returns u
        every { auth.signInWithEmailAndPassword(any(), any()) } returns Tasks.forResult(result)

        val repository = repository()
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

        val repository = repository()
        val res = repository.signInWithEmail("user@example.test", "wrongpass")

        assertTrue(res.isFailure)
        val logs = repository.getSessionLogs()
        assertFalse(logs.isEmpty())
        assertTrue(logs.any { it.contains("EMAIL_SIGN_IN") && it.contains("FAILURE") })
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `deleteAccount successfully wipes firestore data, storage photos, and auth profile`() = runTest {
        checkDeletion(null)
    }

    @Test
    fun `photo deletion failure preserves documents and auth for retry`() = runTest {
        checkDeletion(IllegalStateException("Storage unavailable"))
    }

    @Test
    fun `already deleted photo does not prevent retrying account deletion`() = runTest {
        checkDeletion(StorageException.fromExceptionAndHttpCode(null, 404))
    }

    @Test
    fun `photo deletion cancellation does not continue account deletion`() = runTest {
        checkDeletion(CancellationException("Cancelled"))
    }

    private suspend fun checkDeletion(photoError: Exception?) {
        mockkStatic(FirebaseFirestore::class, FirebaseStorage::class)

        val firestore = mockk<FirebaseFirestore>(relaxed = true)
        val storage = mockk<FirebaseStorage>(relaxed = true)
        every { FirebaseFirestore.getInstance() } returns firestore
        every { FirebaseStorage.getInstance() } returns storage

        val u = user("user-123", false)
        every { auth.currentUser } returns u
        every { u.delete() } returns Tasks.forResult(null)

        val freshAuthTime = System.currentTimeMillis() / 1000L
        val tokenResult = mockk<GetTokenResult> { every { claims } returns mapOf("auth_time" to freshAuthTime) }
        every { u.getIdToken(false) } returns Tasks.forResult(tokenResult)

        val rootRef = mockk<StorageReference>(relaxed = true)
        val userStorageRef = mockk<StorageReference>(relaxed = true)
        val photoRef = mockk<StorageReference>(relaxed = true)
        val listResult = mockk<ListResult>(relaxed = true)

        every { storage.reference } returns rootRef
        every { rootRef.child("plant_photos/user-123") } returns userStorageRef
        every { userStorageRef.listAll() } returns Tasks.forResult(listResult)
        every { listResult.items } returns listOf(photoRef)
        every { listResult.prefixes } returns emptyList()
        every { photoRef.path } returns "plant_photos/user-123/photo.jpg"
        every { photoRef.delete() } returns if (photoError == null) Tasks.forResult(null) else Tasks.forException(photoError)

        val userDoc = mockk<DocumentReference>()
        val obsCollection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        val docSnap = mockk<QueryDocumentSnapshot>(relaxed = true)
        val docRef = mockk<DocumentReference>()

        every { querySnapshot.documents } returns listOf(docSnap)
        every { docSnap.getString("userId") } returns "user-123"
        every { docSnap.reference } returns docRef
        every { docRef.delete() } returns Tasks.forResult(null)

        every { firestore.collection("users").document("user-123") } returns userDoc
        every { userDoc.collection("observations") } returns obsCollection
        every { obsCollection.get(Source.SERVER) } returns Tasks.forResult(querySnapshot)
        every { userDoc.delete() } returns Tasks.forResult(null)

        val repository = repository()
        val result = try {
            repository.deleteAccount()
        } catch (cancelled: CancellationException) {
            assertTrue(photoError is CancellationException)
            Result.failure(cancelled)
        }

        if (photoError is CancellationException) {
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is CancellationException)
            verify(exactly = 0) { docRef.delete(); userDoc.delete(); u.delete() }
            return
        }

        val storageEx = photoError as? StorageException
        if (photoError != null && storageEx?.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND && storageEx?.errorCode != 404) {
            assertTrue(result.isFailure)
            verify(exactly = 0) { docRef.delete(); userDoc.delete(); u.delete() }
            return
        }

        assertTrue(result.isSuccess)
        verify(exactly = 1) { workManager.cancelUniqueWork("observation-sync-user-123") }
        verify(exactly = 1) { photoRef.delete() }
        verify(exactly = 1) { docRef.delete() }
        verify(exactly = 1) { userDoc.delete() }
        coVerify(exactly = 1) { dao.deleteAllForUser("user-123") }
        verify(exactly = 1) { u.delete() }

        val logs = repository.getSessionLogs()
        assertTrue(logs.any { it.contains("DELETE_ACCOUNT") && it.contains("SUCCESS") })
    }

    @Test
    fun `deleteAccount returns failure and logs error if unauthenticated`() = runTest {
        every { auth.currentUser } returns null

        val repository = repository()
        val result = repository.deleteAccount()

        assertTrue(result.isFailure)
        assertEquals("No authenticated user to delete.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `deleteAccount reverts state and logs failure if network or auth error occurs`() = runTest {
        mockkStatic(FirebaseFirestore::class, FirebaseStorage::class)

        val firestore = mockk<FirebaseFirestore>(relaxed = true)
        val storage = mockk<FirebaseStorage>(relaxed = true)
        every { FirebaseFirestore.getInstance() } returns firestore
        every { FirebaseStorage.getInstance() } returns storage

        val u = user("user-123", false)
        every { auth.currentUser } returns u

        val freshAuthTime = System.currentTimeMillis() / 1000L
        val tokenResult = mockk<GetTokenResult> { every { claims } returns mapOf("auth_time" to freshAuthTime) }
        every { u.getIdToken(false) } returns Tasks.forResult(tokenResult)

        every { u.delete() } returns Tasks.forException(Exception("Network unavailable"))

        val rootRef = mockk<StorageReference>(relaxed = true)
        val listResult = mockk<ListResult>(relaxed = true)
        every { storage.reference } returns rootRef
        every { rootRef.child(any()).listAll() } returns Tasks.forResult(listResult)
        every { listResult.items } returns emptyList()

        val userDoc = mockk<DocumentReference>()
        val obsCollection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        every { querySnapshot.documents } returns emptyList()
        every { firestore.collection("users").document("user-123") } returns userDoc
        every { userDoc.collection("observations") } returns obsCollection
        every { obsCollection.get(Source.SERVER) } returns Tasks.forResult(querySnapshot)
        every { userDoc.delete() } returns Tasks.forResult(null)

        val repository = repository()
        val result = repository.deleteAccount()

        assertTrue(result.isFailure)
        assertEquals("Network unavailable", result.exceptionOrNull()?.message)
        assertTrue(repository.authState.value is AuthState.Authenticated)

        val logs = repository.getSessionLogs()
        assertTrue(logs.any { it.contains("DELETE_ACCOUNT") && it.contains("FAILURE") })
    }

    @Test
    fun `deletion fails immediately if reauthentication is expired`() = runTest {
        val u = user("user-123", false)
        every { auth.currentUser } returns u

        val staleAuthTime = (System.currentTimeMillis() / 1000L) - 600L
        val tokenResult = mockk<GetTokenResult> {
            every { claims } returns mapOf("auth_time" to staleAuthTime)
        }
        every { u.getIdToken(false) } returns Tasks.forResult(tokenResult)

        val repository = repository()
        val result = repository.deleteAccount()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FirebaseAuthRecentLoginRequiredException)

        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
        coVerify(exactly = 0) { dao.deleteAllForUser(any()) }
    }

    @Test
    fun `cancellation propagates correctly during cascade`() = runTest {
        mockkStatic(FirebaseFirestore::class, FirebaseStorage::class)

        val firestore = mockk<FirebaseFirestore>(relaxed = true)
        val storage = mockk<FirebaseStorage>(relaxed = true)
        every { FirebaseFirestore.getInstance() } returns firestore
        every { FirebaseStorage.getInstance() } returns storage

        val u = user("user-123", false)
        every { auth.currentUser } returns u

        val freshAuthTime = System.currentTimeMillis() / 1000L
        val tokenResult = mockk<GetTokenResult> { every { claims } returns mapOf("auth_time" to freshAuthTime) }
        every { u.getIdToken(false) } returns Tasks.forResult(tokenResult)

        val rootRef = mockk<StorageReference>(relaxed = true)
        val listResult = mockk<ListResult>(relaxed = true)
        every { storage.reference } returns rootRef
        every { rootRef.child(any()).listAll() } returns Tasks.forResult(listResult)
        every { listResult.items } returns emptyList()

        val userDoc = mockk<DocumentReference>()
        val obsCollection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        every { querySnapshot.documents } returns emptyList()
        every { firestore.collection("users").document("user-123") } returns userDoc
        every { userDoc.collection("observations") } returns obsCollection
        every { obsCollection.get(Source.SERVER) } returns Tasks.forResult(querySnapshot)
        every { userDoc.delete() } returns Tasks.forResult(null)

        coEvery { dao.deleteAllForUser("user-123") } throws CancellationException("User navigated away")

        val repository = repository()
        val result = runCatching { repository.deleteAccount() }

        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `auth logs strictly redact emails and hash UIDs to prevent PII leakage`() = runTest {
        val rawEmail = "student.target@student.unimelb.edu.au"
        val rawUid = "plain-text-uid-12345"

        val context = ApplicationProvider.getApplicationContext<Context>()

        every { auth.signInWithEmailAndPassword(any(), any()) } returns Tasks.forException(
            Exception("Firebase Auth failed for $rawEmail: User disabled")
        )

        val guestUser = user(rawUid, true)
        every { auth.currentUser } returns guestUser

        val repository = repository(context)
        repository.signInWithEmail(rawEmail, "password")
        val logs = repository.getSessionLogs()

        assertTrue(
            "Logs must not contain the raw email string",
            logs.none { it.contains(rawEmail) }
        )
        assertTrue(
            "Logs must not contain the raw UID string",
            logs.none { it.contains(rawUid) }
        )
        assertTrue(
            "Exceptions must be masked with the redaction placeholder",
            logs.any { it.contains("[REDACTED_EMAIL]") }
        )
    }

    @Test
    fun `interrupted erasure resumes and completes on startup`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val erasurePrefs = context.getSharedPreferences("floraguide_erasure_state", Context.MODE_PRIVATE)
        erasurePrefs.edit().putString("erasure_status", "INTERRUPTED").putString("erasure_uid", "user-123").apply()

        mockkStatic(FirebaseFirestore::class, FirebaseStorage::class)
        val firestore = mockk<FirebaseFirestore>(relaxed = true)
        val storage = mockk<FirebaseStorage>(relaxed = true)
        every { FirebaseFirestore.getInstance() } returns firestore
        every { FirebaseStorage.getInstance() } returns storage

        val rootRef = mockk<StorageReference>(relaxed = true)
        val userStorageRef = mockk<StorageReference>(relaxed = true)
        val listResult = mockk<ListResult>(relaxed = true)
        every { storage.reference } returns rootRef
        every { rootRef.child("plant_photos/user-123") } returns userStorageRef
        every { userStorageRef.listAll() } returns Tasks.forResult(listResult)
        every { listResult.items } returns emptyList()
        every { listResult.prefixes } returns emptyList()

        val userDoc = mockk<DocumentReference>()
        val obsCollection = mockk<CollectionReference>()
        val querySnapshot = mockk<QuerySnapshot>()
        every { querySnapshot.documents } returns emptyList()
        every { firestore.collection("users").document("user-123") } returns userDoc
        every { userDoc.collection("observations") } returns obsCollection
        every { obsCollection.get(Source.SERVER) } returns Tasks.forResult(querySnapshot)
        every { userDoc.delete() } returns Tasks.forResult(null)

        repository(context)
        advanceUntilIdle()

        assertEquals("COMPLETE", erasurePrefs.getString("erasure_status", null))
        coVerify(exactly = 1) { dao.deleteAllForUser("user-123") }
    }
}
