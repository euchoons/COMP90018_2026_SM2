package au.edu.unimelb.floraguide.domain.privacy

/** Consent belongs to one photo, one shutter event and one account session. */
data class PendingPhotoConsent(
    val captureId: String,
    val ownerId: String,
    val photoPath: String,
)

/** In-memory only: accepting one photo never enables automatic upload of future photos. */
class PhotoConsentGate {
    private var pending: PendingPhotoConsent? = null
    private var approved: PendingPhotoConsent? = null
    // Protect against a delayed duplicate camera callback deleting an already accepted file.
    // Entries live only as long as the owning ViewModel, not in preferences or on disk.
    private val acceptedPaths = mutableSetOf<String>()

    @Synchronized
    fun offer(request: PendingPhotoConsent) {
        require(request.captureId.isNotBlank()) { "A capture ID is required." }
        require(request.ownerId.isNotBlank()) { "An owner ID is required." }
        require(request.photoPath.isNotBlank()) { "A photo path is required." }
        check(pending == null) { "Resolve the current photo consent request first." }
        approved = null
        pending = request
    }

    /** Consumes the pending request once. Repeated/stale clicks never start a second upload. */
    @Synchronized
    fun approve(captureId: String, ownerId: String): PendingPhotoConsent? {
        val request = pending ?: return null
        if (request.captureId != captureId || request.ownerId != ownerId) return null
        pending = null
        approved = request
        acceptedPaths += request.photoPath
        return request
    }

    @Synchronized
    fun isApproved(captureId: String, ownerId: String, photoPath: String): Boolean =
        approved == PendingPhotoConsent(captureId, ownerId, photoPath)

    @Synchronized
    fun wasEverApproved(photoPath: String): Boolean = photoPath in acceptedPaths

    /** Returns only a never-approved pending photo; accepted photos are never cleanup targets. */
    @Synchronized
    fun reset(): PendingPhotoConsent? {
        val abandoned = pending
        pending = null
        approved = null
        return abandoned
    }
}
