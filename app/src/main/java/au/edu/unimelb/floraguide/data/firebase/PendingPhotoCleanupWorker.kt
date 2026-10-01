package au.edu.unimelb.floraguide.data.firebase

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Cleanup failures do not write to the analysis UI or consume Pl@ntNet quota. */
class PendingPhotoCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString(USER_ID) ?: return Result.failure()
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.uid != uid) return Result.success()
        return try {
            val unfinished = PendingPhotoUploads.cleanup(applicationContext) { auth.currentUser?.uid }(uid)
            // Capped: the journal keeps the work, and the next sign-in or app start schedules it again.
            if (unfinished && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Cleanup setup will retry: ${error.javaClass.simpleName}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FloraGuide-Cleanup"
        private const val USER_ID = "userId"
        private const val MAX_ATTEMPTS = 5

        fun schedule(context: Context, uid: String) {
            val request = OneTimeWorkRequestBuilder<PendingPhotoCleanupWorker>()
                .setInputData(workDataOf(USER_ID to uid))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            // Appending prevents a completion/enqueue race from losing a newly abandoned photo.
            WorkManager.getInstance(context).enqueueUniqueWork("pending-scan-cleanup-$uid", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
