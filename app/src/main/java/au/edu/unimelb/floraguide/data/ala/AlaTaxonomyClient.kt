package au.edu.unimelb.floraguide.data.ala

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val ALA_TAXONOMY_MATCH_URL = "https://api.ala.org.au/species/match"
private const val LOG_TAG = "FloraGuide-Taxonomy"

data class AlaTaxonomyMatch(
    val queryName: String,
    val acceptedGuid: String?,
    val acceptedScientificName: String,
    val matchedName: String,
    val matchType: String,
    val isSynonym: Boolean,
    val taxonRank: String?
)

interface AlaTaxonomySource {
    suspend fun resolveTaxonomy(scientificName: String): AlaTaxonomyMatch
}

class AlaTaxonomyClient(
    private val baseUrl: String = ALA_TAXONOMY_MATCH_URL,
    private val connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection }
) : AlaTaxonomySource {

    override suspend fun resolveTaxonomy(scientificName: String): AlaTaxonomyMatch = withContext(Dispatchers.IO) {
        val encodedName = URLEncoder.encode(scientificName, Charsets.UTF_8.name())
        val url = URL("$baseUrl?q=$encodedName")
        var connection: HttpURLConnection? = null

        try {
            connection = connectionFactory(url).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "FloraGuide-COMP90018/1.0")
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(LOG_TAG, "Taxonomy lookup HTTP $status for $scientificName. Falling back to query string.")
                return@withContext fallbackMatch(scientificName)
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseTaxonomyResponse(scientificName, body)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Taxonomy resolution error for $scientificName: ${e.message}")
            fallbackMatch(scientificName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseTaxonomyResponse(query: String, body: String): AlaTaxonomyMatch {
        val json = JSONObject(body)
        val success = json.optBoolean("success", true)
        val matchType = json.optString("matchType", "NONE")

        if (!success || matchType == "NONE") {
            return fallbackMatch(query)
        }

        val synonym = json.optBoolean("synonym", false)
        val acceptedGuid = if (synonym) {
            json.optString("acceptedConceptID", json.optString("taxonConceptID", null))
        } else {
            json.optString("taxonConceptID", null)
        }

        val acceptedName = if (synonym) {
            json.optString("acceptedScientificName", json.optString("scientificName", query))
        } else {
            json.optString("scientificName", query)
        }

        return AlaTaxonomyMatch(
            queryName = query,
            acceptedGuid = acceptedGuid,
            acceptedScientificName = acceptedName,
            matchedName = json.optString("matchedName", query),
            matchType = matchType,
            isSynonym = synonym,
            taxonRank = json.optString("rank", null)
        )
    }

    private fun fallbackMatch(query: String) = AlaTaxonomyMatch(
        queryName = query,
        acceptedGuid = null,
        acceptedScientificName = query,
        matchedName = query,
        matchType = "FALLBACK",
        isSynonym = false,
        taxonRank = null
    )
}
