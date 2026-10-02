package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.domain.model.ImageClassification
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.repository.PhotoStore
import au.edu.unimelb.floraguide.domain.repository.StoredPhoto
import au.edu.unimelb.floraguide.domain.usecase.IdentifyStoredPhotoUseCase
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Issue14UndeliveredUploadTest {
    @Test fun cancellationAfterUploadBeforeCallbackReleasesTheNewObject() = runBlocking<Unit> {
        val released = mutableListOf<StoredPhoto>()
        val photo = StoredPhoto("plant_photos/test/a.jpg", "gs://bucket/plant_photos/test/a.jpg", 1, "a".repeat(64), "image/jpeg")
        val store = object : PhotoStore {
            override suspend fun uploadPhoto(localPath: String): StoredPhoto {
                currentCoroutineContext().cancel()
                return photo
            }
            override suspend fun downloadPhoto(photo: StoredPhoto): File = error("Must not download")
            override suspend fun deletePhoto(gsUri: String) = error("Deletion is queued by the registry, not run in the cancelled coroutine")
        }
        val classifier = object : ImageClassifier {
            override suspend fun classify(photoPath: String?): ImageClassification = error("Must not classify")
        }
        val job = launch {
            IdentifyStoredPhotoUseCase(store, classifier, released::add)("camera.jpg", onUploaded = { error("Must not deliver after cancellation") })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(photo), released)
    }
}
