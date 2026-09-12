package au.edu.unimelb.floraguide.data.plantnet

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONObject

private const val DEFAULT_PLANTNET_IDENTIFY_URL = "https://my-api.plantnet.org/v2/identify/all"
private const val LOG_TAG = "FloraGuide-PlantNet"

/**
 * One Pl@ntNet candidate. Scores are per-species confidences and deliberately do not sum to 1;
 * normalisation belongs to the ranking use case, not to this transport layer.
 */
data class PlantNetResult(
    val scientificName: String,
    val commonName: String?,
    val score: Double,
)

data class PlantNetIdentification(
    val results: List<PlantNetResult>,
    val httpStatus: Int,
    val elapsedMillis: Long,
    val remainingRequests: Int?,
)

interface PlantNetSource {
    fun identify(photoFile: File): PlantNetIdentification
}

/**
 * Minimal, dependency-free Pl@ntNet v2 client. Network calls must be made from an IO dispatcher.
 * The API key is a query parameter, so it is never written to logs or exception messages.
 */
class PlantNetClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_PLANTNET_IDENTIFY_URL,
    // Bounds the downstream ALA fan-out: one occurrence request is issued per candidate.
    private val maxResults: Int = 8,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    private val nanoTime: () -> Long = System::nanoTime,
    private val boundaryFactory: () -> String = { "FloraGuide-${UUID.randomUUID()}" },
    private val logger: (String) -> Unit = { message -> Log.i(LOG_TAG, message) },
) : PlantNetSource {
    override fun identify(photoFile: File): PlantNetIdentification {
        if (apiKey.isBlank()) {
            throw PlantNetRequestException(
                message = "No Pl@ntNet API key is configured. Add plantnet.api.key to local.properties.",
                httpStatus = null,
                elapsedMillis = 0L,
            )
        }
        if (!photoFile.isFile) {
            throw PlantNetRequestException(
                message = "The captured photo could not be read.",
                httpStatus = null,
                elapsedMillis = 0L,
            )
        }

        val url = URL("$baseUrl?api-key=${encode(apiKey)}&nb-results=$maxResults&lang=en")
        val boundary = boundaryFactory()
        val prefix = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"organs\"\r\n\r\n" +
                "auto\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"images\"; filename=\"${photoFile.name}\"\r\n" +
                "Content-Type: image/jpeg\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val startedAt = nanoTime()
        var status: Int? = null
        var connection: HttpURLConnection? = null

        try {
            connection = connectionFactory(url).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                // A ~1 MB capture measured ~3.4 s end to end; uploads dominate on mobile data.
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "FloraGuide-COMP90018-prototype/0.1")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                // Streams the photo instead of buffering the whole multipart body in memory.
                setFixedLengthStreamingMode(prefix.size + photoFile.length() + suffix.size)
            }

            connection.outputStream.use { output ->
                output.write(prefix)
                photoFile.inputStream().use { photo -> photo.copyTo(output) }
                output.write(suffix)
            }

            status = connection.responseCode
            if (status !in 200..299) {
                val elapsedMillis = elapsedMillisSince(startedAt)
                logger("endpoint=$baseUrl status=$status elapsedMs=$elapsedMillis outcome=http_error")
                throw PlantNetRequestException(
                    message = when (status) {
                        404 -> "Pl@ntNet could not match this photo. Try a closer shot of leaves, flowers or bark."
                        429 -> "Pl@ntNet daily request quota reached. Use the guided demo until it resets."
                        401, 403 -> "Pl@ntNet rejected the API key."
                        else -> "Pl@ntNet returned HTTP $status"
                    },
                    httpStatus = status,
                    elapsedMillis = elapsedMillis,
                )
            }

            val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
            val results = parsePlantNetResults(body, maxResults)
            val remainingRequests = parseRemainingRequests(body)
            val elapsedMillis = elapsedMillisSince(startedAt)
            logger(
                "endpoint=$baseUrl status=$status elapsedMs=$elapsedMillis " +
                    "candidates=${results.size} topMatch=${results.firstOrNull()?.scientificName} " +
                    "remainingRequests=${remainingRequests ?: "unknown"} outcome=success",
            )
            return PlantNetIdentification(
                results = results,
                httpStatus = status,
                elapsedMillis = elapsedMillis,
                remainingRequests = remainingRequests,
            )
        } catch (error: PlantNetRequestException) {
            throw error
        } catch (error: PlantNetResponseException) {
            throw error
        } catch (error: Exception) {
            val elapsedMillis = elapsedMillisSince(startedAt)
            logger(
                "endpoint=$baseUrl status=${status ?: "unavailable"} elapsedMs=$elapsedMillis " +
                    "outcome=failed error=${error.javaClass.simpleName}",
            )
            throw PlantNetRequestException(
                message = "Pl@ntNet request failed: ${error.message ?: error.javaClass.simpleName}",
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

/** Fails closed: a malformed candidate is an error, never a silently shortened candidate list. */
internal fun parsePlantNetResults(body: String, maxResults: Int): List<PlantNetResult> {
    val root = try {
        JSONObject(body)
    } catch (error: Exception) {
        throw PlantNetResponseException("Pl@ntNet response was not valid JSON", error)
    }

    val entries = root.optJSONArray("results")
        ?: throw PlantNetResponseException("Pl@ntNet response did not contain results")

    val results = (0 until minOf(entries.length(), maxResults)).map { index ->
        val entry = entries.optJSONObject(index)
            ?: throw PlantNetResponseException("Pl@ntNet result $index was not an object")

        val score = entry.opt("score") as? Number
            ?: throw PlantNetResponseException("Pl@ntNet result $index had no numeric score")
        val scoreValue = score.toDouble()
        if (!scoreValue.isFinite() || scoreValue < 0.0) {
            throw PlantNetResponseException("Pl@ntNet result $index had an out-of-range score")
        }

        val species = entry.optJSONObject("species")
            ?: throw PlantNetResponseException("Pl@ntNet result $index had no species object")
        val scientificName = species.optString("scientificNameWithoutAuthor").trim()
        if (scientificName.isEmpty()) {
            throw PlantNetResponseException("Pl@ntNet result $index had no scientific name")
        }

        val commonNames = species.optJSONArray("commonNames")
        val commonName = (0 until (commonNames?.length() ?: 0))
            .map { commonNames!!.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() }

        PlantNetResult(
            scientificName = scientificName,
            commonName = commonName,
            score = scoreValue,
        )
    }

    if (results.isEmpty()) {
        throw PlantNetResponseException("Pl@ntNet returned no candidates for this photo")
    }
    return results
}

/** Free keys allow 500 identifications a day, so the remaining budget is worth logging. */
internal fun parseRemainingRequests(body: String): Int? = try {
    (JSONObject(body).opt("remainingIdentificationRequests") as? Number)?.toInt()
} catch (error: Exception) {
    null
}

class PlantNetRequestException(
    message: String,
    val httpStatus: Int?,
    val elapsedMillis: Long,
    cause: Throwable? = null,
) : Exception(message, cause)

class PlantNetResponseException(message: String, cause: Throwable? = null) : Exception(message, cause)
