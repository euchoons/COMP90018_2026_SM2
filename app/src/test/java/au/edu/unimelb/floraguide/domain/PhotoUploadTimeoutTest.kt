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
import au.edu.unimelb.floraguide.domain.usecase.PhotoUploadTimeoutException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Virtual time, fake transfers and real orchestration. No API keys or network are used. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoUploadTimeoutTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture {
        val original = temporary.newFile().apply { writeText("camera original") }
        val cloud = StoredPhoto("plant_photos/test/scan.jpg", "gs://test/plant_photos/test/scan.jpg",
            12, "test-checksum", "image/jpeg")
        val online = MutableStateFlow(true)
        var uploadDelay = 0L
        var downloadDelay = 0L
        var classifyDelay = 0L
        var uploadError: Exception? = null
        var uploadCalls = 0
        var transfers = 0
        var cancelledUploads = 0
        var downloads = 0
        var classifications = 0
        var ignoreUploadCancellation = false
        val released = mutableListOf<StoredPhoto>()
        val stages = mutableListOf<IdentificationStage>()
        val result = ImageClassification(listOf(ImagePrediction(
            Species("plant", "Test plant", "Test species", emptySet(), emptyMap(), 0), 0.8, 1)),
            ImageSource.PLANTNET_LIVE)
        val store = object : PhotoStore {
            override suspend fun uploadPhoto(localPath: String): StoredPhoto {
                uploadCalls++
                uploadError?.let { throw it }
                assertEquals(original.absolutePath, localPath)
                try {
                    online.first { it }
                    transfers++
                    if (ignoreUploadCancellation) withContext(NonCancellable) { delay(uploadDelay) }
                    else delay(uploadDelay)
                    return cloud
                } catch (cancelled: CancellationException) {
                    cancelledUploads++
                    throw cancelled
                }
            }
            override suspend fun downloadPhoto(photo: StoredPhoto): File {
                downloads++
                delay(downloadDelay)
                return temporary.newFile().apply { writeText("verified cloud download") }
            }
            override suspend fun deletePhoto(gsUri: String) = error("No synchronous remote deletion")
        }
        val classifier = object : ImageClassifier {
            override suspend fun classify(photoPath: String?): ImageClassification {
                classifications++
                assertNotEquals(original.absolutePath, photoPath)
                delay(classifyDelay)
                return result
            }
        }
        fun useCase() = IdentifyStoredPhotoUseCase(store, classifier, onUndeliveredUpload = released::add)
    }

    private fun assertUploadTimeout(error: Throwable?) {
        assertTrue(error is PhotoIdentificationException)
        val failure = error as PhotoIdentificationException
        assertEquals(IdentificationStage.UPLOADING, failure.stage)
        assertTrue(failure.cause is PhotoUploadTimeoutException)
        assertEquals(30_000L, (failure.cause as PhotoUploadTimeoutException).timeoutMillis)
    }

    @Test fun offlineWaitStopsAtThirtySecondsNotEarlier() = runTest {
        val f = Fixture().apply { online.value = false }
        val task = async { runCatching { f.useCase()(f.original.absolutePath, onStage = f.stages::add) } }
        runCurrent()
        advanceTimeBy(29_999); runCurrent()
        assertTrue(task.isActive)
        assertEquals(0, f.transfers)
        advanceTimeBy(1); runCurrent()
        assertUploadTimeout(task.await().exceptionOrNull())
        assertEquals(1, f.cancelledUploads)
        assertEquals(listOf(IdentificationStage.UPLOADING), f.stages)
        assertEquals(0, f.downloads)
        assertEquals(0, f.classifications)
        assertTrue(f.original.exists())
    }

    @Test fun successfulUploadDoesNotLeaveALateTimeout() = runTest {
        val f = Fixture().apply { uploadDelay = 5_000 }
        val task = async { f.useCase()(f.original.absolutePath) }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertSame(f.result, task.await())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, f.cancelledUploads)
        assertEquals(1, f.classifications)
    }

    @Test fun networkRecoveryWithinTheOriginalWindowAllowsUpload() = runTest {
        val f = Fixture().apply { online.value = false; uploadDelay = 5_000 }
        val task = async { f.useCase()(f.original.absolutePath) }
        runCurrent(); advanceTimeBy(20_000)
        f.online.value = true
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertSame(f.result, task.await())
        assertEquals(1, f.transfers)
    }

    @Test fun reconnectionDoesNotRestartTheDeadline() = runTest {
        val f = Fixture().apply { online.value = false; uploadDelay = 15_000 }
        val task = async { runCatching { f.useCase()(f.original.absolutePath) } }
        runCurrent(); advanceTimeBy(20_000)
        f.online.value = true
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertUploadTimeout(task.await().exceptionOrNull())
        assertEquals(1, f.cancelledUploads)
        assertEquals(0, f.downloads)
    }

    @Test fun slowOnlineUploadHasTheSameDeadline() = runTest {
        val f = Fixture().apply { uploadDelay = 60_000 }
        val task = async { runCatching { f.useCase()(f.original.absolutePath) } }
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertUploadTimeout(task.await().exceptionOrNull())
        assertEquals(1, f.cancelledUploads)
    }

    @Test fun downloadTimeIsNotCountedAsUploadTime() = runTest {
        val f = Fixture().apply { uploadDelay = 5_000; downloadDelay = 45_000 }
        val task = async { f.useCase()(f.original.absolutePath) }
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertTrue(task.isActive)
        assertEquals(1, f.downloads)
        advanceTimeBy(20_000); runCurrent()
        assertSame(f.result, task.await())
    }

    @Test fun classificationTimeIsNotCountedAsUploadTime() = runTest {
        val f = Fixture().apply { classifyDelay = 45_000 }
        val task = async { f.useCase()(f.original.absolutePath) }
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertTrue(task.isActive)
        advanceTimeBy(15_000); runCurrent()
        assertSame(f.result, task.await())
    }

    @Test fun retryWithAStoredReferenceSkipsUploadAndItsDeadline() = runTest {
        val f = Fixture().apply { online.value = false; downloadDelay = 45_000 }
        val task = async { f.useCase()(f.original.absolutePath, previouslyUploaded = f.cloud) }
        runCurrent(); advanceTimeBy(45_000); runCurrent()
        assertSame(f.result, task.await())
        assertEquals(0, f.uploadCalls)
        assertTrue(f.released.isEmpty())
    }

    @Test fun navigationCancellationIsNotConvertedIntoTimeout() = runTest {
        val f = Fixture().apply { online.value = false }
        var failure: Throwable? = null
        val task = async {
            try { f.useCase()(f.original.absolutePath) }
            catch (e: CancellationException) { failure = e; throw e }
        }
        runCurrent(); advanceTimeBy(10_000)
        task.cancelAndJoin()
        advanceTimeBy(30_000); runCurrent()
        assertTrue(failure is CancellationException)
        assertEquals(1, f.cancelledUploads)
        assertEquals(0, f.downloads)
    }

    @Test fun explicitUploadErrorsFailImmediatelyWithoutNetworkMislabel() = runTest {
        val originalError = IOException("Sign in before uploading")
        val f = Fixture().apply {
            online.value = false
            uploadError = originalError
        }
        val startedAt = testScheduler.currentTime

        val error = runCatching {
            f.useCase()(f.original.absolutePath, onStage = f.stages::add)
        }.exceptionOrNull()

        assertTrue("Expected an upload-stage failure", error is PhotoIdentificationException)
        val failure = error as PhotoIdentificationException
        assertEquals(IdentificationStage.UPLOADING, failure.stage)

        // Coroutine stack-trace recovery can copy exceptions in JVM tests.
        // Validate the error contract, not the identity of the exception instance.
        val cause = failure.cause
        assertEquals(originalError.javaClass, cause?.javaClass)
        assertEquals(originalError.message, cause?.message)
        assertFalse("An explicit error must not become a timeout", cause is PhotoUploadTimeoutException)

        assertEquals("An explicit error must fail without waiting", startedAt, testScheduler.currentTime)
        assertEquals(listOf(IdentificationStage.UPLOADING), f.stages)
        assertEquals(1, f.uploadCalls)
        assertEquals(0, f.transfers)
        assertEquals(0, f.downloads)
        assertEquals(0, f.classifications)
        assertTrue("The local photo must remain available", f.original.exists())
    }

    @Test fun completionAfterDeadlineIsReleasedAndNeverDelivered() = runTest {
        val f = Fixture().apply { ignoreUploadCancellation = true; uploadDelay = 30_001 }
        var delivered = false
        val task = async { runCatching {
            f.useCase()(f.original.absolutePath, onUploaded = { delivered = true })
        } }
        runCurrent(); advanceTimeBy(30_001); runCurrent()
        assertUploadTimeout(task.await().exceptionOrNull())
        assertFalse(delivered)
        assertEquals(listOf(f.cloud), f.released)
        assertEquals(0, f.downloads)
    }

    @Test fun cancellationDuringDeliveryReleasesTheNewCloudObject() = runTest {
        val f = Fixture()
        val failure = runCatching {
            f.useCase()(f.original.absolutePath, onUploaded = { throw CancellationException("left page") })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(listOf(f.cloud), f.released)
        assertTrue(f.original.exists())
    }

    @Test fun retryAfterTimeoutStartsAFreshWindow() = runTest {
        val f = Fixture().apply { online.value = false }
        val first = async { runCatching { f.useCase()(f.original.absolutePath) } }
        runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertUploadTimeout(first.await().exceptionOrNull())
        f.online.value = true
        f.uploadDelay = 20_000
        val second = async { f.useCase()(f.original.absolutePath) }
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertSame(f.result, second.await())
        assertEquals(2, f.uploadCalls)
    }
}
