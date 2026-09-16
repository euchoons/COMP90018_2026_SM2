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
fun CloudIdentificationCard(state: FloraGuideUiState, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Photo identification", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    state.analysisError != null -> "Identification failed"
                    state.identificationStage != null -> state.identificationStage.label
                    state.imageSource == ImageSource.PLANTNET_LIVE -> "Identified from the Firebase-stored photo"
                    state.imageSource == ImageSource.DEMO_ADAPTER -> "Offline guided demo - not a live identification"
                    else -> "Waiting for a photo"
                },
            )
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
                    Text("${prediction.rank}. ${prediction.species.commonName}")
                    Text(prediction.species.scientificName, style = MaterialTheme.typography.bodySmall)
                    Text(String.format(Locale.US, "Raw model score: %.4f", prediction.score))
                }
                Text(
                    "These are the API scores. The context-aware ranking below uses a different, normalised score.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
