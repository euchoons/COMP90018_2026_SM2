package au.edu.unimelb.floraguide.domain.repository

import java.io.File

/** A private Storage reference, not a public download-token URL. */
data class StoredPhoto(
    val storagePath: String,
    val gsUri: String,
    val sizeBytes: Long,
    val sha256: String,
    val contentType: String,
)

interface PhotoStore {
    suspend fun uploadPhoto(localPath: String): StoredPhoto
    /** Returns a temporary file downloaded from Storage. The caller must delete it. */
    suspend fun downloadPhoto(photo: StoredPhoto): File
}
