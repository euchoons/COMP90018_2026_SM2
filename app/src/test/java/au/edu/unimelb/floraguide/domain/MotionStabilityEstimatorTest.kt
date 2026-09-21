package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.domain.sensor.MotionStabilityEstimator
import au.edu.unimelb.floraguide.domain.sensor.MotionStabilityEstimator.Companion.STABLE_THRESHOLD
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the tolerances derived in docs/MOTION_STABILITY_CALIBRATION.md. */
class MotionStabilityEstimatorTest {
    private val motionBudget = ln(1.0 / STABLE_THRESHOLD)

    @Test
    fun steadyStateToleranceForPureTranslationIsAboutHalfAMetrePerSecondSquared() {
        val limit = motionBudget / MotionStabilityEstimator.ACCELERATION_WEIGHT
        assertEquals(0.568, limit, 0.001)
        assertTrue(MotionStabilityEstimator.isStable(settle(accelerationDeviation = limit - 0.01)))
        assertFalse(MotionStabilityEstimator.isStable(settle(accelerationDeviation = limit + 0.01)))
    }

    @Test
    fun steadyStateToleranceForPureRotationIsAboutFortyNineDegreesPerSecond() {
        val limit = motionBudget / MotionStabilityEstimator.ANGULAR_VELOCITY_WEIGHT
        assertEquals(0.851, limit, 0.001)
        assertEquals(48.8, Math.toDegrees(limit), 0.1)
        assertTrue(MotionStabilityEstimator.isStable(settle(angularVelocity = limit - 0.01)))
        assertFalse(MotionStabilityEstimator.isStable(settle(angularVelocity = limit + 0.01)))
    }

    @Test
    fun translationAndRotationShareOneBudget() {
        // Each is well inside its own limit, but together they exceed the threshold.
        val score = settle(accelerationDeviation = 0.35, angularVelocity = 0.4)
        assertFalse(MotionStabilityEstimator.isStable(score))
    }

    @Test
    fun combinedMotionPassesOutToAboutZeroPointFourThreeMetresPerSecondSquared() {
        // With angular deviation at half the acceleration deviation the score collapses to
        // exp(-1.2a), so the gate opens below a = ln(1/0.6) / 1.2.
        val limit = motionBudget / 1.2
        assertEquals(0.426, limit, 0.001)
        assertTrue(MotionStabilityEstimator.isStable(settleHalfAngular(limit - 0.01)))
        assertFalse(MotionStabilityEstimator.isStable(settleHalfAngular(limit + 0.01)))
    }

    @Test
    fun shakyHandIsRejectedOnlyNarrowly() {
        // Illustrative pairs from docs/MOTION_STABILITY_CALIBRATION.md.
        val steadyHand = MotionStabilityEstimator.target(0.2, 0.1)
        val shakyHand = MotionStabilityEstimator.target(0.5, 0.25)
        assertEquals(0.787, steadyHand, 0.001)
        assertEquals(0.549, shakyHand, 0.001)
        assertTrue(MotionStabilityEstimator.isStable(steadyHand))
        assertFalse(MotionStabilityEstimator.isStable(shakyHand))
        // The reject margin is thin; hysteresis is the documented remedy if it flickers.
        assertTrue(STABLE_THRESHOLD - shakyHand < 0.06)
    }

    @Test
    fun captureUnlocksOnTheThirdCalmSample() {
        val estimator = MotionStabilityEstimator()
        repeat(2) { estimator.update(0.0, 0.0) }
        assertFalse(MotionStabilityEstimator.isStable(estimator.score))
        estimator.update(0.0, 0.0)
        assertTrue(MotionStabilityEstimator.isStable(estimator.score))
    }

    @Test
    fun oneJoltIsToleratedButTwoConsecutiveJoltsLockCapture() {
        val estimator = MotionStabilityEstimator()
        repeat(50) { estimator.update(0.0, 0.0) }
        estimator.update(20.0, 5.0)
        assertTrue(MotionStabilityEstimator.isStable(estimator.score))
        estimator.update(20.0, 5.0)
        assertFalse(MotionStabilityEstimator.isStable(estimator.score))
    }

    @Test
    fun missingGyroscopeDefaultNeverReportsStable() {
        // SensorMonitor starts angular velocity at 1 rad/s and never updates it without a
        // gyroscope, which is why ScanScreen switches to manual capture in that case.
        assertFalse(MotionStabilityEstimator.isStable(settle(angularVelocity = 1.0)))
    }

    private fun settleHalfAngular(accelerationDeviation: Double): Double =
        settle(accelerationDeviation, accelerationDeviation / 2.0)

    private fun settle(accelerationDeviation: Double = 0.0, angularVelocity: Double = 0.0): Double {
        val estimator = MotionStabilityEstimator()
        repeat(200) { estimator.update(accelerationDeviation, angularVelocity) }
        return estimator.score
    }
}
