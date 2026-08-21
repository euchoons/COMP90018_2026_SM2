package au.edu.unimelb.bioscout.data.classifier

import au.edu.unimelb.bioscout.data.catalog.DemoSpeciesCatalog
import au.edu.unimelb.bioscout.domain.model.ImagePrediction
import au.edu.unimelb.bioscout.domain.repository.ImageClassifier
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Honest prototype boundary: this class simulates an on-device Top-K image model so the team can
 * demonstrate the complete architecture before training/integrating TensorFlow Lite.
 */
class DemoImageClassifier : ImageClassifier {
    override suspend fun classify(photoPath: String?): List<ImagePrediction> {
        delay(420) // Makes the asynchronous UI state visible without feeling slow.

        val baseScores = listOf(0.41, 0.31, 0.13, 0.07, 0.035, 0.025, 0.012, 0.008)
        val seed = abs(photoPath?.hashCode() ?: 17) % 997
        val adjusted = baseScores.mapIndexed { index, score ->
            // Tiny deterministic variation demonstrates an adapter consuming a real photo path
            // while preserving a repeatable presentation narrative.
            score * (1.0 + (((seed + index * 37) % 9) - 4) * 0.004)
        }
        val normaliser = adjusted.sum()

        return DemoSpeciesCatalog.all
            .zip(adjusted)
            .sortedByDescending { (_, score) -> score }
            .mapIndexed { index, (species, score) ->
                ImagePrediction(
                    species = species,
                    score = score / normaliser,
                    rank = index + 1,
                )
            }
    }
}
