package au.edu.unimelb.floraguide.data.plantnet

import au.edu.unimelb.floraguide.data.classifier.DemoImageClassifier
import au.edu.unimelb.floraguide.domain.model.ImageClassification
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.Species
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud image recognition. A future on-device TensorFlow Lite adapter implements the same
 * [ImageClassifier] seam, so both back ends can coexist behind one interface.
 */
class PlantNetImageClassifier(
    private val client: PlantNetSource,
    private val guidedDemoClassifier: ImageClassifier = DemoImageClassifier(),
) : ImageClassifier {
    override suspend fun classify(photoPath: String?): ImageClassification {
        // The guided demo is an offline, repeatable narrative and must never hit the network.
        if (photoPath == null) return guidedDemoClassifier.classify(null)

        val identification = withContext(Dispatchers.IO) { client.identify(File(photoPath)) }
        val predictions = identification.results
            .distinctBy { it.scientificName }
            .sortedByDescending { it.score }
            .mapIndexed { index, result ->
                ImagePrediction(
                    species = result.toSpecies(),
                    score = result.score,
                    rank = index + 1,
                )
            }

        return ImageClassification(
            predictions = predictions,
            source = ImageSource.PLANTNET_LIVE,
            elapsedMillis = identification.elapsedMillis,
        )
    }
}

/**
 * Pl@ntNet covers the world flora, so there is no fixed catalogue to map into and no label
 * mapping table is required: the ALA occurrence lookup already queries by scientific name.
 *
 * Season and habitat priors are left empty deliberately. `Species.seasonalPrior` and
 * `Species.habitatPrior` then return the same constant for every candidate, which adds a constant
 * to every raw score and therefore cancels out in the softmax. Ranking is driven by the image
 * score and nearby ALA records alone, instead of by invented ecology.
 */
private fun PlantNetResult.toSpecies(): Species = Species(
    id = scientificName,
    commonName = commonName ?: scientificName,
    scientificName = scientificName,
    preferredMonths = emptySet(),
    habitatAffinity = emptyMap(),
    // No offline record count is known for an arbitrary species; a uniform 0 keeps the
    // ALA-unavailable fallback honest rather than inventing a nearby-count ordering.
    demoNearbyCount = 0,
)
