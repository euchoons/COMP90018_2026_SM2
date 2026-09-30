package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.domain.model.ImageClassification
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.Species
import au.edu.unimelb.floraguide.domain.repository.ImageClassifier
import au.edu.unimelb.floraguide.domain.repository.PhotoStore
import au.edu.unimelb.floraguide.domain.repository.StoredPhoto
import au.edu.unimelb.floraguide.domain.usecase.IdentificationStage
import au.edu.unimelb.floraguide.domain.usecase.IdentifyStoredPhotoUseCase
import au.edu.unimelb.floraguide.domain.usecase.PhotoIdentificationException
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Fake services test orchestration; no Firebase account or PlantNet quota is used. */
internal object IdentifyStoredPhotoContract {
    fun successUsesDownloadedFile() = runBlocking {
        Fixture().use { f ->
            val stages = mutableListOf<IdentificationStage>()
            var uploaded: StoredPhoto? = null
            val result = f.useCase()(f.original.absolutePath,
                onUploaded = { uploaded = it }, onStage = stages::add)
            check(f.events == listOf("upload", "download", "classify"))
            check(stages == IdentificationStage.entries.toList())
            check(uploaded == f.stored)
            check(result === f.result)
            check(f.classifiedPath == f.downloaded.absolutePath)
            check(f.classifiedBytes == "cloud-download")
            check(!f.downloaded.exists())
            check(f.original.readText() == "camera-original")
        }
    }

    fun uploadFailureStopsThePipeline() = runBlocking {
        Fixture().use { f ->
            f.failStage = "upload"
            val error = runCatching { f.useCase()(f.original.absolutePath) }.exceptionOrNull()
            check(error is PhotoIdentificationException && error.stage == IdentificationStage.UPLOADING)
            check(f.events == listOf("upload"))
            check(f.original.exists())
        }
    }

    fun downloadFailureDoesNotClassify() = runBlocking {
        Fixture().use { f ->
            f.failStage = "download"
            var uploaded: StoredPhoto? = null
            val error = runCatching { f.useCase()(f.original.absolutePath,
                onUploaded = { uploaded = it }) }.exceptionOrNull()
            check(error is PhotoIdentificationException && error.stage == IdentificationStage.DOWNLOADING)
            check(f.events == listOf("upload", "download"))
            check(uploaded == f.stored)
        }
    }

    fun classificationFailureCleansTheCache() = runBlocking {
        Fixture().use { f ->
            f.failStage = "classify"
            val error = runCatching { f.useCase()(f.original.absolutePath) }.exceptionOrNull()
            check(error is PhotoIdentificationException && error.stage == IdentificationStage.IDENTIFYING)
            check(!f.downloaded.exists())
            check(f.original.exists())
        }
    }

    fun retryReusesTheCloudObject() = runBlocking {
        Fixture().use { f ->
            val stages = mutableListOf<IdentificationStage>()
            f.useCase()(f.original.absolutePath, previouslyUploaded = f.stored, onStage = stages::add)
            check(f.events == listOf("download", "classify"))
            check(stages == listOf(IdentificationStage.DOWNLOADING, IdentificationStage.IDENTIFYING))
            check(!f.downloaded.exists())
        }
    }

    fun emptyResponseIsAnError() = runBlocking {
        Fixture().use { f ->
            f.emptyResult = true
            val error = runCatching { f.useCase()(f.original.absolutePath) }.exceptionOrNull()
            check(error is PhotoIdentificationException)
            check(error.message.orEmpty().contains("No plant candidates"))
            check(!f.downloaded.exists())
        }
    }

    fun cancellationIsNotWrappedAsFailure() = runBlocking {
        Fixture().use { f ->
            val cancelled = CancellationException("user navigated away")
            val store = object : PhotoStore {
                override suspend fun uploadPhoto(localPath: String): StoredPhoto = throw cancelled
                override suspend fun downloadPhoto(photo: StoredPhoto): File = error("Must not download")
                override suspend fun deletePhoto(gsUri: String) = error("Must not delete")
            }
            val error = runCatching { IdentifyStoredPhotoUseCase(store, f.classifier)(f.original.absolutePath) }
                .exceptionOrNull()
            check(error === cancelled)
            check(f.events.isEmpty())
        }
    }

    fun cancellingAnActiveClassificationCleansTheCache() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            var completed = false
            val waitingClassifier = object : ImageClassifier {
                override suspend fun classify(photoPath: String?): ImageClassification {
                    check(photoPath == f.downloaded.absolutePath)
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val job = launch {
                IdentifyStoredPhotoUseCase(f.store, waitingClassifier)(f.original.absolutePath)
                completed = true
            }
            entered.await()
            job.cancelAndJoin()
            check(!completed)
            check(!f.downloaded.exists())
            check(f.original.exists())
        }
    }

    private class Fixture : Closeable {
        private val directory = Files.createTempDirectory("cloud-identification-test").toFile()
        val original = File(directory, "camera.jpg").apply { writeText("camera-original") }
        val downloaded = File(directory, "downloaded.jpg")
        val events = mutableListOf<String>()
        val stored = StoredPhoto("plant_photos/test-user/example.jpg",
            "gs://test-bucket/plant_photos/test-user/example.jpg", 14L, "test-digest", "image/jpeg")
        val result = ImageClassification(listOf(ImagePrediction(
            Species("test-species", "Test plant", "Test species", emptySet(), emptyMap(), 0),
            score = 0.83, rank = 1)), ImageSource.PLANTNET_LIVE, 21L)
        var failStage: String? = null
        var emptyResult = false
        var classifiedPath: String? = null
        var classifiedBytes: String? = null
        val store = object : PhotoStore {
            override suspend fun uploadPhoto(localPath: String): StoredPhoto {
                events += "upload"
                check(localPath == original.absolutePath)
                if (failStage == "upload") error("simulated upload failure")
                return stored
            }
            override suspend fun downloadPhoto(photo: StoredPhoto): File {
                events += "download"
                check(photo == stored)
                if (failStage == "download") error("simulated download failure")
                return downloaded.apply { writeText("cloud-download") }
            }
            override suspend fun deletePhoto(gsUri: String) {
                events += "delete"
            }
        }
        val classifier = object : ImageClassifier {
            override suspend fun classify(photoPath: String?): ImageClassification {
                events += "classify"
                classifiedPath = photoPath
                classifiedBytes = File(requireNotNull(photoPath)).readText()
                if (failStage == "classify") error("simulated classifier failure")
                return if (emptyResult) result.copy(predictions = emptyList()) else result
            }
        }
        fun useCase() = IdentifyStoredPhotoUseCase(store, classifier)
        override fun close() { directory.deleteRecursively() }
    }
}
