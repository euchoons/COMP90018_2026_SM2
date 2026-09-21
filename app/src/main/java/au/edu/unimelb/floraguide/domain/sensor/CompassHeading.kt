package au.edu.unimelb.floraguide.domain.sensor

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Below this horizontal share the camera points too steeply up or down (more than 60° from the
 * horizon) for its compass direction to be well conditioned.
 */
private const val MIN_CAMERA_HORIZONTAL_COMPONENT = 0.5f

/**
 * Magnetic compass heading, in degrees clockwise from magnetic north, for an observation photo.
 *
 * [rotationMatrix] is the 3x3 row-major matrix from `SensorManager.getRotationMatrix`; its
 * columns are the device X, Y and Z axes expressed in world (east, north, up) coordinates.
 *
 * `SensorManager.getOrientation` reports the direction of the device's top edge (+Y). That is
 * undefined when the phone is held upright to photograph a plant, because +Y then points at the
 * sky. The rear camera looks along −Z, so its heading is used whenever it is near horizontal,
 * and the top edge is used for top-down shots where the phone lies roughly flat.
 */
fun observationHeadingDegrees(rotationMatrix: FloatArray): Float {
    require(rotationMatrix.size >= 9) { "Expected a 3x3 rotation matrix" }
    val cameraEast = -rotationMatrix[2]
    val cameraNorth = -rotationMatrix[5]
    val useCamera = sqrt(cameraEast * cameraEast + cameraNorth * cameraNorth) >=
        MIN_CAMERA_HORIZONTAL_COMPONENT
    val east = if (useCamera) cameraEast else rotationMatrix[1]
    val north = if (useCamera) cameraNorth else rotationMatrix[4]
    val degrees = Math.toDegrees(atan2(east, north).toDouble()).toFloat()
    val normalised = (degrees + 360f) % 360f
    return if (normalised >= 360f) 0f else normalised
}
