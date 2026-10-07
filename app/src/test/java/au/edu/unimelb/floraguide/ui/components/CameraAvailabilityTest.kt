package au.edu.unimelb.floraguide.ui.components

import androidx.camera.core.CameraState
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraAvailabilityTest {
    @Test
    fun onlyAnOpenCameraWithoutErrorsEnablesCapture() {
        assertEquals(CameraAvailability.OPEN, cameraAvailability(CameraState.Type.OPEN, null))
        for (type in listOf(CameraState.Type.PENDING_OPEN, CameraState.Type.OPENING, CameraState.Type.CLOSING, CameraState.Type.CLOSED)) {
            assertEquals(CameraAvailability.STARTING, cameraAvailability(type, null))
        }
    }

    @Test
    fun anotherAppHoldingTheCameraIsReportedAsInUse() {
        for (code in listOf(CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE)) {
            assertEquals(CameraAvailability.IN_USE, cameraAvailability(CameraState.Type.PENDING_OPEN, code))
        }
    }

    @Test
    fun otherErrorsMakeTheCameraUnavailableEvenWhenOpen() {
        assertEquals(CameraAvailability.UNAVAILABLE, cameraAvailability(CameraState.Type.CLOSED, CameraState.ERROR_CAMERA_DISABLED))
        assertEquals(CameraAvailability.UNAVAILABLE, cameraAvailability(CameraState.Type.CLOSED, CameraState.ERROR_CAMERA_FATAL_ERROR))
        assertEquals(CameraAvailability.UNAVAILABLE, cameraAvailability(CameraState.Type.OPEN, CameraState.ERROR_STREAM_CONFIG))
    }
}
