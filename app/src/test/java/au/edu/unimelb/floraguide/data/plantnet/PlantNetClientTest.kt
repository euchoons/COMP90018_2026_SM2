package au.edu.unimelb.floraguide.data.plantnet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantNetClientTest {
    @Test
    fun recordedFixtureParsesTopCandidates() {
        val results = parsePlantNetResults(fixture(), maxResults = 8)

        assertEquals(8, results.size)
        assertEquals("Corymbia bella", results[0].scientificName)
        assertEquals(0.17292, results[0].score, 1e-6)
        // Pl@ntNet scores are per-species confidences, not a distribution over the candidate set.
        assertTrue(results.sumOf { it.score } < 1.0)
        assertEquals("Eucalyptus camaldulensis", results[1].scientificName)
        assertEquals("River redgum", results[1].commonName)
        // Not every species has a common name; the classifier falls back to the scientific one.
        assertNull(results[0].commonName)
        assertEquals(499, parseRemainingRequests(fixture()))
    }

    @Test
    fun maxResultsBoundsTheAlaFanOut() {
        assertEquals(3, parsePlantNetResults(fixture(), maxResults = 3).size)
    }

    @Test
    fun malformedResponsesFailClosed() {
        assertThrows(PlantNetResponseException::class.java) {
            parsePlantNetResults("not-json", maxResults = 8)
        }
        assertThrows(PlantNetResponseException::class.java) {
            parsePlantNetResults("{\"bestMatch\":\"x\"}", maxResults = 8)
        }
        assertThrows(PlantNetResponseException::class.java) {
            parsePlantNetResults("{\"results\":[]}", maxResults = 8)
        }
        // A candidate without a usable scientific name must not silently shorten the list.
        assertThrows(PlantNetResponseException::class.java) {
            parsePlantNetResults(
                "{\"results\":[{\"score\":0.5,\"species\":{\"commonNames\":[\"x\"]}}]}",
                maxResults = 8,
            )
        }
        assertThrows(PlantNetResponseException::class.java) {
            parsePlantNetResults(
                "{\"results\":[{\"score\":\"0.5\",\"species\":{\"scientificNameWithoutAuthor\":\"A b\"}}]}",
                maxResults = 8,
            )
        }
    }

    @Test
    fun requestUploadsPhotoAsMultipartAndReturnsTelemetry() {
        val photo = jpegFixtureFile()
        val requestedUrls = mutableListOf<URL>()
        val uploaded = ByteArrayOutputStream()
        val logMessages = mutableListOf<String>()
        val clock = ArrayDeque(listOf(1_000_000_000L, 4_400_000_000L))
        val client = PlantNetClient(
            apiKey = "test-key",
            connectionFactory = { url ->
                requestedUrls += url
                FakeHttpURLConnection(url, status = 200, body = fixture(), captured = uploaded)
            },
            nanoTime = { clock.removeFirst() },
            boundaryFactory = { "TestBoundary" },
            logger = logMessages::add,
        )

        val identification = client.identify(photo)

        assertEquals(8, identification.results.size)
        assertEquals(200, identification.httpStatus)
        assertEquals(3_400L, identification.elapsedMillis)
        assertEquals(499, identification.remainingRequests)

        val query = requestedUrls.single().query
        assertTrue(query.contains("api-key=test-key"))
        assertTrue(query.contains("nb-results=8"))

        // ISO-8859-1 keeps the JPEG bytes byte-for-byte while the headers stay readable.
        val body = uploaded.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(body.startsWith("--TestBoundary\r\n"))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"organs\"\r\n\r\nauto\r\n"))
        assertTrue(body.contains("name=\"images\"; filename=\"${photo.name}\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertTrue(body.contains(JPEG_MARKER.toString(Charsets.ISO_8859_1)))
        assertTrue(body.endsWith("\r\n--TestBoundary--\r\n"))

        // Telemetry must never carry the API key.
        assertTrue(logMessages.single().contains("candidates=8"))
        assertTrue(logMessages.single().contains("remainingRequests=499"))
        assertEquals(false, logMessages.single().contains("test-key"))
    }

    @Test
    fun quotaAndNoMatchStatusesCarryActionableMessages() {
        val quotaError = assertThrows(PlantNetRequestException::class.java) {
            clientReturning(status = 429).identify(jpegFixtureFile())
        }
        assertEquals(429, quotaError.httpStatus)
        assertTrue(quotaError.message!!.contains("quota"))

        val noMatch = assertThrows(PlantNetRequestException::class.java) {
            clientReturning(status = 404).identify(jpegFixtureFile())
        }
        assertTrue(noMatch.message!!.contains("could not match"))
    }

    @Test
    fun missingKeyOrPhotoFailsBeforeAnyRequest() {
        var connectionsOpened = 0
        val counting: (URL) -> HttpURLConnection = { url ->
            connectionsOpened++
            FakeHttpURLConnection(url, 200, fixture(), ByteArrayOutputStream())
        }

        val keyless = PlantNetClient(apiKey = "  ", connectionFactory = counting, logger = {})
        assertTrue(
            assertThrows(PlantNetRequestException::class.java) {
                keyless.identify(jpegFixtureFile())
            }.message!!.contains("local.properties"),
        )

        val configured = PlantNetClient(apiKey = "test-key", connectionFactory = counting, logger = {})
        assertThrows(PlantNetRequestException::class.java) {
            configured.identify(File("/does/not/exist.jpg"))
        }

        assertEquals(0, connectionsOpened)
    }

    private fun clientReturning(status: Int) = PlantNetClient(
        apiKey = "test-key",
        connectionFactory = { url ->
            FakeHttpURLConnection(url, status, body = "", captured = ByteArrayOutputStream())
        },
        nanoTime = ArrayDeque(listOf(0L, 1_000_000L))::removeFirst,
        logger = {},
    )

    private fun fixture(): String = requireNotNull(
        javaClass.classLoader?.getResource("plantnet/identify-eucalyptus-camaldulensis.json"),
    ).readText()

    private fun jpegFixtureFile(): File =
        File.createTempFile("floraguide-test", ".jpg").apply {
            writeBytes(JPEG_MARKER)
            deleteOnExit()
        }

    private class FakeHttpURLConnection(
        url: URL,
        private val status: Int,
        private val body: String,
        private val captured: ByteArrayOutputStream,
    ) : HttpURLConnection(url) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = status

        override fun getOutputStream(): OutputStream = captured

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        val JPEG_MARKER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42)
    }
}
