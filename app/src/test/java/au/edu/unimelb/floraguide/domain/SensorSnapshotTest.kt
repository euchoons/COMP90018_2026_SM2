package au.edu.unimelb.floraguide.domain

import au.edu.unimelb.floraguide.domain.model.LightCondition
import au.edu.unimelb.floraguide.domain.model.SensorAvailability
import au.edu.unimelb.floraguide.domain.model.SensorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorSnapshotTest {
    @Test
    fun missingLightSensorIsReportedAsUnavailable() {
        assertEquals(LightCondition.UNAVAILABLE, SensorSnapshot(lightLux = null).lightCondition)
    }

    @Test
    fun lightThresholdsAreHalfOpen() {
        assertEquals(LightCondition.LOW, SensorSnapshot(lightLux = 0f).lightCondition)
        assertEquals(LightCondition.LOW, SensorSnapshot(lightLux = 24.9f).lightCondition)
        assertEquals(LightCondition.USABLE, SensorSnapshot(lightLux = 25f).lightCondition)
        assertEquals(LightCondition.USABLE, SensorSnapshot(lightLux = 19_999f).lightCondition)
        assertEquals(LightCondition.VERY_BRIGHT, SensorSnapshot(lightLux = 20_000f).lightCondition)
    }

    @Test
    fun stabilityNeedsBothMotionSensors() {
        val both = SensorAvailability(accelerometer = true, gyroscope = true)
        assertTrue(SensorSnapshot(availability = both).canMeasureStability)
        assertFalse(SensorSnapshot(availability = both.copy(gyroscope = false)).canMeasureStability)
        assertFalse(SensorSnapshot(availability = both.copy(accelerometer = false)).canMeasureStability)
    }

    @Test
    fun uncalibratedCompassHeadingIsNotReliable() {
        assertEquals(90f, SensorSnapshot(headingDegrees = 90f).reliableHeadingDegrees)
        assertNull(
            SensorSnapshot(headingDegrees = 90f, compassNeedsCalibration = true).reliableHeadingDegrees,
        )
    }
}
