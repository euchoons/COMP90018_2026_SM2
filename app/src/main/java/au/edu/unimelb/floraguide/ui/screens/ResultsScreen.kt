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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.domain.model.CaptureLocationSource
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.FloweringCheck
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.ui.FloraGuideUiState
import au.edu.unimelb.floraguide.ui.LOCAL_TIME_FORMAT
import au.edu.unimelb.floraguide.ui.components.CandidateResultCard
import au.edu.unimelb.floraguide.ui.components.CandidateSetCard
import au.edu.unimelb.floraguide.ui.components.CloudIdentificationCard
import au.edu.unimelb.floraguide.ui.components.EvidenceBar
import au.edu.unimelb.floraguide.ui.components.HabitatSelector
import au.edu.unimelb.floraguide.ui.components.InformationCard
import au.edu.unimelb.floraguide.ui.components.PhotoThumbnail
import au.edu.unimelb.floraguide.ui.components.RelativeScoreLabel
import au.edu.unimelb.floraguide.ui.components.SectionHeading
import au.edu.unimelb.floraguide.ui.components.StatusPill
import java.time.format.TextStyle
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
                SectionHeading(title = "Context-aware smart matching result", subtitle = "We cross-reference the camera's visual match with local history and seasonal records", modifier = Modifier.weight(1f))
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
        // 1. Keep the unmodified image scores separate from context-adjusted scores.
        item(key = "before-context-scores") {
            CloudIdentificationCard(state = state, onRetry = onRetryIdentification)
        }
        // 2. Reuse the same section and candidate cards, with a green outer container.
        if (state.displayedRanking.isNotEmpty()) {
            item(key = "final-candidate-set") {
                CandidateSetCard(
                    title = "Final Suggested Match List",
                    subtitle = "Select the plant that best matches what you see. These percentages show how likely each choice is compared to the others on the list, rather than a definitive guarantee of absolute accuracy.",
                    highlighted = true,
                ) {
                    state.displayedRanking.forEach { candidate ->
                        key(candidate.species.id) {
                            CandidateResultCard(
                                selected = state.selectedCandidate?.species?.id == candidate.species.id,
                                onClick = { onSelectSpecies(candidate.species.id) },
                            ) {
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
                }
            }
        }
        // 3. Preserve the existing before-and-after comparison.
        if (state.imageOnlyRanking.isNotEmpty()) {
            item(key = "before-after") {
                ResultCard {
                    Text("Before / after (How environmental clues adjusted the results)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        RankingColumn("Image only", state.imageOnlyRanking.take(3), Modifier.weight(1f))
                        RankingColumn(if (state.isContextLoading) "Searching ALA local records..." else "Final smart suggestions",
                            state.fusedRanking.take(3), Modifier.weight(1f))
                    }
                    Text(rankingExplanation(state), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // 4. Keep lookup status and ALA-only retry after the comparison.
        item(key = "ala-lookup") { ContextProgress(state, onRetryContext) }
        state.selectedCandidate?.let { selected ->
            item {
                ResultCard {
                    Text("Why this suggestion?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    EvidenceBar(label = "Original image score", value = selected.evidence.imagePrior,
                        detail = String.format(Locale.US, "%.2f%% (based purely on photo)", selected.evidence.imagePrior * 100))
                    Text(recordLabel(selected, state), style = MaterialTheme.typography.bodyMedium)
                    state.nearbyContext?.takeUnless { it.isUnmatched(selected.species.id) }
                        ?.failuresBySpeciesId?.get(selected.species.id)?.let { reason ->
                            Text("ALA local history lookup: $reason. Unknown or failed check does not mean the plant doesn't grow here.", color = MaterialTheme.colorScheme.error)
                        }
                    if (state.imageSource == ImageSource.DEMO_ADAPTER) {
                        EvidenceBar("Synthetic location prior", selected.evidence.locationPrior, "Guided demo only")
                        EvidenceBar("Synthetic seasonal prior", selected.evidence.seasonalPrior, state.analysisDate.month.name)
                        EvidenceBar("Synthetic habitat prior", selected.evidence.habitatPrior, state.selectedHabitat.label)
                    } else {
                        Text(String.format(Locale.US, "Local abundance boost: %.3fx", selected.evidence.locationMultiplier))
                        Text(String.format(Locale.US, "Flowering season adjustment: %.2fx", selected.evidence.seasonMultiplier))
                        Text(floweringLabel(selected, state), style = MaterialTheme.typography.bodyMedium)
                        selected.evidence.flowering?.let { record ->
                            val uriHandler = LocalUriHandler.current
                            // CC BY 4.0 needs the attribution wherever VicFlora's wording is shown.
                            TextButton(onClick = { runCatching { uriHandler.openUri(record.sourceUrl) } }) {
                                Text("VicFlora, Royal Botanic Gardens Victoria (CC BY 4.0)")
                            }
                        }
                        Text("How this works: When every ALA lookup completes: image score x (1 + 20.5 x support), then normalise. " +
                            "Support uses log-counts capped at 50 records, so the multiplier runs from 1x to 21.5x." +
                            "In other words, plants commonly found growing nearby on campus receive an extra boost up the list. " +
                            "This boost scales from 1x up to a maximum of 21.5x based on local record frequencies.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("A name ALA cannot match counts as zero records; a failed or skipped lookup adds no " +
                            "geographic adjustment. " +
                            "The flowering check is shown for reference and does not change the order: in offline " +
                            "testing it lowered the correct species more often than wrong ones. " +
                            "Habitat does not change live rankings.", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Text("Observation habitat", fontWeight = FontWeight.Bold)
                    HabitatSelector(selected = state.selectedHabitat, onSelected = onHabitatSelected)
                }
            }
            item {
                Button(onClick = onConfirm, enabled = state.canSave, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Icon(Icons.Default.Check, null)
                    Text(if (state.isSaving) "  Securing records to phone..." else "  Save selected suggestion locally to My Field Guide")
                }
            }
            // canSave keeps the button disabled, so its click handler can never explain this.
            if (state.capture != null && state.capture?.location == null) {
                item {
                    Text("Saving needs a capture location. Enable location, wait for a device fix, then take a new photo.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.capture?.locationSource == CaptureLocationSource.APPROXIMATE) {
                item {
                    Text("Approximate location found nearby records, but saving pins the plant on the map. " +
                        "Tap Use precise location on Observe, then take a new photo.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            InformationCard("Interpretation and privacy",
                "ALA Local counts are based on historical sightings, not an exact count of live plants or absolute proof of identity. " +
                    "A zero count does not prove the plant is completely absent - " +
                    "since it could just mean no one has officially ever registered that plant here before " +
                    "To protect your privacy, saving a plant marks it as unverified and stores rounded coordinates locally on this phone; " +
                    "Nothing is ever published or submitted as a public record")
        }
    }
}

@Composable
private fun ContextProgress(state: FloraGuideUiState, onRetry: () -> Unit) {
    val context = state.nearbyContext
    ResultCard {
        Text("ALA local history check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        when {
            state.isContextLoading -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text("Checking what plant records exist near your saved capture location...")
                }
            }
            context != null -> {
                Text(context.source.label, fontWeight = FontWeight.Bold)
                Text("Radius: ${context.radiusKm} km | Successful candidates: " +
                    "${context.successfulRequestCount}/${context.requestCount}", style = MaterialTheme.typography.bodySmall)
                if (context.attemptsBySpeciesId.isNotEmpty()) Text(
                    "HTTP connection attempts: ${context.attemptsBySpeciesId.values.sum()} | " +
                        "Elapsed: ${context.lookupElapsedMillis ?: 0} ms | " +
                        "Status: ${context.httpStatusCodes.sorted().joinToString("/").ifBlank { "unavailable" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
                context.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                context.retryNotBefore?.let { Text("Server retry time: ${LOCAL_TIME_FORMAT.format(it)}", style = MaterialTheme.typography.bodySmall) }
            }
            else -> Text("Waiting for Pl@ntNet visual match list to finish loading...", style = MaterialTheme.typography.bodySmall)
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
    val synonym = state.nearbyContext?.acceptedNamesBySpeciesId?.get(candidate.species.id)?.let { " (as $it)" }.orEmpty()
    return when {
        state.isContextLoading -> "Local history check (ALA count): loading regional records..."
        count == null && state.nearbyContext?.isUnmatched(candidate.species.id) == true ->
            "Local history: no matching plant name currently listed in this specific regional index (counts as 0 past records)"
        count == null -> "Local history: check unavailable or unknown"
        count == 0 -> "Local history: 0 past sightings found nearby$synonym (this does not completely rule out its presence)"
        else -> "Local history: $count past sightings found within ${state.nearbyContext?.radiusKm ?: 8} km of this spot$synonym"
    }
}
/** The documented statement behind the flowering factor, or why none was applied. */
private fun floweringLabel(candidate: RankedCandidate, state: FloraGuideUiState): String {
    if (state.isContextLoading) return "Checked when the ALA lookup finishes."
    val evidence = candidate.evidence
    val month = state.analysisDate.month.getDisplayName(TextStyle.FULL, Locale.US)
    val statement = evidence.flowering?.let { record ->
        val synonym = if (record.sourceName != candidate.species.scientificName) " (as ${record.sourceName})" else ""
        "VicFlora$synonym: \"${record.statement}\" "
    }.orEmpty()
    return when (evidence.floweringCheck) {
        FloweringCheck.OUT_OF_SEASON -> "$statement$month falls completely outside this plant's documented blooming period.."
        FloweringCheck.IN_SEASON -> statement +
            if (state.analysisDate.monthValue in evidence.flowering?.months.orEmpty()) "$month matches this plant's documented blooming period."
            else "$month is within a month of this plant's documented blooming period."
        FloweringCheck.NO_DATA -> "This specific plant is not currently listed in our local seasonal VicFlora flowering table."
        FloweringCheck.NOT_APPLIED -> statement + "Detected plant part: " +
            (state.predictedOrgan?.let { String.format(Locale.US, "%s (%.0f%%)", it.organ, it.score * 100) } ?: "unknown") +
            ". Seasonal blooming checks run only when the scanner confidently recognizes a flower."
    }
}

private fun rankingExplanation(state: FloraGuideUiState): String {
    val season = if (state.fusedRanking.any { it.evidence.seasonMultiplier < 1.0 }) {
        " Plants that aren't supposed to bloom at this time of year have been moved further down the list."
    } else ""
    return when {
        state.isContextLoading -> "Image match suggestions are ready! We are currently still checking local history records near your location to double-check the results..."
        state.imageSource == ImageSource.DEMO_ADAPTER -> "This is a pre-set practice tour results designed to show you how the app handles clues. It is not a real live scan."
        state.nearbyContext?.source == ContextDataSource.ALA_LIVE ->
            "Local history check successful! Plant species officially recorded as growing near your location have been highlighted and moved up the list.$season"
        season.isNotEmpty() -> "We couldn't reach the local history database right now, but missing records aren't treated as a negative result.$season"
        else -> "Showing results based entirely on the photo's look. We couldn't look up local history records, but missing records aren't treated as a negative result."
    }
}
