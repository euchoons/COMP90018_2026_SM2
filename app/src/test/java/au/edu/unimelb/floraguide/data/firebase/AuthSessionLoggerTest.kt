package au.edu.unimelb.floraguide.data.firebase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AuthSessionLoggerTest {
    private lateinit var logger: AuthSessionLogger

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        logger = AuthSessionLogger(context, prefName = "test_auth_logs_${System.currentTimeMillis()}", maxLogEntries = 3)
    }

    @Test
    fun `logs event and retrieves persisted entries`() = runBlocking {
        logger.logEvent("EMAIL_SIGN_IN", "SUCCESS", "user-123", "Logged in via email")
        val logs = logger.getLogs()

        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("EMAIL_SIGN_IN"))
        assertTrue(logs[0].contains("SUCCESS"))
        assertTrue(logs[0].contains("user-1"))
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
        assertTrue(logs[0].contains("jo***@example.com"))
        assertFalse(logs[0].contains("john.doe@example.com"))
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
}
