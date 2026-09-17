package au.edu.unimelb.floraguide.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.ui.FloraGuideUiState
import java.util.Locale

@Composable
fun CloudIdentificationCard(
    state: FloraGuideUiState,
    onRetry: () -> Unit,
) {
    val identificationFailed =
        state.photoPath != null &&
            !state.isClassifying &&
            state.imagePredictions.isEmpty() &&
            state.message != null

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Photo identification",
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = when {
                    state.isClassifying ->
                        "Identifying photo..."

                    identificationFailed ->
                        "Identification failed"

                    state.imageSource == ImageSource.PLANTNET_LIVE ->
                        "Live identification complete"

                    state.imageSource == ImageSource.DEMO_ADAPTER ->
                        "Offline guided demo - not a live identification"

                    state.imagePredictions.isNotEmpty() ->
                        "Identification complete"

                    else ->
                        "Waiting for a photo"
                },
            )

            if (identificationFailed) {
                state.message?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                FilledTonalButton(
                    onClick = onRetry,
                ) {
                    Text("Retry identification")
                }
            }

            if (state.imageSource == ImageSource.PLANTNET_LIVE) {
                Text(
                    text = "Pl@ntNet original Top 3",
                    style = MaterialTheme.typography.titleSmall,
                )

                state.imagePredictions
                    .sortedByDescending { it.score }
                    .take(3)
                    .forEach { prediction ->
                        Text(
                            text = "${prediction.rank}. ${prediction.species.commonName}",
                        )

                        Text(
                            text = prediction.species.scientificName,
                            style = MaterialTheme.typography.bodySmall,
                        )

                        Text(
                            text = String.format(
                                Locale.US,
                                "Raw model score: %.4f",
                                prediction.score,
                            ),
                        )
                    }

                Text(
                    text = "These are the API scores. The context-aware ranking below uses a different, normalised score.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
