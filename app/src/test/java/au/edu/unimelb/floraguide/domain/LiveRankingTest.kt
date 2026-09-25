package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.data.catalog.DemoSpeciesCatalog
import au.edu.unimelb.floraguide.domain.model.*
import au.edu.unimelb.floraguide.domain.usecase.CreateObservationUseCase
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class LiveRankingTest {
    private val ranker = RankSpeciesCandidatesUseCase()
    private val predictions = DemoSpeciesCatalog.all.take(2).mapIndexed { index, species ->
        ImagePrediction(species, if (index == 0) 0.9 else 0.1, index + 1)
    }
    // Fixture months for London plane and river red gum, not sourced flowering data.
    private val seasonal = RankSpeciesCandidatesUseCase(floweringMonths = mapOf(
        predictions[0].species.scientificName to setOf(12, 1, 2),
        predictions[1].species.scientificName to setOf(9, 10),
    ))
    private val flower = PredictedOrgan("flower", 0.9)
    private fun context(counts: List<Int>, source: ContextDataSource = ContextDataSource.ALA_LIVE) =
        NearbyContext(predictions.zip(counts).associate { (p, count) -> p.species.id to count }, source, 8)

    private fun assertImageOnly(result: List<RankedCandidate>) {
        val baseline = ranker.imageOnly(predictions)
        assertEquals(baseline.map { it.species.id }, result.map { it.species.id })
        baseline.zip(result).forEach { (before, after) ->
            assertEquals(before.relativeScore, after.relativeScore, 1e-12)
            assertEquals(1.0, after.evidence.locationMultiplier, 0.0)
            assertEquals(1.0, after.evidence.seasonMultiplier, 0.0)
        }
    }

    private fun seasonFactors(month: Int?, organ: PredictedOrgan?) =
        seasonal.live(predictions, context(listOf(0, 0)), month, organ)
            .associate { it.species.id to it.evidence.seasonMultiplier }

    @Test fun zeroRecordsAreKnownAndNeutral() {
        val result = ranker.live(predictions, context(listOf(0, 0)))
        assertImageOnly(result)
        assertTrue(result.all { it.nearbyRecordCount == 0 })
    }

    @Test fun partialFailedSkippedAndDemoContextNeverBoostLiveCandidates() {
        for (source in listOf(ContextDataSource.ALA_PARTIAL, ContextDataSource.ALA_UNAVAILABLE,
            ContextDataSource.NOT_REQUESTED, ContextDataSource.DEMO_FALLBACK)) {
            assertImageOnly(ranker.live(predictions, context(listOf(0, 500), source)))
        }
        val partial = ranker.live(predictions, context(listOf(500)))
        assertImageOnly(partial)
        assertEquals(500, partial.first().nearbyRecordCount)
        assertNull(partial.last().nearbyRecordCount)
        assertImageOnly(ranker.live(predictions, context(emptyList())))
        assertImageOnly(ranker.live(predictions, context(listOf(0, -1))))
    }

    @Test fun boostIsCappedAndCannotOverturnStrongImageLeader() {
        val result = ranker.live(predictions, context(listOf(0, 500)))
        assertEquals(predictions.first().species.id, result.first().species.id)
        assertEquals(0.9 / 1.015, result.first().relativeScore, 1e-12)
        assertEquals(1.15, result.last().evidence.locationMultiplier, 1e-12)
        assertEquals(result.map { it.relativeScore }, ranker.live(predictions, context(listOf(0, 50))).map { it.relativeScore })
    }

    @Test fun completeContextCanReorderCloseCandidates() {
        val close = predictions.mapIndexed { i, p -> p.copy(score = if (i == 0) 0.51 else 0.49) }
        val result = ranker.live(close, context(listOf(0, 50)))
        assertEquals(close.last().species.id, result.first().species.id)
        assertEquals(2, result.first().imageRank)
        assertEquals(1, result.first().finalRank)
        assertEquals(1.0, result.sumOf { it.relativeScore }, 1e-12)
    }

    @Test fun mixedSeasonAndHabitatMetadataDoNotAffectCurrentLiveRule() {
        val mixed = predictions.mapIndexed { i, p ->
            if (i == 0) p.copy(species = p.species.copy(preferredMonths = emptySet(), habitatAffinity = emptyMap())) else p
        }
        val expected = ranker.live(predictions, context(listOf(10, 50)))
        val actual = ranker.live(mixed, context(listOf(10, 50)))
        assertEquals(expected.map { it.relativeScore }, actual.map { it.relativeScore })
        assertTrue(actual.all { it.evidence.seasonalPrior == 1.0 && it.evidence.habitatPrior == 1.0 })
    }

    @Test fun flowerFactorFollowsDocumentedMonthsWithOneMonthTolerance() {
        assertEquals(mapOf("london_plane" to 1.0, "river_red_gum" to 0.85), seasonFactors(1, flower))
        // December and October are each one month from November, including across the new year.
        assertEquals(mapOf("london_plane" to 1.0, "river_red_gum" to 1.0), seasonFactors(11, flower))
        assertEquals(mapOf("london_plane" to 0.85, "river_red_gum" to 1.0), seasonFactors(8, flower))
    }

    @Test fun otherOrgansWeakGuessesMissingDatesAndUnlistedSpeciesKeepImageOnlyScores() {
        // January would lower the red gum if the cue applied.
        for (organ in listOf(PredictedOrgan("leaf", 0.9), PredictedOrgan("habit", 0.9), PredictedOrgan("flower", 0.49), null)) {
            assertImageOnly(seasonal.live(predictions, context(listOf(0, 0)), 1, organ))
        }
        assertImageOnly(seasonal.live(predictions, context(listOf(0, 0)), null, flower))
        assertImageOnly(ranker.live(predictions, context(listOf(0, 0)), 1, flower))
    }

    @Test fun outOfSeasonFlowerReordersCloseCandidatesWithoutAlaButNotAStrongLeader() {
        val close = predictions.mapIndexed { i, p -> p.copy(score = if (i == 0) 0.51 else 0.49) }
        for (source in listOf(ContextDataSource.ALA_LIVE, ContextDataSource.ALA_UNAVAILABLE)) {
            val result = seasonal.live(close, context(listOf(0, 0), source), 9, flower)
            assertEquals(listOf("river_red_gum", "london_plane"), result.map { it.species.id })
            assertEquals(0.49 / (0.49 + 0.51 * 0.85), result.first().relativeScore, 1e-12)
            assertTrue(result.all { it.evidence.locationMultiplier == 1.0 })
        }
        assertEquals("london_plane", seasonal.live(predictions, context(listOf(0, 0)), 9, flower).first().species.id)
    }

    @Test fun seasonOnlyAdjustmentIsSavedUnderTheLiveRule() {
        val unavailable = context(emptyList(), ContextDataSource.ALA_UNAVAILABLE)
        val capture = CaptureSnapshot("id", Instant.EPOCH, GeoPoint(-37.8, 144.96), CaptureLocationSource.DEVICE, null)
        fun rule(ranking: List<RankedCandidate>) = CreateObservationUseCase()(
            capture, ranking.first(), ranking, Habitat.LAWN, null, null, ImageSource.PLANTNET_LIVE, unavailable, Instant.EPOCH,
        ).rankingRule
        assertEquals(RankSpeciesCandidatesUseCase.LIVE_RULE_VERSION, rule(seasonal.live(predictions, unavailable, 9, flower)))
        assertEquals(RankSpeciesCandidatesUseCase.IMAGE_ONLY_RULE_VERSION, rule(seasonal.live(predictions, unavailable, 9, null)))
    }

    @Test fun invalidFloweringMonthsAndCaptureMonthsAreRejected() {
        for (months in listOf(emptySet(), setOf(0), setOf(13))) {
            assertThrows(IllegalArgumentException::class.java) {
                RankSpeciesCandidatesUseCase(floweringMonths = mapOf("Acacia dealbata" to months))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { ranker.live(predictions, context(listOf(0, 0)), 13, flower) }
    }

    @Test fun emptyInputAndTiesAreDeterministic() {
        assertTrue(ranker.live(emptyList(), context(emptyList())).isEmpty())
        val tied = predictions.map { it.copy(score = 0.5) }.reversed()
        assertEquals(listOf(1, 2), ranker.live(tied, context(listOf(0, 0))).map { it.imageRank })
    }
}
