package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.domain.model.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoPointTest {
    @Test fun contextRequiresFreshAccurateValidFix() {
        val now = 1_000_000L
        val valid = GeoPoint(-37.8, 145.0, 1_000f, now - 120_000L)
        assertTrue(valid.isUsableForContext(now))
        for (invalid in listOf(
            valid.copy(accuracyMetres = null), valid.copy(accuracyMetres = 1_001f),
            valid.copy(accuracyMetres = Float.NaN), valid.copy(accuracyMetres = -1f),
            valid.copy(fixTimeMillis = null), valid.copy(fixTimeMillis = now - 120_001L),
            valid.copy(fixTimeMillis = now + 1), valid.copy(latitude = 91.0),
            valid.copy(longitude = Double.NaN), GeoPoint(-37.8, 145.0),
        )) assertFalse(invalid.isUsableForContext(now))
    }
}
