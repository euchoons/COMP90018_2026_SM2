package au.edu.unimelb.floraguide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.ui.FloraGuideUiState
import au.edu.unimelb.floraguide.ui.components.EvidenceBar
import au.edu.unimelb.floraguide.ui.components.HabitatSelector
import au.edu.unimelb.floraguide.ui.components.InformationCard
import au.edu.unimelb.floraguide.ui.components.PhotoThumbnail
import au.edu.unimelb.floraguide.ui.components.RelativeScoreLabel
import au.edu.unimelb.floraguide.ui.components.SectionHeading
import au.edu.unimelb.floraguide.ui.components.SelectableCard
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
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBackToScan) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to camera")
                }
                SectionHeading(
                    title = "Context-aware result",
                    subtitle = "Image-first, then reranked with environmental evidence.",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhotoThumbnail(
                    path = state.photoPath,
                    modifier = Modifier.size(104.dp),
                    contentDescription = "Captured observation",
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill(label = "Prototype image adapter", positive = false)
                    StatusPill(
                        label = if (state.usingDemoLocation) "Demo location" else "Live location",
                        positive = !state.usingDemoLocation,
                    )
                    Text(
                        text = state.selectedHabitat.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            AnalysisProgressCard(state = state, onRetryContext = onRetryContext)
        }

        if (state.isClassifying) {
            item {
                LoadingCard(
                    title = "Generating image candidates",
                    body = "The architecture expects Top-K candidates so context can rescue a species outside the original Top 3.",
                )
            }
        }

        if (state.imageOnlyRanking.isNotEmpty()) {
            item {
                RankingComparison(
                    imageOnly = state.imageOnlyRanking.take(3),
                    fused = state.fusedRanking.take(3),
                    isContextLoading = state.isContextLoading,
                )
            }
        }

        if (state.displayedRanking.isNotEmpty()) {
            item {
                SectionHeading(
                    title = if (state.fusedRanking.isEmpty()) "Image-only candidates" else "Final Top 3",
                    subtitle = if (state.fusedRanking.isEmpty()) {
                        "Context has not been applied yet."
                    } else {
                        "Tap a candidate to inspect its evidence."
                    },
                )
            }

            items(count = state.displayedRanking.take(3).size, key = { index ->
                state.displayedRanking[index].species.id
            }) { index ->
                val candidate = state.displayedRanking[index]
                CandidateCard(
                    candidate = candidate,
                    selected = state.selectedCandidate?.species?.id == candidate.species.id,
                    contextApplied = state.fusedRanking.isNotEmpty(),
                    onClick = { onSelectSpecies(candidate.species.id) },
                )
            }
        }

        state.selectedCandidate?.let { selected ->
            item {
                EvidenceCard(
                    candidate = selected,
                    state = state,
                    onHabitatSelected = onHabitatSelected,
                )
            }
        }

        if (state.displayedRanking.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onConfirm,
                        enabled = !state.isClassifying && !state.isContextLoading,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text("  Confirm and add to field guide")
                    }
                    FilledTonalButton(
                        onClick = onBackToScan,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Retake observation")
                    }
                }
            }
        }

        item {
            InformationCard(
                title = "Interpretation guardrail",
                body = "Displayed values are relative ranking scores over this candidate set, not calibrated confidence percentages. An unknown/genus-level option should be added when a real model is integrated.",
            )
        }
    }
}

@Composable
private fun AnalysisProgressCard(state: FloraGuideUiState, onRetryContext: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnalysisStep(
                number = "1",
                title = "Image Top-K",
                detail = if (state.isClassifying) "Running locally…" else "Candidate set ready",
                complete = !state.isClassifying && state.imagePredictions.isNotEmpty(),
                loading = state.isClassifying,
            )
            HorizontalDivider()
            AnalysisStep(
                number = "2",
                title = "Nearby occurrence context",
                detail = when {
                    state.isContextLoading -> "Querying nearby records…"
                    state.nearbyContext != null -> state.nearbyContext.progressDetail()
                    else -> "Waiting for image candidates"
                },
                complete = state.nearbyContext != null,
                loading = state.isContextLoading,
            )
            HorizontalDivider()
            AnalysisStep(
                number = "3",
                title = "Explainable fusion",
                detail = if (state.fusedRanking.isNotEmpty()) "Softmax-normalised reranking complete" else "Pending context",
                complete = state.fusedRanking.isNotEmpty(),
                loading = false,
            )
            state.nearbyContext?.warning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val canRetryLiveContext = !state.isContextLoading &&
                state.imagePredictions.isNotEmpty() &&
                state.analysisPrefersLiveData &&
                state.nearbyContext?.source != ContextDataSource.ALA_LIVE
            if (canRetryLiveContext) {
                FilledTonalButton(onClick = onRetryContext, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("  Retry live context")
                }
            }
        }
    }
}

