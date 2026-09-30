package au.edu.unimelb.floraguide.data.plantnet

import au.edu.unimelb.floraguide.domain.model.ImageClassification
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic fixtures exercise app code, not live identification accuracy. */
class PlantNetClientRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    // PN-01
    @Test fun scoreBoundariesArePreservedWithoutNormalisation() {
        val results = parsePlantNetResults(
            """{"results":[${candidate(0.0, "Species zero")},${candidate(1.0, "Species one")},${candidate(0.8)}]}""",
            8,
        )
        assertEquals(listOf(0.0, 1.0, 0.8), results.map { it.score })
        assertEquals(1.8, results.sumOf { it.score }, 1e-12)
    }

    // PN-02
    @Test fun negativeAndOverOneScoresAreRejected() {
        for (score in listOf(-0.01, 1.01)) {
            assertThrows(PlantNetResponseException::class.java) {
                parsePlantNetResults("""{"results":[${candidate(score)}]}""", 8)
            }
        }
    }

    // PN-03
    @Test fun commonNameSkipsBlankEntriesAndTrimsNames() {
        val body = """{"results":[{"score":0.6,"species":{
            "scientificNameWithoutAuthor":"  Acacia dealbata  ",
            "commonNames":["", "   ", "  Silver wattle  ", "Other name"]}}]}"""
        val result = parsePlantNetResults(body, 8).single()
        assertEquals("Acacia dealbata", result.scientificName)
        assertEquals("Silver wattle", result.commonName)
    }

    // PN-04
    @Test fun pngExtensionProducesPngMultipartWithoutChangingBytes() {
        val photo = photo("sample.PNG").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }
        val connection = Connection(body = validBody())
        client(connection).identify(photo)
        val upload = connection.sent.toByteArray().toString(Charsets.ISO_8859_1)
        assertEquals("POST", connection.requestMethod)
        assertTrue(upload.contains("Content-Type: image/png\r\n\r\n"))
        assertTrue(upload.contains(photo.readBytes().toString(Charsets.ISO_8859_1)))
        assertTrue(upload.endsWith("\r\n--RegressionBoundary--\r\n"))
        assertTrue(connection.disconnected)
    }

    // PN-05
    @Test fun remainingHttpErrorsPreserveStatusAndActionableMessages() {
        val cases = mapOf(
            400 to "rejected the request", 401 to "API key", 403 to "API key",
            413 to "too large", 415 to "image format", 500 to "HTTP 500",
        )
        for ((status, hint) in cases) {
            val connection = Connection(status, "must-not-be-exposed")
            val error = assertThrows(PlantNetRequestException::class.java) {
                client(connection).identify(photo("status-$status.jpg"))
            }
            assertEquals(status, error.httpStatus)
            assertTrue("HTTP $status", error.message.orEmpty().contains(hint))
            assertFalse(error.message.orEmpty().contains("must-not-be-exposed"))
            assertTrue(connection.disconnected)
        }
    }

    // PN-06
    @Test fun networkFailureDoesNotLeakKeyOrBecomeDemoSuccess() {
        val logs = mutableListOf<String>()
        val connection = Connection(outputFailure = IOException("https://example.invalid/?api-key=$KEY"))
        val error = assertThrows(PlantNetRequestException::class.java) {
            client(connection, logs).identify(photo())
        }
        assertNull(error.httpStatus)
        assertTrue(error.message.orEmpty().contains("network request failed"))
        assertFalse(error.toString().contains(KEY))
        assertFalse(logs.joinToString().contains(KEY))
        assertTrue(connection.disconnected)

        var demoCalls = 0
        val liveClassifier = PlantNetImageClassifier(
            client = client(Connection(outputFailure = IOException("synthetic offline"))),
            guidedDemoClassifier = object : ImageClassifier {
                override suspend fun classify(photoPath: String?): ImageClassification {
                    demoCalls++
                    error("A live failure must not silently use the guided demo")
                }
            },
        )
        val livePhoto = photo("live-failure.jpg")
        assertThrows(PlantNetRequestException::class.java) {
            runBlocking { liveClassifier.classify(livePhoto.absolutePath) }
        }
        assertEquals(0, demoCalls)
    }

    // PN-07
    @Test fun invalidSuccessJsonRemainsAResponseFailureAndDisconnects() {
        val connection = Connection(body = "not-json")
        assertThrows(PlantNetResponseException::class.java) {
            client(connection).identify(photo())
        }
        assertTrue(connection.disconnected)
    }

    // PN-08
    @Test fun successfulUploadUsesExactStreamingLengthAndDisconnects() {
        val connection = Connection(body = validBody())
        val result = client(connection).identify(photo())
        assertEquals(200, result.httpStatus)
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(20_000, connection.readTimeout)
        assertEquals(connection.sent.size().toLong(), connection.streamingLength)
        assertTrue(connection.disconnected)
    }

    // PN-09
    @Test fun absentOrNonNumericQuotaIsUnknownNotZero() {
        for (body in listOf("{}", "not-json", """{"remainingIdentificationRequests":"10"}""")) {
            assertNull(parseRemainingRequests(body))
        }
        assertEquals(0, parseRemainingRequests("""{"remainingIdentificationRequests":0}"""))
    }

    // PN-10
    @Test fun badCandidateAfterValidCandidateCannotReturnPartialSuccess() {
        val body = """{"results":[${candidate(0.9)},{"score":0.2,"species":{}}]}"""
        assertThrows(PlantNetResponseException::class.java) { parsePlantNetResults(body, 8) }
    }

    private fun candidate(score: Double, name: String = "Acacia dealbata") =
        """{"score":$score,"species":{"scientificNameWithoutAuthor":"$name"}}"""

    private fun validBody() = """{"results":[${candidate(0.7)}]}"""

    // This byte marker is deliberately not a real photograph.
    private fun photo(name: String = "sample.jpg"): File = temporary.newFile(name).apply {
        writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42, 0x00))
    }

    private fun client(connection: Connection, logs: MutableList<String> = mutableListOf()) =
        PlantNetClient(
            apiKey = KEY,
            baseUrl = "https://example.invalid/identify",
            connectionFactory = { connection },
            nanoTime = { 1_000_000L },
            boundaryFactory = { "RegressionBoundary" },
            logger = { logs.add(it); Unit },
        )

    private class Connection(
        private val status: Int = 200,
        private val body: String = "",
        private val outputFailure: IOException? = null,
    ) : HttpURLConnection(URL("https://example.invalid/identify")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var streamingLength = -1L
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream(): OutputStream {
            outputFailure?.let { throw it }
            return sent
        }
        override fun setFixedLengthStreamingMode(contentLength: Long) {
            streamingLength = contentLength
        }
    }

    private companion object { const val KEY = "fake-key-for-local-tests-only" }
}
