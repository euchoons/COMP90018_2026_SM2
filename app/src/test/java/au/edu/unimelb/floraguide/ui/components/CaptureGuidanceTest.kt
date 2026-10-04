package au.edu.unimelb.floraguide.ui.components

import au.edu.unimelb.floraguide.domain.model.SensorAvailability
import au.edu.unimelb.floraguide.domain.model.SensorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGuidanceTest {
    private val motionSensors = SensorAvailability(accelerometer = true, gyroscope = true)
    private val steady = SensorSnapshot(stability = 1.0, availability = motionSensors)
    private val moving = SensorSnapshot(stability = 0.0, availability = motionSensors)

    @Test
    fun gateOnHoldsTheShutterUntilThePhoneIsSteady() {
        val waiting = captureGuidance(moving, gateEnabled = true, consentPending = false)
        assertFalse(waiting.shutterEnabled)
        assertEquals("Hold still", waiting.stabilityLabel)
        assertEquals("Hold still before capturing", waiting.hint)

        val ready = captureGuidance(steady, gateEnabled = true, consentPending = false)
        assertTrue(ready.shutterEnabled)
        assertEquals("Steady", ready.stabilityLabel)
        assertEquals("Ready to capture", ready.hint)
    }

    @Test
    fun gateOffAllowsCaptureWithoutTellingTheUserToHoldStill() {
        val guidance = captureGuidance(moving, gateEnabled = false, consentPending = false)
        assertTrue(guidance.shutterEnabled)
        assertEquals("Moving", guidance.stabilityLabel)
        assertFalse(guidance.stabilityPositive)
        assertEquals("Moving: photo may blur (stability gate off)", guidance.hint)
    }

    @Test
    fun switchingTheGateChangesWhatTheUserSeesEvenWhenSteady() {
        val on = captureGuidance(steady, gateEnabled = true, consentPending = false)
        val off = captureGuidance(steady, gateEnabled = false, consentPending = false)
        assertEquals(on.shutterEnabled, off.shutterEnabled)
        assertNotEquals(on.hint, off.hint)
        assertNotEquals(on.switchSummary, off.switchSummary)
    }

    @Test
    fun missingMotionSensorFallsBackToManualCaptureWhateverTheSwitchSays() {
        for (missing in listOf(motionSensors.copy(gyroscope = false), motionSensors.copy(accelerometer = false))) {
            for (gateEnabled in listOf(true, false)) {
                val guidance = captureGuidance(SensorSnapshot(availability = missing), gateEnabled, consentPending = false)
                assertTrue(guidance.shutterEnabled)
                assertEquals("Stability n/a", guidance.stabilityLabel)
                assertEquals("Manual capture: motion sensors unavailable", guidance.hint)
                assertEquals("Sensor unavailable: manual capture", guidance.switchSummary)
            }
        }
    }

    @Test
    fun pendingUploadChoiceBlocksTheShutterWithoutBlamingMotion() {
        for (snapshot in listOf(steady, moving, SensorSnapshot())) {
            for (gateEnabled in listOf(true, false)) {
                val guidance = captureGuidance(snapshot, gateEnabled, consentPending = true)
                assertFalse(guidance.shutterEnabled)
                assertEquals("Choose whether to use online identification", guidance.hint)
            }
        }
    }
}
