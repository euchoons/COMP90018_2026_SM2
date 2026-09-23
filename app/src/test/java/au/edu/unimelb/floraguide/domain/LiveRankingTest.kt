package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.data.catalog.DemoSpeciesCatalog
import au.edu.unimelb.floraguide.domain.model.*
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import org.junit.Assert.*
import org.junit.Test

class LiveRankingTest {
    private val ranker = RankSpeciesCandidatesUseCase()
    private val predictions = DemoSpeciesCatalog.all.take(2).mapIndexed { index, species ->
        ImagePrediction(species, if (index == 0) 0.9 else 0.1, index + 1)
    }
    private fun context(counts: List<Int>, source: ContextDataSource = ContextDataSource.ALA_LIVE) =
        NearbyContext(predictions.zip(counts).associate { (p, count) -> p.species.id to count }, source, 8)

    private fun assertImageOnly(result: List<RankedCandidate>) {
        val baseline = ranker.imageOnly(predictions)
        assertEquals(baseline.map { it.species.id }, result.map { it.species.id })
        baseline.zip(result).forEach { (before, after) ->
            assertEquals(before.relativeScore, after.relativeScore, 1e-12)
            assertEquals(1.0, after.evidence.locationMultiplier, 0.0)
        }
    }

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

    @Test fun emptyInputAndTiesAreDeterministic() {
        assertTrue(ranker.live(emptyList(), context(emptyList())).isEmpty())
        val tied = predictions.map { it.copy(score = 0.5) }.reversed()
        assertEquals(listOf(1, 2), ranker.live(tied, context(listOf(0, 0))).map { it.imageRank })
    }
}
