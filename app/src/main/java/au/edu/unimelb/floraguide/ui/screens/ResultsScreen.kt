package au.edu.unimelb.floraguide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.domain.model.CaptureLocationSource
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.ui.FloraGuideUiState
import au.edu.unimelb.floraguide.ui.LOCAL_TIME_FORMAT
import au.edu.unimelb.floraguide.ui.components.CloudIdentificationCard
import au.edu.unimelb.floraguide.ui.components.EvidenceBar
import au.edu.unimelb.floraguide.ui.components.HabitatSelector
import au.edu.unimelb.floraguide.ui.components.InformationCard
import au.edu.unimelb.floraguide.ui.components.PhotoThumbnail
import au.edu.unimelb.floraguide.ui.components.RelativeScoreLabel
import au.edu.unimelb.floraguide.ui.components.SectionHeading
import au.edu.unimelb.floraguide.ui.components.SelectableCard
import au.edu.unimelb.floraguide.ui.components.StatusPill
import java.util.Locale

@Composable
fun ResultsScreen(
    state: FloraGuideUiState,
    onBackToScan: () -> Unit,
    onHabitatSelected: (Habitat) -> Unit,
    onSelectSpecies: (String) -> Unit,
    onRetryContext: () -> Unit,
    onRetryIdentification: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackToScan) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to camera")
                }
                SectionHeading(title = "Context-aware result", subtitle = "Image evidence with a separate ALA history check.", modifier = Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PhotoThumbnail(state.photoPath, Modifier.size(100.dp), "Captured observation")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    StatusPill(state.imageSource?.label ?: "Identifying", state.imageSource == ImageSource.PLANTNET_LIVE)
                    StatusPill(state.capture?.locationSource?.label ?: "Location unavailable",
                        state.capture?.locationSource == CaptureLocationSource.DEVICE)
                    state.capture?.capturedAt?.let { Text("Capture: ${LOCAL_TIME_FORMAT.format(it)}", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        item { CloudIdentificationCard(state = state, onRetry = onRetryIdentification) }
        item { ContextProgress(state, onRetryContext) }
        if (state.isClassifying) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(state.identificationStage?.label ?: "Preparing image candidates")
                }
            }
        }
        if (state.imageOnlyRanking.isNotEmpty()) {
            item {
                ResultCard {
                    Text("Before / after", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        RankingColumn("Image only", state.imageOnlyRanking.take(3), Modifier.weight(1f))
                        RankingColumn(if (state.isContextLoading) "Checking ALA" else "Final suggestions",
                            state.fusedRanking.take(3), Modifier.weight(1f))
                    }
                    Text(rankingExplanation(state), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.displayedRanking.isNotEmpty()) {
            item { SectionHeading(title = "Final candidate set", subtitle = "Select a suggestion. Scores are relative, not accuracy estimates.") }
            items(state.displayedRanking, key = { it.species.id }) { candidate ->
                SelectableCard(selected = state.selectedCandidate?.species?.id == candidate.species.id,
                    onClick = { onSelectSpecies(candidate.species.id) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${candidate.finalRank}", fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(candidate.species.commonName, fontWeight = FontWeight.Bold)
                            Text(candidate.species.scientificName, fontStyle = FontStyle.Italic,
                                style = MaterialTheme.typography.bodySmall)
                            Text("Image rank #${candidate.imageRank} -> final #${candidate.finalRank}",
                                style = MaterialTheme.typography.labelSmall)
                            Text(recordLabel(candidate, state), style = MaterialTheme.typography.labelSmall)
                        }
                        RelativeScoreLabel(candidate.relativeScore)
                    }
                }
            }
        }
        state.selectedCandidate?.let { selected ->
            item {
                ResultCard {
                    Text("Why this suggestion?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    EvidenceBar(label = "Original image score", value = selected.evidence.imagePrior,
                        detail = String.format(Locale.US, "%.2f%% (unmodified)", selected.evidence.imagePrior * 100))
                    Text(recordLabel(selected, state), style = MaterialTheme.typography.bodyMedium)
                    state.nearbyContext?.failuresBySpeciesId?.get(selected.species.id)?.let { reason ->
                        Text("ALA lookup: $reason. Unknown is not zero.", color = MaterialTheme.colorScheme.error)
                    }
                    if (state.imageSource == ImageSource.DEMO_ADAPTER) {
                        EvidenceBar("Synthetic location prior", selected.evidence.locationPrior, "Guided demo only")
                        EvidenceBar("Synthetic seasonal prior", selected.evidence.seasonalPrior, state.analysisDate.month.name)
                        EvidenceBar("Synthetic habitat prior", selected.evidence.habitatPrior, state.selectedHabitat.label)
                    } else {
                        Text(String.format(Locale.US, "Geographic multiplier: %.3fx", selected.evidence.locationMultiplier))
                        Text("For complete ALA results: image score x (1 + 0.15 x support), then normalise. " +
                            "Support uses capped log-counts; the maximum multiplier is 1.15x.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Partial, unavailable or skipped ALA context keeps the image-only order. " +
                            "Season and habitat do not change live rankings.", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Text("Observation habitat", fontWeight = FontWeight.Bold)
                    HabitatSelector(selected = state.selectedHabitat, onSelected = onHabitatSelected)
                }
            }
            item {
                Button(onClick = onConfirm, enabled = state.canSave, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Icon(Icons.Default.Check, null)
                    Text(if (state.isSaving) "  Saving locally..." else "  Save selected suggestion locally")
                }
            }
            // canSave keeps the button disabled, so its click handler can never explain this.
            if (state.capture != null && state.capture?.location == null) {
                item {
                    Text("Saving needs a capture location. Enable location, wait for a device fix, then take a new photo.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            InformationCard("Interpretation and privacy",
                "ALA counts are historical records, not a count of individual plants and not proof of identity. " +
                    "Zero records do not prove absence. Exact-name queries can miss synonyms. " +
                    "Saving marks your selection as unverified, stores rounded coordinates locally, and does not submit it to ALA.")
        }
    }
}

@Composable
private fun ContextProgress(state: FloraGuideUiState, onRetry: () -> Unit) {
    val context = state.nearbyContext
    ResultCard {
        Text("ALA lookup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        when {
            state.isContextLoading -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text("Checking candidate names near the saved capture location...")
                }
            }
            context != null -> {
                Text(context.source.label, fontWeight = FontWeight.Bold)
                Text("Radius: ${context.radiusKm} km | Successful candidates: " +
                    "${context.successfulRequestCount}/${context.requestCount}", style = MaterialTheme.typography.bodySmall)
                if (context.attemptsBySpeciesId.isNotEmpty()) Text(
                    "HTTP attempts: ${context.attemptsBySpeciesId.values.sum()} | " +
                        "Elapsed: ${context.lookupElapsedMillis ?: 0} ms | " +
                        "Status: ${context.httpStatusCodes.sorted().joinToString("/").ifBlank { "unavailable" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
                context.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                context.retryNotBefore?.let { Text("Server retry time: ${LOCAL_TIME_FORMAT.format(it)}", style = MaterialTheme.typography.bodySmall) }
            }
            else -> Text("Waiting for Pl@ntNet candidate names.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.analysisPrefersLiveData && state.imagePredictions.isNotEmpty() &&
            state.capture?.location != null && !state.isContextLoading && context?.source != ContextDataSource.ALA_LIVE) {
            FilledTonalButton(onClick = onRetry, enabled = !state.isSaving) {
                Icon(Icons.Default.Refresh, null)
                Text(" Retry ALA only")
            }
        }
    }
}

@Composable
private fun ResultCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun RankingColumn(title: String, candidates: List<RankedCandidate>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        if (candidates.isEmpty()) Text("Pending", style = MaterialTheme.typography.bodySmall)
        candidates.forEach { Text("${it.finalRank}. ${it.species.commonName}", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun recordLabel(candidate: RankedCandidate, state: FloraGuideUiState): String {
    val count = candidate.nearbyRecordCount
    if (state.imageSource == ImageSource.DEMO_ADAPTER) return "Synthetic demo count: ${count ?: "pending"}"
    return when {
        state.isContextLoading -> "ALA count: pending"
        count == null -> "ALA count: unknown / not available"
        count == 0 -> "ALA: 0 matching historical records (not proof of absence)"
        else -> "ALA: $count historical records within ${state.nearbyContext?.radiusKm ?: 8} km"
    }
}
private fun rankingExplanation(state: FloraGuideUiState): String = when {
    state.isContextLoading -> "Image suggestions are ready; location evidence is still being checked."
    state.imageSource == ImageSource.DEMO_ADAPTER -> "Explicit synthetic demonstration, not a real identification."
    state.nearbyContext?.source == ContextDataSource.ALA_LIVE -> "All candidate queries succeeded. ALA may add a small, capped positive adjustment."
    else -> "Image-only ranking retained. Missing geographic evidence is not treated as a negative result."
}
