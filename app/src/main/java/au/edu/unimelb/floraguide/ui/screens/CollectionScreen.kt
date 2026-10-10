package au.edu.unimelb.floraguide.ui.screens

import android.R
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Park
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.domain.model.CaptureLocationSource
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.ui.FloraGuideUiState
import au.edu.unimelb.floraguide.ui.MISSION_SPECIES_GOAL
import au.edu.unimelb.floraguide.ui.components.InformationCard
import au.edu.unimelb.floraguide.ui.components.MissionCounter
import au.edu.unimelb.floraguide.ui.components.MissionNote
import au.edu.unimelb.floraguide.ui.components.ObservationMap
import au.edu.unimelb.floraguide.ui.components.PhotoThumbnail
import au.edu.unimelb.floraguide.ui.components.SectionHeading
import au.edu.unimelb.floraguide.ui.components.StatusPill
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.tooling.preview.Preview


@Composable
fun CollectionScreen(
    state: FloraGuideUiState,
    onStartScan: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mapGestureActive by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Observation?>(null) }
    var selectedObservationId by rememberSaveable { mutableStateOf<String?>(null) }

    state.observations.firstOrNull { it.id == selectedObservationId }?.let { observation ->
        ObservationDetailsDialog(
            observation = observation,
            onDismiss = { selectedObservationId = null },
            onDelete = {
                selectedObservationId = null
                deleting = observation
            },
        )
    }

    deleting?.let { observation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete observation?") },
            shape = RoundedCornerShape(10.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            textContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            text = {
                Text(
                    "Remove this ${observation.species.commonName} observation from your field guide? " +
                        "Cloud deletion will sync when online.",
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(modifier = Modifier.weight(1f), onClick = { deleting = null }) { Text("Cancel") }
                    FilledTonalButton(
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFFB2C36).copy(alpha = 0.2f), contentColor = Color(0xFFe7000b)),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDelete(observation.id)
                            deleting = null
                        },
                    ) {
                        Text("Delete")
                    }
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = !mapGestureActive,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            SectionHeading(
                title = "My Discovered Plants",
                //subtitle = "Saved only on this device; signed-in observations will be sync when online.",
            )
        }

        item { CollectionMissionCard(uniqueSpecies = state.uniqueSpeciesCount) }

        item {
            ObservationMap(
                observations = state.observations,
                location = state.location,
                usingDemo = state.usingDemoLocation,
                onViewDetails = { selectedObservationId = it },
                modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                    // Observe without consuming: Maps handles the touch sequence, while the
                    // surrounding list waits until all fingers are lifted (including pinch zoom).
                    awaitEachGesture {
                        try {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            mapGestureActive = true
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            mapGestureActive = false
                        }
                    }
                },
            )
        }

        if (state.observations.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Park,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(38.dp),
                        )
                        Text(
                            text = "No observations yet",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Complete a live scan, select a candidate, then start saving it here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.AddAPhoto, contentDescription = null)
                            Text("  Start observation")
                        }
                    }
                }
            }
        } else {
            items(items = state.observations, key = { it.id }) { observation ->
                ObservationCard(
                    observation = observation,
                    onViewDetails = { selectedObservationId = observation.id },
                    onDelete = {
                        selectedObservationId = null
                        deleting = observation
                    },
                )
            }
            item {
                Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null)
                    Text("  Add another observation")
                }
            }
        }

        item {
            InformationCard(
                title = "Photo Storage Policy",
                body = "Guest observations are saved only on this device; signed-in observations will be sync when online. " +
                    "Observation metadata is cached locally and may sync for signed-in users. ",
//                    "The saved coordinate is rounded to three decimal places. ALA is queried for historical occurrence context only; " +
//                    "saving here does not submit a new public ALA record.",
            )
        }
    }
}

