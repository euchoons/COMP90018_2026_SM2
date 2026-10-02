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
    private val onUndeliveredUpload: (StoredPhoto) -> Unit = {},
) {
    suspend operator fun invoke(
        localPath: String,
        previouslyUploaded: StoredPhoto? = null,
        onUploaded: (StoredPhoto) -> Unit = {},
        onStage: (IdentificationStage) -> Unit = {},
    ): ImageClassification {
        var stage = IdentificationStage.UPLOADING
        var downloaded: File? = null
        var stored: StoredPhoto? = null
        var deliveredToCaller = false
        try {
            currentCoroutineContext().ensureActive()
            val uploaded = previouslyUploaded ?: run {
                onStage(stage)
                photoStore.uploadPhoto(localPath)
            }
            stored = uploaded
            currentCoroutineContext().ensureActive()
            onUploaded(uploaded)
            deliveredToCaller = true

            stage = IdentificationStage.DOWNLOADING
            onStage(stage)
            downloaded = photoStore.downloadPhoto(uploaded)
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
            // A completed upload can be cancelled before the ViewModel receives its reference.
            // Only a newly uploaded, undelivered object is released here. Retry-owned photos stay.
            if (!deliveredToCaller && previouslyUploaded == null) {
                stored?.let { photo ->
                    try { onUndeliveredUpload(photo) } catch (_: Exception) {
                        // Keep the primary error/cancellation. The durable journal recovers on restart.
                    }
                }
            }
            // The camera original is never deleted. The ViewModel owns delivered cloud references.
            downloaded?.delete()
        }
    }
}
