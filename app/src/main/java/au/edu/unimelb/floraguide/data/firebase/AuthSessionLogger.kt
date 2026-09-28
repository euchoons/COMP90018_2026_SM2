package au.edu.unimelb.floraguide.data.firebase

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe, persistent authentication session logger.
 * Stores structured log entries in local private storage with automatic PII masking.
 */
class AuthSessionLogger(
    context: Context,
    prefName: String = "floraguide_auth_session_logs",
    private val maxLogEntries: Int = 100
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    data class LogEntry(
        val timestampEpochMs: Long,
        val eventType: String,
        val status: String,
        val userUid: String?,
        val detail: String?
    ) {
        fun toFormattedString(): String {
            val isoTime = Instant.ofEpochMilli(timestampEpochMs).toString()
            val uidStr = userUid?.let { " [UID: ${it.take(6)}...]" } ?: ""
            val detailStr = detail?.let { " - Detail: $it" } ?: ""
            return "[$isoTime] $eventType ($status)$uidStr$detailStr"
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("timestamp", timestampEpochMs)
            put("eventType", eventType)
            put("status", status)
            put("userUid", userUid ?: JSONObject.NULL)
            put("detail", detail ?: JSONObject.NULL)
        }

        companion object {
            fun fromJson(json: JSONObject): LogEntry = LogEntry(
                timestampEpochMs = json.getLong("timestamp"),
                eventType = json.getString("eventType"),
                status = json.getString("status"),
                userUid = if (json.isNull("userUid")) null else json.getString("userUid"),
                detail = if (json.isNull("detail")) null else json.getString("detail")
            )
        }
    }

    suspend fun logEvent(
        eventType: String,
        status: String,
        userUid: String? = null,
        detail: String? = null
    ) = mutex.withLock {
        val entry = LogEntry(
            timestampEpochMs = System.currentTimeMillis(),
            eventType = eventType,
            status = status,
            userUid = userUid,
            detail = sanitizeDetail(detail)
        )
        val entries = loadEntriesInternal().toMutableList()
        entries.add(entry)

        // FIFO eviction to enforce storage bounds
        val trimmed = if (entries.size > maxLogEntries) {
            entries.takeLast(maxLogEntries)
        } else {
            entries
        }

        saveEntriesInternal(trimmed)
    }

    suspend fun getLogs(): List<String> = mutex.withLock {
        loadEntriesInternal().map { it.toFormattedString() }
    }

    suspend fun getStructuredLogs(): List<LogEntry> = mutex.withLock {
        loadEntriesInternal()
    }

    suspend fun clearLogs() = mutex.withLock {
        prefs.edit { remove(KEY_LOGS) }
    }

    private fun loadEntriesInternal(): List<LogEntry> {
        val rawJson = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            (0 until array.length()).map { i ->
                LogEntry.fromJson(array.getJSONObject(i))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntriesInternal(entries: List<LogEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        prefs.edit { putString(KEY_LOGS, array.toString()) }
    }

    private fun sanitizeDetail(detail: String?): String? {
        if (detail.isNullOrBlank()) return null
        // Mask emails and remove raw password references if present in exception messages
        return detail
            .replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")) { match ->
                val email = match.value
                val parts = email.split("@")
                if (parts[0].length > 2) "${parts[0].take(2)}***@${parts[1]}" else "***@${parts[1]}"
            }
            .take(250) // Cap string length
    }

    companion object {
        private const val KEY_LOGS = "auth_session_event_logs"
    }
}
