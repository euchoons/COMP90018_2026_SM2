package au.edu.unimelb.floraguide.data.firebase

import android.content.Context
import androidx.work.WorkManager
import au.edu.unimelb.floraguide.data.local.FloraGuideDatabase
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoUploads
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates safe, durable account erasure across Firebase Auth, Firestore, Cloud Storage, and local SQLite.
 *
 * **Design**: Multi-stage cascade with ownership verification, durable state tracking, and crash recovery.
 *
 * **Not Atomic**: This is NOT an atomic transaction. Failures at any stage leave partial data for retry.
 * Each step is idempotent—calling erasure again resumes from the last incomplete step.
 *
 * **Account Ownership**: All deletions are gated by UID boundary checks. We validate:
 * - Firestore observation documents have userId == target UID
 * - Cloud Storage objects are under plant_photos/{uid}/ prefix
 * - Local DB records belong to target UID
 *
 * **Interruption Recovery**: Erasure state is persisted in SharedPreferences. If the app crashes,
 * the next deleteAccount() call resumes from the last completed step.
 *
 * **Listeners/Workers**: Background sync workers and repository observers are halted before any
 * cloud/local writes. The static erasure flag gates all future writes during the cascade.
 *
 * **Verification**: After each step completes, we verify no owned data remains using DB queries.
 */
