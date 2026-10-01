package au.edu.unimelb.floraguide.data.firebase

import android.content.Context
import android.util.Log
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoRecord
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoRecordStore
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoRegistry
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoState
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

/** One process-wide registry is shared by the uploader, ViewModel and cleanup worker. */
object PendingPhotoUploads {
    private const val TAG = "FloraGuide-Cleanup"
    private val processId = UUID.randomUUID().toString()
    @Volatile private var instance: PendingPhotoRegistry? = null

    fun get(context: Context): PendingPhotoRegistry = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(context: Context): PendingPhotoRegistry {
        val preferences = context.getSharedPreferences("pending_scan_photos_v1", Context.MODE_PRIVATE)
        val store = object : PendingPhotoRecordStore {
            override fun readAll(): List<PendingPhotoRecord> = preferences.all.mapNotNull { (uri, value) ->
                try {
                    val json = JSONObject(value as String)
                    val uid = json.getString("userId")
                    if (!PendingPhotoRegistry.isManagedScanPhoto(uid, uri)) return@mapNotNull null
                    PendingPhotoRecord(uid, uri, json.getString("processId"), PendingPhotoState.valueOf(json.getString("state")))
                } catch (_: Exception) {
                    // Do not guess a deletion target from a corrupt journal entry.
                    Log.w(TAG, "Ignoring an invalid pending-photo journal entry.")
                    null
                }
            }

            override fun put(record: PendingPhotoRecord) {
                val json = JSONObject().put("userId", record.userId).put("processId", record.processId)
                    .put("state", record.state.name)
                if (!preferences.edit().putString(record.gsUri, json.toString()).commit()) {
                    throw IOException("Could not persist the pending-photo journal.")
                }
            }

            override fun remove(gsUri: String) {
                if (!preferences.edit().remove(gsUri).commit()) {
                    throw IOException("Could not update the pending-photo journal.")
                }
            }
        }
        return PendingPhotoRegistry(
            store = store,
            processId = processId,
            scheduleCleanup = { uid -> PendingPhotoCleanupWorker.schedule(context, uid) },
            reportFailure = { Log.w(TAG, "Pending-photo cleanup remains queued: ${it.javaClass.simpleName}") },
        )
    }
}
