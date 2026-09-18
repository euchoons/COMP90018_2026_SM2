// File: app/src/test/java/au/edu/unimelb/floraguide/data/observation/OfflineFirstObservationRepositoryTest.kt
package au.edu.unimelb.floraguide.data.observation

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import au.edu.unimelb.floraguide.data.local.ObservationDao
import au.edu.unimelb.floraguide.data.local.ObservationEntity
import au.edu.unimelb.floraguide.data.local.SyncState
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.Species
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineFirstObservationRepositoryTest {

    private lateinit var fakeDao: FakeObservationDao
    private lateinit var repository: OfflineFirstObservationRepository
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)

    @Before
    fun setUp() {
        fakeDao = FakeObservationDao()
        every { auth.currentUser } returns null
        repository = OfflineFirstObservationRepository(
            context = ApplicationProvider.getApplicationContext(),
            dao = fakeDao,
            firestore = firestore,
            auth = auth,
            workManager = workManager
        )
    }

    @Test
    fun `save inserts local observation with PENDING_UPLOAD status`() {
        val observation = Observation(
            id = "test-uuid-1",
            species = Species("blackwood", "Blackwood", "Acacia melanoxylon", emptySet(), emptyMap(), 0),
            observedAt = Instant.now(),
            coarseLocation = GeoPoint(-37.796, 144.961),
            habitat = Habitat.GARDEN_BED,
            photoPath = "/tmp/image.jpg",
            headingDegrees = 90.0f,
            relativeScore = 0.92,
            contextSource = ContextDataSource.ALA_LIVE
        )

        repository.save(observation)

        val savedEntity = fakeDao.entities["test-uuid-1"]
        assertEquals("test-uuid-1", savedEntity?.id)
        assertEquals(SyncState.PENDING_UPLOAD, savedEntity?.syncState)
        assertEquals(-37.796, savedEntity?.coarseLatitude ?: 0.0, 1e-4)
    }

    @Test
    fun `deleteObservation marks syncState as PENDING_DELETE`() {
        val existingEntity = ObservationEntity(
            id = "test-uuid-2",
            userId = "anonymous_user",
            speciesId = "dandelion",
            scientificName = "Taraxacum officinale",
            commonName = "Dandelion",
            observedAtEpochMs = System.currentTimeMillis(),
            coarseLatitude = -37.796,
            coarseLongitude = 144.961,
            habitatName = "LAWN",
            localPhotoPath = "/tmp/photo.jpg",
            remotePhotoUrl = null,
            headingDegrees = null,
            relativeScore = 0.5,
            contextSource = "DEMO_FALLBACK",
            syncState = SyncState.SYNCED
        )
        fakeDao.entities[existingEntity.id] = existingEntity

        repository.deleteObservation("test-uuid-2")

        assertEquals(SyncState.PENDING_DELETE, fakeDao.entities["test-uuid-2"]?.syncState)
    }

    private class FakeObservationDao : ObservationDao {
        val entities = mutableMapOf<String, ObservationEntity>()

        override fun getAllForUser(userId: String): List<ObservationEntity> {
            return entities.values.filter { it.userId == userId && it.syncState != SyncState.PENDING_DELETE }
        }

        override fun getPendingSync(): List<ObservationEntity> {
            return entities.values.filter { it.syncState == SyncState.PENDING_UPLOAD || it.syncState == SyncState.PENDING_DELETE }
        }

        override fun insertOrUpdate(observation: ObservationEntity) {
            entities[observation.id] = observation
        }

        override fun updateSyncStatus(id: String, state: SyncState, remoteUrl: String?) {
            entities[id]?.let {
                entities[id] = it.copy(syncState = state, remotePhotoUrl = remoteUrl)
            }
        }

        override fun deletePermanently(id: String) {
            entities.remove(id)
        }
    }
}
