package au.edu.unimelb.floraguide.data.plantnet

import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImageClassification
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PlantNetImageClassifierTest {
    @Test
    fun candidatesAreBuiltFromScientificNamesWithNeutralEcologyPriors() = runBlocking {
        val classification = classifierReturning(LIVE_CAPTURE_RESULTS).classify("/tmp/photo.jpg")

        assertEquals(ImageSource.PLANTNET_LIVE, classification.source)
        assertEquals(120L, classification.elapsedMillis)

        val top = classification.predictions.first()
        // The ALA repository queries by scientific name, so no label mapping table is needed.
        assertEquals("Corymbia bella", top.species.id)
        assertEquals("Corymbia bella", top.species.scientificName)
        assertEquals(1, top.rank)
        // No common name in the response, so the scientific name is shown rather than a blank.
        assertEquals("Corymbia bella", top.species.commonName)
        assertEquals("River redgum", classification.predictions[1].species.commonName)

        // Ecology priors stay empty: inventing months or habitats for arbitrary world flora
        // would fabricate evidence the app cannot support.
        assertTrue(classification.predictions.all { it.species.preferredMonths.isEmpty() })
        assertTrue(classification.predictions.all { it.species.habitatAffinity.isEmpty() })
    }

    /**
     * The design claim behind the empty priors: identical season/habitat priors add the same
     * constant to every raw score, so they cancel in the softmax and ranking is decided by the
     * image score and nearby ALA records. This is the real capture recorded during integration —
     * Pl@ntNet ranked a tropical species first, and Melbourne occurrence records correct it.
     */
    @Test
    fun nearbyAlaRecordsPromoteTheLocallyPlausibleSpecies() = runBlocking {
        val predictions = classifierReturning(LIVE_CAPTURE_RESULTS)
            .classify("/tmp/photo.jpg")
            .predictions
        assertEquals("Corymbia bella", predictions.first().species.scientificName)

        val fused = RankSpeciesCandidatesUseCase()(
            predictions = predictions,
            nearbyCounts = mapOf(
                // Measured ALA counts within 8 km of the Parkville campus, 2026-09-07.
                "Corymbia bella" to 0,
                "Eucalyptus camaldulensis" to 532,
            ),
            habitat = Habitat.TREE_CANOPY,
            date = LocalDate.of(2026, 9, 7),
        )

        assertEquals("Eucalyptus camaldulensis", fused.first().species.scientificName)
        assertEquals(2, fused.first().imageRank)
    }

    @Test
    fun guidedDemoDelegatesOfflineAndNeverCallsPlantNet() = runBlocking {
        var identifyCalls = 0
        val classifier = PlantNetImageClassifier(
            client = { _ -> identifyCalls++; error("network must not be used") },
            guidedDemoClassifier = StubClassifier,
        )

        val classification = classifier.classify(null)

        assertEquals(ImageSource.DEMO_ADAPTER, classification.source)
        assertEquals(0, identifyCalls)
    }

    @Test
    fun duplicateSpeciesCannotCollideInTheNearbyCountsMap() = runBlocking {
        val duplicated = LIVE_CAPTURE_RESULTS + LIVE_CAPTURE_RESULTS.first().copy(score = 0.01)

        val predictions = classifierReturning(duplicated).classify("/tmp/photo.jpg").predictions

        assertEquals(predictions.size, predictions.map { it.species.id }.distinct().size)
        assertEquals(listOf(1, 2), predictions.map { it.rank })
        assertFalse(predictions.any { it.score == 0.01 })
    }

    private fun classifierReturning(results: List<PlantNetResult>) = PlantNetImageClassifier(
        client = { _ ->
            PlantNetIdentification(
                results = results,
                httpStatus = 200,
                elapsedMillis = 120L,
                remainingRequests = 499,
            )
        },
        guidedDemoClassifier = StubClassifier,
    )

    private object StubClassifier : ImageClassifier {
        override suspend fun classify(photoPath: String?): ImageClassification =
            ImageClassification(predictions = emptyList(), source = ImageSource.DEMO_ADAPTER)
    }

    private companion object {
        /** Recorded from a live River Red Gum capture during Pl@ntNet integration. */
        val LIVE_CAPTURE_RESULTS = listOf(
            PlantNetResult("Corymbia bella", commonName = null, score = 0.17292),
            PlantNetResult("Eucalyptus camaldulensis", commonName = "River redgum", score = 0.12478),
        )
    }
}

private fun PlantNetImageClassifier(
    client: (File) -> PlantNetIdentification,
    guidedDemoClassifier: ImageClassifier,
) = PlantNetImageClassifier(
    client = object : PlantNetSource {
        override fun identify(photoFile: File): PlantNetIdentification = client(photoFile)
    },
    guidedDemoClassifier = guidedDemoClassifier,
)
