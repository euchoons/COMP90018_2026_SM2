package au.edu.unimelb.floraguide.data.firebase

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe, persistent authentication session logger.
 * Stores structured log entries in local private storage with automatic PII masking.
 */
class AuthSessionLogger internal constructor(
    private val prefs: SharedPreferences?,
    private val maxLogEntries: Int = 100
) {
    constructor(
        context: Context,
        prefName: String = "floraguide_auth_session_logs",
        maxLogEntries: Int = 100,
    ) : this(openEncryptedLogs(context.applicationContext, prefName, maxLogEntries), maxLogEntries)

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
            userUid = userUid?.let(::hashIdentifier),
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
        prefs?.edit { remove(KEY_LOGS) }
    }

    private fun loadEntriesInternal(): List<LogEntry> {
        return runCatching {
            val rawJson = prefs?.getString(KEY_LOGS, null) ?: return emptyList()
            val array = JSONArray(rawJson)
            (0 until array.length()).map { i ->
                LogEntry.fromJson(array.getJSONObject(i))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntriesInternal(entries: List<LogEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        // Diagnostics must not make authentication fail if secure storage becomes unavailable.
        runCatching { prefs?.edit { putString(KEY_LOGS, array.toString()) } }
    }

    companion object {
        private const val KEY_LOGS = "auth_session_event_logs"

        private fun hashIdentifier(uid: String): String = MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun openEncryptedLogs(context: Context, name: String, limit: Int): SharedPreferences? = runCatching {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val secure = EncryptedSharedPreferences.create(
                context, "$name.encrypted", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migrateLegacyLogs(context.getSharedPreferences(name, Context.MODE_PRIVATE), secure, limit)
            secure
        }.getOrNull() // Fail closed: omit diagnostics, never fall back to plaintext logging.

        internal fun migrateLegacyLogs(legacy: SharedPreferences, secure: SharedPreferences, limit: Int) {
            val encoded = legacy.getString(KEY_LOGS, null) ?: return
            val old = JSONArray(encoded)
            val migrated = (0 until old.length()).map { LogEntry.fromJson(old.getJSONObject(it)) }
                .map { it.copy(userUid = it.userUid?.let(::hashIdentifier), detail = sanitizeDetail(it.detail)) }
            val existing = JSONArray(secure.getString(KEY_LOGS, "[]"))
            val current = (0 until existing.length()).map { LogEntry.fromJson(existing.getJSONObject(it)) }
            val combined = (migrated + current).distinct().sortedBy { it.timestampEpochMs }.takeLast(limit)
            val result = JSONArray().apply { combined.forEach { put(it.toJson()) } }
            check(secure.edit().putString(KEY_LOGS, result.toString()).commit()) { "Cannot migrate auth logs securely." }
            check(legacy.edit().remove(KEY_LOGS).commit()) { "Cannot remove legacy auth logs." }
        }

        private fun sanitizeDetail(detail: String?): String? {
            if (detail.isNullOrBlank()) return null
            return detail
                .replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "[REDACTED_EMAIL]")
                .take(250)
        }
    }
}
