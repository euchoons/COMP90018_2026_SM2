package au.edu.unimelb.floraguide.domain.usecase

import au.edu.unimelb.floraguide.domain.model.ImageClassification
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.repository.PhotoStore
import au.edu.unimelb.floraguide.domain.repository.StoredPhoto
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class IdentificationStage(val label: String) {
    UPLOADING("Uploading photo to Firebase"),
    DOWNLOADING("Reading the stored photo from Firebase"),
    IDENTIFYING("Identifying the stored photo with Pl@ntNet"),
}

class PhotoIdentificationException(
    val stage: IdentificationStage,
    cause: Exception,
) : Exception("${stage.label}: ${cause.message ?: cause.javaClass.simpleName}", cause)

/** No local-photo fallback: classification only consumes a completed cloud download. */
class IdentifyStoredPhotoUseCase(
    private val photoStore: PhotoStore,
    private val classifier: ImageClassifier,
) {
    suspend operator fun invoke(
        localPath: String,
        previouslyUploaded: StoredPhoto? = null,
        onUploaded: (StoredPhoto) -> Unit = {},
        onStage: (IdentificationStage) -> Unit = {},
    ): ImageClassification {
        var stage = IdentificationStage.UPLOADING
        var downloaded: File? = null
        try {
            currentCoroutineContext().ensureActive()
            val stored = previouslyUploaded ?: run {
                onStage(stage)
                photoStore.uploadPhoto(localPath)
            }
            currentCoroutineContext().ensureActive()
            onUploaded(stored)

            stage = IdentificationStage.DOWNLOADING
            onStage(stage)
            downloaded = photoStore.downloadPhoto(stored)
            currentCoroutineContext().ensureActive()

            stage = IdentificationStage.IDENTIFYING
            onStage(stage)
            val classification = classifier.classify(downloaded.absolutePath)
            currentCoroutineContext().ensureActive()
            check(classification.predictions.isNotEmpty()) { "No plant candidates were returned." }
            return classification
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw PhotoIdentificationException(stage, error)
        } finally {
            // Only delete the cloud-download cache; keep the camera original and cloud object.
            downloaded?.delete()
        }
    }
}
