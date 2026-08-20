package au.edu.unimelb.nightwatch.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import au.edu.unimelb.nightwatch.core.detect.Activity
import au.edu.unimelb.nightwatch.core.detect.ActivityClassifier
import au.edu.unimelb.nightwatch.core.detect.FallDetector
import au.edu.unimelb.nightwatch.core.detect.SnatchDetector
import kotlin.math.sqrt

/**
 * Owns every motion/environment sensor subscription and routes samples into the
 * detectors. The rest of the app sees only the semantic events in [Listener],
 * never raw sensor callbacks.
 *
 * Sampling rate note: SENSOR_DELAY_GAME is ~50 Hz, which is the practical floor
 * for catching an impact transient — SENSOR_DELAY_NORMAL (~5 Hz) will miss the
 * spike entirely. The light sensor is deliberately kept slow because ambient
 * light changes over seconds and a fast rate only wastes battery.
 */
class SensorHub(context: Context) : SensorEventListener {

    interface Listener {
        fun onFallDetected(peakG: Float)
        fun onSnatchDetected()
        fun onActivityChanged(activity: Activity)
        fun onDarkAreaEntered()
        fun onDarkAreaExited()
        fun onReadings(accelerationG: Float, lightLux: Float)
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val lightSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val fallDetector = FallDetector()
    private val snatchDetector = SnatchDetector()
    private val activityClassifier = ActivityClassifier()

    private var listener: Listener? = null

    private var lastLux = 40f
    private var inDarkArea = false
    private var darkSinceMs = 0L

    /** Sustained low light before the advisory fires, so a tunnel or a hand over
     *  the sensor does not trip it. */
    private val darkThresholdLux = 5f
    private val darkSustainMs = 4_000L

    val hasGyroscope: Boolean get() = gyroscope != null
    val hasLightSensor: Boolean get() = lightSensor != null

    fun start(listener: Listener) {
        this.listener = listener
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        listener = null
        fallDetector.reset()
        snatchDetector.reset()
        activityClassifier.reset()
        inDarkArea = false
        darkSinceMs = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val l = listener ?: return

        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {
                val magnitudeG = magnitude(event.values) / SensorManager.GRAVITY_EARTH

                if (fallDetector.onSample(magnitudeG, now)) {
                    l.onFallDetected(fallDetector.lastImpactPeakG)
                }
                if (snatchDetector.onAccelerometer(magnitudeG, now)) {
                    l.onSnatchDetected()
                }
                activityClassifier.onSample(magnitudeG)?.let { l.onActivityChanged(it) }

                l.onReadings(magnitudeG, lastLux)
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (snatchDetector.onGyroscope(magnitude(event.values), now)) {
                    l.onSnatchDetected()
                }
            }

            Sensor.TYPE_LIGHT -> {
                lastLux = event.values[0]
                evaluateLight(now, l)
            }
        }
    }

    private fun evaluateLight(now: Long, l: Listener) {
        if (lastLux < darkThresholdLux) {
            if (darkSinceMs == 0L) darkSinceMs = now
            if (!inDarkArea && now - darkSinceMs >= darkSustainMs) {
                inDarkArea = true
                l.onDarkAreaEntered()
            }
        } else {
            darkSinceMs = 0L
            if (inDarkArea) {
                inDarkArea = false
                l.onDarkAreaExited()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun magnitude(v: FloatArray): Float =
        sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}
