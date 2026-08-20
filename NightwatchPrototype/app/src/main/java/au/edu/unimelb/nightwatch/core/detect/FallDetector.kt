package au.edu.unimelb.nightwatch.core.detect

/**
 * Three-phase fall detector operating on accelerometer magnitude, in g.
 *
 * A single threshold on acceleration is not enough: dropping the phone into a
 * pocket, sitting down heavily, or jogging all cross any impact threshold you
 * pick. What distinguishes a real fall is the *sequence*:
 *
 *   1. FREE FALL   magnitude collapses toward 0 g as the body accelerates down.
 *                  Optional, because not every fall has a clean free-fall phase
 *                  (a trip against a wall may not), so an impact alone can also
 *                  enter phase 2.
 *   2. IMPACT      a sharp spike above [impactThresholdG].
 *   3. STILLNESS   magnitude settles at ~1 g (gravity only) and *stays* there.
 *                  This is the discriminating phase. Someone who trips and keeps
 *                  walking produces phase 1 and 2 but never phase 3.
 *
 * Only when all required phases complete in order, within their timing windows,
 * does the detector fire. Tune the constants against your own recorded traces.
 */
class FallDetector(
    private val freeFallThresholdG: Float = 0.60f,
    private val impactThresholdG: Float = 2.80f,
    private val stillnessToleranceG: Float = 0.18f,
    private val freeFallToImpactWindowMs: Long = 1_200,
    private val impactToStillnessWindowMs: Long = 1_500,
    private val requiredStillnessMs: Long = 2_500,
    private val recoveryThresholdG: Float = 1.70f
) {

    private enum class Phase { IDLE, FREE_FALL, AWAIT_STILLNESS, STILL }

    private var phase = Phase.IDLE
    private var freeFallAtMs = 0L
    private var impactAtMs = 0L
    private var stillnessStartedAtMs = 0L

    /** Peak g of the impact that opened the current candidate, for the alert payload. */
    var lastImpactPeakG: Float = 0f
        private set

    /**
     * Feed one sample.
     *
     * @param magnitudeG accelerometer magnitude in g (1.0 at rest)
     * @param nowMs      sample timestamp, milliseconds
     * @return true exactly once, on the sample that confirms a fall
     */
    fun onSample(magnitudeG: Float, nowMs: Long): Boolean {
        when (phase) {

            Phase.IDLE -> {
                if (magnitudeG < freeFallThresholdG) {
                    phase = Phase.FREE_FALL
                    freeFallAtMs = nowMs
                } else if (magnitudeG > impactThresholdG) {
                    // Impact without a detected free-fall phase still counts.
                    enterImpact(magnitudeG, nowMs)
                }
            }

            Phase.FREE_FALL -> {
                if (magnitudeG > impactThresholdG) {
                    enterImpact(magnitudeG, nowMs)
                } else if (nowMs - freeFallAtMs > freeFallToImpactWindowMs) {
                    // Low-g reading with no impact behind it: not a fall.
                    reset()
                }
            }

            Phase.AWAIT_STILLNESS -> {
                if (magnitudeG > lastImpactPeakG) lastImpactPeakG = magnitudeG

                if (isStill(magnitudeG)) {
                    phase = Phase.STILL
                    stillnessStartedAtMs = nowMs
                } else if (nowMs - impactAtMs > impactToStillnessWindowMs) {
                    // Still moving well after the impact: they stayed on their feet.
                    reset()
                }
            }

            Phase.STILL -> {
                if (magnitudeG > recoveryThresholdG) {
                    // Vigorous movement: they got up. Stand the candidate down.
                    reset()
                } else if (!isStill(magnitudeG)) {
                    // Minor movement, drop back and let the timer restart.
                    phase = Phase.AWAIT_STILLNESS
                } else if (nowMs - stillnessStartedAtMs >= requiredStillnessMs) {
                    reset()
                    return true
                }
            }
        }
        return false
    }

    private fun enterImpact(magnitudeG: Float, nowMs: Long) {
        phase = Phase.AWAIT_STILLNESS
        impactAtMs = nowMs
        lastImpactPeakG = magnitudeG
    }

    /** Close to 1 g means gravity only: the device is not being moved. */
    private fun isStill(magnitudeG: Float) =
        kotlin.math.abs(magnitudeG - 1f) < stillnessToleranceG

    fun reset() {
        phase = Phase.IDLE
        freeFallAtMs = 0L
        impactAtMs = 0L
        stillnessStartedAtMs = 0L
    }
}
