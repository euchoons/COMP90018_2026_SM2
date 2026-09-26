package au.edu.unimelb.floraguide.data.ala

import android.util.Log
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

private const val DEFAULT_ALA_SEARCH_URL =
    "https://api.ala.org.au/occurrences/occurrences/search"
private const val MAX_RESPONSE_BYTES = 1_048_576

/** Count-only public queries. No API key, photo, account ID or write request is sent to ALA. */
class AlaOccurrenceClient(
    private val baseUrl: String = DEFAULT_ALA_SEARCH_URL,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val nanoTime: () -> Long = System::nanoTime,
    private val logger: (String) -> Unit = { Log.i("FloraGuide-ALA", it) },
    private val executor: ExecutorService = networkExecutor,
    private val connectTimeoutMillis: Int = 4_000,
    private val readTimeoutMillis: Int = 5_000,
    private val nameMatchUrl: String = "https://api.ala.org.au/namematching/api/searchByClassification",
) : AlaOccurrenceSource {
    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

    override suspend fun countNearbyOccurrencesAsync(
        scientificName: String,
        location: GeoPoint,
        radiusKm: Int,
    ): AlaOccurrenceResponse {
        require(location.hasValidCoordinates()) { "Invalid query coordinates" }
        require(radiusKm in 1..100) { "Radius must be between 1 and 100 km" }
        val name = scientificName.trim()
        require(name.isNotEmpty() && name.length <= 300 && name.none { it.isISOControl() }) {
            "Invalid scientific name"
        }
        val started = nanoTime()
        val taxonId = request(URL("$nameMatchUrl?scientificName=${URLEncoder.encode(name, "UTF-8")}")) { body, status, elapsed ->
            parseTaxonId(body) ?: throw AlaRequestException(
                "ALA did not resolve a valid species taxon", status, elapsed, kind = AlaFailureKind.UNRESOLVED_TAXON,
            )
        }
        // URL encoding alone does not escape Solr query syntax inside a quoted phrase.
        val escapedId = taxonId.replace("\\", "\\\\").replace("\"", "\\\"")
        val query = URLEncoder.encode("taxonConceptID:\"$escapedId\"", "UTF-8")
        val url = URL("$baseUrl?q=$query&lat=${location.latitude}&lon=${location.longitude}" +
            "&radius=$radiusKm&pageSize=0&facet=false")
        return request(url) { body, status, _ ->
            AlaOccurrenceResponse(parseTotalRecords(body), status, elapsed(started))
        }
    }

    /** Both endpoints share the same cancellable, bounded transport; repository owns retries. */
    private suspend fun <T> request(url: URL, parse: (String, Int, Long) -> T): T {
        return suspendCancellableCoroutine { continuation ->
            val activeConnection = AtomicReference<HttpURLConnection?>()
            val future = AtomicReference<Future<*>?>()
            continuation.invokeOnCancellation {
                future.get()?.cancel(true)
                // Close the socket as well as interrupting the worker: interrupt alone is not enough.
                runCatching { activeConnection.getAndSet(null)?.disconnect() }
            }
            val task = executor.submit {
                val started = nanoTime()
                var status: Int? = null
                var connection: HttpURLConnection? = null
                try {
                    if (!continuation.isActive) return@submit
                    connection = connectionFactory(url).apply {
                        requestMethod = "GET"
                        connectTimeout = connectTimeoutMillis
                        readTimeout = readTimeoutMillis
                        instanceFollowRedirects = false
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "FloraGuide-COMP90018/ALA-1.0")
                    }
                    activeConnection.set(connection)
                    if (!continuation.isActive) return@submit
                    status = connection.responseCode
                    if (status !in 200..299) {
                        throw AlaRequestException(
                            message = "ALA returned HTTP $status",
                            httpStatus = status,
                            elapsedMillis = elapsed(started),
                            retryAfterMillis = parseRetryAfterMillis(connection.getHeaderField("Retry-After")),
                        )
                    }
                    val bytes = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            if (!continuation.isActive) return@submit
                            val size = input.read(buffer)
                            if (size < 0) break
                            if (bytes.size() + size > MAX_RESPONSE_BYTES) {
                                throw AlaResponseException("ALA response was unexpectedly large")
                            }
                            bytes.write(buffer, 0, size)
                        }
                    }
                    val elapsedMillis = elapsed(started)
                    val result = parse(bytes.toString("UTF-8"), status, elapsedMillis)
                    // Do not log query URLs, coordinates, image URLs or response bodies.
                    runCatching { logger("status=$status elapsedMs=$elapsedMillis outcome=success") }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (!continuation.isActive) return@submit
                    val failure = if (error is AlaRequestException) error else AlaRequestException(
                        message = when (error) {
                            is SocketTimeoutException -> "ALA request timed out"
                            is UnknownHostException, is ConnectException -> "ALA network connection unavailable"
                            is AlaResponseException -> "ALA returned an invalid response"
                            else -> "ALA request failed"
                        },
                        httpStatus = status,
                        elapsedMillis = elapsed(started),
                        cause = error,
                        kind = when (error) {
                            is SocketTimeoutException -> AlaFailureKind.TIMEOUT
                            is UnknownHostException, is ConnectException -> AlaFailureKind.OFFLINE
                            is AlaResponseException -> AlaFailureKind.INVALID_RESPONSE
                            else -> AlaFailureKind.OTHER
                        },
                    )
                    runCatching { logger("status=${status ?: "unavailable"} " +
                        "elapsedMs=${failure.elapsedMillis} outcome=${failure.kind}") }
                    continuation.resumeWithException(failure)
                } finally {
                    activeConnection.compareAndSet(connection, null)
                    runCatching { connection?.disconnect() }
                }
            }
            future.set(task)
            if (!continuation.isActive) task.cancel(true)
        }
    }

    private fun elapsed(started: Long): Long = (nanoTime() - started).coerceAtLeast(0L) / 1_000_000L

    private companion object {
        // Shared daemon workers avoid a new thread pool for every photo. Keep at least as many as the
        // repository's permits, or permitted requests queue here while their timeout is running.
        val networkExecutor: ExecutorService = Executors.newFixedThreadPool(5) { task ->
            Thread(task, "FloraGuide-ALA-http").apply { isDaemon = true }
        }
    }
}


