package au.edu.unimelb.nightwatch.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Thin wrapper over the fused location provider.
 *
 * The last known position is cached so an alert can still carry a location even
 * if GPS has just dropped out — which is exactly when it is most likely to,
 * between buildings at night.
 */
class LocationTracker(private val context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    var lastLocation: Location? = null
        private set

    private var callback: LocationCallback? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * @param intervalMs 4 s is a reasonable walking cadence. Drop it to ~1 s only
     *   while an alert is active, then restore it: high-accuracy polling is one of
     *   the biggest battery costs in this app.
     */
    fun start(intervalMs: Long = 4_000, onUpdate: (Location) -> Unit) {
        if (!hasPermission()) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    lastLocation = it
                    onUpdate(it)
                }
            }
        }
        callback = cb

        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}
