package au.edu.unimelb.nightwatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import au.edu.unimelb.nightwatch.core.SafeWalkEngine

/**
 * Holds the single [SafeWalkEngine] instance.
 *
 * The engine outlives any one screen — sensing must continue while the phone is
 * in a pocket with the activity destroyed — so it is owned by the Application
 * and observed by both the foreground service and the UI.
 */
class NightwatchApp : Application() {

    val engine: SafeWalkEngine by lazy { SafeWalkEngine(this) }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITORING,
                getString(R.string.channel_monitoring),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        const val CHANNEL_MONITORING = "monitoring"
        const val CHANNEL_ALERTS = "alerts"
    }
}
