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
    fun missingNearbyRecordsDisableLocationWithoutDiscardingCandidates() {
        val result = ranker(
            predictions = predictions,
            nearbyCounts = mapOf("river_red_gum" to 50),
            habitat = Habitat.TREE_CANOPY,
            date = LocalDate.of(2026, 8, 17),
        )

        assertEquals(predictions.size, result.size)
        assertTrue(result.all { it.relativeScore > 0.0 })
        assertTrue(result.all { !it.evidence.locationUsed })
    }

    @Test
    fun incompleteCuesReturnExactImageOnlyBaseline() {
        val mixed = predictions.mapIndexed { index, prediction ->
            if (index == 0) prediction.copy(species = prediction.species.copy(
                preferredMonths = emptySet(), habitatAffinity = emptyMap(),
            )) else prediction
        }
        for (counts in listOf(emptyMap(), mapOf(mixed.first().species.id to 999))) {
            assertEquals(ranker.imageOnly(mixed), ranker(mixed, counts, Habitat.TREE_CANOPY, LocalDate.of(2026, 8, 17)))
        }
    }

    @Test
    fun completeZeroCountsRemainKnownAndDoNotChangeImageRanking() {
        val live = predictions.map { it.copy(species = it.species.copy(preferredMonths = emptySet(), habitatAffinity = emptyMap())) }
        val baseline = ranker.imageOnly(live)
        val ranked = ranker(live, live.associate { it.species.id to 0 }, Habitat.LAWN, LocalDate.of(2026, 8, 17))
        assertEquals(baseline.map { it.species.id }, ranked.map { it.species.id })
        ranked.zip(baseline).forEach { (actual, expected) ->
            assertEquals(expected.relativeScore, actual.relativeScore, 1e-12)
            assertTrue(actual.evidence.locationUsed)
            assertEquals(0, actual.nearbyRecordCount)
            assertTrue(!actual.evidence.seasonUsed && !actual.evidence.habitatUsed)
        }
    }

    @Test
    fun missingSelectedHabitatDisablesOnlyHabitatCue() {
        val mixed = predictions.mapIndexed { index, prediction ->
            if (index == 0) prediction.copy(species = prediction.species.copy(
                habitatAffinity = mapOf(Habitat.LAWN to 1.0),
            )) else prediction
        }
        val ranked = ranker(mixed, emptyMap(), Habitat.TREE_CANOPY, LocalDate.of(2026, 8, 17))
        assertTrue(ranked.all { it.evidence.seasonUsed && !it.evidence.habitatUsed && !it.evidence.locationUsed })
    }
}