@Composable
private fun AnalysisStep(
    number: String,
    title: String,
    detail: String,
    complete: Boolean,
    loading: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (complete) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    complete -> Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    else -> Text(number, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RankingComparison(
    imageOnly: List<RankedCandidate>,
    fused: List<RankedCandidate>,
    isContextLoading: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Before / after", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Icon(
                    Icons.AutoMirrored.Filled.CompareArrows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RankingColumn(
                    title = "Image only",
                    candidates = imageOnly,
                    modifier = Modifier.weight(1f),
                )
                RankingColumn(
                    title = if (isContextLoading) "Context loading" else "Fused",
                    candidates = fused,
                    modifier = Modifier.weight(1f),
                    placeholder = if (isContextLoading) "ALA / fallback\nthen rerank" else "Pending",
                )
            }
        }
    }
}

@Composable
private fun RankingColumn(
    title: String,
    candidates: List<RankedCandidate>,
    modifier: Modifier = Modifier,
    placeholder: String = "Pending",
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (candidates.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            candidates.forEachIndexed { index, candidate ->
                Text(
                    text = "${index + 1}. ${candidate.species.commonName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: RankedCandidate,
    selected: Boolean,
    contextApplied: Boolean,
    onClick: () -> Unit,
) {
    SelectableCard(selected = selected, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${candidate.finalRank}", fontWeight = FontWeight.Black)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = candidate.species.commonName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = candidate.species.scientificName,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (contextApplied) {
                        "Image #${candidate.imageRank} → fused #${candidate.finalRank} · ${candidate.nearbyRecordCount} nearby records"
                    } else {
                        "Image rank #${candidate.imageRank}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            RelativeScoreLabel(score = candidate.relativeScore)
        }
    }
}

@Composable
private fun EvidenceCard(
    candidate: RankedCandidate,
    state: FloraGuideUiState,
    onHabitatSelected: (Habitat) -> Unit,
) {
    val month = state.analysisDate.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Why this species?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = candidate.species.commonName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            EvidenceBar(
                label = "Image evidence",
                value = candidate.evidence.imagePrior,
                detail = "camera rank #${candidate.imageRank}",
            )
            EvidenceBar(
                label = "Location prior",
                value = candidate.evidence.locationPrior,
                detail = "${candidate.nearbyRecordCount} records / ${state.nearbyContext?.radiusKm ?: 8} km",
            )
            EvidenceBar(
                label = "Seasonal prior",
                value = candidate.evidence.seasonalPrior,
                detail = month,
            )
            EvidenceBar(
                label = "Microhabitat prior",
                value = candidate.evidence.habitatPrior,
                detail = state.selectedHabitat.shortLabel,
            )
            HorizontalDivider()
            Text(
                text = "score(s) = α log(image) + β log(location) + γ log(season) + δ log(habitat), followed by softmax",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Try changing the habitat below; the final ordering updates without another network request.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            HabitatSelector(selected = state.selectedHabitat, onSelected = onHabitatSelected)
        }
    }
}

private fun NearbyContext.progressDetail(): String = buildString {
    append(source.label)
    append(" · ")
    append(radiusKm)
    append(" km")
    if (requestCount == 0) {
        append(" · network skipped")
    } else {
        append(" · ")
        append(successfulRequestCount)
        append("/")
        append(requestCount)
        append(" live")
        lookupElapsedMillis?.let { elapsed ->
            append(" · ")
            append(elapsed)
            append(" ms")
        }
        if (httpStatusCodes.isNotEmpty()) {
            append(" · HTTP ")
            append(httpStatusCodes.sorted().joinToString("/"))
        }
    }
}
