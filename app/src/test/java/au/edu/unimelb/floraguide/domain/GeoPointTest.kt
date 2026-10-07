package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoPointTest {
    @Test
    fun coarseningRoundsToTheThousandthDegreeGridAndKeepsAccuracy() {
        val point = GeoPoint(-37.7968912, 144.9614567, accuracyMetres = 6f).coarsened()
        assertEquals(-37.797, point.latitude, 0.0)
        assertEquals(144.961, point.longitude, 0.0)
        assertEquals(6f, point.accuracyMetres)
    }

    @Test
    fun coarseningIsIdempotentSoStoredPointsNeverMove() {
        for (i in 0..2_000) {
            val once = GeoPoint(-37.80 + i * 0.0000137, 144.95 + i * 0.0000173).coarsened()
            assertEquals(once, once.coarsened())
        }
    }
}
