package au.edu.unimelb.floraguide.data.firebase

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.util.UUID

class FirebasePhotoStorage(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) {

    fun uploadPhoto(
        localPath: String,
        onSuccess: (storagePath: String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val localFile = File(localPath)

        if (!localFile.exists()) {
            onFailure(
                IllegalArgumentException(
                    "Photo file does not exist: $localPath"
                )
            )
            return
        }

        val fileName = "${UUID.randomUUID()}.jpg"

        val photoReference = storage.reference
            .child("plant_photos")
            .child(fileName)

        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCustomMetadata("originalFileName", localFile.name)
            .build()

        photoReference
            .putFile(
                Uri.fromFile(localFile),
                metadata
            )
            .addOnSuccessListener {
                onSuccess(photoReference.path)
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }
}