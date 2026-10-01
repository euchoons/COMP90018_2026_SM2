package au.edu.unimelb.floraguide.domain.usecase

import java.net.URI

/** Only independently named scan uploads are eligible for automatic cleanup. */
enum class PendingPhotoState { ACTIVE, SAVING, SAVING_ABANDONED, ABANDONED }

data class PendingPhotoRecord(
    val userId: String,
    val gsUri: String,
    val processId: String,
    val state: PendingPhotoState = PendingPhotoState.ACTIVE,
)

/** Implementations must durably write each record before returning from put/remove. */
interface PendingPhotoRecordStore {
    fun readAll(): List<PendingPhotoRecord>
    fun put(record: PendingPhotoRecord)
    fun remove(gsUri: String)
}

/**
 * Durable ownership of unconfirmed scan photos. No network operations run here.
 * ACTIVE/SAVING records in this process are leased; records from a terminated process
 * can be checked against the observation database before deletion.
 */
class PendingPhotoRegistry(
    private val store: PendingPhotoRecordStore,
    private val processId: String,
    private val scheduleCleanup: (String) -> Unit = {},
    private val reportFailure: (Exception) -> Unit = {},
) {
    private val records = store.readAll().associateBy { it.gsUri }.toMutableMap()
    private val inFlightUploads = mutableSetOf<String>()
    private val cleanupClaims = mutableSetOf<String>()

    /** A failed journal write must prevent the upload, rather than create an untracked object. */
    @Synchronized
    fun registerUpload(userId: String, gsUri: String) {
        require(isManagedScanPhoto(userId, gsUri)) { "Not an independently owned scan photo." }
        check(gsUri !in records) { "This scan upload is already registered." }
        val record = PendingPhotoRecord(userId, gsUri, processId)
        store.put(record)
        records[gsUri] = record
        inFlightUploads += gsUri
    }

    /** Called only when the SDK upload task is terminal, including cancellation. */
    @Synchronized
    fun uploadFinished(gsUri: String) {
        inFlightUploads -= gsUri
        records[gsUri]?.takeIf(::eligible)?.let { schedule(it.userId) }
    }

    /** Unknown/legacy content-addressed objects are deliberately never queued for deletion. */
    @Synchronized
    fun abandon(gsUri: String?) {
        val old = records[gsUri] ?: return
        val state = when (old.state) {
            PendingPhotoState.SAVING, PendingPhotoState.SAVING_ABANDONED -> PendingPhotoState.SAVING_ABANDONED
            else -> PendingPhotoState.ABANDONED
        }
        updateBestEffort(old.copy(state = state))
        schedule(old.userId)
    }

    /** Protect the reference BEFORE the local observation save starts. */
    @Synchronized
    fun beginSave(gsUri: String?) {
        val old = records[gsUri] ?: return
        check(old.processId == processId && old.state == PendingPhotoState.ACTIVE && gsUri !in cleanupClaims) {
            "This photo session is no longer available. Retry the scan before saving."
        }
        val saving = old.copy(state = PendingPhotoState.SAVING)
        store.put(saving)
        records[old.gsUri] = saving
    }

    /** The observation now owns the photo. A failed journal removal is safe: recovery checks the DB. */
    @Synchronized
    fun saved(gsUri: String?) {
        val old = records[gsUri] ?: return
        try {
            store.remove(old.gsUri)
            records.remove(old.gsUri)
        } catch (error: Exception) {
            reportFailure(error)
        }
    }

    @Synchronized
    fun saveFailed(gsUri: String?) {
        val old = records[gsUri] ?: return
        val state = if (old.state == PendingPhotoState.SAVING_ABANDONED) {
            PendingPhotoState.ABANDONED
        } else {
            PendingPhotoState.ACTIVE
        }
        updateBestEffort(old.copy(state = state))
        if (state == PendingPhotoState.ABANDONED) schedule(old.userId)
    }

    /** Called after authentication/startup. Entries for another account stay queued, not deleted. */
    @Synchronized
    fun resumeCleanup(userId: String) {
        if (records.values.any { it.userId == userId && eligible(it) }) schedule(userId)
    }

    @Synchronized
    fun candidates(userId: String): List<PendingPhotoRecord> = records.values.filter {
        it.userId == userId && eligible(it) && it.gsUri !in inFlightUploads && it.gsUri !in cleanupClaims
    }

    @Synchronized
    fun claim(gsUri: String): Boolean {
        val record = records[gsUri] ?: return false
        if (!eligible(record) || gsUri in inFlightUploads || gsUri in cleanupClaims) return false
        cleanupClaims += gsUri
        return true
    }

    @Synchronized
    fun releaseClaim(gsUri: String) { cleanupClaims -= gsUri }

    @Synchronized
    fun hasQueuedCleanup(userId: String): Boolean = records.values.any { it.userId == userId && eligible(it) }

    @Synchronized
    fun snapshot(): List<PendingPhotoRecord> = records.values.toList()

    private fun eligible(record: PendingPhotoRecord): Boolean =
        isManagedScanPhoto(record.userId, record.gsUri) &&
            (record.processId != processId || record.state == PendingPhotoState.ABANDONED)

    private fun updateBestEffort(record: PendingPhotoRecord) {
        records[record.gsUri] = record
        try { store.put(record) } catch (error: Exception) { reportFailure(error) }
    }

    private fun schedule(userId: String) {
        try { scheduleCleanup(userId) } catch (error: Exception) { reportFailure(error) }
    }

    companion object {
        private val SCAN_NAME = Regex("scan-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9a-f]{64}\\.(jpg|png)")

        fun isManagedScanPhoto(userId: String, gsUri: String): Boolean = try {
            val uri = URI(gsUri)
            val prefix = "/plant_photos/$userId/"
            uri.scheme == "gs" && !uri.host.isNullOrBlank() && uri.query == null && uri.fragment == null &&
                uri.path.startsWith(prefix) && SCAN_NAME.matches(uri.path.removePrefix(prefix))
        } catch (_: Exception) {
            false
        }
    }
}
