package au.edu.unimelb.floraguide.data.ala

import au.edu.unimelb.floraguide.domain.model.GeoPoint
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlaOccurrenceClientTest {
    @Test
    fun recordedAlaFixtureParsesTotalRecords() {
        val fixture = requireNotNull(
            javaClass.classLoader?.getResource(
                "ala/occurrence-search-eucalyptus-camaldulensis.json",
            ),
        ).readText()

        assertEquals(532, parseTotalRecords(fixture))
    }

    @Test
    fun missingOrInvalidTotalRecordsFailsClosed() {
        assertThrows(AlaResponseException::class.java) {
            parseTotalRecords("{\"status\":\"OK\"}")
        }
        assertThrows(AlaResponseException::class.java) {
            parseTotalRecords("{\"totalRecords\":\"532\"}")
        }
        assertThrows(AlaResponseException::class.java) {
            parseTotalRecords("{\"totalRecords\":1.5}")
        }
        assertThrows(AlaResponseException::class.java) {
            parseTotalRecords("not-json")
        }
    }

    @Test
    fun successfulRequestReturnsTelemetryAndUsesCountOnlyQuery() {
        val requestedUrls = mutableListOf<URL>()
        val logMessages = mutableListOf<String>()
        val clock = ArrayDeque(listOf(1_000_000_000L, 1_042_000_000L))
        val client = AlaOccurrenceClient(
            connectionFactory = { url ->
                requestedUrls += url
                FakeHttpURLConnection(
                    url = url,
                    status = 200,
                    body = if (url.path.contains("searchByClassification")) taxonMatch("Eucalyptus camaldulensis") else "{\"totalRecords\":532}",
                )
            },
            nanoTime = { clock.removeFirst() },
            logger = logMessages::add,
        )

        val result = client.countNearbyOccurrences(
            scientificName = "Eucalyptus camaldulensis",
            location = GeoPoint(-37.7963, 144.9614),
            radiusKm = 8,
        )

        assertEquals(532, result.totalRecords)
        assertEquals(200, result.httpStatus)
        assertEquals(42L, result.elapsedMillis)
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls.last().query.contains("pageSize=0"))
        assertTrue(requestedUrls.last().query.contains("facet=false"))
        assertTrue(requestedUrls.last().query.contains("taxonConceptID%3A%22"))
        assertTrue(logMessages.single().contains("totalRecords=532"))
    }

    @Test
    fun nonSuccessfulHttpStatusCarriesTelemetry() {
        val clock = ArrayDeque(listOf(2_000_000_000L, 2_125_000_000L))
        val client = AlaOccurrenceClient(
            connectionFactory = { url ->
                if (url.path.contains("searchByClassification")) FakeHttpURLConnection(url, 200, taxonMatch("Acacia melanoxylon"))
                else FakeHttpURLConnection(url, status = 503, body = "")
            },
            nanoTime = { clock.removeFirst() },
            logger = {},
        )

        val error = assertThrows(AlaRequestException::class.java) {
            client.countNearbyOccurrences(
                scientificName = "Acacia melanoxylon",
                location = GeoPoint(-37.7963, 144.9614),
                radiusKm = 8,
            )
        }

        assertEquals(503, error.httpStatus)
        assertEquals(125L, error.elapsedMillis)
    }

    @Test
    fun unresolvedOrInexactTaxaAreNotZeroCounts() {
        for (body in listOf("{\"success\":false}", taxonMatch("Different species"), taxonMatch("Acacia melanoxylon").replace("exactMatch", "fuzzyMatch"))) {
            assertThrows(UnresolvedAlaTaxonException::class.java) { parseTaxonId(body, "Acacia melanoxylon") }
        }
        assertThrows(AlaResponseException::class.java) { parseTaxonId("{}", "Acacia melanoxylon") }
    }

    @Test
    fun unresolvedMatchSkipsOccurrenceRequest() {
        val urls = mutableListOf<URL>()
        val client = AlaOccurrenceClient(connectionFactory = { url ->
            urls += url
            FakeHttpURLConnection(url, 200, "{\"success\":false}")
        }, logger = {})
        assertThrows(UnresolvedAlaTaxonException::class.java) {
            client.countNearbyOccurrences("Unknown plant", GeoPoint(0.0, 0.0), 8)
        }
        assertEquals(1, urls.size)
    }

    private fun taxonMatch(name: String) =
        """{"success":true,"scientificName":"$name","rank":"species","matchType":"exactMatch","taxonConceptID":"https://id.biodiversity.org.au/node/apni/2921040"}"""

    private class FakeHttpURLConnection(
        url: URL,
        private val status: Int,
        private val body: String,
    ) : HttpURLConnection(url) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = status

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
