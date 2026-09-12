// File: app/src/main/java/au/edu/unimelb/floraguide/data/observation/OfflineFirstObservationRepository.kt
package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import androidx.work.*
import au.edu.unimelb.floraguide.data.local.ObservationDao
import au.edu.unimelb.floraguide.data.local.ObservationEntity
import au.edu.unimelb.floraguide.data.local.SyncState
import au.edu.unimelb.floraguide.domain.model.*
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.time.Instant

class OfflineFirstObservationRepository(
    private val context: Context,
    private val dao: ObservationDao,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val workManager: WorkManager? = null
) : ObservationRepository {

    private val currentUserId: String get() = auth.currentUser?.uid ?: "anonymous_user"
    private val actualWorkManager: WorkManager get() = workManager ?: WorkManager.getInstance(context)

    override fun loadAll(): List<Observation> {
        return dao.getAllForUser(currentUserId).map { entity ->
            Observation(
                id = entity.id,
                species = Species(
                    id = entity.speciesId,
                    commonName = entity.commonName,
                    scientificName = entity.scientificName,
                    preferredMonths = emptySet(),
                    habitatAffinity = emptyMap(),
                    demoNearbyCount = 0
                ),
                observedAt = Instant.ofEpochMilli(entity.observedAtEpochMs),
                coarseLocation = GeoPoint(entity.coarseLatitude, entity.coarseLongitude),
                habitat = Habitat.valueOf(entity.habitatName),
                photoPath = entity.remotePhotoUrl ?: entity.localPhotoPath,
                headingDegrees = entity.headingDegrees,
                relativeScore = entity.relativeScore,
                contextSource = ContextDataSource.valueOf(entity.contextSource)
            )
        }
    }

    override fun save(observation: Observation) {
        val entity = ObservationEntity(
            id = observation.id,
            userId = currentUserId,
            speciesId = observation.species.id,
            scientificName = observation.species.scientificName,
            commonName = observation.species.commonName,
            observedAtEpochMs = observation.observedAt.toEpochMilli(),
            coarseLatitude = observation.coarseLocation.latitude,
            coarseLongitude = observation.coarseLocation.longitude,
            habitatName = observation.habitat.name,
            localPhotoPath = observation.photoPath,
            remotePhotoUrl = null,
            headingDegrees = observation.headingDegrees,
            relativeScore = observation.relativeScore,
            contextSource = observation.contextSource.name,
            syncState = SyncState.PENDING_UPLOAD
        )
        dao.insertOrUpdate(entity)
        scheduleBackgroundSync()
    }

    fun deleteObservation(id: String) {
        val existing = dao.getAllForUser(currentUserId).firstOrNull { it.id == id } ?: return
        dao.insertOrUpdate(existing.copy(syncState = SyncState.PENDING_DELETE))
        scheduleBackgroundSync()
    }

    private fun scheduleBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWork = OneTimeWorkRequestBuilder<ObservationSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        actualWorkManager.enqueueUniqueWork(
            "ObservationSyncWorker",
            ExistingWorkPolicy.KEEP,
            syncWork
        )
    }
}