@Composable
private fun CollectionMissionCard(uniqueSpecies: Int) {
    val progress = (uniqueSpecies / MISSION_SPECIES_GOAL.toFloat()).coerceIn(0f, 1f)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // The title wraps so the counter keeps its width.
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Campus discovery mission",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "Record $MISSION_SPECIES_GOAL different species",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                MissionCounter(uniqueSpecies)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            MissionNote(uniqueSpecies)
        }
    }
}

@Composable
private fun ObservationCard(
    observation: Observation,
    onViewDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoThumbnail(
                path = observation.photoPath,
                modifier = Modifier.size(90.dp),
                contentDescription = observation.species.commonName,
                cloudPhotoUri = observation.cloudPhotoUri,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = observation.species.commonName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = observation.species.scientificName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onDelete, shape = CircleShape, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp),) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                    FilledTonalButton(onClick = onViewDetails, modifier = Modifier.fillMaxWidth()) {
                        Text("View details")
                    }
                }

            }
        }
    }
}

@Composable
private fun ObservationDetailsDialog(
    observation: Observation,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(10.dp),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        textContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        title = { Column() {
            Text(observation.species.commonName, style = MaterialTheme.typography.titleLarge)
            Text(observation.species.scientificName, style = MaterialTheme.typography.bodyMedium)
        }},
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PhotoThumbnail(
                        path = observation.photoPath,
                        cloudPhotoUri = observation.cloudPhotoUri,
                        contentDescription = observation.species.commonName,
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                    if (observation.photoPath == null && observation.cloudPhotoUri == null) {
                        Text("No photo saved for this observation")
                    }
                }
                item {
                    Text("Observed: ${formatter.format(observation.observedAt)}")
                }
                item {
                    Text("Location", fontWeight = FontWeight.Bold)
                    val location = observation.coarseLocation.coarsened()
                    if (observation.locationSource != CaptureLocationSource.UNAVAILABLE && location.hasValidCoordinates()) {
                        Text(String.format(Locale.US, "%.3f, %.3f (rounded)", location.latitude, location.longitude))
                    } else {
                        Text("Coordinates unavailable")
                    }
                    Text(observation.locationSource.label)
                    Text("Habitat: ${observation.habitat.label}")
                    observation.headingDegrees?.let {
                        Text(String.format(Locale.US, "Heading: %.0f°", it))
                    }
                }
                item {
                    Text("Identification", fontWeight = FontWeight.Bold)
                    Text(
                        if (observation.imageSource == ImageSource.DEMO_ADAPTER) {
                            "Demo entry: not a real sighting"
                        } else {
                            "User-selected; not independently verified"
                        },
                    )
                    Text(observation.imageSource?.label ?: "Image source not recorded")
                    Text(observation.imageScore?.let {
                        String.format(Locale.US, "Image score: %.2f%%", it * 100)
                    } ?: "Image score: not recorded")
                    Text(String.format(Locale.US, "Final relative score: %.2f%%", observation.relativeScore * 100))
                    Text("Scores are ranking signals, not a verified identification.")
                }
                item {
                    Text("Nearby occurrence context", fontWeight = FontWeight.Bold)
                    StatusPill(
                        label = observation.contextSource.label,
                        positive = observation.contextSource == ContextDataSource.ALA_LIVE,
                    )
                    Text("ALA count: ${observation.nearbyRecordCount?.toString() ?: "unknown / not recorded"}")
                    observation.contextRadiusKm?.let { Text("Search radius: $it km") }
                    observation.contextQueriedAt?.let { Text("Queried: ${formatter.format(it)}") }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onDelete, shape = CircleShape, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                }
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }

            }
                        },
    )
}

@Preview(showBackground = true, heightDp = 10000)
@Composable
fun CollectionScreenPreview() {
    au.edu.unimelb.floraguide.ui.theme.FloraGuideTheme {
        CollectionScreen(
            state = FloraGuideUiState(),
            onStartScan = {},
            onDelete = {}
        )
    }
}

