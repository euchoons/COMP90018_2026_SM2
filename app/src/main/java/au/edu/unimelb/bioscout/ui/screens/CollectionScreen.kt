package au.edu.unimelb.bioscout.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Park
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.bioscout.domain.model.Observation
import au.edu.unimelb.bioscout.ui.BioScoutUiState
import au.edu.unimelb.bioscout.ui.components.InformationCard
import au.edu.unimelb.bioscout.ui.components.PhotoThumbnail
import au.edu.unimelb.bioscout.ui.components.SectionHeading
import au.edu.unimelb.bioscout.ui.components.StatusPill
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CollectionScreen(
    state: BioScoutUiState,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            SectionHeading(
                title = "My field guide",
                subtitle = "Confirmed observations are stored locally in this prototype build.",
            )
        }

        item {
            CollectionMissionCard(uniqueSpecies = state.uniqueSpeciesCount)
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
                            text = "Complete a live scan or guided demo, select a candidate, then confirm it here.",
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
                ObservationCard(observation = observation)
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
                title = "Privacy by default",
                body = "The repository stores coordinates rounded to three decimal places rather than the exact GPS fix. A production build should also offer user-controlled hiding and sensitive-species obfuscation.",
            )
        }
    }
}

@Composable
private fun CollectionMissionCard(uniqueSpecies: Int) {
    val progress = (uniqueSpecies / 3f).coerceIn(0f, 1f)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp),
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
                Column {
                    Text("Campus discovery mission", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Record 3 unique species",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "$uniqueSpecies / 3",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                )
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ObservationCard(observation: Observation) {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
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
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatter.format(observation.observedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(15.dp))
                    Text(
                        text = String.format(
                            Locale.US,
                            "%.3f, %.3f",
                            observation.coarseLocation.latitude,
                            observation.coarseLocation.longitude,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                observation.headingDegrees?.let { heading ->
                    Text(
                        text = "Observation heading ${heading.toInt()}°",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(label = observation.contextSource.label, positive = observation.contextSource.name.startsWith("ALA"))
            }
        }
    }
}
