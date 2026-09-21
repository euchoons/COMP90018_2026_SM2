package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.domain.sensor.observationHeadingDegrees
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class CompassHeadingTest {
    // World axes used by SensorManager.getRotationMatrix.
    private val east = floatArrayOf(1f, 0f, 0f)
    private val west = floatArrayOf(-1f, 0f, 0f)
    private val north = floatArrayOf(0f, 1f, 0f)
    private val south = floatArrayOf(0f, -1f, 0f)
    private val up = floatArrayOf(0f, 0f, 1f)

    @Test
    fun flatPhoneUsesTheTopEdge() {
        assertEquals(0f, observationHeadingDegrees(rotation(x = east, y = north, z = up)), 0.01f)
        assertEquals(90f, observationHeadingDegrees(rotation(x = south, y = east, z = up)), 0.01f)
    }

    @Test
    fun uprightPortraitPhoneUsesTheRearCamera() {
        // Screen faces the user, so +Z points back at them and the camera faces the other way.
        assertEquals(0f, observationHeadingDegrees(rotation(x = east, y = up, z = south)), 0.01f)
        assertEquals(90f, observationHeadingDegrees(rotation(x = south, y = up, z = west)), 0.01f)
        assertEquals(180f, observationHeadingDegrees(rotation(x = west, y = up, z = north)), 0.01f)
        assertEquals(270f, observationHeadingDegrees(rotation(x = north, y = up, z = east)), 0.01f)
    }

    @Test
    fun uprightLandscapePhoneStillUsesTheRearCamera() {
        // Right edge points up, top edge points west, camera faces north.
        assertEquals(0f, observationHeadingDegrees(rotation(x = up, y = west, z = south)), 0.01f)
    }

    @Test
    fun uprightHeadingIsStableUnderSmallTilt() {
        // Camera facing east, pitched 5° towards the ground. The top-edge azimuth that
        // getOrientation reports is dominated by noise here; the camera heading is not.
        val tilt = Math.toRadians(5.0)
        val y = floatArrayOf(sin(tilt).toFloat(), 0f, cos(tilt).toFloat())
        val z = floatArrayOf(-cos(tilt).toFloat(), 0f, sin(tilt).toFloat())
        assertEquals(90f, observationHeadingDegrees(rotation(x = south, y = y, z = z)), 0.01f)
    }

    @Test
    fun cameraTiltedFortyFiveDegreesDownKeepsTheCameraHeading() {
        val c = cos(Math.toRadians(45.0)).toFloat()
        // Camera faces south-down; top edge faces south-up.
        val y = floatArrayOf(0f, -c, c)
        val z = floatArrayOf(0f, c, c)
        assertEquals(180f, observationHeadingDegrees(rotation(x = west, y = y, z = z)), 0.01f)
    }

    /** Builds the row-major matrix whose columns are the device axes in world coordinates. */
    private fun rotation(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray = floatArrayOf(
        x[0], y[0], z[0],
        x[1], y[1], z[1],
        x[2], y[2], z[2],
    )
}
