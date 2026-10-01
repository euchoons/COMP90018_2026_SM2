package au.edu.unimelb.floraguide.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Deletes only journalled, abandoned, unreferenced objects belonging to the current account. */
class PendingPhotoCleanupUseCase(
    private val registry: PendingPhotoRegistry,
    private val currentUserId: () -> String?,
    private val isReferenced: suspend (String, String) -> Boolean,
    private val deletePhoto: suspend (String, String) -> Unit,
    private val reportFailure: (Exception) -> Unit = {},
) {
    /** True requests another attempt. A different account leaves the journal untouched. */
    suspend operator fun invoke(userId: String): Boolean {
        if (currentUserId() != userId) return false
        var retry = false
        for (entry in registry.candidates(userId)) {
            currentCoroutineContext().ensureActive()
            if (currentUserId() != userId) return false
            if (!registry.claim(entry.gsUri)) continue
            try {
                val referenced = isReferenced(userId, entry.gsUri)
                currentCoroutineContext().ensureActive()
                if (currentUserId() != userId) return false
                if (!referenced) deletePhoto(userId, entry.gsUri)
                currentCoroutineContext().ensureActive()
                // On crash after deletion, the journal makes the next attempt idempotent.
                registry.saved(entry.gsUri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: IllegalArgumentException) {
                // Another bucket or owner: no retry can delete it, so stop tracking it.
                registry.saved(entry.gsUri)
                reportFailure(rejected)
            } catch (error: Exception) {
                retry = true
                reportFailure(error)
            } finally {
                registry.releaseClaim(entry.gsUri)
            }
        }
        return retry || registry.hasQueuedCleanup(userId)
    }
}
