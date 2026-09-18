// File: app/src/main/java/au/edu/unimelb/floraguide/data/observation/ObservationSyncWorker.kt
package au.edu.unimelb.floraguide.data.observation

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import au.edu.unimelb.floraguide.data.local.ObservationDao
import au.edu.unimelb.floraguide.data.local.SyncState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File

open class ObservationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    protected val dao: ObservationDao,
    firestore: FirebaseFirestore? = null,
    storage: FirebaseStorage? = null,
    auth: FirebaseAuth? = null
) : CoroutineWorker(appContext, workerParams) {

    private val actualFirestore: FirebaseFirestore by lazy { firestore ?: FirebaseFirestore.getInstance() }
    private val actualStorage: FirebaseStorage by lazy { storage ?: FirebaseStorage.getInstance() }
    private val actualAuth: FirebaseAuth by lazy { auth ?: FirebaseAuth.getInstance() }

    protected open fun getUserId(): String? = actualAuth.currentUser?.uid
    protected open fun provideDao(): ObservationDao = dao

    override suspend fun doWork(): Result {
        val userId = getUserId() ?: return Result.failure()
        val pendingItems = provideDao().getPendingSync().filter { it.userId == userId }

        for (item in pendingItems) {
            try {
                if (item.syncState == SyncState.PENDING_DELETE) {
                    actualFirestore.collection("users").document(userId)
                        .collection("observations").document(item.id).delete().await()
                    provideDao().deletePermanently(item.id)
                    continue
                }

                var remoteUrl = item.remotePhotoUrl
                if (remoteUrl == null && !item.localPhotoPath.isNullOrBlank()) {
                    val file = File(item.localPhotoPath)
                    if (file.exists()) {
                        val storageRef = actualStorage.reference.child("users/$userId/photos/${item.id}.jpg")
                        storageRef.putFile(Uri.fromFile(file)).await()
                        remoteUrl = storageRef.downloadUrl.await().toString()
                    }
                }

                val firestoreDoc = mapOf(
                    "id" to item.id,
                    "userId" to userId,
                    "speciesId" to item.speciesId,
                    "scientificName" to item.scientificName,
                    "commonName" to item.commonName,
                    "observedAtEpochMs" to item.observedAtEpochMs,
                    "coarseLatitude" to item.coarseLatitude,
                    "coarseLongitude" to item.coarseLongitude,
                    "habitat" to item.habitatName,
                    "remotePhotoUrl" to remoteUrl,
                    "headingDegrees" to item.headingDegrees,
                    "relativeScore" to item.relativeScore,
                    "contextSource" to item.contextSource
                )

                actualFirestore.collection("users").document(userId)
                    .collection("observations").document(item.id).set(firestoreDoc).await()

                provideDao().updateSyncStatus(item.id, SyncState.SYNCED, remoteUrl)
            } catch (e: Exception) {
                if (item.retryCount >= 3) {
                    provideDao().insertOrUpdate(item.copy(syncState = SyncState.FAILED))
                } else {
                    provideDao().insertOrUpdate(item.copy(retryCount = item.retryCount + 1))
                    return Result.retry()
                }
            }
        }
        return Result.success()
    }
}
