package au.edu.unimelb.floraguide.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import au.edu.unimelb.floraguide.BuildConfig
import au.edu.unimelb.floraguide.domain.model.SensorAvailability
import au.edu.unimelb.floraguide.domain.model.SensorSnapshot
import au.edu.unimelb.floraguide.domain.sensor.MotionStabilityEstimator
import au.edu.unimelb.floraguide.domain.sensor.observationHeadingDegrees
import java.util.Locale
import kotlin.math.abs
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
    // Start "moving" so capture stays locked until real readings arrive.
    private var accelerationDeviation = 1.0
    private var angularVelocity = 1.0
    private val stabilityEstimator = MotionStabilityEstimator()
    private var lightLux: Float? = null
    private var headingDegrees: Float? = null
    private var gravityVector: FloatArray? = null
    private var magneticVector: FloatArray? = null
    private var magnetometerAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

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
                magnetometerAccuracy = event.accuracy
            }

            Sensor.TYPE_LIGHT -> lightLux = event.values.firstOrNull()
        }

        updateHeading()
        stabilityEstimator.update(accelerationDeviation, angularVelocity)
        if (BuildConfig.DEBUG) logMotion(event)
        publish()
    }

    /** Debug builds only: every gate update, for tools/motion-calibration.py. */
    private fun logMotion(event: SensorEvent) {
        val score = stabilityEstimator.score
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "t=%d event=motion sensor=%d accDev=%.4f gyro=%.4f target=%.4f score=%.4f open=%b",
                event.timestamp / 1_000_000L, event.sensor.type, accelerationDeviation, angularVelocity,
                MotionStabilityEstimator.target(accelerationDeviation, angularVelocity), score,
                MotionStabilityEstimator.isStable(score),
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetometerAccuracy = accuracy
            publish()
        }
    }

    private fun updateHeading() {
        val gravity = gravityVector ?: return
        val magnetic = magneticVector ?: return
        val rotation = FloatArray(9)
        if (!SensorManager.getRotationMatrix(rotation, null, gravity, magnetic)) return
        headingDegrees = observationHeadingDegrees(rotation)
    }

    private fun publish() {
        listener?.invoke(
            SensorSnapshot(
                stability = stabilityEstimator.score,
                lightLux = lightLux,
                headingDegrees = headingDegrees,
                // Only UNRELIABLE is flagged: many phones sit at LOW accuracy for long periods,
                // and hiding the heading there would make it unavailable most of the time.
                compassNeedsCalibration =
                    magnetometerAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE,
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

    private companion object {
        const val TAG = "FloraGuide-Motion"
    }
}
