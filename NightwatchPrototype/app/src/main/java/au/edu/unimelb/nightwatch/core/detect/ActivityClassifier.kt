package au.edu.unimelb.nightwatch.core.detect

/** Coarse gait state used to give the escalation logic context. */
enum class Activity { STILL, WALKING, RUNNING }

/**
 * Classifies gait from the variance of accelerometer magnitude over a short window.
 *
 * Variance is used rather than raw magnitude because magnitude sits near 1 g
 * whether the phone is on a table or in the pocket of someone standing still —
 * it is the *spread* of the signal that separates standing from walking from
 * running. Hysteresis (via [confirmations]) stops the label flickering between
 * classes on borderline windows.
 *
 * The transition WALKING -> RUNNING is what the escalation layer treats as a
 * possible flight response: a sudden sprint on a night walk is worth a check-in
 * even though it is innocent most of the time.
 */
class ActivityClassifier(
    windowSamples: Int = 100,          // ~2 s at 50 Hz
    private val walkingVarianceG2: Float = 0.02f,
    private val runningVarianceG2: Float = 0.35f,
    private val confirmations: Int = 3
) {

    private val window = SlidingWindow(windowSamples)
    private var candidate = Activity.STILL
    private var candidateCount = 0

    var current: Activity = Activity.STILL
        private set
    var lastVariance: Float = 0f
        private set

    /** @return the new [Activity] on a confirmed transition, otherwise null */
    fun onSample(magnitudeG: Float): Activity? {
        window.add(magnitudeG)
        if (!window.isFull) return null

        lastVariance = window.variance()
        val observed = when {
            lastVariance < walkingVarianceG2 -> Activity.STILL
            lastVariance < runningVarianceG2 -> Activity.WALKING
            else -> Activity.RUNNING
        }

        if (observed == current) {
            candidateCount = 0
            return null
        }

        if (observed == candidate) candidateCount++ else {
            candidate = observed
            candidateCount = 1
        }

        if (candidateCount >= confirmations) {
            current = candidate
            candidateCount = 0
            return current
        }
        return null
    }

    fun reset() {
        window.clear()
        current = Activity.STILL
        candidate = Activity.STILL
        candidateCount = 0
    }
}