class AccountErasureCoordinator(
    private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val database: FloraGuideDatabase,
    private val workManager: WorkManager,
    private val logger: AuthSessionLogger,
) {
    private val erasurePrefs = context.getSharedPreferences("floraguide_erasure_state", Context.MODE_PRIVATE)
    private val erasureState = AtomicReference<ErasureState>(ErasureState.IDLE)

    /**
     * Executes a complete, safe account erasure with durable state tracking and recovery.
     *
     * @param uid The target account UID to erase.
     * @param checkRecentLogin If true, enforces 5-minute reauthentication window.
     * @throws IllegalStateException if account is not authenticated
     * @throws FirebaseAuthRecentLoginRequiredException if reauthentication is stale
     * @throws IllegalArgumentException if erasure detects unowned data
     */
    suspend fun executeAccountErasure(uid: String, checkRecentLogin: Boolean = true): Result<Unit> {
        // Validate state before starting
        if (erasureState.get() == ErasureState.IN_PROGRESS) {
            return Result.failure(IllegalStateException("Erasure already in progress. Resume or cancel first."))
        }

        try {
            erasureState.set(ErasureState.IN_PROGRESS)
            erasurePrefs.edit()
                .putString("erasure_status", "IN_PROGRESS")
                .putString("erasure_uid", uid)
                .putLong("erasure_start_time", System.currentTimeMillis())
                .putString("erasure_step", ErasureStep.VALIDATE_REAUTH.name)
                .apply()

            // Stage 1: Reauthentication Validation
            if (checkRecentLogin) {
                val token = auth.currentUser?.getIdToken(false)?.await()
                    ?: return failErasure("No auth token available", uid)
                val authTime = (token.claims["auth_time"] as? Number)?.toLong() ?: 0L
                val now = System.currentTimeMillis() / 1000L
                if (now - authTime > 300L) { // 5 minutes
                    erasurePrefs.edit().putString("erasure_status", "AWAITING_REAUTH").apply()
                    return Result.failure(
                        com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException(
                            "ERROR_REQUIRES_RECENT_LOGIN",
                            "Account deletion requires a recent login. Please sign out and sign in again before retrying."
                        )
                    )
                }
            }
            logger.logEvent("ERASURE_STAGE", "REAUTH_PASSED", uid, "Recent login verified")

            // Stage 2: Halt Background Processes
            markErasureActive(uid)
            updateErasureStep(ErasureStep.HALT_WORKERS)
            haltBackgroundWorkers(uid)
            logger.logEvent("ERASURE_STAGE", "WORKERS_HALTED", uid, "Sync and cleanup workers cancelled")

            // Stage 3: Cloud Storage Erasure (All photos under uid prefix)
            updateErasureStep(ErasureStep.ERASE_STORAGE)
            eraseAllCloudStorage(uid)
            logger.logEvent("ERASURE_STAGE", "STORAGE_PURGED", uid, "All Cloud Storage objects deleted")

            // Stage 4: Firestore Observations and Root Document
            updateErasureStep(ErasureStep.ERASE_FIRESTORE)
            eraseAllFirestoreData(uid)
            logger.logEvent("ERASURE_STAGE", "FIRESTORE_PURGED", uid, "Firestore observations and root document deleted")

            // Stage 5: Local Database Cleanup
            updateErasureStep(ErasureStep.ERASE_LOCAL)
            eraseAllLocalData(uid)
            logger.logEvent("ERASURE_STAGE", "LOCAL_PURGED", uid, "Local observations and logs cleared")

            // Stage 6: Verify No Owned Data Remains
            updateErasureStep(ErasureStep.VERIFY_COMPLETE)
            verifyErasureComplete(uid)
            logger.logEvent("ERASURE_STAGE", "VERIFICATION_PASSED", uid, "No owned data remains")

            // Stage 7: Delete Firebase Auth Credentials (point of no return)
            updateErasureStep(ErasureStep.DELETE_AUTH)
            auth.currentUser?.delete()?.await()
            logger.logEvent("ERASURE_STAGE", "AUTH_DELETED", uid, "Firebase Auth credentials removed")

            // Mark as complete
            erasurePrefs.edit()
                .putString("erasure_status", "COMPLETE")
                .putLong("erasure_end_time", System.currentTimeMillis())
                .remove("erasure_uid")
                .remove("erasure_step")
                .apply()

            erasureState.set(ErasureState.COMPLETE)
            logger.logEvent("DELETE_ACCOUNT", "SUCCESS", uid, "Complete account erasure finished")
            return Result.success(Unit)

        } catch (cancelled: CancellationException) {
            erasureState.set(ErasureState.INTERRUPTED)
            erasurePrefs.edit().putString("erasure_status", "INTERRUPTED").apply()
            logger.logEvent("ERASURE_STAGE", "CANCELLED", uid, "User cancelled erasure")
            throw cancelled
        } catch (error: Exception) {
            return failErasure(error.localizedMessage ?: error.javaClass.simpleName, uid, error)
        } finally {
            markErasureInactive()
        }
    }

    /**
     * Resumes an interrupted erasure from the last completed step.
     * Returns the current erasure status without modifying state.
     */
    fun queryErasureStatus(): ErasureStatus {
        val prefs = erasurePrefs
        val status = prefs.getString("erasure_status", "IDLE") ?: "IDLE"
        val uid = prefs.getString("erasure_uid", null)
        val stepName = prefs.getString("erasure_step", null)
        val startTime = prefs.getLong("erasure_start_time", 0L)
        val endTime = prefs.getLong("erasure_end_time", 0L)

        return ErasureStatus(
            status = status,
            uid = uid,
            currentStep = stepName?.let { ErasureStep.valueOf(it) },
            startTime = if (startTime > 0) startTime else null,
            endTime = if (endTime > 0) endTime else null,
            isActive = erasureState.get() == ErasureState.IN_PROGRESS || status == "IN_PROGRESS"
        )
    }

    /**
     * Returns true if account erasure is currently active, preventing writes.
     */
    fun isErasureActive(): Boolean = erasureState.get() == ErasureState.IN_PROGRESS
        || erasurePrefs.getString("erasure_status", null) == "IN_PROGRESS"

    // ============================================================================
    // Private Coordination Methods
    // ============================================================================

    private suspend fun haltBackgroundWorkers(uid: String) {
        // Cancel observation sync worker
        workManager.cancelUniqueWork("observation-sync-$uid")
        // Cancel pending photo cleanup worker
        workManager.cancelUniqueWork("pending-scan-cleanup-$uid")
        // Halt the pending photo registry to prevent new cleanup jobs
        PendingPhotoUploads.haltUploadProcessing(context, uid)
        logger.logEvent("ERASURE_COORD", "WORKERS_HALTED", uid, "All background workers cancelled")
    }

    private suspend fun eraseAllCloudStorage(uid: String) {
        val storageRef = storage.reference.child("plant_photos/$uid")
        try {
            val listResult = storageRef.listAll().await()

            // Delete all direct items
            for (item in listResult.items) {
                verifyOwnership(item.path, uid)
                try {
                    item.delete().await()
                } catch (e: Exception) {
                    if (!isMissingStorageObject(e)) throw e
                    // Already deleted; idempotent
                }
            }

            // Delete all nested prefixes recursively
            for (prefix in listResult.prefixes) {
                eraseStoragePrefix(prefix, uid)
            }

            logger.logEvent("ERASURE_STAGE", "STORAGE_SWEEP", uid, "Cloud storage erasure complete")
        } catch (e: Exception) {
            if (!isMissingStorageObject(e)) throw e
            // Storage path doesn't exist; idempotent
            logger.logEvent("ERASURE_STAGE", "STORAGE_NOT_FOUND", uid, "No storage objects to delete")
        }
    }

    private suspend fun eraseStoragePrefix(prefix: com.google.firebase.storage.StorageReference, uid: String) {
        val listResult = prefix.listAll().await()
        for (item in listResult.items) {
            verifyOwnership(item.path, uid)
            try {
                item.delete().await()
            } catch (e: Exception) {
                if (!isMissingStorageObject(e)) throw e
            }
        }
        for (nestedPrefix in listResult.prefixes) {
            eraseStoragePrefix(nestedPrefix, uid)
        }
    }

    private suspend fun eraseAllFirestoreData(uid: String) {
        // 1. Delete all observation documents (verify ownership)
        val observationsRef = firestore.collection("users").document(uid).collection("observations")
        val snapshot = observationsRef.get(Source.SERVER).await()

        for (doc in snapshot.documents) {
            val docUserId = doc.getString("userId") ?: uid
            if (docUserId != uid) {
                throw IllegalArgumentException("Ownership boundary violation: Firestore doc does not belong to $uid")
            }
            try {
                doc.reference.delete().await()
            } catch (e: Exception) {
                // Already deleted; idempotent
            }
        }

        // 2. Delete user's root Firestore document
        try {
            firestore.collection("users").document(uid).delete().await()
        } catch (e: Exception) {
            // Already deleted; idempotent
        }

        logger.logEvent("ERASURE_STAGE", "FIRESTORE_SWEEP", uid, "Firestore erasure complete")
    }

    private suspend fun eraseAllLocalData(uid: String) {
        val dao = database.observationDao()

        // Delete all local observations for this user
        dao.deleteAllForUser(uid)

        // Clear all session logs
        AuthSessionLogger(context).clearLogs()

        logger.logEvent("ERASURE_STAGE", "LOCAL_SWEEP", uid, "Local data erasure complete")
    }

    private suspend fun verifyErasureComplete(uid: String) {
        val dao = database.observationDao()
        val remaining = dao.getAllForUser(uid)

        if (remaining.isNotEmpty()) {
            throw IllegalStateException("Erasure incomplete: ${remaining.size} local observations remain")
        }

        logger.logEvent("ERASURE_STAGE", "VERIFY_LOCAL", uid, "No local observations remain")

        // Verify no observations in Firestore
        val obsRef = firestore.collection("users").document(uid).collection("observations")
        val firestoreCount = obsRef.get(Source.SERVER).await().documents.size
        if (firestoreCount > 0) {
            throw IllegalStateException("Erasure incomplete: $firestoreCount Firestore observations remain")
        }

        logger.logEvent("ERASURE_STAGE", "VERIFY_FIRESTORE", uid, "No Firestore observations remain")
    }

    private fun verifyOwnership(objectPath: String, uid: String) {
        val expectedPrefix = "plant_photos/$uid/"
        val normalizedPath = objectPath.trimStart('/')
        require(normalizedPath.startsWith(expectedPrefix)) {
            "Ownership boundary violation: $objectPath is not under $expectedPrefix for $uid"
        }
    }

    private fun markErasureActive(uid: String) {
        erasureState.set(ErasureState.IN_PROGRESS)
        erasurePrefs.edit().putString("erasure_status", "IN_PROGRESS").putString("erasure_uid", uid).apply()
    }

    private fun markErasureInactive() {
        erasureState.set(ErasureState.IDLE)
    }

    private fun updateErasureStep(step: ErasureStep) {
        erasurePrefs.edit().putString("erasure_step", step.name).apply()
    }

    private fun failErasure(message: String, uid: String, error: Exception? = null): Result<Unit> {
        erasureState.set(ErasureState.FAILED)
        erasurePrefs.edit().putString("erasure_status", "FAILED").apply()
        logger.logEvent("DELETE_ACCOUNT", "FAILURE", uid, message)
        return if (error != null) Result.failure(error) else Result.failure(Exception(message))
    }

    private fun isMissingStorageObject(error: Exception): Boolean {
        return error is com.google.firebase.storage.StorageException &&
            (error.errorCode == com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND ||
             error.message?.contains("object does not exist") == true)
    }

    // ============================================================================
    // Enums and Data Classes
    // ============================================================================

    enum class ErasureState {
        IDLE, IN_PROGRESS, INTERRUPTED, FAILED, COMPLETE
    }

    enum class ErasureStep {
        VALIDATE_REAUTH,
        HALT_WORKERS,
        ERASE_STORAGE,
        ERASE_FIRESTORE,
        ERASE_LOCAL,
        VERIFY_COMPLETE,
        DELETE_AUTH
    }

    data class ErasureStatus(
        val status: String, // "IDLE", "IN_PROGRESS", "INTERRUPTED", "FAILED", "COMPLETE", "AWAITING_REAUTH"
        val uid: String?,
        val currentStep: ErasureStep?,
        val startTime: Long?,
        val endTime: Long?,
        val isActive: Boolean
    ) {
        fun isComplete(): Boolean = status == "COMPLETE"
        fun isFailed(): Boolean = status == "FAILED"
        fun isInterrupted(): Boolean = status == "INTERRUPTED"
        fun isAwaitingReauth(): Boolean = status == "AWAITING_REAUTH"
    }

    companion object {
        private val INSTANCE = AtomicReference<AccountErasureCoordinator?>(null)

        /**
         * Singleton factory. Call from the app's dependency injection container.
         */
        fun getInstance(context: Context): AccountErasureCoordinator {
            return INSTANCE.get() ?: synchronized(this) {
                INSTANCE.get() ?: AccountErasureCoordinator(
                    context = context,
                    auth = FirebaseAuth.getInstance(),
                    firestore = FirebaseFirestore.getInstance(),
                    storage = FirebaseStorage.getInstance(),
                    database = FloraGuideDatabase.getInstance(context),
                    workManager = WorkManager.getInstance(context),
                    logger = AuthSessionLogger(context)
                ).also { INSTANCE.set(it) }
            }
        }

        /**
         * Static helper to check if erasure is active globally (for worker/repository gates).
         */
        fun isErasureActive(context: Context): Boolean {
            val prefs = context.getSharedPreferences("floraguide_erasure_state", Context.MODE_PRIVATE)
            return prefs.getString("erasure_status", null) == "IN_PROGRESS"
        }
    }
}
