package au.edu.unimelb.floraguide.domain.privacy

import org.junit.Assert.*
import org.junit.Test

class PhotoConsentGateTest {
    private val first = PendingPhotoConsent("capture-a", "user-a", "/photos/a.jpg")

    @Test fun offeringPhotoDoesNotGrantConsent() {
        val gate = PhotoConsentGate()
        gate.offer(first)
        assertFalse(gate.isApproved(first.captureId, first.ownerId, first.photoPath))
    }

    @Test fun approvalAllowsOnlyTheExactCaptureOwnerAndPath() {
        val gate = PhotoConsentGate()
        gate.offer(first)
        assertEquals(first, gate.approve(first.captureId, first.ownerId))
        assertTrue(gate.isApproved(first.captureId, first.ownerId, first.photoPath))
        assertFalse(gate.isApproved("capture-b", first.ownerId, first.photoPath))
        assertFalse(gate.isApproved(first.captureId, "user-b", first.photoPath))
        assertFalse(gate.isApproved(first.captureId, first.ownerId, "/photos/b.jpg"))
    }

    @Test fun staleOrWrongOwnerApprovalDoesNotConsumeTheRequest() {
        val gate = PhotoConsentGate()
        gate.offer(first)
        assertNull(gate.approve("capture-b", first.ownerId))
        assertNull(gate.approve(first.captureId, "user-b"))
        assertEquals(first, gate.approve(first.captureId, first.ownerId))
    }

    @Test fun approvalIsConsumedOnceButSamePhotoRetriesRemainAllowed() {
        val gate = PhotoConsentGate()
        gate.offer(first)
        gate.approve(first.captureId, first.ownerId)
        assertNull(gate.approve(first.captureId, first.ownerId))
        repeat(3) { assertTrue(gate.isApproved(first.captureId, first.ownerId, first.photoPath)) }
    }

    @Test fun resetReturnsOnlyAnUnapprovedCleanupTarget() {
        val gate = PhotoConsentGate()
        gate.offer(first)
        assertEquals(first, gate.reset())
        assertNull(gate.reset())
        assertNull(gate.approve(first.captureId, first.ownerId))
        assertFalse(gate.isApproved(first.captureId, first.ownerId, first.photoPath))
    }

    @Test fun resetRevokesLivePermissionWithoutReturningAnAcceptedPhoto() {
        val gate = PhotoConsentGate()
        gate.offer(first)
        gate.approve(first.captureId, first.ownerId)
        assertNull(gate.reset())
        assertFalse(gate.isApproved(first.captureId, first.ownerId, first.photoPath))
        assertTrue(gate.wasEverApproved(first.photoPath))
    }

    @Test fun aNewCaptureNeedsNewConsent() {
        val gate = PhotoConsentGate()
        gate.offer(first)
        gate.approve(first.captureId, first.ownerId)
        val second = first.copy(captureId = "capture-b", photoPath = "/photos/b.jpg")
        gate.offer(second)
        assertFalse(gate.isApproved(first.captureId, first.ownerId, first.photoPath))
        assertFalse(gate.isApproved(second.captureId, second.ownerId, second.photoPath))
        assertEquals(second, gate.approve(second.captureId, second.ownerId))
    }

    @Test(expected = IllegalStateException::class)
    fun anUnresolvedRequestCannotBeSilentlyReplaced() {
        val gate = PhotoConsentGate()
        gate.offer(first)
        gate.offer(first.copy(captureId = "capture-b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankPhotoPathIsRejected() {
        PhotoConsentGate().offer(first.copy(photoPath = ""))
    }
}
