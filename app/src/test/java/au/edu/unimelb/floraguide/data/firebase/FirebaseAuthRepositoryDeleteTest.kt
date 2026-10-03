package au.edu.unimelb.floraguide.data.firebase

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import au.edu.unimelb.floraguide.data.local.ObservationDao
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
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
class FirebaseAuthRepositoryDeleteTest {
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val dao = mockk<ObservationDao>(relaxed = true)
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        mockkStatic(FirebaseFirestore::class, FirebaseStorage::class)
        val firestore = mockk<FirebaseFirestore>(relaxed = true)
        val storage = mockk<FirebaseStorage>(relaxed = true)
        every { FirebaseFirestore.getInstance() } returns firestore
        every { FirebaseStorage.getInstance() } returns storage
        val docRef = mockk<com.google.firebase.firestore.DocumentReference>(relaxed = true)
        val colRef = mockk<com.google.firebase.firestore.CollectionReference>(relaxed = true)
        val querySnap = mockk<com.google.firebase.firestore.QuerySnapshot>(relaxed = true)
        every { firestore.collection(any()) } returns colRef
        every { colRef.document(any()) } returns docRef
        every { colRef.document() } returns docRef
        every { docRef.collection(any()) } returns colRef
        every { colRef.get(any()) } returns Tasks.forResult(querySnap)
        every { querySnap.documents } returns emptyList()
        every { docRef.delete() } returns Tasks.forResult(null)
        val storageRef = mockk<com.google.firebase.storage.StorageReference>(relaxed = true)
        val listResult = mockk<com.google.firebase.storage.ListResult>(relaxed = true)
        every { storage.reference } returns storageRef
        every { storageRef.child(any()) } returns storageRef
        every { storageRef.listAll() } returns Tasks.forResult(listResult)
        every { listResult.items } returns emptyList()
        every { listResult.prefixes } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkAll()
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
    fun `deleteAccount handles resumable multi-step cascading cleanup successfully`() = runTest {
        val u = user("test-uid", false, "user@example.test", "Test User")
        val tokenResult = mockk<GetTokenResult> {
            every { claims } returns mapOf("auth_time" to (System.currentTimeMillis() / 1000L - 60L))
        }
        every { u.getIdToken(any()) } returns Tasks.forResult(tokenResult)
        every { u.delete() } returns Tasks.forResult(null)
        every { auth.currentUser } returns u

        val repository = repository()
        val result = repository.deleteAccount()

        assertTrue(result.isSuccess)
        verify(exactly = 1) { workManager.cancelUniqueWork("observation-sync-test-uid") }
        verify(exactly = 1) { workManager.cancelUniqueWork("pending-scan-cleanup-test-uid") }
        coVerify(exactly = 1) { dao.deleteAllForUser("test-uid") }
        verify(exactly = 1) { u.delete() }
    }
}
