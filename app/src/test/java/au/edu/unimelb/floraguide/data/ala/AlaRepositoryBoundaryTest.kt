package au.edu.unimelb.floraguide.data.ala

import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Species
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlaRepositoryBoundaryTest {
    private val candidates = listOf(
        Species("test-a", "Test candidate A", "Species a", emptySet(), emptyMap(), 77),
        Species("test-b", "Test candidate B", "Species b", emptySet(), emptyMap(), 88),
    )
    private val location = GeoPoint(-37.7963, 144.9614)
    private val fixedNow = Instant.parse("2026-09-24T00:00:00Z")

    // ALA-R01
    @Test fun emptyCandidatesSkipTheService() = runTest {
        var calls = 0
        val repository = repository(source { calls++; AlaOccurrenceResponse(10, 200, 1) })
        val result = repository.nearbyOccurrenceCounts(emptyList(), location, 8, true)
        assertEquals(ContextDataSource.NOT_REQUESTED, result.source)
        assertTrue(result.countsBySpeciesId.isEmpty())
        assertEquals(0, calls)
    }

    // ALA-R02
    @Test fun duplicateCandidateIdIsQueriedOnlyOnce() = runTest {
        var calls = 0
        val repository = repository(source { calls++; AlaOccurrenceResponse(10, 200, 1) })
        val result = repository.nearbyOccurrenceCounts(listOf(candidates[0], candidates[0]), location, 8, true)
        assertEquals(1, calls)
        assertEquals(1, result.requestCount)
        assertEquals(mapOf(candidates[0].id to 10), result.countsBySpeciesId)
    }

    // ALA-R03
    @Test fun transientFailureThenSuccessRetainsAttemptHistory() = runTest {
        var calls = 0
        val repository = repository(source {
            calls++
            if (calls == 1) throw AlaRequestException("temporary", 503, 1)
            AlaOccurrenceResponse(12, 200, 1)
        })
        val result = repository.nearbyOccurrenceCounts(candidates.take(1), location, 8, true)
        assertEquals(ContextDataSource.ALA_LIVE, result.source)
        assertEquals(2, calls)
        assertEquals(2, result.attemptsBySpeciesId[candidates[0].id])
        assertEquals(setOf(503, 200), result.httpStatusCodes)
        assertEquals(12, result.countsBySpeciesId[candidates[0].id])
        assertTrue(result.failuresBySpeciesId.isEmpty())
    }

    // ALA-R04
    @Test fun permanentHttpFailuresAreNotRetriedOrConvertedToDemoCounts() = runTest {
        for (status in listOf(400, 401, 403)) {
            var calls = 0
            val repository = repository(source { calls++; throw AlaRequestException("rejected", status, 1) })
            val result = repository.nearbyOccurrenceCounts(candidates.take(1), location, 8, true)
            assertEquals(1, calls)
            assertEquals(ContextDataSource.ALA_UNAVAILABLE, result.source)
            assertTrue(result.countsBySpeciesId.isEmpty())
            assertEquals("HTTP_$status", result.failuresBySpeciesId[candidates[0].id])
        }
    }

    // ALA-R05
    @Test fun negativeAdapterCountIsNotAcceptedAsEvidence() = runTest {
        val result = repository(source { AlaOccurrenceResponse(-1, 200, 1) })
            .nearbyOccurrenceCounts(candidates.take(1), location, 8, true)
        assertEquals(ContextDataSource.ALA_UNAVAILABLE, result.source)
        assertTrue(result.countsBySpeciesId.isEmpty())
        assertEquals("INVALID_INPUT", result.failuresBySpeciesId[candidates[0].id])
        assertEquals(1, result.attemptsBySpeciesId[candidates[0].id])
    }

    // ALA-R06
    @Test fun serverRetryTimeIsExactAndNeverRetriedInsideInsufficientBudget() = runTest {
        var calls = 0
        val result = repository(source {
            calls++
            throw AlaRequestException("quota", 429, 1, retryAfterMillis = 60_000L)
        }).nearbyOccurrenceCounts(candidates.take(1), location, 8, true)
        assertEquals(1, calls)
        assertEquals(fixedNow.plusSeconds(60), result.retryNotBefore)
        assertTrue(result.countsBySpeciesId.isEmpty())
        assertEquals("HTTP_429", result.failuresBySpeciesId[candidates[0].id])
    }

    // ALA-R07
    @Test fun candidateConcurrencyNeverExceedsConfiguredLimit() = runTest {
        var active = 0
        var peak = 0
        // Five synthetic candidates test the configured limit without a real service.
        val many = (1..5).map { candidates[0].copy(id = "test-$it", scientificName = "Species $it") }
        val source = source {
            active++
            peak = maxOf(peak, active)
            try { delay(100); AlaOccurrenceResponse(1, 200, 1) } finally { active-- }
        }
        val repository = ReliableAlaSpeciesContextRepository(
            source, nanoTime = { testScheduler.currentTime * 1_000_000L },
            maxConcurrentRequests = 2, perCandidateTimeoutMillis = 1_000L,
            retryDelayMillis = 0, now = { fixedNow },
        )
        val result = repository.nearbyOccurrenceCounts(many, location, 8, true)
        assertEquals(2, peak)
        assertEquals(0, active)
        assertEquals(5, result.successfulRequestCount)
        assertEquals(ContextDataSource.ALA_LIVE, result.source)
    }

    // ALA-R08
    @Test fun candidateTimeoutCancelsWorkAndReturnsUnknown() = runTest {
        var cleanedUp = false
        val source = source {
            try { delay(10_000); AlaOccurrenceResponse(5, 200, 1) } finally { cleanedUp = true }
        }
        val repository = ReliableAlaSpeciesContextRepository(
            source, nanoTime = { testScheduler.currentTime * 1_000_000L },
            perCandidateTimeoutMillis = 100L, retryDelayMillis = 0, now = { fixedNow },
        )
        val result = repository.nearbyOccurrenceCounts(candidates.take(1), location, 8, true)
        assertTrue(cleanedUp)
        assertEquals(ContextDataSource.ALA_UNAVAILABLE, result.source)
        assertEquals("TIMEOUT", result.failuresBySpeciesId[candidates[0].id])
        assertTrue(result.countsBySpeciesId.isEmpty())
    }

    private fun repository(source: AlaOccurrenceSource) = ReliableAlaSpeciesContextRepository(
        source, nanoTime = { 0L }, retryDelayMillis = 0L, now = { fixedNow },
    )

    private fun source(block: suspend () -> AlaOccurrenceResponse) = object : AlaOccurrenceSource {
        override suspend fun countNearbyOccurrencesAsync(
            scientificName: String, location: GeoPoint, radiusKm: Int,
        ): AlaOccurrenceResponse = block()
    }
}
