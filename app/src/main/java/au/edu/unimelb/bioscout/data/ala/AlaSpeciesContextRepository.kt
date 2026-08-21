package au.edu.unimelb.bioscout.data.ala

import au.edu.unimelb.bioscout.domain.model.ContextDataSource
import au.edu.unimelb.bioscout.domain.model.GeoPoint
import au.edu.unimelb.bioscout.domain.model.NearbyContext
import au.edu.unimelb.bioscout.domain.model.Species
import au.edu.unimelb.bioscout.domain.repository.SpeciesContextRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class AlaSpeciesContextRepository(
    private val client: AlaOccurrenceSource,
    private val nanoTime: () -> Long = System::nanoTime,
) : SpeciesContextRepository {
    override suspend fun nearbyOccurrenceCounts(
        candidates: List<Species>,
        location: GeoPoint,
        radiusKm: Int,
        preferLiveData: Boolean,
    ): NearbyContext {
        if (!preferLiveData) return demoFallback(candidates, radiusKm)

        val startedAt = nanoTime()
        val outcomes = coroutineScope {
            candidates.map { species ->
                async(Dispatchers.IO) {
                    try {
                        LookupOutcome(
                            speciesId = species.id,
                            response = client.countNearbyOccurrences(
                                scientificName = species.scientificName,
                                location = location,
                                radiusKm = radiusKm,
                            ),
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        LookupOutcome(
                            speciesId = species.id,
                            errorStatus = (error as? AlaRequestException)?.httpStatus,
                        )
                    }
                }
            }.awaitAll()
        }

        val elapsedMillis = elapsedMillisSince(startedAt)
        val liveResults = outcomes.mapNotNull { outcome ->
            outcome.response?.let { response -> outcome.speciesId to response.totalRecords }
        }.toMap()
        val httpStatusCodes = outcomes.mapNotNull { outcome ->
            outcome.response?.httpStatus ?: outcome.errorStatus
        }.toSet()

        if (liveResults.isEmpty()) {
            return demoFallback(
                candidates = candidates,
                radiusKm = radiusKm,
                warning = "ALA could not be reached. Showing deterministic demo records so the flow remains testable.",
                lookupElapsedMillis = elapsedMillis,
                requestCount = candidates.size,
                httpStatusCodes = httpStatusCodes,
            )
        }

        val merged = candidates.associate { species ->
            species.id to (liveResults[species.id] ?: species.demoNearbyCount)
        }
        val isComplete = liveResults.size == candidates.size

        return NearbyContext(
            countsBySpeciesId = merged,
            source = if (isComplete) ContextDataSource.ALA_LIVE else ContextDataSource.ALA_PARTIAL,
            radiusKm = radiusKm,
            lookupElapsedMillis = elapsedMillis,
            successfulRequestCount = liveResults.size,
            requestCount = candidates.size,
            httpStatusCodes = httpStatusCodes,
            warning = if (isComplete) {
                null
            } else {
                "${liveResults.size}/${candidates.size} ALA requests succeeded; missing counts use demo fallback values."
            },
        )
    }

    private suspend fun demoFallback(
        candidates: List<Species>,
        radiusKm: Int,
        warning: String? = null,
        lookupElapsedMillis: Long? = null,
        requestCount: Int = 0,
        httpStatusCodes: Set<Int> = emptySet(),
    ): NearbyContext = withContext(Dispatchers.Default) {
        NearbyContext(
            countsBySpeciesId = candidates.associate { it.id to it.demoNearbyCount },
            source = ContextDataSource.DEMO_FALLBACK,
            radiusKm = radiusKm,
            lookupElapsedMillis = lookupElapsedMillis,
            successfulRequestCount = 0,
            requestCount = requestCount,
            httpStatusCodes = httpStatusCodes,
            warning = warning,
        )
    }

    private fun elapsedMillisSince(startedAt: Long): Long =
        ((nanoTime() - startedAt).coerceAtLeast(0L) / 1_000_000L)

    private data class LookupOutcome(
        val speciesId: String,
        val response: AlaOccurrenceResponse? = null,
        val errorStatus: Int? = null,
    )
}
