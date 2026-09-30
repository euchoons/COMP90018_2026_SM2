package au.edu.unimelb.floraguide.data.firebase

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import java.security.MessageDigest
import io.mockk.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AuthSessionLoggerTest {
    private lateinit var logger: AuthSessionLogger
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        preferences = context.getSharedPreferences("test_auth_logs", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        logger = AuthSessionLogger(preferences, maxLogEntries = 3)
    }

    @Test
    fun `logs event and retrieves persisted entries`() = runBlocking {
        logger.logEvent("EMAIL_SIGN_IN", "SUCCESS", "user-123", "Logged in via email")
        val logs = logger.getLogs()

        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("EMAIL_SIGN_IN"))
        assertTrue(logs[0].contains("SUCCESS"))
        val hash = MessageDigest.getInstance("SHA-256").digest("user-123".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(hash, logger.getStructuredLogs().single().userUid)
        assertFalse(preferences.getString("auth_session_event_logs", "")!!.contains("user-123"))
    }

    @Test
    fun `logs failure state and redacts email addresses from details`() = runBlocking {
        logger.logEvent(
            eventType = "EMAIL_SIGN_IN",
            status = "FAILURE",
            userUid = null,
            detail = "Invalid password for user john.doe@example.com"
        )
        val logs = logger.getLogs()

        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("FAILURE"))
        assertTrue(logs[0].contains("[REDACTED_EMAIL]"))
        assertFalse(logs[0].contains("john.doe@example.com"))
        assertFalse(preferences.getString("auth_session_event_logs", "")!!.contains("example.com"))
    }

    @Test
    fun `enforces maxLogEntries capacity limit using FIFO eviction`() = runBlocking {
        logger.logEvent("EVENT_1", "SUCCESS")
        logger.logEvent("EVENT_2", "SUCCESS")
        logger.logEvent("EVENT_3", "SUCCESS")
        logger.logEvent("EVENT_4", "SUCCESS")

        val logs = logger.getLogs()
        assertEquals(3, logs.size)
        assertFalse(logs.any { it.contains("EVENT_1") })
        assertTrue(logs.any { it.contains("EVENT_2") })
        assertTrue(logs.any { it.contains("EVENT_3") })
        assertTrue(logs.any { it.contains("EVENT_4") })
    }

    @Test
    fun `clears logs successfully`() = runBlocking {
        logger.logEvent("EVENT_1", "SUCCESS")
        logger.clearLogs()
        assertTrue(logger.getLogs().isEmpty())
    }

    @Test
    fun `legacy migration sanitizes persisted contents before removing plaintext`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val legacy = context.getSharedPreferences("legacy_logs", Context.MODE_PRIVATE)
        val entry = AuthSessionLogger.LogEntry(1L, "LOGIN", "FAILURE", "raw-user", "Email person+tag@example.com")
        val raw = JSONArray().put(entry.toJson()).toString()
        legacy.edit().putString("auth_session_event_logs", raw).commit()
        AuthSessionLogger.migrateLegacyLogs(legacy, preferences, 3)
        assertFalse(legacy.contains("auth_session_event_logs"))
        val stored = preferences.getString("auth_session_event_logs", "")!!
        assertFalse(stored.contains("raw-user"))
        assertFalse(stored.contains("example.com"))
        assertTrue(stored.contains("[REDACTED_EMAIL]"))
        // A retry after writing the destination but before deleting the source is idempotent.
        legacy.edit().putString("auth_session_event_logs", raw).commit()
        AuthSessionLogger.migrateLegacyLogs(legacy, preferences, 3)
        assertEquals(1, logger.getStructuredLogs().size)
    }

    @Test
    fun `failed secure migration preserves the source`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val legacy = context.getSharedPreferences("legacy_logs", Context.MODE_PRIVATE)
        val raw = JSONArray().put(AuthSessionLogger.LogEntry(1L, "LOGIN", "SUCCESS", "raw-user", null).toJson()).toString()
        legacy.edit().putString("auth_session_event_logs", raw).commit()
        val secure = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { secure.getString("auth_session_event_logs", "[]") } returns "[]"
        every { secure.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false
        assertTrue(runCatching { AuthSessionLogger.migrateLegacyLogs(legacy, secure, 3) }.isFailure)
        assertEquals(raw, legacy.getString("auth_session_event_logs", null))
    }

    @Test
    fun `unavailable secure storage does not fall back to plaintext`() = runBlocking {
        val unavailable = AuthSessionLogger(null)
        unavailable.logEvent("LOGIN", "SUCCESS", "raw-user", "person@example.com")
        assertTrue(unavailable.getLogs().isEmpty())
        assertFalse(preferences.contains("auth_session_event_logs"))
    }
}
