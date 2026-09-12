package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.data.catalog.DemoSpeciesCatalog
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankSpeciesCandidatesUseCaseTest {
    private val ranker = RankSpeciesCandidatesUseCase()
    private val predictions = DemoSpeciesCatalog.all
        .zip(listOf(0.41, 0.31, 0.13, 0.07, 0.035, 0.025, 0.012, 0.008))
        .mapIndexed { index, (species, score) ->
            ImagePrediction(species = species, score = score, rank = index + 1)
        }

    @Test
    fun locationSeasonAndHabitatCanRerankImageLeader() {
        val result = ranker(
            predictions = predictions,
            nearbyCounts = DemoSpeciesCatalog.all.associate { it.id to it.demoNearbyCount },
            habitat = Habitat.TREE_CANOPY,
            date = LocalDate.of(2026, 8, 17),
        )

        assertEquals("river_red_gum", result.first().species.id)
        assertEquals(2, result.first().imageRank)
        assertEquals(1, result.first().finalRank)
    }

    @Test
    fun relativeScoresAreNormalised() {
        val result = ranker(
            predictions = predictions,
            nearbyCounts = emptyMap(),
            habitat = Habitat.LAWN,
            date = LocalDate.of(2026, 8, 17),
        )

        assertTrue(abs(result.sumOf { it.relativeScore } - 1.0) < 1e-9)
        assertTrue(result.all { it.relativeScore in 0.0..1.0 })
    }

    @Test
    fun missingNearbyRecordsAreSmoothedRatherThanZeroed() {
        val result = ranker(
            predictions = predictions,
            nearbyCounts = mapOf("river_red_gum" to 50),
            habitat = Habitat.TREE_CANOPY,
            date = LocalDate.of(2026, 8, 17),
        )

        assertEquals(predictions.size, result.size)
        assertTrue(result.all { it.relativeScore > 0.0 })
        assertTrue(result.first { it.species.id == "london_plane" }.evidence.locationPrior > 0.0)
    }
}
