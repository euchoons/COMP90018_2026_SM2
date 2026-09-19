package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import au.edu.unimelb.floraguide.data.local.ObservationDao
import au.edu.unimelb.floraguide.data.local.ObservationEntity
import au.edu.unimelb.floraguide.data.local.SyncState
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.repository.ObservationRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class OfflineFirstObservationRepository(
    private val context: Context,
    private val dao: ObservationDao,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val workManager: WorkManager? = null,
) : ObservationRepository {
    private val currentUserId: String get() = auth.currentUser?.uid ?: LOCAL_GUEST

    private suspend fun prepareUser() {
        val legacy = PreferencesObservationRepository(context).loadAll()
        dao.importLegacy(legacy.map { it.toEntity(legacyOwner(it)) })
    }

    override suspend fun loadAll(): List<Observation> {
        val uid = currentUserId
        return withContext(Dispatchers.IO) {
            prepareUser()
            dao.getAllForUser(uid).map { it.toObservation() }
        }
    }

    // One account per subscription. The ViewModel cancels/rebinds this flow on session changes.
    override fun observeAll(): Flow<List<Observation>> = channelFlow {
        val uid = currentUserId
        withContext(Dispatchers.IO) { prepareUser() }
        launch {
            dao.observeForUser(uid).collect { rows ->
                if (currentUserId == uid) send(rows.map { it.toObservation() })
            }
        }
        if (uid == LOCAL_GUEST) {
            awaitClose {}
        } else {
            dao.retryFailed(uid)
            schedule(uid)
            val registration = firestore.collection("users").document(uid).collection("observations")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("FloraGuide-Sync", "Cloud read failed; retaining local records.")
                    } else if (snapshot != null) {
                        launch(Dispatchers.IO) {
                            for (change in snapshot.documentChanges) {
                                if (currentUserId != uid) return@launch
                                // Local pending writes/deletes always win over cloud callbacks.
                                if (change.type == DocumentChange.Type.REMOVED) {
                                    dao.removeRemote(uid, change.document.id, change.document.getLong("revision") ?: 0)
                                } else {
                                    decodeRemote(change.document, uid)?.let { dao.mergeRemote(it) }
                                }
                            }
                        }
                    }
                }
            awaitClose { registration.remove() }
        }
    }

    override suspend fun save(observation: Observation) {
        val uid = currentUserId
        withContext(Dispatchers.IO) {
            prepareUser()
            dao.saveLocal(observation.toEntity(uid))
            if (uid != LOCAL_GUEST) schedule(uid)
        }
    }

    override suspend fun delete(id: String) {
        val uid = currentUserId
        withContext(Dispatchers.IO) {
            dao.markDeleted(uid, id)
            if (uid != LOCAL_GUEST) schedule(uid)
        }
    }

    override suspend fun importLocalObservations() {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in before importing local observations." }
        withContext(Dispatchers.IO) {
            prepareUser()
            dao.claimLocalGuest(uid)
            schedule(uid)
        }
    }

    override suspend fun retrySync() {
        val uid = requireNotNull(auth.currentUser?.uid) { "Sign in before retrying cloud sync." }
        dao.retryFailed(uid)
        schedule(uid)
    }

    private fun schedule(uid: String) {
        ObservationSyncWorker.schedule(context, uid, workManager ?: WorkManager.getInstance(context))
    }

    private fun decodeRemote(doc: DocumentSnapshot, uid: String): ObservationEntity? = runCatching {
        require(doc.getString("userId") == null || doc.getString("userId") == uid)
        val encoded = doc.getString("observationJson")
        if (encoded != null) {
            val observation = requireNotNull(ObservationJsonCodec.decode(JSONObject(encoded)))
            require(observation.id == doc.id)
            observation.copy(photoPath = null).toEntity(uid).copy(
                revision = doc.getLong("revision") ?: 0, syncState = SyncState.SYNCED,
            )
        } else {
            ObservationEntity(
                id = doc.id, userId = uid, speciesId = requireNotNull(doc.getString("speciesId")),
                scientificName = requireNotNull(doc.getString("scientificName")),
                commonName = doc.getString("commonName") ?: requireNotNull(doc.getString("scientificName")),
                observedAtEpochMs = requireNotNull(doc.getLong("observedAtEpochMs")),
                coarseLatitude = requireNotNull(doc.getDouble("coarseLatitude")),
                coarseLongitude = requireNotNull(doc.getDouble("coarseLongitude")),
                habitatName = requireNotNull(doc.getString("habitat")), localPhotoPath = null,
                remotePhotoUrl = doc.getString("remotePhotoUrl"), headingDegrees = doc.getDouble("headingDegrees")?.toFloat(),
                relativeScore = requireNotNull(doc.getDouble("relativeScore")),
                contextSource = requireNotNull(doc.getString("contextSource")), syncState = SyncState.SYNCED,
                revision = doc.getLong("revision") ?: 0,
            ).also { it.toObservation() } // Validate old enum and observation data before storing.
        }
    }.getOrNull()

    companion object {
        const val LOCAL_GUEST = "anonymous_user"
        internal fun legacyOwner(observation: Observation): String =
            observation.cloudPhotoUri?.let { Regex("^gs://[^/]+/(?:plant_photos|users)/([^/]+)/").find(it)?.groupValues?.get(1) }
                ?: LOCAL_GUEST
    }
}
