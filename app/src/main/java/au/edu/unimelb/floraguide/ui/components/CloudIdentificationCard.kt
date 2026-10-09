package au.edu.unimelb.floraguide.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.ui.FloraGuideUiState
import java.util.Locale
import androidx.compose.ui.tooling.preview.Preview
/** Displays raw Pl@ntNet scores, before any context cue or normalisation. */
@Composable
fun CloudIdentificationCard(state: FloraGuideUiState, onRetry: () -> Unit) {
    CandidateSetCard(
        title = "Score before context cue",
        subtitle = "(Photo identification before ALA context is applied.)",
    ) {
        val status = when {
            state.analysisError != null -> "Identification failed"
            state.identificationStage != null -> state.identificationStage.label
            state.isClassifying -> "Preparing image candidates"
            state.imageSource == ImageSource.PLANTNET_LIVE -> "Identified from the Firebase-stored photo"
            state.imageSource == ImageSource.DEMO_ADAPTER -> "Offline guided demo - not a live identification"
            else -> "Waiting for a photo"
        }
        if (state.isClassifying) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp))
                Text(status)
            }
        } else {
            Text(status)
        }
        state.storedPhoto?.let {
            Text("Firebase upload complete", style = MaterialTheme.typography.bodySmall)
            Text(it.storagePath, style = MaterialTheme.typography.bodySmall)
        }
        state.analysisError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
            if (state.storedPhoto != null) {
                Text("The cloud photo is retained. Retry will reuse it.")
            }
            if (state.photoPath != null && !state.isClassifying) {
                FilledTonalButton(onClick = onRetry) { Text("Retry identification") }
            }
        }
        if (state.imageSource == ImageSource.PLANTNET_LIVE) {
            Text("Pl@ntNet original Top 3", style = MaterialTheme.typography.titleSmall)
            state.imagePredictions.sortedByDescending { it.score }.take(3).forEach { prediction ->
                key(prediction.species.id) {
                    CandidateResultCard {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#${prediction.rank}", fontWeight = FontWeight.Bold)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(prediction.species.commonName, fontWeight = FontWeight.Bold)
                                Text(
                                    prediction.species.scientificName,
                                    fontStyle = FontStyle.Italic,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text("Raw model score", style = MaterialTheme.typography.labelSmall)
                            }
                            RawModelScoreLabel(prediction.score)
                        }
                    }
                }
            }
            Text(
                "These percentages show how much the plant looks like your photo. " +
                    "The final suggestions set below combine this score with context cues.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Match the final-score badge style without rounding raw scores to two decimals. */
@Composable
private fun RawModelScoreLabel(score: Double) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape,
    ) {
        Text(
            text = String.format(Locale.US, "%.4f", score),
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
@Preview(showBackground = true)
@Composable
fun CloudIdentificationCardPreview() {
    au.edu.unimelb.floraguide.ui.theme.FloraGuideTheme {
        CloudIdentificationCard(
            state = FloraGuideUiState(imageSource = ImageSource.PLANTNET_LIVE),
            onRetry = {}
        )
    }
}
