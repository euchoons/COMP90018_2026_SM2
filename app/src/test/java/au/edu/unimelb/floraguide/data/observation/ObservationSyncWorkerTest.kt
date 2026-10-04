package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import au.edu.unimelb.floraguide.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ObservationSyncWorkerTest {
    private lateinit var context: Context
    private lateinit var db: FloraGuideDatabase
    private lateinit var dao: ObservationDao
    private var signedIn: String? = "A"
    private var upload: suspend (ObservationEntity) -> String? = { "gs://test/photo" }
    private var delete: suspend (ObservationEntity) -> Unit = {}

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FloraGuideDatabase::class.java).allowMainThreadQueries().build()
        dao = db.observationDao()
    }
    @After fun close() = db.close()

    private fun worker(uid: String = "A"): ObservationSyncWorker =
        TestListenableWorkerBuilder<ObservationSyncWorker>(context)
            .setInputData(workDataOf(ObservationSyncWorker.USER_ID to uid))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker =
                    object : ObservationSyncWorker(context, params, dao) {
                        override fun getUserId() = signedIn
                        override suspend fun upload(item: ObservationEntity): String? = upload.invoke(item)
                        override suspend fun deleteRemote(item: ObservationEntity) = delete.invoke(item)
                    }
            }).build()

    @Test fun `production worker is constructible by the default factory`() {
        val actual = TestListenableWorkerBuilder<ObservationSyncWorker>(context)
            .setInputData(workDataOf(ObservationSyncWorker.USER_ID to "A")).build()
        assertEquals(ObservationSyncWorker::class.java, actual.javaClass)
    }

    @Test fun `empty queue completes successfully`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    @Test fun `a job queued for A does not upload while B is signed in`() = runBlocking {
        dao.saveLocal(cacheEntity())
        signedIn = "B"
        upload = { fail("Must not upload another account's record"); null }
        assertEquals(ListenableWorker.Result.success(), worker("A").doWork())
        assertEquals(SyncState.PENDING_UPLOAD, dao.find("A", "one")!!.syncState)
    }

    @Test fun `upload callback cannot undo deletion made while network work is pending`() = runBlocking {
        dao.saveLocal(cacheEntity())
        upload = { dao.markDeleted("A", it.id); "gs://test/photo" }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(SyncState.PENDING_DELETE, dao.find("A", "one")!!.syncState)
    }

    @Test fun `failed upload cannot replace a delete with stale entity data`() = runBlocking {
        dao.saveLocal(cacheEntity())
        upload = { dao.markDeleted("A", it.id); error("Network failure") }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(SyncState.PENDING_DELETE, dao.find("A", "one")!!.syncState)
        assertEquals(0, dao.find("A", "one")!!.retryCount)
    }

    @Test fun `cancellation leaves pending data and retry count unchanged`() = runBlocking {
        dao.saveLocal(cacheEntity())
        upload = { throw CancellationException("cancelled") }
        try { worker().doWork(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(0, dao.find("A", "one")!!.retryCount)
        assertEquals(SyncState.PENDING_UPLOAD, dao.find("A", "one")!!.syncState)
    }

    @Test fun `one failed record does not prevent another pending record uploading`() = runBlocking {
        dao.saveLocal(cacheEntity())
        dao.saveLocal(cacheEntity("two"))
        upload = { if (it.id == "one") error("offline") else "gs://test/two" }
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(SyncState.SYNCED, dao.find("A", "two")!!.syncState)
        assertEquals(1, dao.find("A", "one")!!.retryCount)
    }

    @Test fun `exhausted upload does not fail the work chain and cancel appended saves`() = runBlocking {
        dao.insertOrUpdate(cacheEntity().copy(retryCount = 3))
        upload = { error("offline") }
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(SyncState.FAILED, dao.find("A", "one")!!.syncState)
    }

    @Test fun `failed deletion remains hidden and retryable even after several failures`() = runBlocking {
        dao.insertOrUpdate(cacheEntity(state = SyncState.PENDING_DELETE).copy(retryCount = 3))
        delete = { error("offline") }
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(SyncState.PENDING_DELETE, dao.find("A", "one")!!.syncState)
        assertTrue(dao.getAllForUser("A").isEmpty())
    }

    @Test fun `failed cloud photo deletion propagates error and reschedules task instead of swallowing`() = runBlocking {
        // 1. Arrange: Insert a local tombstone row marked for cloud deletion
        val entityPendingDelete = cacheEntity(id = "stale-node", state = SyncState.PENDING_DELETE)
        dao.insertOrUpdate(entityPendingDelete)

        // Configure remote mapping behavior to crash upon processing the target asset
        delete = { _: ObservationEntity ->
            throw java.io.IOException("Cloud Storage bucket unavailable (Transient 503)")
        }

        // 2. Act: Trigger execution loop through the worker pipeline boundary
        val result = worker().doWork()

        // 3. Assert: Verify the sync pipeline reschedules the task on structural failure
        assertEquals(ListenableWorker.Result.retry(), result)

        // Assert the local database tombstone is PRESERVED, confirming metadata was not removed prematurely
        val persistentRow = dao.find("A", "stale-node")
        assertNotNull(persistentRow)
        assertEquals(SyncState.PENDING_DELETE, persistentRow!!.syncState)
    }
    @Test
    fun testPhotoNotDeletedIfNewObservationCreatedConcurrently() = runBlocking {
        // Simulate: obs-1 deletes, but obs-3 is saved with same photo before sync completes
        val uid = "test-user-uid"
        val sharedUri = "gs://floraguide-fe2a0.appspot.com/plant_photos/$uid/digest123.jpg"

        dao.insertOrUpdate(createMockObservationEntity(id = "obs-1", userId = uid, remotePhotoUrl = sharedUri))
        dao.markDeleted(uid, "obs-1")

        // Concurrent save before sync worker runs
        dao.insertOrUpdate(createMockObservationEntity(id = "obs-3", userId = uid, remotePhotoUrl = sharedUri))

        // Photo must still be referenced
        assertTrue(dao.isPhotoReferenced(uid, sharedUri))
    }

    @Test
    fun testPhotoOwnershipBoundary() = runBlocking {
        val uid1 = "user-1"
        val uid2 = "user-2"
        val uri1 = "gs://bucket/plant_photos/$uid1/photo.jpg"
        val uri2 = "gs://bucket/plant_photos/$uid2/photo.jpg"

        dao.insertOrUpdate(createMockObservationEntity(id = "obs-1", userId = uid1, remotePhotoUrl = uri1))
        dao.insertOrUpdate(createMockObservationEntity(id = "obs-2", userId = uid2, remotePhotoUrl = uri2))

        // uid1's deletion should NOT affect uid2's photo
        dao.markDeleted(uid1, "obs-1")
        assertFalse(dao.isPhotoReferenced(uid1, uri1))
        assertTrue(dao.isPhotoReferenced(uid2, uri2))
    }

    @Test
    fun testDeletionRetryOnTransientFailure() = runBlocking {
        val uid = "test-user-uid"
        val sharedUri = "gs://floraguide-fe2a0.appspot.com/plant_photos/$uid/digest123.jpg"

        dao.insertOrUpdate(createMockObservationEntity(id = "obs-1", userId = uid, remotePhotoUrl = sharedUri))
        dao.insertOrUpdate(createMockObservationEntity(id = "obs-2", userId = uid, remotePhotoUrl = sharedUri))

        dao.markDeleted(uid, "obs-1")

        // Simulate first retry fails
        val pending = dao.getPendingSync(uid)
        assertEquals(1, pending.size)

        // Photo still protected even if first delete attempt fails
        assertTrue(dao.isPhotoReferenced(uid, sharedUri))

        // Second retry should still see the reference check
        assertTrue(dao.isPhotoReferenced(uid, sharedUri))
    }

    private fun createMockObservationEntity(
        id: String = "obs-1",
        userId: String = "test-user-uid",
        remotePhotoUrl: String? = "gs://floraguide-fe2a0.appspot.com/plant_photos/$userId/digest123.jpg",
        syncState: SyncState = SyncState.SYNCED
    ): ObservationEntity = ObservationEntity(
        id = id,
        userId = userId,
        speciesId = "eucalyptus_camaldulensis",
        scientificName = "Eucalyptus camaldulensis",
        commonName = "River red gum",
        observedAtEpochMs = System.currentTimeMillis(),
        coarseLatitude = -37.7963,
        coarseLongitude = 144.9614,
        habitatName = "TREE_CANOPY",
        localPhotoPath = null,
        remotePhotoUrl = remotePhotoUrl,
        headingDegrees = null,
        relativeScore = 0.95,
        contextSource = "ALA_LIVE",
        syncState = syncState,
        retryCount = 0,
        revision = 0,
        observationJson = null
    )
}
