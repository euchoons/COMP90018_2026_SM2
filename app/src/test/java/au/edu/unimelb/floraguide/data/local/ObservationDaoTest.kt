package au.edu.unimelb.floraguide.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

internal fun cacheEntity(id: String = "one", uid: String = "A", state: SyncState = SyncState.PENDING_UPLOAD) =
    ObservationEntity(id, uid, "blackwood", "Acacia melanoxylon", "Blackwood",
        observedAtEpochMs = 1000, coarseLatitude = -37.796, coarseLongitude = 144.961,
        habitatName = "LAWN", localPhotoPath = "/local/photo.jpg", remotePhotoUrl = null,
        headingDegrees = 90f, relativeScore = 0.8, contextSource = "ALA_LIVE", syncState = state)

@RunWith(RobolectricTestRunner::class)
class ObservationDaoTest {
    private lateinit var db: FloraGuideDatabase
    private lateinit var dao: ObservationDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FloraGuideDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.observationDao()
    }
    @After fun close() = db.close()

    @Test fun `same observation id remains isolated across accounts`() = runBlocking {
        dao.saveLocal(cacheEntity(uid = "A"))
        dao.saveLocal(cacheEntity(uid = "B").copy(commonName = "B only"))
        dao.markDeleted("A", "one")
        assertTrue(dao.getAllForUser("A").isEmpty())
        assertEquals("B only", dao.getAllForUser("B").single().commonName)
        assertEquals("A", dao.getPendingSync("A").single().userId)
    }

    @Test fun `successful upload stores URL and keeps local photo`() = runBlocking {
        dao.saveLocal(cacheEntity())
        assertEquals(1, dao.acknowledgeUpload("A", "one", 1, "gs://bucket/photo"))
        val saved = dao.getAllForUser("A").single()
        assertEquals(SyncState.SYNCED, saved.syncState)
        assertEquals("gs://bucket/photo", saved.remotePhotoUrl)
        assertEquals("/local/photo.jpg", saved.localPhotoPath)
    }

    @Test fun `upload success and failure cannot undo a concurrent delete`() = runBlocking {
        dao.saveLocal(cacheEntity())
        dao.markDeleted("A", "one")
        assertEquals(0, dao.acknowledgeUpload("A", "one", 1, "old"))
        assertEquals(0, dao.recordFailure("A", "one", 1, SyncState.PENDING_UPLOAD))
        assertEquals(SyncState.PENDING_DELETE, dao.getPendingSync("A").single().syncState)
        assertTrue(dao.getAllForUser("A").isEmpty())
    }

    @Test fun `stale callbacks cannot overwrite a newer save with the same state`() = runBlocking {
        dao.saveLocal(cacheEntity())
        dao.saveLocal(cacheEntity().copy(commonName = "new"))
        assertEquals(0, dao.acknowledgeUpload("A", "one", 1, "old"))
        assertEquals(0, dao.recordFailure("A", "one", 1, SyncState.PENDING_UPLOAD))
        assertEquals(2L, dao.find("A", "one")!!.revision)
        assertEquals("new", dao.find("A", "one")!!.commonName)
    }

    @Test fun `late delete acknowledgement cannot remove a new save`() = runBlocking {
        dao.saveLocal(cacheEntity())
        dao.markDeleted("A", "one")
        dao.saveLocal(cacheEntity().copy(commonName = "restored"))
        assertEquals(0, dao.acknowledgeDeletion("A", "one", 2))
        assertEquals("restored", dao.getAllForUser("A").single().commonName)
    }

    @Test fun `cloud callbacks cannot overwrite pending changes or resurrect tombstones`() = runBlocking {
        dao.saveLocal(cacheEntity())
        dao.mergeRemote(cacheEntity(state = SyncState.SYNCED).copy(commonName = "stale cloud"))
        assertEquals("Blackwood", dao.find("A", "one")!!.commonName)
        dao.markDeleted("A", "one")
        assertEquals(1, dao.acknowledgeDeletion("A", "one", 2))
        dao.mergeRemote(cacheEntity(state = SyncState.SYNCED).copy(revision = 100))
        assertTrue(dao.getAllForUser("A").isEmpty())
        assertTrue(dao.getPendingSync("A").isEmpty())
    }

    @Test fun `legacy import and guest claim are idempotent and never move authenticated rows`() = runBlocking {
        dao.importLegacy(listOf(cacheEntity(uid = "anonymous_user")))
        dao.saveLocal(cacheEntity("private", "previous-account"))
        dao.claimLocalGuest("A")
        dao.importLegacy(listOf(cacheEntity(uid = "anonymous_user")))
        dao.claimLocalGuest("B")
        assertEquals(listOf("one"), dao.getAllForUser("A").map { it.id })
        assertTrue(dao.getAllForUser("B").isEmpty())
        assertEquals("private", dao.getAllForUser("previous-account").single().id)
    }

    @Test fun `late remote deletion does not remove a newer synced revision`() = runBlocking {
        dao.saveLocal(cacheEntity())
        dao.saveLocal(cacheEntity().copy(commonName = "new"))
        dao.acknowledgeUpload("A", "one", 2, null)
        dao.removeRemote("A", "one", 1)
        assertEquals("new", dao.find("A", "one")!!.commonName)
        dao.removeRemote("A", "one", 2)
        assertNull(dao.find("A", "one"))
    }

    @Test fun `duplicate guest import cannot leak into a later account`() = runBlocking {
        dao.saveLocal(cacheEntity(uid = "A"))
        dao.importLegacy(listOf(cacheEntity(uid = "anonymous_user")))
        dao.claimLocalGuest("A")
        dao.claimLocalGuest("B")
        assertEquals(1, dao.getAllForUser("A").size)
        assertTrue(dao.getAllForUser("B").isEmpty())
        assertTrue(dao.getAllGuestRows().isEmpty())
    }
}
