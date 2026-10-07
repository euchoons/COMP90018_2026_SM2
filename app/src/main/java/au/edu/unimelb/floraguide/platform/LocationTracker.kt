package au.edu.unimelb.floraguide.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import au.edu.unimelb.floraguide.BuildConfig
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.usecase.LocationFreshnessPolicy

/** Foreground device location only. No Google Play Services or background-location dependency. */
class LocationTracker(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val policy = LocationFreshnessPolicy()
    private val handler = Handler(Looper.getMainLooper())
    private var activeListener: LocationListener? = null
    private var lastFix: Location? = null
    private var staleCheck: Runnable? = null

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Magnetic declination at a place and time, east-positive, from Android's World Magnetic Model. */
    fun magneticDeclinationDegrees(point: GeoPoint, timeMillis: Long): Float =
        GeomagneticField(point.latitude.toFloat(), point.longitude.toFloat(), 0f, timeMillis).declination

    /** Approximate-only permission: Android blurs fixes to about 2 km and sends one about every 10 minutes. */
    fun isApproximate(): Boolean =
        !granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun enabledProviders(): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }

    /** Recheck permission, provider state, age and accuracy at the capture callback itself. */
    fun snapshotForObservation(): GeoPoint? = usableFix().also { point ->
        log { "event=capture usable=${point != null} accuracyM=${point?.accuracyMetres}" }
    }

    private fun usableFix(): GeoPoint? {
        if (!hasPermission() || enabledProviders().isEmpty()) return null
        val fix = lastFix ?: return null
        if (fix.provider !in enabledProviders()) return null
        val point = fix.toGeoPoint()
        return point.takeIf {
            policy.isUsable(it, fix.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos(), isApproximate())
        }
    }

    @SuppressLint("MissingPermission")
    fun start(onLocation: (GeoPoint) -> Unit, onError: (String) -> Unit, onStale: () -> Unit = {}) {
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
        log { "event=start providers=${providers.joinToString(",")} approximate=${isApproximate()}" }
        fun accept(location: Location) {
            val point = location.toGeoPoint()
            val now = SystemClock.elapsedRealtimeNanos()
            val current = lastFix
            val approximate = isApproximate()
            val accepted = policy.isUsable(point, location.elapsedRealtimeNanos, now, approximate) && (
                current == null || policy.shouldReplace(
                    current.toGeoPoint(), current.elapsedRealtimeNanos, point, location.elapsedRealtimeNanos,
                    sameProvider = current.provider == location.provider, nowElapsedNanos = now,
                    approximate = approximate,
                )
            )
            log {
                "event=fix provider=${location.provider} accuracyM=${point.accuracyMetres} " +
                    "ageMs=${(now - location.elapsedRealtimeNanos) / 1_000_000L} accepted=$accepted " +
                    "lat=${location.latitude} lon=${location.longitude}"
            }
            if (!accepted) return
            lastFix = Location(location)
            onLocation(point)
            scheduleStaleCheck(location.elapsedRealtimeNanos, onStale)
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = accept(location)
            @Deprecated("Deprecated in Android")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                log { "event=provider_disabled provider=$provider" }
                if (lastFix?.provider == provider) {
                    lastFix = null
                    onStale()
                }
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

    /** Re-checks when the accepted fix would expire, so an aged-out fix is never presented as ready. */
    private fun scheduleStaleCheck(fixElapsedNanos: Long, onStale: () -> Unit) {
        staleCheck?.let { handler.removeCallbacks(it) }
        val check = Runnable {
            if (usableFix() == null) {
                log { "event=stale" }
                onStale()
            }
        }
        staleCheck = check
        // isUsable still passes at exactly the limit, so check just after it.
        val remaining = policy.millisUntilStale(fixElapsedNanos, SystemClock.elapsedRealtimeNanos(), isApproximate())
        handler.postDelayed(check, remaining + 50L)
    }

    fun stop() {
        staleCheck?.let { handler.removeCallbacks(it) }
        staleCheck = null
        activeListener?.let { listener -> runCatching { manager.removeUpdates(listener) } }
        activeListener = null
        lastFix = null
    }

    private fun Location.toGeoPoint() = GeoPoint(
        latitude, longitude, if (hasAccuracy()) accuracy else null,
    )

    /** Debug builds only: raw fixes for tools/location-accuracy.py. Release builds never log coordinates. */
    private inline fun log(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "t=${SystemClock.elapsedRealtime()} ${message()}")
    }

    private companion object {
        const val TAG = "FloraGuide-Location"
    }
}
