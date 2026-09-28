package au.edu.unimelb.floraguide.data.ala

import au.edu.unimelb.floraguide.domain.model.GeoPoint
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val clock = AtomicLong(1_000_000_000L)
        val client = AlaOccurrenceClient(
            connectionFactory = { url ->
                requestedUrls += url
                FakeHttpURLConnection(
                    url = url,
                    status = 200,
                    body = if (url.isNameMatch()) taxonMatch() else "{\"totalRecords\":532}",
                )
            },
            nanoTime = { clock.getAndAdd(1_000_000L) },
            logger = logMessages::add,
        )

        val result = runBlocking {
            client.countNearbyOccurrencesAsync(
                scientificName = "Eucalyptus camaldulensis",
                location = GeoPoint(-37.7963, 144.9614),
                radiusKm = 8,
            )
        }

        assertEquals(532, result.totalRecords)
        assertEquals(200, result.httpStatus)
        assertTrue(result.elapsedMillis > 0)
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls.first().isNameMatch())
        assertTrue(requestedUrls.last().query.contains("pageSize=0"))
        assertTrue(requestedUrls.last().query.contains("facet=false"))
        assertTrue(URLDecoder.decode(requestedUrls.last().query, "UTF-8").contains("taxonConceptID:\"taxon-123\""))
        assertEquals(2, logMessages.size)
        assertTrue(logMessages.all { it.contains("outcome=success") && !it.contains("taxon-123") && !it.contains("Eucalyptus") })
    }

    @Test
    fun nonSuccessfulHttpStatusCarriesTelemetry() {
        val clock = ArrayDeque(listOf(1_000_000_000L, 2_000_000_000L, 2_125_000_000L))
        val client = AlaOccurrenceClient(
            connectionFactory = { url -> FakeHttpURLConnection(url, status = 503, body = "") },
            nanoTime = { clock.removeFirst() },
            logger = {},
        )

        val error = assertThrows(AlaRequestException::class.java) {
            runBlocking {
                client.countNearbyOccurrencesAsync(
                    scientificName = "Acacia melanoxylon",
                    location = GeoPoint(-37.7963, 144.9614),
                    radiusKm = 8,
                )
            }
        }

        assertEquals(503, error.httpStatus)
        assertEquals(125L, error.elapsedMillis)
    }

    @Test fun exactMatchesOnlyAndMalformedResponsesStayDistinct() {
        assertEquals("taxon-123", parseTaxonId(taxonMatch()))
        for (body in listOf(
            "{\"success\":false}", taxonMatch().replace("exactMatch", "fuzzyMatch"),
            taxonMatch().replace("\"species\"", "\"genus\""), "{\"success\":true}",
        )) assertNull(parseTaxonId(body))
        for (body in listOf("not-json", "{}", "{\"success\":\"true\"}",
            taxonMatch().replace("taxon-123", ""))) {
            assertThrows(AlaResponseException::class.java) { parseTaxonId(body) }
        }
    }

    @Test fun unresolvedTaxonSkipsOccurrencesAndIsNotRetryable() {
        val urls = mutableListOf<URL>()
        val client = AlaOccurrenceClient(connectionFactory = { url ->
            urls += url
            FakeHttpURLConnection(url, 200, "{\"success\":false}")
        }, logger = {})
        val error = assertThrows(AlaRequestException::class.java) {
            runBlocking { client.countNearbyOccurrencesAsync(NAME, LOCATION, 8) }
        }
        assertEquals(AlaFailureKind.UNRESOLVED_TAXON, error.kind)
        assertFalse(error.isRetryable)
        assertEquals(1, urls.size)
    }

    @Test fun resolvedZeroIsSuccessfulEvidence() = runBlocking {
        val client = AlaOccurrenceClient(connectionFactory = { url ->
            FakeHttpURLConnection(url, 200, if (url.isNameMatch()) taxonMatch() else "{\"totalRecords\":0}")
        }, logger = {})
        assertEquals(0, client.countNearbyOccurrencesAsync(NAME, LOCATION, 8).totalRecords)
    }

    @Test fun bothEndpointsRetainHttpAndResponseSizeGuards() {
        for (nameEndpoint in listOf(true, false)) {
            for (oversized in listOf(true, false)) {
                val connections = mutableListOf<FakeHttpURLConnection>()
                val client = AlaOccurrenceClient(connectionFactory = { url ->
                    val target = url.isNameMatch() == nameEndpoint
                    FakeHttpURLConnection(url,
                        if (target && !oversized) 429 else 200,
                        if (target && oversized) "x".repeat(1_048_577) else taxonMatch(),
                        retryAfter = "7",
                    ).also { connections += it }
                }, logger = {})
                val error = assertThrows(AlaRequestException::class.java) {
                    runBlocking { client.countNearbyOccurrencesAsync(NAME, LOCATION, 8) }
                }
                assertEquals(if (oversized) AlaFailureKind.INVALID_RESPONSE else AlaFailureKind.HTTP, error.kind)
                if (!oversized) {
                    assertEquals(7_000L, error.retryAfterMillis)
                    assertTrue(error.isRetryable)
                } else assertFalse(error.isRetryable)
                assertEquals(if (nameEndpoint) 1 else 2, connections.size)
                assertTrue(connections.all { it.awaitDisconnect() && !it.instanceFollowRedirects && it.connectTimeout == 4_000 && it.readTimeout == 5_000 })
            }
        }
    }

    @Test fun cancellationDisconnectsEitherEndpointAndDoesNotStartNextRequest() = runBlocking {
        for (nameEndpoint in listOf(true, false)) {
            val reading = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val urls = java.util.Collections.synchronizedList(mutableListOf<URL>())
            val client = AlaOccurrenceClient(connectionFactory = { url ->
                urls += url
                if (url.isNameMatch() != nameEndpoint) FakeHttpURLConnection(url, 200, taxonMatch())
                else object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun disconnect() { disconnected.countDown() }
                    override fun getInputStream(): InputStream = object : InputStream() {
                        override fun read(): Int {
                            reading.countDown()
                            disconnected.await(5, TimeUnit.SECONDS)
                            throw java.io.IOException("closed")
                        }
                    }
                }
            }, logger = {})
            val job = launch(Dispatchers.Default) { client.countNearbyOccurrencesAsync(NAME, LOCATION, 8) }
            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS))
                job.cancelAndJoin()
                assertTrue(disconnected.await(5, TimeUnit.SECONDS))
                assertEquals(if (nameEndpoint) 1 else 2, urls.size)
            } finally { job.cancelAndJoin() }
        }
    }
    @Test fun `validates and extracts taxon id for exact matches, canonical matches, and synonyms`() {
        val exactMatch = """{"success":true,"scientificName":"Eucalyptus camaldulensis","rank":"species","matchType":"exactMatch","taxonConceptID":"taxon-123"}"""
        val synonymMatch = """{"success":true,"scientificName":"Eucalyptus rostrata","rank":"species","matchType":"synonym","taxonConceptID":"taxon-synonym","acceptedConceptID":"taxon-123"}"""
        val synonymMissingAccepted = """{"success":true,"scientificName":"Eucalyptus rostrata","rank":"species","matchType":"synonym","taxonConceptID":"taxon-123"}"""
        val canonicalMatch = """{"success":true,"scientificName":"Eucalyptus camaldulensis Dehnh.","rank":"species","matchType":"canonicalMatch","taxonConceptID":"taxon-123"}"""

        assertEquals("taxon-123", parseTaxonId(exactMatch))
        assertEquals("taxon-123", parseTaxonId(synonymMatch))
        assertEquals("taxon-123", parseTaxonId(synonymMissingAccepted)) // Falls back gracefully
        assertEquals("taxon-123", parseTaxonId(canonicalMatch))
    }

    @Test fun `rejects higher ranks and fuzzy taxonomy matches`() {
        val genusMatch = """{"success":true,"scientificName":"Eucalyptus","rank":"genus","matchType":"exactMatch","taxonConceptID":"taxon-genus"}"""
        val fuzzyMatch = """{"success":true,"scientificName":"Eucalyptus camaldulensis","rank":"species","matchType":"fuzzyMatch","taxonConceptID":"taxon-123"}"""

        assertNull(parseTaxonId(genusMatch))
        assertNull(parseTaxonId(fuzzyMatch))
    }

    @Test fun `malformed JSON structure throws AlaResponseException`() {
        for (body in listOf("not-json", "{}", "{\"success\":\"true\"}")) {
            assertThrows(AlaResponseException::class.java) { parseTaxonId(body) }
        }
    }
    private fun URL.isNameMatch() = path.endsWith("searchByClassification")
    private fun taxonMatch() = """{"success":true,"scientificName":"$NAME","rank":"species","matchType":"exactMatch","taxonConceptID":"taxon-123"}"""

    private companion object {
        const val NAME = "Eucalyptus camaldulensis"
        val LOCATION = GeoPoint(-37.7963, 144.9614)
    }

    private class FakeHttpURLConnection(
        url: URL,
        private val status: Int,
        private val body: String,
        private val retryAfter: String? = null,
    ) : HttpURLConnection(url) {
        private val disconnected = CountDownLatch(1)
        fun awaitDisconnect() = disconnected.await(5, TimeUnit.SECONDS)
        override fun connect() = Unit

        override fun disconnect() { disconnected.countDown() }
        override fun getHeaderField(name: String): String? = if (name == "Retry-After") retryAfter else null

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = status

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }
}
