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
 * mapping table is required: ALA resolves scientific names at lookup time.
 *
 * Season and habitat metadata are left empty deliberately. The ranker disables incomplete
 * cues for the entire candidate set instead of substituting invented ecology.
 */
private fun PlantNetResult.toSpecies(): Species = Species(
    id = scientificName,
    commonName = commonName ?: scientificName,
    scientificName = scientificName,
    preferredMonths = emptySet(),
    habitatAffinity = emptyMap(),
    // Unused by live lookup; demo counts are only read for explicit guided-demo candidates.
    demoNearbyCount = 0,
)
