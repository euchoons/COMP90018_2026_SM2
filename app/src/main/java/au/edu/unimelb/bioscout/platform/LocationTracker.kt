package au.edu.unimelb.bioscout.platform

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import au.edu.unimelb.bioscout.domain.model.GeoPoint

/** Uses platform LocationManager to keep the prototype independent of Google Play Services. */
class LocationTracker(context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var activeListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun start(onLocation: (GeoPoint) -> Unit, onError: (String) -> Unit) {
        stop()
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            onError("Location providers are disabled; using the campus demo location.")
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location.toGeoPoint())
            }

            @Deprecated("Deprecated in Android")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                onError("Location provider disabled; the last known location is retained.")
            }
        }
        activeListener = listener

        providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }?.let { onLocation(it.toGeoPoint()) }

        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    2_500L,
                    3f,
                    listener,
                    Looper.getMainLooper(),
                )
            }.onFailure { onError("Unable to start $provider location updates.") }
        }
    }

    fun stop() {
        activeListener?.let { manager.removeUpdates(it) }
        activeListener = null
    }

    private fun Location.toGeoPoint(): GeoPoint = GeoPoint(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = if (hasAccuracy()) accuracy else null,
    )
}
