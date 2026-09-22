package au.edu.unimelb.floraguide.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.BuildConfig
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState

/** Shares the observation's location source: no second location subscription or permission request. */
@Composable
fun LocationMap(location: GeoPoint, usingDemo: Boolean, modifier: Modifier = Modifier) {
    if (!BuildConfig.MAPS_CONFIGURED) {
        Surface(modifier = modifier, shape = RoundedCornerShape(10.dp)) {
            Text(
                text = "Map unavailable. Location context can still be used.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    val position = LatLng(location.latitude, location.longitude)
    val markerState = rememberUpdatedMarkerState(position = position)
    val cameraState = rememberCameraPositionState {
        this.position = CameraPosition.fromLatLngZoom(position, 16f)
    }
    var mapLoaded by remember { mutableStateOf(false) }

    // Wait for the map before animating; key by coordinates so every new fix moves the camera.
    // Preserve the user's zoom while following their location.
    LaunchedEffect(mapLoaded, position) {
        if (mapLoaded) cameraState.animate(CameraUpdateFactory.newLatLng(position), 600)
    }

    Box(modifier = modifier.height(220.dp).clip(RoundedCornerShape(10.dp))) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            onMapLoaded = { mapLoaded = true },
            uiSettings = MapUiSettings(
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false,
                scrollGesturesEnabled = false,
                rotationGesturesEnabled = false,
                tiltGesturesEnabled = false,
            ),
        ) {
            Marker(
                state = markerState,
                title = if (usingDemo) "Campus demo location" else "Your location",
                snippet = if (usingDemo) "Sample coordinate, not your GPS position" else null,
            )
            if (!usingDemo) {
                location.accuracyMetres?.takeIf { it.isFinite() && it > 0f }?.let { accuracy ->
                    Circle(
                        center = position,
                        radius = accuracy.toDouble(),
                        fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        strokeWidth = 2f,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                text = if (usingDemo) "Demo location" else "Following your location",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
