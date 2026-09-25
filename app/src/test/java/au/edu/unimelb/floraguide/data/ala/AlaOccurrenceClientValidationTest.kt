package au.edu.unimelb.floraguide.data.ala

import au.edu.unimelb.floraguide.domain.model.GeoPoint
import java.net.URL
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** No network connection is opened by this class. */
class AlaOccurrenceClientValidationTest {
    // ALA-C01
    @Test fun invalidCoordinatesFailBeforeTransport() {
        val invalid = listOf(
            GeoPoint(91.0, 0.0), GeoPoint(-91.0, 0.0), GeoPoint(0.0, 181.0),
            GeoPoint(0.0, -181.0), GeoPoint(Double.NaN, 0.0),
            GeoPoint(0.0, Double.POSITIVE_INFINITY),
        )
        var calls = 0
        val client = rejectingClient { calls++ }
        for (location in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.countNearbyOccurrencesAsync(NAME, location, 8) }
            }
        }
        assertEquals(0, calls)
    }

    // ALA-C02
    @Test fun invalidRadiusFailsBeforeTransport() {
        var calls = 0
        val client = rejectingClient { calls++ }
        for (radius in listOf(0, -1, 101)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.countNearbyOccurrencesAsync(NAME, LOCATION, radius) }
            }
        }
        assertEquals(0, calls)
    }

    // ALA-C03
    @Test fun invalidScientificNameFailsBeforeTransport() {
        var calls = 0
        val client = rejectingClient { calls++ }
        for (name in listOf("", "   ", "Acacia\ndealbata", "x".repeat(301))) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.countNearbyOccurrencesAsync(name, LOCATION, 8) }
            }
        }
        assertEquals(0, calls)
    }

    // ALA-C04
    @Test fun countsRejectNegativeAndOverflowButAcceptLargestInteger() {
        assertEquals(Int.MAX_VALUE, parseTotalRecords("""{"totalRecords":2147483647}"""))
        for (value in listOf("-1", "2147483648", "null", "true")) {
            assertThrows(AlaResponseException::class.java) {
                parseTotalRecords("""{"totalRecords":$value}""")
            }
        }
    }

    // ALA-C05
    @Test fun retryAfterSupportsSecondsAndHttpDateWithFixedClock() {
        assertEquals(7_000L, parseRetryAfterMillis(" 7 ", NOW))
        val future = DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(30).atZone(ZoneOffset.UTC))
        assertEquals(30_000L, parseRetryAfterMillis(future, NOW))
    }

    // ALA-C06
    @Test fun invalidRetryAfterIsUnknownAndPastDateHasNoWait() {
        for (header in listOf(null, "", "-1", "not-a-date", "1.5")) {
            assertNull(parseRetryAfterMillis(header, NOW))
        }
        val past = DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.minusSeconds(30).atZone(ZoneOffset.UTC))
        assertEquals(0L, parseRetryAfterMillis(past, NOW))
    }

    // ALA-C07
    @Test fun offlineTransportDoesNotBecomeZeroRecords() {
        var calls = 0
        val client = AlaOccurrenceClient(
            connectionFactory = { calls++; throw UnknownHostException("synthetic offline") },
            logger = {},
        )
        val error = assertThrows(AlaRequestException::class.java) {
            runBlocking { client.countNearbyOccurrencesAsync(NAME, LOCATION, 8) }
        }
        assertEquals(1, calls)
        assertEquals(AlaFailureKind.OFFLINE, error.kind)
        assertFalse(error.isRetryable)
        assertNull(error.httpStatus)
    }

    // ALA-C08
    @Test fun exactNameMatchingToleratesCaseAndInputPadding() {
        val body = """{"success":true,"scientificName":"acacia dealbata","rank":"SPECIES",
            "matchType":"exactMatch","taxonConceptID":"test-taxon-1"}"""
        assertEquals("test-taxon-1", parseTaxonId(body, "  Acacia dealbata  "))
        assertNull(parseTaxonId(body.replace("exactMatch", "fuzzyMatch"), NAME))
    }

    private fun rejectingClient(onConnection: () -> Unit) = AlaOccurrenceClient(
        connectionFactory = { _: URL ->
            onConnection()
            throw AssertionError("Invalid input must not open an HTTP connection")
        },
        logger = {},
    )

    private companion object {
        const val NAME = "Acacia dealbata"
        val LOCATION = GeoPoint(-37.7963, 144.9614)
        val NOW: Instant = Instant.parse("2026-09-24T00:00:00Z")
    }
}
