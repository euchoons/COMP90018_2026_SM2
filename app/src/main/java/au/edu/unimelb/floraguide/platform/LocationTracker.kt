package au.edu.unimelb.floraguide.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.usecase.LocationFreshnessPolicy

/** Foreground device location only. No Google Play Services or background-location dependency. */
class LocationTracker(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val policy = LocationFreshnessPolicy()
    private var activeListener: LocationListener? = null
    private var lastFix: Location? = null

    private fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }

    /** Recheck permission, provider state, age and accuracy at the capture callback itself. */
    fun snapshotForObservation(): GeoPoint? {
        if (!hasPermission() || enabledProviders().isEmpty()) return null
        val fix = lastFix ?: return null
        if (fix.provider !in enabledProviders()) return null
        val point = fix.toGeoPoint()
        return point.takeIf { policy.isUsable(it, fix.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos()) }
    }

    @SuppressLint("MissingPermission")
    fun start(onLocation: (GeoPoint) -> Unit, onError: (String) -> Unit) {
        stop()
        if (!hasPermission()) {
            onError("Location permission is unavailable. Live scans will not use a demo coordinate.")
            return
        }
        val providers = enabledProviders()
        if (providers.isEmpty()) {
            onError("Device location is off. Enable it before capture to add ALA context.")
            return
        }
        fun accept(location: Location) {
            val point = location.toGeoPoint()
            val now = SystemClock.elapsedRealtimeNanos()
            if (!policy.isUsable(point, location.elapsedRealtimeNanos, now)) return
            val current = lastFix
            if (current != null && !policy.shouldReplace(
                    current.toGeoPoint(), current.elapsedRealtimeNanos, point, location.elapsedRealtimeNanos,
                    sameProvider = current.provider == location.provider, nowElapsedNanos = now,
                )
            ) return
            lastFix = Location(location)
            onLocation(point)
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = accept(location)
            @Deprecated("Deprecated in Android")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                if (lastFix?.provider == provider) lastFix = null
                if (enabledProviders().isEmpty()) onError("Device location is off; no capture location is available.")
            }
        }
        activeListener = listener
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .sortedBy { it.elapsedRealtimeNanos }.forEach(::accept)
        var registrations = 0
        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 2_500L, 0f, listener, Looper.getMainLooper())
            }.onSuccess { registrations++ }
        }
        if (registrations == 0) {
            stop()
            onError("Unable to request device location. Check location permissions and settings.")
        }
    }

    fun stop() {
        activeListener?.let { listener -> runCatching { manager.removeUpdates(listener) } }
        activeListener = null
        lastFix = null
    }

    private fun Location.toGeoPoint() = GeoPoint(
        latitude, longitude, if (hasAccuracy()) accuracy else null,
    )
}
