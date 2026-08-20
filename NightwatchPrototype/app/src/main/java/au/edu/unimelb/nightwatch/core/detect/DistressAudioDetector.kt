package au.edu.unimelb.nightwatch.core.detect

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Flags shout- or scream-like acoustic events from short audio frames.
 *
 * Privacy note, and this matters for the report as much as the code: no audio is
 * recorded, buffered to disk, or transmitted. Each frame is reduced to a single
 * loudness number and then discarded, so the only thing that ever leaves this
 * class is a level in dB and a boolean.
 *
 * A fixed loudness threshold does not survive contact with the real world: a quiet
 * residential street and a road with traffic differ by tens of dB. So the detector
 * tracks a slow-moving baseline of ambient level and fires on a *relative* jump
 * above it, gated by an absolute floor so a jump in a silent room cannot trigger.
 *
 * Currently a level-based heuristic. The natural upgrade, and a strong technical
 * depth story for A2, is to replace [isCandidate] with a small on-device
 * classifier (for example a TensorFlow Lite YAMNet head) over log-mel features,
 * which discriminates screams from car horns and door slams far better.
 */
class DistressAudioDetector(
    private val relativeJumpDb: Float = 22f,
    private val absoluteFloorDb: Float = 78f,
    private val requiredSustainMs: Long = 400,
    private val cooldownMs: Long = 10_000,
    private val baselineSmoothing: Float = 0.02f,
    /** Maps dBFS (negative) onto an approximate SPL. Calibrate against a meter. */
    private val calibrationOffsetDb: Float = 90f
) {

    var baselineDb: Float = 45f
        private set
    var currentDb: Float = 45f
        private set

    private var candidateStartedAtMs = 0L
    private var lastFiredAtMs = 0L

    /**
     * @param frame 16-bit PCM samples
     * @param readCount valid sample count in [frame]
     * @return true when a sustained distress-like event is confirmed
     */
    fun onAudioFrame(frame: ShortArray, readCount: Int, nowMs: Long): Boolean {
        if (readCount <= 0) return false

        var sumSquares = 0.0
        for (i in 0 until readCount) {
            val s = frame[i].toDouble()
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / readCount)
        if (rms <= 0.0) return false

        // dBFS relative to full scale, shifted into an approximate SPL range.
        val dbfs = 20f * log10((rms / Short.MAX_VALUE).toFloat())
        currentDb = dbfs + calibrationOffsetDb

        val loud = isCandidate(currentDb)

        // Only let the baseline follow quiet frames, or a long shout would
        // drag the baseline up and silence the detector.
        if (!loud) {
            baselineDb += baselineSmoothing * (currentDb - baselineDb)
        }

        if (!loud) {
            candidateStartedAtMs = 0L
            return false
        }

        if (candidateStartedAtMs == 0L) candidateStartedAtMs = nowMs

        val sustained = nowMs - candidateStartedAtMs >= requiredSustainMs
        val outOfCooldown = nowMs - lastFiredAtMs > cooldownMs

        if (sustained && outOfCooldown) {
            lastFiredAtMs = nowMs
            candidateStartedAtMs = 0L
            return true
        }
        return false
    }

    private fun isCandidate(levelDb: Float) =
        levelDb > absoluteFloorDb && levelDb > baselineDb + relativeJumpDb

    fun reset() {
        candidateStartedAtMs = 0L
    }
}
