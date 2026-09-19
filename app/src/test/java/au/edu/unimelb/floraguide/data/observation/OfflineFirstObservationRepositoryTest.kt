package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import au.edu.unimelb.floraguide.data.local.*
import au.edu.unimelb.floraguide.domain.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class OfflineFirstObservationRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: FloraGuideDatabase
    private lateinit var repository: OfflineFirstObservationRepository
    private val auth = mockk<FirebaseAuth>()
    private val work = mockk<WorkManager>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("floraguide_observations", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, FloraGuideDatabase::class.java).allowMainThreadQueries().build()
        every { auth.currentUser } returns null
        repository = OfflineFirstObservationRepository(context, db.observationDao(), firestore, auth, work)
    }
    @After fun close() = db.close()

    private fun signIn(uid: String) {
        val user = mockk<FirebaseUser>()
        every { user.uid } returns uid
        every { auth.currentUser } returns user
    }

    private fun observation() = Observation(
        id = "one", species = Species("blackwood", "Blackwood", "Acacia melanoxylon", setOf(2), mapOf(Habitat.LAWN to 0.7), 2),
        observedAt = Instant.ofEpochMilli(1000), coarseLocation = GeoPoint(-37.796, 144.961),
        habitat = Habitat.LAWN, photoPath = "/local/photo.jpg", cloudPhotoUri = null,
        headingDegrees = 90f, relativeScore = 0.8, contextSource = ContextDataSource.ALA_LIVE,
        imageScore = 0.6, imageSource = ImageSource.PLANTNET_LIVE,
    )

    @Test fun `offline save persists full metadata without queuing unauthenticated work`() = runBlocking {
        repository.save(observation())
        assertEquals(observation(), repository.loadAll().single())
        verify { work wasNot Called }
    }

    @Test fun `each authenticated save appends work instead of dropping it`() = runBlocking {
        signIn("A")
        repository.save(observation())
        repository.save(observation().copy(id = "two"))
        verify(exactly = 2) {
            work.enqueueUniqueWork("observation-sync-A", ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>())
        }
        assertEquals(2, repository.loadAll().size)
    }

    @Test fun `guest data is explicitly imported once and accounts never see each others records`() = runBlocking {
        repository.save(observation())
        signIn("A")
        assertTrue(repository.loadAll().isEmpty())
        repository.importLocalObservations()
        assertEquals("one", repository.loadAll().single().id)
        signIn("B")
        assertTrue(repository.loadAll().isEmpty())
        signIn("A")
        assertEquals("one", repository.loadAll().single().id)
    }

    @Test fun `legacy records with no stored owner remain local until explicitly imported`() = runBlocking {
        PreferencesObservationRepository(context).save(observation())
        signIn("A")
        assertTrue(repository.loadAll().isEmpty())
        signIn("B")
        assertTrue(repository.loadAll().isEmpty())
        every { auth.currentUser } returns null
        assertEquals(observation(), repository.loadAll().single())
        signIn("A")
        repository.importLocalObservations()
        assertEquals(observation(), repository.loadAll().single())
        signIn("B")
        assertTrue(repository.loadAll().isEmpty())
    }

    @Test fun `explicit sync retry revives failed uploads only for the current account`() = runBlocking {
        db.observationDao().insertOrUpdate(cacheEntity(uid = "A", state = SyncState.FAILED).copy(retryCount = 4))
        db.observationDao().insertOrUpdate(cacheEntity(uid = "B", state = SyncState.FAILED).copy(retryCount = 4))
        signIn("A")
        repository.retrySync()
        assertEquals(SyncState.PENDING_UPLOAD, db.observationDao().find("A", "one")!!.syncState)
        assertEquals(0, db.observationDao().find("A", "one")!!.retryCount)
        assertEquals(SyncState.FAILED, db.observationDao().find("B", "one")!!.syncState)
        verify { work.enqueueUniqueWork("observation-sync-A", ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>()) }
    }

    @Test fun `Preferences migration survives repeat reads and retains photo ownership`() = runBlocking {
        val original = observation().copy(cloudPhotoUri = "gs://bucket/plant_photos/A/photo.jpg")
        PreferencesObservationRepository(context).save(original)
        signIn("B")
        assertTrue(repository.loadAll().isEmpty())
        signIn("A")
        assertEquals(original, repository.loadAll().single())
        assertEquals(original, repository.loadAll().single())
        assertTrue(db.observationDao().hasImported("preferences"))
    }

    @Test fun `deletion hides the row and queues the owning account`() = runBlocking {
        signIn("A")
        repository.save(observation())
        repository.delete("one")
        assertTrue(repository.loadAll().isEmpty())
        assertEquals(SyncState.PENDING_DELETE, db.observationDao().find("A", "one")!!.syncState)
        verify(exactly = 2) {
            work.enqueueUniqueWork("observation-sync-A", ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test fun `synced remote URI does not replace a usable local photo path`() = runBlocking {
        signIn("A")
        repository.save(observation())
        db.observationDao().acknowledgeUpload("A", "one", 1, "gs://bucket/plant_photos/A/photo.jpg")
        val saved = repository.loadAll().single()
        assertEquals("/local/photo.jpg", saved.photoPath)
        assertEquals("gs://bucket/plant_photos/A/photo.jpg", saved.cloudPhotoUri)
        assertEquals(0.6, saved.imageScore!!, 0.0001)
    }

    @Test fun `cloud snapshots update local flow and cancellation removes the account listener`() = runBlocking {
        signIn("A")
        val collection = mockk<CollectionReference>()
        val registration = mockk<ListenerRegistration>(relaxed = true)
        val ready = CompletableDeferred<EventListener<QuerySnapshot>>()
        every { firestore.collection("users").document("A").collection("observations") } returns collection
        every { collection.addSnapshotListener(any<EventListener<QuerySnapshot>>()) } answers {
            ready.complete(firstArg())
            registration
        }
        val rows = Channel<List<Observation>>(Channel.UNLIMITED)
        val observer = launch { repository.observeAll().collect { rows.send(it) } }
        try {
            val listener = withTimeout(5000) { ready.await() }
            val cloud = observation().copy(photoPath = null)
            val document = mockk<QueryDocumentSnapshot> {
                every { id } returns "one"
                every { getString("userId") } returns "A"
                every { getString("observationJson") } returns ObservationJsonCodec.encode(cloud).toString()
                every { getLong("revision") } returns 1
            }
            val change = mockk<DocumentChange> {
                every { type } returns DocumentChange.Type.ADDED
                every { this@mockk.document } returns document
            }
            val snapshot = mockk<QuerySnapshot> { every { documentChanges } returns listOf(change) }
            listener.onEvent(snapshot, null)
            val received = withTimeout(5000) {
                var next = rows.receive()
                while (next.isEmpty()) next = rows.receive()
                next.single()
            }
            assertEquals(cloud, received)
            assertEquals(SyncState.SYNCED, db.observationDao().find("A", "one")!!.syncState)
        } finally {
            observer.cancelAndJoin()
            rows.close()
        }
        verify(exactly = 1) { registration.remove() }
    }
}
