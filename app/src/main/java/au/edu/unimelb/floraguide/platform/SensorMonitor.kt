package au.edu.unimelb.floraguide.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import au.edu.unimelb.floraguide.domain.model.SensorAvailability
import au.edu.unimelb.floraguide.domain.model.SensorSnapshot
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Fuses accelerometer and gyroscope readings into a smoothed stability score, while exposing
 * ambient light and compass heading as independent capture-quality cues.
 */
class SensorMonitor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val light = manager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val availability = SensorAvailability(
        accelerometer = accelerometer != null,
        gyroscope = gyroscope != null,
        magnetometer = magnetometer != null,
        ambientLight = light != null,
    )

    private var listener: ((SensorSnapshot) -> Unit)? = null
    private var accelerationDeviation = 1.0
    private var angularVelocity = 1.0
    private var smoothedStability = 0.0
    private var lightLux: Float? = null
    private var headingDegrees: Float? = null
    private var gravityVector: FloatArray? = null
    private var magneticVector: FloatArray? = null

    fun start(onSnapshot: (SensorSnapshot) -> Unit) {
        listener = onSnapshot
        listOfNotNull(accelerometer, gyroscope, magnetometer, light).forEach { sensor ->
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        publish()
    }

    fun stop() {
        manager.unregisterListener(this)
        listener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val values = event.values.copyOf()
                gravityVector = lowPass(values, gravityVector)
                val magnitude = vectorMagnitude(values)
                accelerationDeviation = abs(magnitude - SensorManager.GRAVITY_EARTH)
            }

            Sensor.TYPE_GYROSCOPE -> {
                angularVelocity = vectorMagnitude(event.values)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticVector = lowPass(event.values.copyOf(), magneticVector)
            }

            Sensor.TYPE_LIGHT -> lightLux = event.values.firstOrNull()
        }

        updateHeading()
        val target = exp(-(accelerationDeviation * 0.9 + angularVelocity * 0.6))
            .coerceIn(0.0, 1.0)
        smoothedStability = (0.7 * smoothedStability + 0.3 * target).coerceIn(0.0, 1.0)
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateHeading() {
        val gravity = gravityVector ?: return
        val magnetic = magneticVector ?: return
        val rotation = FloatArray(9)
        if (!SensorManager.getRotationMatrix(rotation, null, gravity, magnetic)) return
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        headingDegrees = Math.toDegrees(orientation[0].toDouble())
            .toFloat()
            .let { (it + 360f) % 360f }
    }

    private fun publish() {
        listener?.invoke(
            SensorSnapshot(
                stability = smoothedStability,
                lightLux = lightLux,
                headingDegrees = headingDegrees,
                availability = availability,
            ),
        )
    }

    private fun lowPass(input: FloatArray, previous: FloatArray?): FloatArray {
        if (previous == null) return input
        val alpha = 0.18f
        return FloatArray(input.size) { index ->
            previous[index] + alpha * (input[index] - previous[index])
        }
    }

    private fun vectorMagnitude(values: FloatArray): Double = sqrt(
        values.take(3).sumOf { value -> (value * value).toDouble() },
    )
}
