package au.edu.unimelb.floraguide.data.firebase

import android.content.Context
import android.net.Uri
import android.util.Log
import au.edu.unimelb.floraguide.domain.repository.PhotoStore
import au.edu.unimelb.floraguide.domain.repository.StoredPhoto
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FirebasePhotoStorage(
    context: Context,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val expectedUserId: String? = null,
) : PhotoStore {
    private val cacheDirectory = File(context.applicationContext.cacheDir, "plantnet-cloud")

    override suspend fun uploadPhoto(localPath: String): StoredPhoto {
        val uid = ensureUser()
        return withContext(Dispatchers.IO) {
            cleanStaleCache()
            val file = File(localPath)
            require(file.isFile && file.canRead()) { "The captured photo cannot be read." }
            require(file.length() in 1L..MAX_IMAGE_BYTES) {
                "The photo is empty or exceeds the app's 20 MiB upload limit."
            }
            val contentType = imageContentType(file)
            val digest = sha256(file)
            check(ensureUser() == uid) { "Account changed before upload." }
            val extension = if (contentType == "image/png") "png" else "jpg"
            val reference = storage.reference.child("plant_photos/$uid/$digest.$extension")
            val metadata = StorageMetadata.Builder()
                .setContentType(contentType)
                .setCustomMetadata("sha256", digest)
                .build()
            val upload = reference.putFile(Uri.fromFile(file), metadata)
            try {
                upload.await()
                currentCoroutineContext().ensureActive()
                check(ensureUser() == uid) { "Account changed during upload." }
                Log.i(TAG, "stage=upload outcome=success bytes=${file.length()}")
                StoredPhoto(
                    storagePath = reference.path,
                    gsUri = reference.toString(),
                    sizeBytes = file.length(),
                    sha256 = digest,
                    contentType = contentType,
                )
            } catch (cancelled: CancellationException) {
                upload.cancel()
                throw cancelled
            } catch (error: StorageException) {
                throw storageError("Upload", error)
            }
        }
    }

    override suspend fun downloadPhoto(photo: StoredPhoto): File {
        var temporary: File? = null
        try {
            return withContext(Dispatchers.IO) {
                cleanStaleCache()
                val uid = ensureUser()
                val reference = storage.getReferenceFromUrl(photo.gsUri)
                require(reference.bucket == storage.reference.bucket) { "Unexpected Storage bucket." }
                require(reference.path.trimStart('/').startsWith("plant_photos/$uid/")) {
                    "This photo does not belong to the signed-in user."
                }
                require(photo.sizeBytes in 1L..MAX_IMAGE_BYTES) { "Unsupported stored photo size." }
                val metadata = try {
                    reference.metadata.await()
                } catch (error: StorageException) {
                    throw storageError("Reading metadata", error)
                }
                require(metadata.sizeBytes == photo.sizeBytes && metadata.sizeBytes <= MAX_IMAGE_BYTES) {
                    "The stored photo size changed. Retake the photo."
                }
                check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Cannot create image cache." }
                val suffix = if (photo.contentType == "image/png") ".png" else ".jpg"
                val target = File.createTempFile("from-firebase-", suffix, cacheDirectory)
                temporary = target
                val download = reference.getFile(target)
                try {
                    download.await()
                } catch (cancelled: CancellationException) {
                    download.cancel()
                    throw cancelled
                } catch (error: StorageException) {
                    throw storageError("Download", error)
                }
                currentCoroutineContext().ensureActive()
                require(target.length() == photo.sizeBytes && sha256(target) == photo.sha256) {
                    "The downloaded photo failed its integrity check. Retry identification."
                }
                Log.i(TAG, "stage=download outcome=success sha256Verified=true bytes=${target.length()}")
                target
            }
        } catch (error: Exception) {
            temporary?.delete()
            throw error
        }
    }

    override suspend fun deletePhoto(gsUri: String) {
        if (gsUri.isBlank()) return
        val uid = ensureUser()
        withContext(Dispatchers.IO) {
            val reference = storage.getReferenceFromUrl(gsUri)
            require(reference.bucket == storage.reference.bucket) { "Unexpected Storage bucket." }
            require(reference.path.trimStart('/').startsWith("plant_photos/$uid/")) {
                "This photo does not belong to the signed-in user."
            }
            try {
                reference.delete().await()
                Log.i(TAG, "stage=delete outcome=success uri=$gsUri")
            } catch (error: StorageException) {
                if (error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                    Log.i(TAG, "stage=delete outcome=already_deleted uri=$gsUri")
                    return@withContext
                }
                throw storageError("Delete photo", error)
            }
        }
    }

    /** Evicts temporary files older than 1 hour left behind by crashed processes. */
    private fun cleanStaleCache() {
        runCatching {
            if (!cacheDirectory.exists()) return
            val threshold = System.currentTimeMillis() - 3_600_000L
            cacheDirectory.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < threshold) {
                    file.delete()
                }
            }
        }
    }

    private fun ensureUser(): String {
        val uid = auth.currentUser?.uid ?: throw IOException("Sign in from Account before uploading a photo. The guided demo works offline.")
        check(expectedUserId == null || expectedUserId == uid) { "Account changed before photo transfer." }
        return uid
    }

    private fun storageError(action: String, error: StorageException): IOException {
        val hint = when (error.errorCode) {
            StorageException.ERROR_NOT_AUTHENTICATED -> "Sign in with Firebase Authentication."
            StorageException.ERROR_NOT_AUTHORIZED ->
                "Check the UID-based Storage rules and App Check configuration."
            StorageException.ERROR_OBJECT_NOT_FOUND -> "The stored photo no longer exists."
            StorageException.ERROR_BUCKET_NOT_FOUND -> "Check the bucket in google-services.json."
            StorageException.ERROR_QUOTA_EXCEEDED -> "Check Storage quota and billing."
            StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> "The Storage request timed out. Check the network."
            else -> "Check Firebase Storage configuration and network connectivity."
        }
        Log.w(TAG, "action=$action storageCode=${error.errorCode}")
        return IOException("$action failed (Storage ${error.errorCode}). $hint")
    }

    private fun imageContentType(file: File): String {
        val header = ByteArray(8)
        val count = file.inputStream().use { it.read(header) }
        if (count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        require(count == 8 && header.contentEquals(pngHeader)) { "Only JPEG and PNG photos are supported." }
        return "image/png"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val TAG = "FloraGuide-Storage"
        const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
    }
}
