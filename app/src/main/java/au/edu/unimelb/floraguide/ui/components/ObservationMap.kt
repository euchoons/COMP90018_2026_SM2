package au.edu.unimelb.floraguide.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.BuildConfig
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Observation
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ObservationMap(
    observations: List<Observation>,
    location: GeoPoint,
    usingDemo: Boolean,
    modifier: Modifier = Modifier,
) {
    val mapModifier = modifier.fillMaxWidth().aspectRatio(1f)
    // Derive pins from the same list as the cards; never keep a separate saved marker list.
    val locations = remember(observations) { observationMapLocations(observations) }
    var selectedPoint by remember { mutableStateOf<ObservationMapPoint?>(null) }
    val selectedObservations = locations[selectedPoint].orEmpty()
    LaunchedEffect(locations.keys) {
        if (selectedPoint !in locations) selectedPoint = null
    }

    if (locations.isEmpty() || !BuildConfig.MAPS_CONFIGURED) {
        LocationMap(location = location, usingDemo = usingDemo, modifier = mapModifier)
        return
    }

    val points = locations.keys.map { LatLng(it.latitude, it.longitude) }
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(points.first(), 16f)
    }
    var mapLoaded by remember { mutableStateOf(false) }
    val cameraPadding = with(LocalDensity.current) { 48.dp.roundToPx() }
    // Fit saved locations on load or when their set changes, not on live GPS updates.
    LaunchedEffect(mapLoaded, locations.keys, cameraPadding) {
        if (mapLoaded) {
            val update = if (points.size == 1) {
                CameraUpdateFactory.newLatLngZoom(points.single(), 16f)
            } else {
                val bounds = LatLngBounds.builder().apply { points.forEach { include(it) } }.build()
                CameraUpdateFactory.newLatLngBounds(bounds, cameraPadding)
            }
            cameraState.animate(update, 600)
        }
    }

    Box(modifier = mapModifier.clip(RoundedCornerShape(10.dp))) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            onMapLoaded = { mapLoaded = true },
            uiSettings = MapUiSettings(
                scrollGesturesEnabled = true,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false,
                rotationGesturesEnabled = false,
                tiltGesturesEnabled = false,
            ),
        ) {
            locations.forEach { (point, plants) ->
                key(point) {
                    val title = if (plants.size == 1) plants.single().species.commonName
                        else "${plants.size} observations at this location"
                    MarkerComposable(
                        plants.size,
                        state = rememberUpdatedMarkerState(LatLng(point.latitude, point.longitude)),
                        title = title,
                        contentDescription = "Plant: $title. Tap to view photos.",
                        onClick = {
                            selectedPoint = point
                            true
                        },
                    ) {
                        Surface(
                            modifier = Modifier.size(if (plants.size > 1) 56.dp else 44.dp),
                            shape = CircleShape,
                            color = Color(0xFF166534),
                            contentColor = Color.White,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Default.LocalFlorist, contentDescription = null, modifier = Modifier.size(24.dp))
                                if (plants.size > 1) Text("${plants.size}", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedObservations.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { selectedPoint = null },
            title = { Text(if (selectedObservations.size == 1) "Plant observation" else "Plants at this location") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(selectedObservations, key = { it.id }) { observation ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PhotoThumbnail(
                                path = observation.photoPath,
                                cloudPhotoUri = observation.cloudPhotoUri,
                                contentDescription = "Photo of ${observation.species.commonName}",
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                            )
                            Text(observation.species.commonName, style = MaterialTheme.typography.titleMedium)
                            Text(observation.species.scientificName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a")
                                    .withZone(ZoneId.systemDefault()).format(observation.observedAt),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (observation.photoPath == null && observation.cloudPhotoUri == null) {
                                Text("No photo saved for this observation", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedPoint = null }) { Text("Close") } },
        )
    }
}
