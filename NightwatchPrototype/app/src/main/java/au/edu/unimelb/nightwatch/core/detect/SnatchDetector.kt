package au.edu.unimelb.nightwatch.core.detect

import kotlin.math.abs

/**
 * Detects the phone being pulled out of the user's hand.
 *
 * The signature is a *co-occurrence*, not a single large reading. Acceleration
 * alone spikes whenever the phone is pocketed or set down hard; rotation alone
 * spikes every time it is turned to landscape. A snatch produces both at once:
 *
 *   - high jerk (rate of change of acceleration) as the phone is yanked away, and
 *   - high angular velocity as it twists out of the grip,
 *
 * within a short co-occurrence window. Requiring both inside [coincidenceWindowMs]
 * removes almost all of the everyday false positives.
 *
 * Unlike [FallDetector] this deliberately does NOT wait for stillness: after a
 * snatch the phone is usually moving fast, away from the user.
 */
class SnatchDetector(
    private val jerkThresholdGPerS: Float = 22f,
    private val angularThresholdRadPerS: Float = 5.5f,
    private val coincidenceWindowMs: Long = 400,
    private val cooldownMs: Long = 8_000
) {

    private var lastMagnitudeG = 1f
    private var lastSampleAtMs = 0L

    private var jerkSpikeAtMs = 0L
    private var rotationSpikeAtMs = 0L
    private var lastFiredAtMs = 0L

    var lastJerkGPerS: Float = 0f
        private set

    /** Feed accelerometer magnitude. Returns true when both conditions coincide. */
    fun onAccelerometer(magnitudeG: Float, nowMs: Long): Boolean {
        if (lastSampleAtMs != 0L) {
            val dt = (nowMs - lastSampleAtMs) / 1000f
            if (dt > 0f) {
                lastJerkGPerS = abs(magnitudeG - lastMagnitudeG) / dt
                if (lastJerkGPerS > jerkThresholdGPerS) jerkSpikeAtMs = nowMs
            }
        }
        lastMagnitudeG = magnitudeG
        lastSampleAtMs = nowMs
        return evaluate(nowMs)
    }

    /** Feed gyroscope magnitude in rad/s. */
    fun onGyroscope(angularSpeedRadPerS: Float, nowMs: Long): Boolean {
        if (angularSpeedRadPerS > angularThresholdRadPerS) rotationSpikeAtMs = nowMs
        return evaluate(nowMs)
    }

    private fun evaluate(nowMs: Long): Boolean {
        if (jerkSpikeAtMs == 0L || rotationSpikeAtMs == 0L) return false
        if (nowMs - lastFiredAtMs < cooldownMs) return false

        val coincident = abs(jerkSpikeAtMs - rotationSpikeAtMs) <= coincidenceWindowMs &&
                nowMs - maxOf(jerkSpikeAtMs, rotationSpikeAtMs) <= coincidenceWindowMs

        if (coincident) {
            lastFiredAtMs = nowMs
            jerkSpikeAtMs = 0L
            rotationSpikeAtMs = 0L
            return true
        }
        return false
    }

    fun reset() {
        jerkSpikeAtMs = 0L
        rotationSpikeAtMs = 0L
        lastSampleAtMs = 0L
        lastMagnitudeG = 1f
    }
}
