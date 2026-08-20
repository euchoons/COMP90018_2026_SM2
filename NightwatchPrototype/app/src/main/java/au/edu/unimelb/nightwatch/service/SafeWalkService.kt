package au.edu.unimelb.nightwatch.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.edu.unimelb.nightwatch.MainActivity
import au.edu.unimelb.nightwatch.NightwatchApp
import au.edu.unimelb.nightwatch.R
import au.edu.unimelb.nightwatch.core.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps sensing running with the screen off and the app backgrounded.
 *
 * Without a foreground service Android will suspend the sensor callbacks within
 * minutes of the screen going off — which is precisely when the phone is in a
 * pocket and the app is supposed to be doing its job. The persistent
 * notification is also the honest thing to do: the user can always see that
 * sensing is active.
 */
class SafeWalkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observer: Job? = null

    private val engine get() = (application as NightwatchApp).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine.stopWalk()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Android 14 throws if you declare a foreground service type whose
        // permission has not been granted, so the type is computed from what the
        // user actually allowed rather than assumed from the manifest.
        val notification = buildNotification("Monitoring your walk")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Service types did not exist before Android 10.
            startForeground(NOTIFICATION_ID, notification)
        } else {
            val types = grantedServiceTypes()
            if (types == 0) {
                // Neither location nor microphone was granted: there is nothing
                // useful to monitor, so fail loudly instead of running a silent,
                // permanently blind service.
                stopSelf()
                return START_NOT_STICKY
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        }
        engine.startWalk()

        observer = scope.launch {
            engine.state.collectLatest { state ->
                val text = when (state.phase) {
                    Phase.CONFIRMING -> "Checking in — ${state.secondsRemaining}s to cancel"
                    Phase.ALERTED -> "Emergency contacts notified"
                    Phase.CAUTION -> "Unlit stretch — watching closely"
                    Phase.MONITORING -> "Monitoring your walk"
                    Phase.IDLE -> "Idle"
                }
                notify(text)
            }
        }

        // Restart if killed: an interrupted safety app is worse than useless.
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        engine.stopWalk()
        super.onDestroy()
    }

    private fun grantedServiceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        var types = 0
        if (granted(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            granted(android.Manifest.permission.RECORD_AUDIO)
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        // 0 means neither permission was granted; the caller stops the service
        // rather than starting one that cannot legally run.
        return types
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NightwatchApp.CHANNEL_MONITORING)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(tap)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notify(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "au.edu.unimelb.nightwatch.STOP"

        fun start(context: Context) {
            val intent = Intent(context, SafeWalkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SafeWalkService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
