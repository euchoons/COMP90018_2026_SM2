package au.edu.unimelb.floraguide.data.ala

import au.edu.unimelb.floraguide.data.catalog.DemoSpeciesCatalog
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlaSpeciesContextRepositoryTest {
    private val candidates = DemoSpeciesCatalog.all.take(2)
    private val location = GeoPoint(-37.7963, 144.9614)

    @Test
    fun guidedFallbackSkipsNetwork() = runBlocking {
        val source = FakeSource(successCounts = emptyMap())
        val repository = AlaSpeciesContextRepository(source)

        val result = repository.nearbyOccurrenceCounts(
            candidates = candidates,
            location = location,
            radiusKm = 8,
            preferLiveData = false,
        )

        assertEquals(ContextDataSource.DEMO_FALLBACK, result.source)
        assertEquals(0, result.requestCount)
        assertEquals(0, source.callCount)
        assertEquals(
            candidates.associate { it.id to it.demoNearbyCount },
            result.countsBySpeciesId,
        )
    }

    @Test
    fun completeLiveLookupExposesBatchTelemetry() = runBlocking {
        val source = FakeSource(
            successCounts = mapOf(
                candidates[0].scientificName to 7,
                candidates[1].scientificName to 532,
            ),
        )
        val clock = ArrayDeque(listOf(4_000_000_000L, 4_250_000_000L))
        val repository = AlaSpeciesContextRepository(source, nanoTime = { clock.removeFirst() })

        val result = repository.nearbyOccurrenceCounts(
            candidates = candidates,
            location = location,
            radiusKm = 8,
            preferLiveData = true,
        )

        assertEquals(ContextDataSource.ALA_LIVE, result.source)
        assertEquals(2, result.successfulRequestCount)
        assertEquals(2, result.requestCount)
        assertEquals(250L, result.lookupElapsedMillis)
        assertEquals(setOf(200), result.httpStatusCodes)
        assertEquals(532, result.countsBySpeciesId.getValue(candidates[1].id))
    }

    @Test
    fun partialLookupMergesDemoFallbackAndRemainsRetryable() = runBlocking {
        val source = FakeSource(
            successCounts = mapOf(candidates[0].scientificName to 11),
            failureStatuses = mapOf(candidates[1].scientificName to 503),
        )
        val clock = ArrayDeque(listOf(5_000_000_000L, 5_090_000_000L))
        val repository = AlaSpeciesContextRepository(source, nanoTime = { clock.removeFirst() })

        val result = repository.nearbyOccurrenceCounts(
            candidates = candidates,
            location = location,
            radiusKm = 8,
            preferLiveData = true,
        )

        assertEquals(ContextDataSource.ALA_PARTIAL, result.source)
        assertEquals(1, result.successfulRequestCount)
        assertEquals(2, result.requestCount)
        assertEquals(setOf(200, 503), result.httpStatusCodes)
        assertEquals(11, result.countsBySpeciesId.getValue(candidates[0].id))
        assertEquals(candidates[1].demoNearbyCount, result.countsBySpeciesId.getValue(candidates[1].id))
        assertTrue(result.warning.orEmpty().contains("1/2"))
    }

    @Test
    fun cancellationIsNotConvertedToFallback() {
        val source = object : AlaOccurrenceSource {
            override fun countNearbyOccurrences(
                scientificName: String,
                location: GeoPoint,
                radiusKm: Int,
            ): AlaOccurrenceResponse = throw CancellationException("superseded analysis")
        }
        val repository = AlaSpeciesContextRepository(source)

        val failure = runCatching {
            runBlocking {
                repository.nearbyOccurrenceCounts(
                    candidates = candidates,
                    location = location,
                    radiusKm = 8,
                    preferLiveData = true,
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private class FakeSource(
        private val successCounts: Map<String, Int>,
        private val failureStatuses: Map<String, Int> = emptyMap(),
    ) : AlaOccurrenceSource {
        var callCount: Int = 0
            private set

        override fun countNearbyOccurrences(
            scientificName: String,
            location: GeoPoint,
            radiusKm: Int,
        ): AlaOccurrenceResponse {
            callCount += 1
            failureStatuses[scientificName]?.let { status ->
                throw AlaRequestException(
                    message = "HTTP $status",
                    httpStatus = status,
                    elapsedMillis = 10,
                )
            }
            return AlaOccurrenceResponse(
                totalRecords = requireNotNull(successCounts[scientificName]),
                httpStatus = 200,
                elapsedMillis = 10,
            )
        }
    }
}
