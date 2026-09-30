package au.edu.unimelb.floraguide.data.firebase

import android.app.Application
import android.content.Context
import android.net.Uri
import au.edu.unimelb.floraguide.domain.repository.StoredPhoto
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.storage.FileDownloadTask
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Executes the real FirebasePhotoStorage adapter with injected SDK mocks.
 * Does not initialise Firebase, contact a bucket, or evaluate deployed Storage Rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class FirebasePhotoStorageLocalTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var storage: FirebaseStorage
    private lateinit var auth: FirebaseAuth
    private lateinit var user: FirebaseUser
    private lateinit var rootReference: StorageReference
    private lateinit var reference: StorageReference
    private lateinit var uploadTask: UploadTask
    private lateinit var downloadTask: FileDownloadTask
    private lateinit var cacheRoot: File
    private var objectPath = "plant_photos/$UID/fixture.jpg"
    private var sentMetadata: StorageMetadata? = null
    private var sentUri: Uri? = null
    private var downloadedBytes = JPEG

    @Before fun setUp() {
        cacheRoot = temporary.newFolder("app-cache")
        context = mockk()
        storage = mockk()
        auth = mockk()
        user = mockk()
        rootReference = mockk()
        reference = mockk()
        every { context.applicationContext } returns context
        every { context.cacheDir } returns cacheRoot
        every { user.uid } returns UID
        every { auth.currentUser } returns user
        every { storage.reference } returns rootReference
        every { rootReference.bucket } returns BUCKET
        every { rootReference.child(any()) } answers {
            objectPath = firstArg()
            reference
        }
        every { reference.path } answers { "/$objectPath" }
        every { reference.bucket } returns BUCKET
        every { reference.toString() } answers { "gs://$BUCKET/$objectPath" }
        every { storage.getReferenceFromUrl(any()) } returns reference
        uploadTask = completedUpload()
        downloadTask = completedDownload()
        every { reference.putFile(any<Uri>(), any<StorageMetadata>()) } answers {
            sentUri = firstArg()
            sentMetadata = secondArg()
            uploadTask
        }
        every { reference.getFile(any<File>()) } answers {
            firstArg<File>().writeBytes(downloadedBytes)
            downloadTask
        }
    }

    // FB-01
    @Test fun signedOutUploadFailsBeforeAnyStorageRequest() {
        every { auth.currentUser } returns null
        val error = assertThrows(IOException::class.java) {
            runBlocking { adapter().uploadPhoto(photo().absolutePath) }
        }
        assertTrue(error.message.orEmpty().contains("Sign in"))
        verify(exactly = 0) { storage.reference }
        verify(exactly = 0) { reference.putFile(any<Uri>(), any<StorageMetadata>()) }
    }

    // FB-02
    @Test fun expectedAccountMismatchFailsBeforeTransfer() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { adapter(expectedUserId = "different-user").uploadPhoto(photo().absolutePath) }
        }
        verify(exactly = 0) { storage.reference }
    }

    // FB-03
    @Test fun missingLocalFileIsRejectedBeforeUpload() {
        val missing = File(temporary.root, "missing.jpg")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter().uploadPhoto(missing.absolutePath) }
        }
        verifyNoUpload()
    }

    // FB-04
    @Test fun emptyLocalFileIsRejectedBeforeUpload() {
        val empty = temporary.newFile("empty.jpg")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter().uploadPhoto(empty.absolutePath) }
        }
        verifyNoUpload()
    }

    // FB-05
    @Test fun photoAboveTwentyMiBLimitIsRejectedBeforeUpload() {
        val oversized = temporary.newFile("oversized.jpg")
        RandomAccessFile(oversized, "rw").use { it.setLength(MAX_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter().uploadPhoto(oversized.absolutePath) }
        }
        verifyNoUpload()
    }

    // FB-06
    @Test fun unsupportedHeaderIsRejectedEvenWhenExtensionIsJpeg() {
        val invalid = photo("not-an-image.jpg", "plain text".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter().uploadPhoto(invalid.absolutePath) }
        }
        verifyNoUpload()
    }

    // FB-07
    @Test fun jpegUploadUsesUidDigestAndPrivateStorageReference() = runBlocking<Unit> {
        val original = photo()
        val result = adapter().uploadPhoto(original.absolutePath)
        val digest = sha256(JPEG)
        assertEquals("plant_photos/$UID/$digest.jpg", objectPath)
        assertEquals("/$objectPath", result.storagePath)
        assertEquals("gs://$BUCKET/$objectPath", result.gsUri)
        assertEquals(JPEG.size.toLong(), result.sizeBytes)
        assertEquals(digest, result.sha256)
        assertEquals("image/jpeg", result.contentType)
        assertEquals("image/jpeg", sentMetadata!!.contentType)
        assertEquals(digest, sentMetadata!!.getCustomMetadata("sha256"))
        assertEquals(Uri.fromFile(original), sentUri)
        assertTrue(original.exists())
        verify(exactly = 1) { reference.putFile(any<Uri>(), any<StorageMetadata>()) }
    }

    // FB-08
    @Test fun pngHeaderChoosesPngMetadataAndObjectSuffix() = runBlocking<Unit> {
        // Deliberately misleading extension: Storage uses the signature, not the extension.
        val result = adapter().uploadPhoto(photo("signature.jpg", PNG).absolutePath)
        assertEquals("image/png", result.contentType)
        assertEquals("image/png", sentMetadata!!.contentType)
        assertEquals("plant_photos/$UID/${sha256(PNG)}.png", objectPath)
    }

    // FB-09
    @Test fun sameContentWithDifferentNamesUsesSameObjectPath() = runBlocking<Unit> {
        val store = adapter()
        val first = store.uploadPhoto(photo("first.jpg").absolutePath)
        val second = store.uploadPhoto(photo("second.jpg").absolutePath)
        assertEquals(first.gsUri, second.gsUri)
        assertEquals(first.sha256, second.sha256)
        verify(exactly = 2) { reference.putFile(any<Uri>(), any<StorageMetadata>()) }
    }

    // FB-10
    @Test fun accountChangeAfterUploadIsNotReportedAsSuccess() {
        val changed = mockk<FirebaseUser>()
        every { changed.uid } returns "changed-user"
        // Initial check, pre-upload check, then post-upload check.
        every { auth.currentUser } returnsMany listOf(user, user, changed)
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { adapter().uploadPhoto(photo().absolutePath) }
        }
        assertTrue(error.message.orEmpty().contains("Account changed"))
        verify(exactly = 1) { reference.putFile(any<Uri>(), any<StorageMetadata>()) }
    }

    // FB-11
    @Test fun downloadFromDifferentBucketIsRejectedBeforeMetadata() {
        every { reference.bucket } returns "other-bucket"
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter().downloadPhoto(stored().copy(gsUri = "gs://other-bucket/$objectPath")) }
        }
        verify(exactly = 0) { reference.metadata }
        verifyNoDownload()
    }

    // FB-12
    @Test fun similarButDifferentUidPathIsRejectedBeforeMetadata() {
        objectPath = "plant_photos/${UID}-other/fixture.jpg"
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter().downloadPhoto(stored(path = objectPath)) }
        }
        verify(exactly = 0) { reference.metadata }
        verifyNoDownload()
    }

    // FB-13
    @Test fun invalidStoredSizeIsRejectedBeforeMetadata() {
        for (size in listOf(0L, MAX_BYTES + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { adapter().downloadPhoto(stored().copy(sizeBytes = size)) }
            }
        }
        verify(exactly = 0) { reference.metadata }
        verifyNoDownload()
    }

    // FB-14
    @Test fun changedRemoteSizeIsRejectedBeforeFileDownload() {
        metadataSize(JPEG.size.toLong() + 1)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter().downloadPhoto(stored()) }
        }
        verifyNoDownload()
        assertNoTemporaryDownloads()
    }

    // FB-15
    @Test fun successfulDownloadReturnsVerifiedTemporaryBytes() = runBlocking<Unit> {
        metadataSize(JPEG.size.toLong())
        val file = adapter().downloadPhoto(stored())
        try {
            assertTrue(file.exists())
            assertArrayEquals(JPEG, file.readBytes())
            assertEquals(File(cacheRoot, "plantnet-cloud").canonicalFile, file.parentFile.canonicalFile)
            assertTrue(file.name.startsWith("from-firebase-"))
            verify(exactly = 1) { reference.getFile(any<File>()) }
        } finally {
            // The successful caller owns cache cleanup.
            file.delete()
        }
    }

    // FB-16
    @Test fun checksumMismatchRemovesTemporaryDownload() {
        metadataSize(JPEG.size.toLong())
        downloadedBytes = JPEG.copyOf().also { it[it.lastIndex] = 0x43 }
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter().downloadPhoto(stored()) }
        }
        assertTrue(error.message.orEmpty().contains("integrity check"))
        assertNoTemporaryDownloads()
    }

    // FB-17
    @Test fun truncatedDownloadRemovesTemporaryFile() {
        metadataSize(JPEG.size.toLong())
        downloadedBytes = JPEG.copyOf(JPEG.size - 1)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { adapter().downloadPhoto(stored()) }
        }
        assertNoTemporaryDownloads()
    }

    // FB-18
    @Test fun deniedUploadMapsSdkErrorToActionableIOException() {
        uploadTask = completedUpload(error = storageFailure(StorageException.ERROR_NOT_AUTHORIZED))
        val error = assertThrows(IOException::class.java) {
            runBlocking { adapter().uploadPhoto(photo().absolutePath) }
        }
        assertTrue(error.message.orEmpty().contains("UID-based Storage rules"))
        assertTrue(error.message.orEmpty().contains(StorageException.ERROR_NOT_AUTHORIZED.toString()))
    }

    // FB-19
    @Test fun failedDownloadMapsErrorAndRemovesTemporaryFile() {
        metadataSize(JPEG.size.toLong())
        downloadTask = completedDownload(error = storageFailure(StorageException.ERROR_OBJECT_NOT_FOUND))
        val error = assertThrows(IOException::class.java) {
            runBlocking { adapter().downloadPhoto(stored()) }
        }
        assertTrue(error.message.orEmpty().contains("no longer exists"))
        assertNoTemporaryDownloads()
    }

    // FB-20
    @Test fun cancelledSdkUploadPropagatesCancellationAndCallsCancel() {
        // This checks an SDK task already marked cancelled, not socket-level cancellation timing.
        uploadTask = completedUpload(cancelled = true)
        assertThrows(CancellationException::class.java) {
            runBlocking { adapter().uploadPhoto(photo().absolutePath) }
        }
        verify(exactly = 1) { uploadTask.cancel() }
    }


    // FB-21: Verify Cloud Storage photo deletion
    @Test fun deletePhotoRemovesRemoteReferenceSuccessfully() = runBlocking<Unit> {
        val task = mockk<com.google.android.gms.tasks.Task<Void>>()
        every { task.isComplete } returns true
        every { task.exception } returns null
        every { task.isCanceled } returns false
        every { task.result } returns null
        every { reference.delete() } returns task

        adapter().deletePhoto("gs://$BUCKET/$objectPath")
        verify(exactly = 1) { reference.delete() }
    }

    // FB-22: Verify idempotent handling when deleting an already deleted object
    @Test fun deletePhotoIgnoresObjectNotFoundException() = runBlocking<Unit> {
        val task = mockk<com.google.android.gms.tasks.Task<Void>>()
        val notFoundError = mockk<StorageException>()
        every { notFoundError.errorCode } returns StorageException.ERROR_OBJECT_NOT_FOUND
        every { task.isComplete } returns true
        every { task.exception } returns notFoundError
        every { task.isCanceled } returns false
        every { reference.delete() } returns task

        adapter().deletePhoto("gs://$BUCKET/$objectPath")
        verify(exactly = 1) { reference.delete() }
    }
    private fun adapter(expectedUserId: String? = null) =
        FirebasePhotoStorage(context, storage, auth, expectedUserId)

    private fun photo(name: String = "capture.jpg", bytes: ByteArray = JPEG): File =
        temporary.newFile(name).apply { writeBytes(bytes) }

    private fun stored(path: String = objectPath) = StoredPhoto(
        storagePath = "/$path", gsUri = "gs://$BUCKET/$path", sizeBytes = JPEG.size.toLong(),
        sha256 = sha256(JPEG), contentType = "image/jpeg",
    )

    private fun metadataSize(size: Long) {
        val metadata = mockk<StorageMetadata>()
        every { metadata.sizeBytes } returns size
        every { reference.metadata } returns Tasks.forResult(metadata)
    }

    private fun storageFailure(code: Int): StorageException = mockk<StorageException>().also {
        every { it.errorCode } returns code
    }

    // Configure the public Task contract consumed by kotlinx-coroutines Task.await().
    private fun completedUpload(error: Exception? = null, cancelled: Boolean = false): UploadTask =
        mockk<UploadTask>().also {
            every { it.isComplete } returns true
            every { it.exception } returns error
            every { it.isCanceled } returns cancelled
            every { it.result } returns mockk<UploadTask.TaskSnapshot>()
            every { it.cancel() } returns true
        }

    private fun completedDownload(error: Exception? = null): FileDownloadTask =
        mockk<FileDownloadTask>().also {
            every { it.isComplete } returns true
            every { it.exception } returns error
            every { it.isCanceled } returns false
            every { it.result } returns mockk<FileDownloadTask.TaskSnapshot>()
            every { it.cancel() } returns true
        }

    private fun verifyNoUpload() {
        verify(exactly = 0) { reference.putFile(any<Uri>(), any<StorageMetadata>()) }
    }

    private fun verifyNoDownload() {
        verify(exactly = 0) { reference.getFile(any<File>()) }
    }

    private fun assertNoTemporaryDownloads() {
        assertTrue(File(cacheRoot, "plantnet-cloud").listFiles().orEmpty().isEmpty())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val UID = "local-test-user"
        const val BUCKET = "local-test-bucket"
        const val MAX_BYTES = 20L * 1024L * 1024L
        // Signature fixtures test the existing header checks, not image decoding.
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42)
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
