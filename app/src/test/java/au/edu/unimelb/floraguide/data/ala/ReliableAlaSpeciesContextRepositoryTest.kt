package au.edu.unimelb.floraguide.data.ala

import au.edu.unimelb.floraguide.data.catalog.DemoSpeciesCatalog
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/** Ported from the removed AlaSpeciesContextRepositoryTest, with the partial case inverted. */
class ReliableAlaSpeciesContextRepositoryTest {
    private val candidates = DemoSpeciesCatalog.all.take(2)
    private val location = GeoPoint(-37.7963, 144.9614)

    private suspend fun lookup(source: AlaOccurrenceSource, preferLiveData: Boolean = true) =
        ReliableAlaSpeciesContextRepository(source, retryDelayMillis = 0)
            .nearbyOccurrenceCounts(candidates, location, radiusKm = 8, preferLiveData = preferLiveData)

    @Test
    fun guidedDemoSkipsNetwork() = runBlocking {
        val source = FakeSource(successCounts = emptyMap())
        val result = lookup(source, preferLiveData = false)

        assertEquals(ContextDataSource.DEMO_FALLBACK, result.source)
        assertEquals(0, source.calls.size)
        assertEquals(candidates.associate { it.id to it.demoNearbyCount }, result.countsBySpeciesId)
    }

    @Test
    fun completeLiveLookupReportsEveryCount() = runBlocking {
        val source = FakeSource(mapOf(candidates[0].scientificName to 7, candidates[1].scientificName to 532))
        val result = lookup(source)

        assertEquals(ContextDataSource.ALA_LIVE, result.source)
        assertEquals(2, result.successfulRequestCount)
        assertEquals(2, result.requestCount)
        assertEquals(setOf(200), result.httpStatusCodes)
        assertEquals(532, result.countsBySpeciesId.getValue(candidates[1].id))
    }

    @Test
    fun partialLookupRetriesOnceAndNeverSubstitutesDemoCounts() = runBlocking {
        val source = FakeSource(
            successCounts = mapOf(candidates[0].scientificName to 11),
            failureStatuses = mapOf(candidates[1].scientificName to 503),
        )
        val result = lookup(source)

        assertEquals(ContextDataSource.ALA_PARTIAL, result.source)
        assertEquals(11, result.countsBySpeciesId.getValue(candidates[0].id))
        // Unknown is not zero and not a demo count: the failed candidate has no entry at all.
        assertFalse(candidates[1].id in result.countsBySpeciesId)
        assertEquals("HTTP_503", result.failuresBySpeciesId[candidates[1].id])
        assertEquals(2, result.attemptsBySpeciesId[candidates[1].id])
        assertEquals(setOf(200, 503), result.httpStatusCodes)
        assertTrue(result.warning.orEmpty().contains("1/2"))
    }

    @Test
    fun cancellationIsNotConvertedToFallback() {
        val source = object : AlaOccurrenceSource {
            override suspend fun countNearbyOccurrencesAsync(
                scientificName: String,
                location: GeoPoint,
                radiusKm: Int,
            ): AlaOccurrenceResponse = throw CancellationException("superseded analysis")
        }
        val failure = runCatching { runBlocking { lookup(source) } }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test fun allZeroCountsAreCompleteLiveResults() = runBlocking {
        val result = lookup(FakeSource(candidates.associate { it.scientificName to 0 }))
        assertEquals(ContextDataSource.ALA_LIVE, result.source)
        assertEquals(candidates.associate { it.id to 0 }, result.countsBySpeciesId)
        assertTrue(result.failuresBySpeciesId.isEmpty())
    }

    @Test fun unresolvedTaxonIsNotRetriedWhileTimeoutIsRetried() = runBlocking {
        val source = object : AlaOccurrenceSource {
            override suspend fun countNearbyOccurrencesAsync(scientificName: String, location: GeoPoint, radiusKm: Int): AlaOccurrenceResponse {
                throw AlaRequestException("unavailable", null, 0, kind =
                    if (scientificName == candidates.first().scientificName) AlaFailureKind.UNRESOLVED_TAXON else AlaFailureKind.TIMEOUT)
            }
        }
        val result = lookup(source)
        assertEquals(ContextDataSource.ALA_UNAVAILABLE, result.source)
        assertTrue(result.countsBySpeciesId.isEmpty())
        assertEquals("UNRESOLVED_TAXON", result.failuresBySpeciesId[candidates[0].id])
        assertEquals("TIMEOUT", result.failuresBySpeciesId[candidates[1].id])
        assertEquals(1, result.attemptsBySpeciesId[candidates[0].id])
        assertEquals(2, result.attemptsBySpeciesId[candidates[1].id])
        assertTrue(result.warning.orEmpty().contains("1 taxon"))
    }

    @Test fun oversizedRetryAfterIsReportedWithoutEarlyRetry() = runBlocking {
        val result = lookup(object : AlaOccurrenceSource {
            override suspend fun countNearbyOccurrencesAsync(scientificName: String, location: GeoPoint, radiusKm: Int): AlaOccurrenceResponse =
                throw AlaRequestException("rate limited", 429, 0, retryAfterMillis = 60_000)
        })
        assertTrue(result.attemptsBySpeciesId.values.all { it == 1 })
        assertTrue(result.failuresBySpeciesId.values.all { it == "HTTP_429" })
        assertTrue(result.retryNotBefore != null)
    }

    @Test
    fun `privacy safeguard coarsens geographic coordinates before ALA network request`() = runBlocking {
        var interceptedLocation: GeoPoint? = null

        // 1. Create a fake source that traps the exact parameters sent to the network
        val trappingSource = object : AlaOccurrenceSource {
            override suspend fun countNearbyOccurrencesAsync(
                scientificName: String,
                location: GeoPoint,
                radiusKm: Int
            ): AlaOccurrenceResponse {
                interceptedLocation = location
                return AlaOccurrenceResponse(totalRecords = 1, httpStatus = 200, elapsedMillis = 10)
            }
        }

        val repository = ReliableAlaSpeciesContextRepository(trappingSource, retryDelayMillis = 0)

        // 2. Supply highly precise input simulating raw device GPS (7 decimal places)
        val preciseLocation = GeoPoint(latitude = -37.7968912, longitude = 144.9614567)

        repository.nearbyOccurrenceCounts(
            candidates = candidates.take(1),
            location = preciseLocation,
            radiusKm = 8,
            preferLiveData = true
        )

        // 3. Assert the location was successfully truncated to 3 decimal places (~111m)
        val actual = requireNotNull(interceptedLocation) { "The network adapter was never called" }
        assertEquals(-37.797, actual.latitude, 0.00001)
        assertEquals(144.961, actual.longitude, 0.00001)
    }

    private class FakeSource(
        private val successCounts: Map<String, Int>,
        private val failureStatuses: Map<String, Int> = emptyMap(),
    ) : AlaOccurrenceSource {
        val calls = mutableListOf<String>()

        override suspend fun countNearbyOccurrencesAsync(
            scientificName: String,
            location: GeoPoint,
            radiusKm: Int,
        ): AlaOccurrenceResponse {
            synchronized(calls) { calls += scientificName }
            failureStatuses[scientificName]?.let { status ->
                throw AlaRequestException(message = "HTTP $status", httpStatus = status, elapsedMillis = 10)
            }
            return AlaOccurrenceResponse(requireNotNull(successCounts[scientificName]), 200, 10)
        }
    }
}
