package au.edu.unimelb.floraguide.data.classifier

import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImageClassification
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.Species
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

class PlantNetImageClassifier(
    private val apiKey: String,
    private val project: String = "all",
    private val maxResults: Int = 5,
) : ImageClassifier {

    override suspend fun classify(
        photoPath: String?
    ): ImageClassification = withContext(Dispatchers.IO) {

        val startTime = System.nanoTime()

        val path = photoPath
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "No photo was provided for PlantNet identification."
            )

        if (apiKey.isBlank()) {
            throw IllegalStateException(
                "PlantNet API key is not configured."
            )
        }

        val imageFile = File(path)

        if (!imageFile.exists() || !imageFile.isFile) {
            throw IllegalArgumentException(
                "Photo file does not exist: $path"
            )
        }

        val mimeType = when (
            imageFile.extension.lowercase(Locale.ROOT)
        ) {
            "png" -> "image/png"
            else -> "image/jpeg"
        }

        val boundary =
            "----FloraGuideBoundary${UUID.randomUUID()}"

        val encodedApiKey = URLEncoder.encode(
            apiKey,
            Charsets.UTF_8.name()
        )

        val endpoint = URL(
            "$BASE_URL/$project" +
                "?api-key=$encodedApiKey" +
                "&lang=en" +
                "&nb-results=$maxResults"
        )

        val connection =
            endpoint.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true

            connection.connectTimeout =
                CONNECT_TIMEOUT_MS

            connection.readTimeout =
                READ_TIMEOUT_MS

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )

            connection.setChunkedStreamingMode(0)

            connection.outputStream.use { output ->

                output.writeText(
                    "--$boundary\r\n"
                )

                output.writeText(
                    "Content-Disposition: form-data; " +
                        "name=\"images\"; " +
                        "filename=\"${imageFile.name}\"\r\n"
                )

                output.writeText(
                    "Content-Type: $mimeType\r\n\r\n"
                )

                imageFile.inputStream().use { input ->
                    input.copyTo(output)
                }

                output.writeText("\r\n")

                output.writeText(
                    "--$boundary--\r\n"
                )

                output.flush()
            }

            val statusCode =
                connection.responseCode

            val responseStream =
                if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val responseBody =
                responseStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use {
                        it.readText()
                    }
                    .orEmpty()

            if (statusCode !in 200..299) {
                throw IOException(
                    createErrorMessage(
                        statusCode,
                        responseBody
                    )
                )
            }

            val predictions =
                parsePredictions(responseBody)

            val elapsedMillis =
                (System.nanoTime() - startTime) /
                    1_000_000

            ImageClassification(
                predictions = predictions,
                source = ImageSource.PLANTNET_LIVE,
                elapsedMillis = elapsedMillis
            )

        } finally {
            connection.disconnect()
        }
    }

    private fun parsePredictions(
        responseBody: String
    ): List<ImagePrediction> {

        val root =
            JSONObject(responseBody)

        val results =
            root.optJSONArray("results")
                ?: throw IOException(
                    "PlantNet returned no results."
                )

        val predictions =
            mutableListOf<ImagePrediction>()

        val numberOfResults =
            minOf(
                results.length(),
                maxResults
            )

        for (index in 0 until numberOfResults) {

            val result =
                results.optJSONObject(index)
                    ?: continue

            val speciesJson =
                result.optJSONObject("species")
                    ?: continue

            val scientificName =
                speciesJson
                    .optString(
                        "scientificNameWithoutAuthor"
                    )
                    .trim()

            if (scientificName.isBlank()) {
                continue
            }

            val commonName =
                getFirstCommonName(
                    speciesJson,
                    scientificName
                )

            val score =
                result
                    .optDouble(
                        "score",
                        0.0
                    )
                    .coerceIn(
                        0.0,
                        1.0
                    )

            val species =
                Species(
                    id = createSpeciesId(
                        scientificName
                    ),

                    commonName =
                        commonName,

                    scientificName =
                        scientificName,

                    preferredMonths =
                        (1..12).toSet(),

                    habitatAffinity =
                        Habitat.entries
                            .associateWith {
                                1.0
                            },

                    demoNearbyCount = 0
                )

            predictions +=
                ImagePrediction(
                    species = species,
                    score = score,
                    rank =
                        predictions.size + 1
                )
        }

        if (predictions.isEmpty()) {
            throw IOException(
                "PlantNet could not identify any plant candidates."
            )
        }

        return predictions
    }

    private fun getFirstCommonName(
        speciesJson: JSONObject,
        fallback: String
    ): String {

        val commonNames =
            speciesJson
                .optJSONArray("commonNames")
                ?: return fallback

        for (
        index in 0
            until commonNames.length()
        ) {

            val name =
                commonNames
                    .optString(index)
                    .trim()

            if (name.isNotBlank()) {
                return name
            }
        }

        return fallback
    }

    private fun createSpeciesId(
        scientificName: String
    ): String {

        return scientificName
            .lowercase(Locale.ROOT)
            .replace(
                Regex("[^a-z0-9]+"),
                "-"
            )
            .trim('-')
    }

    private fun createErrorMessage(
        statusCode: Int,
        responseBody: String
    ): String {

        val apiMessage =
            runCatching {

                val json =
                    JSONObject(responseBody)

                json
                    .optString("message")
                    .ifBlank {
                        json.optString(
                            "error"
                        )
                    }

            }.getOrDefault("")

        return if (
            apiMessage.isNotBlank()
        ) {

            "PlantNet request failed " +
                "(HTTP $statusCode): " +
                apiMessage

        } else {

            "PlantNet request failed " +
                "(HTTP $statusCode)."
        }
    }

    private fun OutputStream.writeText(
        text: String
    ) {

        write(
            text.toByteArray(
                Charsets.UTF_8
            )
        )
    }

    companion object {

        private const val BASE_URL =
            "https://my-api.plantnet.org/v2/identify"

        private const val CONNECT_TIMEOUT_MS =
            10_000

        private const val READ_TIMEOUT_MS =
            30_000
    }
}
