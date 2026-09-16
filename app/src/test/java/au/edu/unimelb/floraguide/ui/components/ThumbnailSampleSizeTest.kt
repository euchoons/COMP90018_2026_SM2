package au.edu.unimelb.floraguide.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSampleSizeTest {
    @Test
    fun smallPhotosAreDecodedAtFullSize() {
        assertEquals(1, thumbnailSampleSize(300))
        assertEquals(1, thumbnailSampleSize(799))
    }

    @Test
    fun boundedCaptureIsHalved() {
        // 1920x1440 capture -> 960x720 thumbnail.
        assertEquals(2, thumbnailSampleSize(1440))
    }

    @Test
    fun sampleSizeGrowsWithSensorResolution() {
        // 12 MP (4000x3000) matches the previous fixed value of 4.
        assertEquals(4, thumbnailSampleSize(3000))
        // 50 MP (8160x6120) -> 1020x765 instead of 2040x1530.
        assertEquals(8, thumbnailSampleSize(6120))
    }
}