/** Null means unresolved; malformed success payloads are failures, never ecological evidence. */
internal fun parseTaxonId(body: String): String? = try {
    val root = JSONObject(body)
    val success = root.opt("success")
    if (success !is Boolean) throw AlaResponseException("ALA name matching omitted boolean success")
    if (!success) null else {
        val rank = root.optString("rank", "")
        val match = root.optString("matchType", "")

        // Accept exact matches, synonyms, and canonical (alternate spelling/authority) matches.
        val isAcceptedMatch = match.equals("exactMatch", ignoreCase = true) ||
            match.equals("synonym", ignoreCase = true) ||
            match.equals("canonicalMatch", ignoreCase = true)

        if (!isAcceptedMatch || !rank.equals("species", ignoreCase = true)) {
            null
        } else {
            // Synonyms provide 'acceptedConceptID' to link back to the authoritative taxon.
            // Fall back to 'taxonConceptID' for exact or canonical matches.
            val id = root.optString("acceptedConceptID").takeIf { it.isNotBlank() }
                ?: root.optString("taxonConceptID").takeIf { it.isNotBlank() }

            if (id == null || id.length > 2_048 || id.any { it.isISOControl() }) {
                throw AlaResponseException("ALA name matching returned an invalid or missing taxon ID")
            }
            id
        }
    }
} catch (error: AlaResponseException) {
    throw error
} catch (error: Exception) {
    throw AlaResponseException("ALA name matching response was not valid JSON", error)
}

internal fun parseTotalRecords(body: String): Int = try {
    val root = JSONObject(body)
    val raw = root.opt("totalRecords")
    if (raw !is Number) throw AlaResponseException("ALA totalRecords was not numeric")
    val count = raw.toLong()
    val numeric = raw.toDouble()
    if (!numeric.isFinite() || numeric != count.toDouble() || count !in 0L..Int.MAX_VALUE.toLong()) {
        throw AlaResponseException("ALA totalRecords was not a supported non-negative integer")
    }
    count.toInt()
} catch (error: AlaResponseException) {
    throw error
} catch (error: Exception) {
    throw AlaResponseException("ALA response was not valid JSON", error)
}

/** Supports both RFC delay-seconds and an HTTP-date. Never retry before this duration. */
internal fun parseRetryAfterMillis(header: String?, now: Instant = Instant.now()): Long? {
    val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { seconds ->
        if (seconds < 0) return null
        return seconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
    }
    return runCatching {
        val target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        java.time.Duration.between(now, target).toMillis().coerceAtLeast(0L)
    }.getOrNull()
}


// Shared contract types remain here for source compatibility with existing callers.
/** One network attempt, before repository-level retry. */
data class AlaOccurrenceResponse(
    val totalRecords: Int,
    val httpStatus: Int,
    val elapsedMillis: Long,
)

interface AlaOccurrenceSource {
    /** Cancellable: cancelling the caller closes the connection. */
    suspend fun countNearbyOccurrencesAsync(
        scientificName: String,
        location: GeoPoint,
        radiusKm: Int,
    ): AlaOccurrenceResponse
}

enum class AlaFailureKind {
    OFFLINE, TIMEOUT, HTTP, INVALID_RESPONSE, INVALID_INPUT, UNRESOLVED_TAXON, OTHER,
}

class AlaRequestException(
    message: String,
    val httpStatus: Int?,
    val elapsedMillis: Long,
    cause: Throwable? = null,
    val kind: AlaFailureKind = AlaFailureKind.HTTP,
    val retryAfterMillis: Long? = null,
) : Exception(message, cause) {
    val isRetryable: Boolean
        get() = kind == AlaFailureKind.TIMEOUT ||
            (kind == AlaFailureKind.HTTP &&
                (httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599))
}

class AlaResponseException(message: String, cause: Throwable? = null) : Exception(message, cause)
