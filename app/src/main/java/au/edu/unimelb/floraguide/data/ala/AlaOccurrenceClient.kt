package au.edu.unimelb.floraguide.data.ala

import android.util.Log
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

private const val DEFAULT_ALA_SEARCH_URL =
    "https://api.ala.org.au/occurrences/occurrences/search"
private const val LOG_TAG = "FloraGuide-ALA"

data class AlaOccurrenceResponse(
    val totalRecords: Int,
    val httpStatus: Int,
    val elapsedMillis: Long,
)

interface AlaOccurrenceSource {
    fun countNearbyOccurrences(
        scientificName: String,
        location: GeoPoint,
        radiusKm: Int,
    ): AlaOccurrenceResponse
}

/** Minimal, dependency-free ALA read client. Network calls must be made from an IO dispatcher. */
class AlaOccurrenceClient(
    private val baseUrl: String = DEFAULT_ALA_SEARCH_URL,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    private val nanoTime: () -> Long = System::nanoTime,
    private val logger: (String) -> Unit = { message -> Log.i(LOG_TAG, message) },
) : AlaOccurrenceSource {
    override fun countNearbyOccurrences(
        scientificName: String,
        location: GeoPoint,
        radiusKm: Int,
    ): AlaOccurrenceResponse {
        val query = "scientificName:\"$scientificName\""
        val url = URL(
            buildString {
                append(baseUrl)
                append("?q=")
                append(encode(query))
                append("&lat=")
                append(location.latitude)
                append("&lon=")
                append(location.longitude)
                append("&radius=")
                append(radiusKm)
                // Only the aggregate count is used, so avoid downloading an occurrence record.
                append("&pageSize=0&facet=false")
            },
        )

        val startedAt = nanoTime()
        var status: Int? = null
        var connection: HttpURLConnection? = null

        try {
            connection = connectionFactory(url).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "FloraGuide-COMP90018-prototype/0.1")
            }

            status = connection.responseCode
            if (status !in 200..299) {
                val elapsedMillis = elapsedMillisSince(startedAt)
                logger(
                    "endpoint=$baseUrl status=$status elapsedMs=$elapsedMillis " +
                        "scientificName=$scientificName outcome=http_error",
                )
                throw AlaRequestException(
                    message = "ALA returned HTTP $status",
                    httpStatus = status,
                    elapsedMillis = elapsedMillis,
                )
            }

            val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            val totalRecords = parseTotalRecords(body)
            val elapsedMillis = elapsedMillisSince(startedAt)
            logger(
                "endpoint=$baseUrl status=$status elapsedMs=$elapsedMillis " +
                    "scientificName=$scientificName totalRecords=$totalRecords outcome=success",
            )
            return AlaOccurrenceResponse(
                totalRecords = totalRecords,
                httpStatus = status,
                elapsedMillis = elapsedMillis,
            )
        } catch (error: AlaRequestException) {
            throw error
        } catch (error: Exception) {
            val elapsedMillis = elapsedMillisSince(startedAt)
            logger(
                "endpoint=$baseUrl status=${status ?: "unavailable"} elapsedMs=$elapsedMillis " +
                    "scientificName=$scientificName outcome=failed error=${error.javaClass.simpleName}",
            )
            throw AlaRequestException(
                message = "ALA request failed: ${error.message ?: error.javaClass.simpleName}",
                httpStatus = status,
                elapsedMillis = elapsedMillis,
                cause = error,
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun elapsedMillisSince(startedAt: Long): Long =
        ((nanoTime() - startedAt).coerceAtLeast(0L) / 1_000_000L)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

internal fun parseTotalRecords(body: String): Int = try {
    val root = JSONObject(body)
    if (!root.has("totalRecords") || root.isNull("totalRecords")) {
        throw AlaResponseException("ALA response did not contain totalRecords")
    }

    val rawValue = root.get("totalRecords")
    if (rawValue !is Number) {
        throw AlaResponseException("ALA totalRecords was not numeric")
    }

    val count = rawValue.toLong()
    val exactNumericValue = rawValue.toDouble()
    if (!exactNumericValue.isFinite() || exactNumericValue != count.toDouble()) {
        throw AlaResponseException("ALA totalRecords was not an integer")
    }
    if (count !in 0L..Int.MAX_VALUE.toLong()) {
        throw AlaResponseException("ALA totalRecords was outside the supported range")
    }
    count.toInt()
} catch (error: AlaResponseException) {
    throw error
} catch (error: Exception) {
    throw AlaResponseException("ALA response was not valid JSON", error)
}

class AlaRequestException(
    message: String,
    val httpStatus: Int?,
    val elapsedMillis: Long,
    cause: Throwable? = null,
) : Exception(message, cause)

class AlaResponseException(message: String, cause: Throwable? = null) : Exception(message, cause)
