package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.data.firebase.PhotoContentValidation
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoCleanupUseCase
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoRecord
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoRecordStore
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoRegistry
import au.edu.unimelb.floraguide.domain.usecase.PendingPhotoState
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** Android-independent assertions, run as parameterized JUnit cases by Issue14CoreTest. */
internal object Issue14CoreContract {
    fun cases(): List<Pair<String, () -> Unit>> = listOf(
        "PNG supports one-byte reads" to { check(PhotoContentValidation.contentType(shortReads(PNG, 1)) == "image/png") },
        "PNG supports uneven reads" to { check(PhotoContentValidation.contentType(shortReads(PNG, 3)) == "image/png") },
        "Zero-byte reads make progress" to {
            val stream = object : ByteArrayInputStream(PNG) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = 0
            }
            check(PhotoContentValidation.contentType(stream) == "image/png")
        },
        "JPEG supports a short signature fixture" to { check(PhotoContentValidation.contentType(ByteArrayInputStream(JPEG)) == "image/jpeg") },
        "Truncated PNG is rejected" to { expect<IllegalArgumentException> { PhotoContentValidation.contentType(ByteArrayInputStream(PNG.copyOf(7))) } },
        "Empty input is rejected" to { expect<IllegalArgumentException> { PhotoContentValidation.contentType(ByteArrayInputStream(byteArrayOf())) } },
        "Unrelated bytes are rejected" to { expect<IllegalArgumentException> { PhotoContentValidation.contentType(ByteArrayInputStream("not an image".toByteArray())) } },
        "Active scan is not cleanup eligible" to { fixture().apply { register(); check(registry.candidates(UID).isEmpty()) } },
        "Abandoned completed scan is eligible" to { fixture().apply { register(); registry.abandon(URI); check(registry.candidates(UID).single().gsUri == URI) } },
        "In-flight cancelled upload is protected until terminal" to {
            fixture().apply {
                registry.registerUpload(UID, URI)
                registry.abandon(URI)
                check(registry.candidates(UID).isEmpty())
                check(registry.hasQueuedCleanup(UID))
                registry.uploadFinished(URI)
                check(registry.candidates(UID).size == 1)
            }
        },
        "Saving blocks navigation cleanup" to {
            fixture().apply {
                register(); registry.beginSave(URI); registry.abandon(URI)
                check(registry.snapshot().single().state == PendingPhotoState.SAVING_ABANDONED)
                check(registry.candidates(UID).isEmpty())
            }
        },
        "Successful save transfers ownership" to {
            fixture().apply { register(); registry.beginSave(URI); registry.saved(URI); registry.abandon(URI); check(registry.snapshot().isEmpty()) }
        },
        "Save failure keeps current photo retryable" to {
            fixture().apply { register(); registry.beginSave(URI); registry.saveFailed(URI); check(registry.snapshot().single().state == PendingPhotoState.ACTIVE) }
        },
        "Abandoned failed save becomes eligible" to {
            fixture().apply { register(); registry.beginSave(URI); registry.abandon(URI); registry.saveFailed(URI); check(registry.candidates(UID).size == 1) }
        },
        "Legacy shared digest path is never registered or deleted" to {
            fixture().apply {
                val legacy = "gs://test-bucket/plant_photos/$UID/${"a".repeat(64)}.jpg"
                expect<IllegalArgumentException> { registry.registerUpload(UID, legacy) }
                registry.abandon(legacy)
                check(registry.snapshot().isEmpty())
            }
        },
        "Another owner cannot register the reference" to { fixture().apply { expect<IllegalArgumentException> { registry.registerUpload("other-user", URI) } } },
        "Journal failure prevents an untracked upload" to {
            fixture().apply {
                store.failWrites = true
                expect<IOException> { registry.registerUpload(UID, URI) }
                check(registry.snapshot().isEmpty())
            }
        },
        "Restart recovers a formerly active scan" to {
            fixture().apply {
                register()
                val restarted = PendingPhotoRegistry(store, "new-process")
                check(restarted.candidates(UID).single().gsUri == URI)
            }
        },
        "Cleanup is idempotent" to {
            runBlocking {
                val f = fixture(); f.register(); f.registry.abandon(URI)
                var deletions = 0
                val cleanup = f.cleanup(delete = { deletions++ })
                check(!cleanup(UID)); check(!cleanup(UID))
                check(deletions == 1 && f.registry.snapshot().isEmpty())
            }
        },
        "Saved DB reference survives restart cleanup" to {
            runBlocking {
                val f = fixture(); f.register(); f.registry.beginSave(URI)
                val restarted = PendingPhotoRegistry(f.store, "new-process")
                var deletions = 0
                val cleanup = PendingPhotoCleanupUseCase(restarted, { UID }, { _, _ -> true }, { _, _ -> deletions++ })
                check(!cleanup(UID))
                check(deletions == 0 && restarted.snapshot().isEmpty())
            }
        },
        "Network failure preserves the deletion queue" to {
            runBlocking {
                val f = fixture(); f.register(); f.registry.abandon(URI)
                check(f.cleanup(delete = { throw IOException("offline") })(UID))
                check(f.registry.snapshot().size == 1)
            }
        },
        "Wrong account does not delete or forget pending work" to {
            runBlocking {
                val f = fixture(); f.register(); f.registry.abandon(URI)
                var deletes = 0
                check(!f.cleanup(user = { "other" }, delete = { deletes++ })(UID))
                check(deletes == 0 && f.registry.snapshot().size == 1)
            }
        },
        "Account changes during DB lookup do not delete" to {
            runBlocking {
                val f = fixture(); f.register(); f.registry.abandon(URI)
                var user = UID
                var deletes = 0
                val cleanup = PendingPhotoCleanupUseCase(f.registry, { user }, { _, _ -> user = "other"; false }, { _, _ -> deletes++ })
                check(!cleanup(UID))
                check(deletes == 0 && f.registry.snapshot().size == 1)
            }
        },
        "Cancellation is propagated and releases the claim" to {
            runBlocking {
                val f = fixture(); f.register(); f.registry.abandon(URI)
                var caught = false
                try { f.cleanup(delete = { throw CancellationException("cancelled") })(UID) } catch (_: CancellationException) { caught = true }
                check(caught && f.registry.candidates(UID).size == 1)
            }
        },
        "Cleanup scheduling failure does not lose the journal" to {
            val store = MemoryStore()
            val registry = PendingPhotoRegistry(store, "process", scheduleCleanup = { throw IllegalStateException("scheduler unavailable") })
            registry.registerUpload(UID, URI); registry.uploadFinished(URI); registry.abandon(URI)
            check(store.readAll().single().gsUri == URI)
            check(registry.snapshot().single().state == PendingPhotoState.ABANDONED)
        },
        "Rejected reference is dropped instead of retried" to {
            runBlocking {
                val f = fixture(); f.register(); f.registry.abandon(URI)
                check(!f.cleanup(delete = { throw IllegalArgumentException("Unexpected Storage bucket.") })(UID))
                check(f.registry.snapshot().isEmpty())
            }
        },
        "Another cleanup cannot claim the same object" to {
            fixture().apply { register(); registry.abandon(URI); check(registry.claim(URI)); check(!registry.claim(URI)); registry.releaseClaim(URI); check(registry.claim(URI)) }
        },
        "Failed journal removal remains recoverable" to {
            runBlocking {
                val f = fixture(); f.register(); f.registry.abandon(URI); f.store.failRemovals = true
                check(f.cleanup()(UID))
                check(f.registry.snapshot().size == 1)
                f.store.failRemovals = false
                check(!f.cleanup()(UID))
            }
        },
    )

    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42)
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val UID = "test-user"
    private val URI = "gs://test-bucket/plant_photos/$UID/scan-00000000-0000-0000-0000-000000000001-${"a".repeat(64)}.jpg"

    private fun shortReads(bytes: ByteArray, maximum: Int): InputStream = object : ByteArrayInputStream(bytes) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, maximum))
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        check(error is T) { "Expected ${T::class.java.simpleName}, got $error" }
    }

    private fun fixture() = Fixture()
    private class Fixture {
        val store = MemoryStore()
        val registry = PendingPhotoRegistry(store, "test-process")
        fun register() { registry.registerUpload(UID, URI); registry.uploadFinished(URI) }
        fun cleanup(user: () -> String? = { UID }, delete: suspend () -> Unit = {}) =
            PendingPhotoCleanupUseCase(registry, user, { _, _ -> false }, { _, _ -> delete() })
    }

    private class MemoryStore : PendingPhotoRecordStore {
        private val records = mutableMapOf<String, PendingPhotoRecord>()
        var failWrites = false
        var failRemovals = false
        override fun readAll() = records.values.toList()
        override fun put(record: PendingPhotoRecord) {
            if (failWrites) throw IOException("disk full")
            records[record.gsUri] = record
        }
        override fun remove(gsUri: String) {
            if (failRemovals) throw IOException("disk full")
            records.remove(gsUri)
        }
    }
}
