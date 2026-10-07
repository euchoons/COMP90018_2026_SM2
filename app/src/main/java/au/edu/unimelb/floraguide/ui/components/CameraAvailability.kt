package au.edu.unimelb.floraguide.ui.components

import androidx.camera.core.CameraState

/** What the preview can tell the user about the camera, from the state CameraX reports. */
internal enum class CameraAvailability { STARTING, OPEN, IN_USE, UNAVAILABLE }

/**
 * Binding only requests the camera: it can still fail to open, or another app can take it away.
 * CameraX reopens it by itself once it is free, so only an open camera may enable the shutter.
 */
internal fun cameraAvailability(type: CameraState.Type, errorCode: Int?): CameraAvailability = when (errorCode) {
    CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> CameraAvailability.IN_USE
    null -> if (type == CameraState.Type.OPEN) CameraAvailability.OPEN else CameraAvailability.STARTING
    else -> CameraAvailability.UNAVAILABLE
}
