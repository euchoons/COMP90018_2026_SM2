package au.edu.unimelb.floraguide.domain.sensor

import kotlin.math.exp

/**
 * Turns accelerometer and gyroscope motion into a smoothed 0..1 stability score.
 *
 * Kept free of Android types so the calibrated constants can be unit-tested. The derivation of
 * each constant is in docs/MOTION_STABILITY_CALIBRATION.md.
 */
class MotionStabilityEstimator {
    var score: Double = 0.0
        private set

    /**
     * Folds one sample into the score. Called once per sensor event, so the smoothing time
     * constant depends on the combined event rate of the registered sensors.
     *
     * @param accelerationDeviation |‖a‖ − g| in m/s².
     * @param angularVelocity ‖ω‖ in rad/s.
     */
    fun update(accelerationDeviation: Double, angularVelocity: Double): Double {
        val target = target(accelerationDeviation, angularVelocity)
        score = ((1.0 - SMOOTHING_ALPHA) * score + SMOOTHING_ALPHA * target).coerceIn(0.0, 1.0)
        return score
    }

    companion object {
        const val ACCELERATION_WEIGHT = 0.9
        const val ANGULAR_VELOCITY_WEIGHT = 0.6
        const val SMOOTHING_ALPHA = 0.3
        const val STABLE_THRESHOLD = 0.6

        fun target(accelerationDeviation: Double, angularVelocity: Double): Double =
            exp(-(accelerationDeviation * ACCELERATION_WEIGHT + angularVelocity * ANGULAR_VELOCITY_WEIGHT))
                .coerceIn(0.0, 1.0)

        fun isStable(score: Double): Boolean = score >= STABLE_THRESHOLD
    }
}
