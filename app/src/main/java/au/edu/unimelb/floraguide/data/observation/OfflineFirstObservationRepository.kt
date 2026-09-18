package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import android.util.Log
import androidx.work.*
import au.edu.unimelb.floraguide.data.local.ObservationDao
import au.edu.unimelb.floraguide.data.local.ObservationEntity
import au.edu.unimelb.floraguide.data.local.SyncState
import au.edu.unimelb.floraguide.domain.model.*
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

private const val TAG = "FloraGuide-OfflineRepo"

class OfflineFirstObservationRepository(
    private val context: Context,
    private val dao: ObservationDao,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val workManager: WorkManager? = null
) : ObservationRepository {

    private val currentUserId: String get() = auth.currentUser?.uid ?: "anonymous_user"
    private val actualWorkManager: WorkManager get() = workManager ?: WorkManager.getInstance(context)
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startRealtimeCloudSync()
    }

    private fun startRealtimeCloudSync() {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).collection("observations")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Firestore snapshot listener failed: ${error.message}")
                    return@addSnapshotListener
                }
                repositoryScope.launch {
                    snapshot?.documents?.forEach { doc ->
                        val id = doc.getString("id") ?: return@forEach
                        val speciesId = doc.getString("speciesId") ?: id
                        val scientificName = doc.getString("scientificName") ?: ""
                        val commonName = doc.getString("commonName") ?: scientificName
                        val observedAtEpochMs = doc.getLong("observedAtEpochMs") ?: System.currentTimeMillis()
                        val coarseLatitude = doc.getDouble("coarseLatitude") ?: 0.0
                        val coarseLongitude = doc.getDouble("coarseLongitude") ?: 0.0
                        val habitatName = doc.getString("habitat") ?: Habitat.TREE_CANOPY.name
                        val remotePhotoUrl = doc.getString("remotePhotoUrl")
                        val relativeScore = doc.getDouble("relativeScore") ?: 0.0
                        val contextSource = doc.getString("contextSource") ?: ContextDataSource.DEMO_FALLBACK.name

                        val entity = ObservationEntity(
                            id = id,
                            userId = uid,
                            speciesId = speciesId,
                            scientificName = scientificName,
                            commonName = commonName,
                            preferredMonthsCsv = "",
                            habitatAffinityJson = "{}",
                            observedAtEpochMs = observedAtEpochMs,
                            coarseLatitude = coarseLatitude,
                            coarseLongitude = coarseLongitude,
                            habitatName = habitatName,
                            localPhotoPath = null,
                            remotePhotoUrl = remotePhotoUrl,
                            headingDegrees = doc.getDouble("headingDegrees")?.toFloat(),
                            relativeScore = relativeScore,
                            contextSource = contextSource,
                            syncState = SyncState.SYNCED
                        )
                        dao.insertOrUpdate(entity)
                    }
                }
            }
    }

    override suspend fun loadAll(): List<Observation> = withContext(Dispatchers.IO) {
        dao.getAllForUser(currentUserId).map { entity ->
            val months = entity.preferredMonthsCsv.split(",")
                .mapNotNull { it.trim().toIntOrNull() }.toSet()

            val habitatMap = mutableMapOf<Habitat, Double>()
            runCatching {
                val json = JSONObject(entity.habitatAffinityJson)
                json.keys().forEach { key ->
                    runCatching { Habitat.valueOf(key) }.getOrNull()?.let { habitat ->
                        habitatMap[habitat] = json.getDouble(key)
                    }
                }
            }

            Observation(
                id = entity.id,
                species = Species(
                    id = entity.speciesId,
                    commonName = entity.commonName,
                    scientificName = entity.scientificName,
                    preferredMonths = months,
                    habitatAffinity = habitatMap,
                    demoNearbyCount = 0
                ),
                observedAt = Instant.ofEpochMilli(entity.observedAtEpochMs),
                coarseLocation = GeoPoint(entity.coarseLatitude, entity.coarseLongitude),
                habitat = Habitat.valueOf(entity.habitatName),
                photoPath = entity.localPhotoPath ?: entity.remotePhotoUrl,
                cloudPhotoUri = entity.remotePhotoUrl,
                headingDegrees = entity.headingDegrees,
                relativeScore = entity.relativeScore,
                contextSource = ContextDataSource.valueOf(entity.contextSource)
            )
        }
    }

    override suspend fun save(observation: Observation) = withContext(Dispatchers.IO) {
        val monthsCsv = observation.species.preferredMonths.joinToString(",")
        val habitatJson = JSONObject().apply {
            observation.species.habitatAffinity.forEach { (h, score) -> put(h.name, score) }
        }.toString()

        val entity = ObservationEntity(
            id = observation.id,
            userId = currentUserId,
            speciesId = observation.species.id,
            scientificName = observation.species.scientificName,
            commonName = observation.species.commonName,
            preferredMonthsCsv = monthsCsv,
            habitatAffinityJson = habitatJson,
            observedAtEpochMs = observation.observedAt.toEpochMilli(),
            coarseLatitude = observation.coarseLocation.latitude,
            coarseLongitude = observation.coarseLocation.longitude,
            habitatName = observation.habitat.name,
            localPhotoPath = observation.photoPath,
            remotePhotoUrl = observation.cloudPhotoUri,
            headingDegrees = observation.headingDegrees,
            relativeScore = observation.relativeScore,
            contextSource = observation.contextSource.name,
            syncState = SyncState.PENDING_UPLOAD
        )
        dao.insertOrUpdate(entity)
        scheduleBackgroundSync()
    }

    suspend fun deleteObservation(id: String) = withContext(Dispatchers.IO) {
        val existing = dao.getAllForUser(currentUserId).firstOrNull { it.id == id } ?: return@withContext
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
