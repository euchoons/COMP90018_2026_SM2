package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.usecase.LocationFreshnessPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFreshnessPolicyTest {
    private val policy = LocationFreshnessPolicy()
    private val s = 1_000_000_000L
    private val gps = GeoPoint(-37.7963, 144.9614, 5f)
    private val network = GeoPoint(-37.8, 144.97, 1_900f)

    @Test fun `context eligibility requires valid accurate and fresh device fix`() {
        assertTrue(policy.isUsable(gps.copy(accuracyMetres = 2_000f), s, 61 * s))
        assertFalse(policy.isUsable(gps, s, 61 * s + 1_000_000L))
        assertFalse(policy.isUsable(gps, 0, s))
        assertFalse(policy.isUsable(gps, 2 * s, s))
        for (point in listOf(gps.copy(accuracyMetres = null), gps.copy(accuracyMetres = 2_001f),
            gps.copy(accuracyMetres = Float.NaN), gps.copy(accuracyMetres = -1f),
            gps.copy(latitude = 91.0), gps.copy(longitude = Double.NaN))) {
            assertFalse(policy.isUsable(point, s, 2 * s))
        }
    }

    @Test fun `a fresh accurate fix is kept until it goes stale, same-provider updates always win`() {
        // A network fix one second later must not replace a fresh 5 m GPS fix.
        assertFalse(policy.shouldReplace(gps, 10 * s, network, 11 * s, sameProvider = false, nowElapsedNanos = 11 * s))
        // Once the GPS fix is older than 60 s, the coarser fix takes over.
        assertTrue(policy.shouldReplace(gps, 10 * s, network, 80 * s, sameProvider = false, nowElapsedNanos = 80 * s))
        // GPS accuracy fluctuates; a newer GPS fix still replaces the previous one.
        assertTrue(policy.shouldReplace(gps, 10 * s, gps.copy(accuracyMetres = 8f), 12 * s, sameProvider = true, nowElapsedNanos = 12 * s))
        assertFalse(policy.shouldReplace(gps, 10 * s, gps, 9 * s, sameProvider = true, nowElapsedNanos = 12 * s))
    }
}
