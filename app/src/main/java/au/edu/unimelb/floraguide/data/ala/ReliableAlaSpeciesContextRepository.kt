package au.edu.unimelb.floraguide.data.ala

import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.Species
import au.edu.unimelb.floraguide.domain.repository.SpeciesContextRepository
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Production adapter selected by AppContainer. Failed live lookups never manufacture demo counts. */
class ReliableAlaSpeciesContextRepository(
    private val client: AlaOccurrenceSource,
    private val nanoTime: () -> Long = System::nanoTime,
    // One permit per candidate (the ViewModel checks at most 5): none waits behind another's timeout.
    maxConcurrentRequests: Int = 5,
    private val perCandidateTimeoutMillis: Long = 12_000L,
    private val maxAttempts: Int = 2,
    private val retryDelayMillis: Long = 500L,
    private val now: () -> Instant = Instant::now,
) : SpeciesContextRepository {
    private val semaphore = Semaphore(maxConcurrentRequests)
    init {
        require(maxConcurrentRequests > 0)
        require(perCandidateTimeoutMillis > 0 && maxAttempts in 1..3 && retryDelayMillis >= 0)
    }

    override suspend fun nearbyOccurrenceCounts(
        candidates: List<Species>,
        location: GeoPoint,
        radiusKm: Int,
        preferLiveData: Boolean,
    ): NearbyContext {
        val unique = candidates.distinctBy { it.id }
        if (!preferLiveData) return NearbyContext(
            countsBySpeciesId = unique.associate { it.id to it.demoNearbyCount.coerceAtLeast(0) },
            source = ContextDataSource.DEMO_FALLBACK,
            radiusKm = radiusKm,
            warning = "Guided demo only. These are not live ALA records.",
        )
        if (unique.isEmpty()) return NearbyContext(
            countsBySpeciesId = emptyMap(), source = ContextDataSource.NOT_REQUESTED, radiusKm = radiusKm,
        )
        require(location.hasValidCoordinates()) { "Invalid ALA query coordinates" }
        require(radiusKm in 1..100)
        val started = nanoTime()
        val queriedAt = now()
        val outcomes = supervisorScope {
            unique.map { species -> async { lookup(species, location, radiusKm) } }.awaitAll()
        }
        val successes = outcomes.mapNotNull { item -> item.count?.let { item.id to it } }.toMap()
        val source = when (successes.size) {
            0 -> ContextDataSource.ALA_UNAVAILABLE
            unique.size -> ContextDataSource.ALA_LIVE
            else -> ContextDataSource.ALA_PARTIAL
        }
        return NearbyContext(
            countsBySpeciesId = successes,
            source = source,
            radiusKm = radiusKm,
            lookupElapsedMillis = (nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
            successfulRequestCount = successes.size,
            requestCount = unique.size,
            httpStatusCodes = outcomes.flatMap { it.statuses }.toSet(),
            warning = when (source) {
                ContextDataSource.ALA_UNAVAILABLE ->
                    "ALA context unavailable. Pl@ntNet results are retained; no demo evidence is used."
                ContextDataSource.ALA_PARTIAL ->
                    "${successes.size}/${unique.size} ALA lookups succeeded. Missing counts are unknown; " +
                        "the image-only order is retained to avoid rewarding selective availability."
                else -> null
            },
            failuresBySpeciesId = outcomes.mapNotNull { it.failure?.let { reason -> it.id to reason } }.toMap(),
            attemptsBySpeciesId = outcomes.associate { it.id to it.attempts },
            queriedAt = queriedAt,
            retryNotBefore = outcomes.mapNotNull { it.retryNotBefore }.maxOrNull(),
        )
    }

    private suspend fun lookup(species: Species, location: GeoPoint, radiusKm: Int): Outcome =
        semaphore.withPermit {
            var attempts = 0
            val statuses = mutableSetOf<Int>()
            var lastFailure = "UNAVAILABLE"
            var retryAt: Instant? = null
            val result = withTimeoutOrNull(perCandidateTimeoutMillis) {
                val started = nanoTime()
                while (attempts < maxAttempts) {
                    attempts++
                    try {
                        val response = client.countNearbyOccurrencesAsync(species.scientificName, location, radiusKm)
                        require(response.totalRecords >= 0) { "Invalid count from ALA adapter" }
                        statuses += response.httpStatus
                        return@withTimeoutOrNull Outcome(
                            species.id, response.totalRecords, attempts, statuses.toSet(),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val request = error as? AlaRequestException
                        request?.httpStatus?.let { statuses += it }
                        lastFailure = when (error) {
                            is AlaRequestException -> if (error.kind == AlaFailureKind.HTTP) {
                                "HTTP_${error.httpStatus}"
                            } else error.kind.name
                            is UnknownHostException, is ConnectException -> "OFFLINE"
                            is SocketTimeoutException -> "TIMEOUT"
                            is IllegalArgumentException -> "INVALID_INPUT"
                            else -> "UNAVAILABLE"
                        }
                        val serverWait = request?.retryAfterMillis
                        retryAt = serverWait?.let { duration ->
                            runCatching { now().plusMillis(duration) }.getOrNull()
                        }
                        val retryable = request?.isRetryable == true || error is SocketTimeoutException
                        if (!retryable || attempts >= maxAttempts) break
                        val wait = maxOf(retryDelayMillis * attempts, serverWait ?: 0L)
                        val elapsed = (nanoTime() - started).coerceAtLeast(0L) / 1_000_000L
                        if (wait >= perCandidateTimeoutMillis - elapsed) break
                        delay(wait)
                    }
                }
                Outcome(species.id, null, attempts, statuses.toSet(), lastFailure, retryAt)
            }
            result ?: Outcome(species.id, null, attempts, statuses.toSet(), "TIMEOUT", retryAt)
        }

    private data class Outcome(
        val id: String,
        val count: Int?,
        val attempts: Int,
        val statuses: Set<Int>,
        val failure: String? = null,
        val retryNotBefore: Instant? = null,
    )
}
