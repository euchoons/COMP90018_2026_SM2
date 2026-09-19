package au.edu.unimelb.floraguide.data.firebase

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBitmapFactory
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ObservationPhotoLoaderTest {
    @Before fun rejectCorruptImagesLikeAndroid() {
        // Robolectric's fallback graphics implementation otherwise creates fake bitmaps for junk bytes.
        ShadowBitmapFactory.setAllowInvalidImageData(false)
    }
    @Test fun `thumbnail only accepts configured bucket and exact account directory`() {
        assertTrue(ownsObservationPhoto("bucket", "/plant_photos/A/photo.jpg", "bucket", "A"))
        assertFalse(ownsObservationPhoto("other", "/plant_photos/A/photo.jpg", "bucket", "A"))
        assertFalse(ownsObservationPhoto("bucket", "/plant_photos/AB/photo.jpg", "bucket", "A"))
        assertFalse(ownsObservationPhoto("bucket", "/plant_photos/B/photo.jpg", "bucket", "A"))
        assertFalse(ownsObservationPhoto("bucket", "/plant_photos//photo.jpg", "bucket", ""))
    }

    @Test fun `large image is sampled to a bounded thumbnail`() {
        val original = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val thumbnail = requireNotNull(decodeObservationThumbnail(encoded))
        assertEquals(1024, thumbnail.width)
        assertEquals(512, thumbnail.height)
        original.recycle()
        thumbnail.recycle()
    }

    @Test fun `missing photo shows placeholder without initializing Firebase`() = runBlocking {
        assertNull(loadObservationThumbnail("/does-not-exist/photo.jpg", null))
        assertNull(decodeObservationThumbnail(byteArrayOf(1, 2, 3)))
    }
}
