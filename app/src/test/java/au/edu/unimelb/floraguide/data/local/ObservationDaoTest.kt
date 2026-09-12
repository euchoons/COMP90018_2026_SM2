// File: app/src/test/java/au/edu/unimelb/floraguide/data/local/ObservationDaoTest.kt
package au.edu.unimelb.floraguide.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ObservationDaoTest {

    private lateinit var database: FloraGuideDatabase
    private lateinit var dao: ObservationDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FloraGuideDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.observationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insertAndQueryObservations filter by user and exclude pending deletion`() {
        val item1 = createEntity("obs-1", "user-A", SyncState.SYNCED)
        val item2 = createEntity("obs-2", "user-A", SyncState.PENDING_DELETE)
        val item3 = createEntity("obs-3", "user-B", SyncState.SYNCED)

        dao.insertOrUpdate(item1)
        dao.insertOrUpdate(item2)
        dao.insertOrUpdate(item3)

        val userAResults = dao.getAllForUser("user-A")

        assertEquals(1, userAResults.size)
        assertEquals("obs-1", userAResults[0].id)
    }

    @Test
    fun `getPendingSync fetches PENDING_UPLOAD and PENDING_DELETE items`() {
        val item1 = createEntity("obs-1", "user-A", SyncState.SYNCED)
        val item2 = createEntity("obs-2", "user-A", SyncState.PENDING_UPLOAD)
        val item3 = createEntity("obs-3", "user-A", SyncState.PENDING_DELETE)

        dao.insertOrUpdate(item1)
        dao.insertOrUpdate(item2)
        dao.insertOrUpdate(item3)

        val pending = dao.getPendingSync()

        assertEquals(2, pending.size)
        assertTrue(pending.any { it.id == "obs-2" })
        assertTrue(pending.any { it.id == "obs-3" })
    }

    @Test
    fun `updateSyncStatus changes status and sets remote photo URL`() {
        val item = createEntity("obs-1", "user-A", SyncState.PENDING_UPLOAD)
        dao.insertOrUpdate(item)

        dao.updateSyncStatus("obs-1", SyncState.SYNCED, "https://storage.googleapis.com/photo.jpg")

        val updated = dao.getAllForUser("user-A").first()
        assertEquals(SyncState.SYNCED, updated.syncState)
        assertEquals("https://storage.googleapis.com/photo.jpg", updated.remotePhotoUrl)
    }

    @Test
    fun `deletePermanently removes entity completely from database`() {
        val item = createEntity("obs-1", "user-A", SyncState.PENDING_DELETE)
        dao.insertOrUpdate(item)

        dao.deletePermanently("obs-1")

        assertTrue(dao.getPendingSync().isEmpty())
    }

    private fun createEntity(id: String, userId: String, syncState: SyncState) = ObservationEntity(
        id = id,
        userId = userId,
        speciesId = "eucalyptus_camaldulensis",
        scientificName = "Eucalyptus camaldulensis",
        commonName = "River Red Gum",
        observedAtEpochMs = System.currentTimeMillis(),
        coarseLatitude = -37.796,
        coarseLongitude = 144.961,
        habitatName = "TREE_CANOPY",
        localPhotoPath = "/local/path/photo.jpg",
        remotePhotoUrl = null,
        headingDegrees = 180.0f,
        relativeScore = 0.85,
        contextSource = "ALA_LIVE",
        syncState = syncState
    )
}
