package au.edu.unimelb.floraguide.data.firebase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val MAX_THUMBNAIL_SOURCE_BYTES = 20L * 1024L * 1024L

internal suspend fun loadObservationThumbnail(path: String?, cloudPhotoUri: String?): Bitmap? = withContext(Dispatchers.IO) {
    try {
        // Local originals remain usable offline; a missing original falls back to the owned cloud object.
        path?.takeUnless { it.contains("://") }?.let { local ->
            val file = File(local)
            if (file.isFile && file.length() in 1L..MAX_THUMBNAIL_SOURCE_BYTES) {
                decodeObservationThumbnail(file.readBytes())?.let { return@withContext it }
            }
        }
        val uri = cloudPhotoUri ?: path?.takeIf { it.startsWith("gs://") } ?: return@withContext null
        if (!uri.startsWith("gs://")) return@withContext null
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid ?: return@withContext null
        val storage = FirebaseStorage.getInstance()
        val reference = storage.getReferenceFromUrl(uri)
        if (!ownsObservationPhoto(reference.bucket, reference.path, storage.reference.bucket, uid)) return@withContext null
        val bytes = reference.getBytes(MAX_THUMBNAIL_SOURCE_BYTES).await()
        if (auth.currentUser?.uid != uid) return@withContext null
        decodeObservationThumbnail(bytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null // An unavailable thumbnail must not prevent browsing the locally saved field guide.
    }
}

internal fun ownsObservationPhoto(bucket: String, path: String, configuredBucket: String, uid: String): Boolean =
    uid.isNotBlank() && bucket == configuredBucket && path.trimStart('/').startsWith("plant_photos/$uid/")

internal fun decodeObservationThumbnail(bytes: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val orientation = runCatching {
        bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return decoded
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
        if (it !== decoded) decoded.recycle()
    }
}
