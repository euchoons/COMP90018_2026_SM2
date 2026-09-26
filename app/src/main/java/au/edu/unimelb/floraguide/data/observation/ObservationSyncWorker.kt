package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import android.util.Log
import androidx.work.*
import au.edu.unimelb.floraguide.data.firebase.FirebasePhotoStorage
import au.edu.unimelb.floraguide.data.local.FloraGuideDatabase
import au.edu.unimelb.floraguide.data.local.ObservationDao
import au.edu.unimelb.floraguide.data.local.ObservationEntity
import au.edu.unimelb.floraguide.data.local.SyncState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

open class ObservationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val dao: ObservationDao,
    private val firestore: FirebaseFirestore? = null,
    private val auth: FirebaseAuth? = null,
) : CoroutineWorker(appContext, workerParams) {

    constructor(appContext: Context, workerParams: WorkerParameters) : this(
        appContext, workerParams, FloraGuideDatabase.getInstance(appContext).observationDao(),
    )

    protected open fun getUserId(): String? = (auth ?: FirebaseAuth.getInstance()).currentUser?.uid

    protected open suspend fun upload(item: ObservationEntity): String? {
        val uid = item.userId
        check(getUserId() == uid) { "Account changed before upload." }
        val photo = item.remotePhotoUrl ?: item.localPhotoPath?.let {
            FirebasePhotoStorage(applicationContext, expectedUserId = uid).uploadPhoto(it).gsUri
        }
        currentCoroutineContext().ensureActive()
        check(getUserId() == uid) { "Account changed during upload." }
        val observation = item.toObservation().copy(photoPath = null, cloudPhotoUri = photo)
        val data = mapOf(
            "id" to item.id, "userId" to uid, "revision" to item.revision,
            "observationJson" to ObservationJsonCodec.encode(observation).toString(),
            "speciesId" to item.speciesId, "scientificName" to item.scientificName,
            "commonName" to item.commonName, "observedAtEpochMs" to item.observedAtEpochMs,
            "coarseLatitude" to item.coarseLatitude, "coarseLongitude" to item.coarseLongitude,
            "habitat" to item.habitatName, "remotePhotoUrl" to photo,
            "headingDegrees" to item.headingDegrees, "relativeScore" to item.relativeScore,
            "contextSource" to item.contextSource,
        )
        (firestore ?: FirebaseFirestore.getInstance()).collection("users").document(uid)
            .collection("observations").document(item.id).set(data).await()
        return photo
    }

    protected open suspend fun deleteRemote(item: ObservationEntity) {
        val uid = item.userId
        check(getUserId() == uid) { "Account changed before deletion." }

        // Clean up Cloud Storage image if present
        item.remotePhotoUrl?.let { cloudUri ->
            if (cloudUri.startsWith("gs://")) {
                runCatching {
                    FirebasePhotoStorage(applicationContext, expectedUserId = uid).deletePhoto(cloudUri)
                }.onFailure { error ->
                    Log.w("ObservationSyncWorker", "Failed to delete remote photo $cloudUri: ${error.message}")
                }
            }
        }

        // MANDATORY: Ensure the job wasn't cancelled and the account didn't change while waiting for Storage
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(getUserId() == uid) { "Account changed during deletion." }

        (firestore ?: FirebaseFirestore.getInstance()).collection("users").document(uid)
            .collection("observations").document(item.id).delete().await()
    }

    override suspend fun doWork(): Result {
        val uid = inputData.getString(USER_ID) ?: return Result.failure()
        if (getUserId() != uid) return Result.success()
        var retry = false
        for (item in dao.getPendingSync(uid)) {
            currentCoroutineContext().ensureActive()
            if (getUserId() != uid) return Result.success()
            try {
                if (item.syncState == SyncState.PENDING_DELETE) {
                    deleteRemote(item)
                    currentCoroutineContext().ensureActive()
                    dao.acknowledgeDeletion(uid, item.id, item.revision)
                } else {
                    val photo = upload(item)
                    currentCoroutineContext().ensureActive()
                    dao.acknowledgeUpload(uid, item.id, item.revision, photo)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (getUserId() != uid) return Result.success()
                if (dao.recordFailure(uid, item.id, item.revision, item.syncState) != 0) {
                    if (item.syncState == SyncState.PENDING_DELETE || item.retryCount < 3) retry = true
                }
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val USER_ID = "userId"
        fun schedule(context: Context, uid: String, workManager: WorkManager = WorkManager.getInstance(context)) {
            require(uid != "anonymous_user")
            val work = OneTimeWorkRequestBuilder<ObservationSyncWorker>()
                .setInputData(workDataOf(USER_ID to uid))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            workManager.enqueueUniqueWork("observation-sync-$uid", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}
