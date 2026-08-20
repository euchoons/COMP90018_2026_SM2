package au.edu.unimelb.nightwatch

import au.edu.unimelb.nightwatch.core.detect.FallDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These run on the JVM with no emulator, which matters: tuning thresholds by
 * repeatedly dropping a phone is slow and hard on the phone. Record real traces
 * once, replay them here, and iterate on the constants in seconds.
 *
 * The negative cases are the important ones. Any detector fires on a real fall;
 * a *usable* detector is one that stays quiet through a normal walk home.
 */
class FallDetectorTest {

    private fun feed(
        detector: FallDetector,
        samples: List<Float>,
        stepMs: Long = 20,
        startMs: Long = 0
    ): Boolean {
        var fired = false
        var t = startMs
        samples.forEach { g ->
            if (detector.onSample(g, t)) fired = true
            t += stepMs
        }
        return fired
    }

    private fun steady(count: Int, g: Float = 1.0f) = List(count) { g }

    @Test
    fun `fires on free fall, impact, then stillness`() {
        val detector = FallDetector()
        val trace = steady(50) +          // walking-ish baseline
            List(10) { 0.3f } +           // free fall
            List(3) { 4.2f } +            // impact
            steady(200)                   // 4 s motionless
        assertTrue(feed(detector, trace))
    }

    @Test
    fun `fires on impact without a clean free fall phase`() {
        val detector = FallDetector()
        val trace = steady(30) + List(3) { 3.5f } + steady(200)
        assertTrue(feed(detector, trace))
    }

    @Test
    fun `stays quiet when the user keeps walking after a bump`() {
        val detector = FallDetector()
        // Impact, then continued gait: no stillness phase, so not a fall.
        val gait = List(200) { if (it % 2 == 0) 1.4f else 0.7f }
        val trace = steady(30) + List(3) { 3.5f } + gait
        assertFalse(feed(detector, trace))
    }

    @Test
    fun `stays quiet when the phone is set down gently`() {
        val detector = FallDetector()
        // Stillness with no preceding impact.
        assertFalse(feed(detector, steady(300)))
    }

    @Test
    fun `stands down if the user gets up quickly`() {
        val detector = FallDetector()
        val trace = steady(20) +
            List(3) { 4.0f } +
            steady(60) +                  // ~1.2 s still, short of the 2.5 s needed
            List(50) { 2.2f }             // vigorous movement: got up
        assertFalse(feed(detector, trace))
    }
}
