package au.edu.unimelb.nightwatch.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import au.edu.unimelb.nightwatch.core.contacts.AlertDispatcher
import au.edu.unimelb.nightwatch.core.contacts.AlertPayload
import au.edu.unimelb.nightwatch.core.contacts.ContactRepository
import au.edu.unimelb.nightwatch.core.contacts.LoggingAlertDispatcher
import au.edu.unimelb.nightwatch.core.detect.Activity
import au.edu.unimelb.nightwatch.core.detect.DistressAudioDetector
import au.edu.unimelb.nightwatch.core.location.LocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The brain of the app: turns detector events into escalation decisions.
 *
 * Design rule that shapes everything here — nothing is ever sent without first
 * giving the user a chance to cancel. Detection is not proof, and the cost of a
 * false alarm sent to three people is high enough that a confirmation window is
 * always offered, sized to how much the detector is trusted.
 *
 * The one exception is [raiseAlertNow], which the user triggers deliberately.
 */
class SafeWalkEngine(
    private val context: Context,
    private val contacts: ContactRepository = ContactRepository(),
    private val dispatcher: AlertDispatcher = LoggingAlertDispatcher()
) : SensorHub.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val sensorHub = SensorHub(context)
    private val locationTracker = LocationTracker(context)
    private val audioDetector = DistressAudioDetector()

    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private var audioJob: Job? = null
    private var elapsedJob: Job? = null
    private var startedAtMs = 0L

    // ---------------------------------------------------------------- lifecycle

    fun startWalk() {
        if (_state.value.isMonitoring) return

        startedAtMs = System.currentTimeMillis()
        _state.update {
            SafetyState(phase = Phase.MONITORING, log = it.log)
        }
        log("Safe walk started — sensing on device", LogEntry.Severity.GOOD)

        if (!sensorHub.hasGyroscope) {
            log("No gyroscope on this device — snatch detection disabled", LogEntry.Severity.WARN)
        }
        if (!sensorHub.hasLightSensor) {
            log("No light sensor — dark-area advisories disabled", LogEntry.Severity.WARN)
        }

        sensorHub.start(this)
        startLocation()
        startAudioMonitoring()
        startElapsedTimer()
    }

    fun stopWalk() {
        countdownJob?.cancel()
        audioJob?.cancel()
        elapsedJob?.cancel()
        sensorHub.stop()
        locationTracker.stop()
        _state.update { it.copy(phase = Phase.IDLE, threat = null, secondsRemaining = 0) }
        log("Safe walk ended", LogEntry.Severity.INFO)
    }

    private fun startLocation() {
        if (!locationTracker.hasPermission()) {
            log("Location permission not granted — alerts will not carry a position", LogEntry.Severity.WARN)
            return
        }
        locationTracker.start { loc ->
            _state.update {
                it.copy(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    locationAccuracyM = loc.accuracy
                )
            }
        }
    }

    private fun startElapsedTimer() {
        elapsedJob = scope.launch {
            while (isActive) {
                delay(1_000)
                if (_state.value.isMonitoring) {
                    _state.update {
                        it.copy(elapsedSeconds = (System.currentTimeMillis() - startedAtMs) / 1000)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------- audio

    @SuppressLint("MissingPermission")
    private fun startAudioMonitoring() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            log("Microphone permission not granted — distress-sound detection off", LogEntry.Severity.WARN)
            return
        }

        audioJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return@launch

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2
                )
            } catch (e: SecurityException) {
                return@launch
            }

            val frame = ShortArray(minBuffer)
            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(frame, 0, frame.size)
                    val now = System.currentTimeMillis()

                    // Frame is reduced to a loudness figure and immediately reused.
                    // Nothing is stored, buffered to disk, or transmitted.
                    val distress = audioDetector.onAudioFrame(frame, read, now)

                    _state.update { it.copy(soundDb = audioDetector.currentDb) }

                    if (distress && _state.value.phase == Phase.MONITORING) {
                        onThreat(Threat.DISTRESS_SOUND)
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
    }

    // --------------------------------------------------- SensorHub.Listener

    override fun onFallDetected(peakG: Float) {
        log("Impact %.1f g then stillness".format(peakG), LogEntry.Severity.ALERT)
        onThreat(Threat.FALL)
    }

    override fun onSnatchDetected() {
        log("High jerk with rapid rotation", LogEntry.Severity.ALERT)
        onThreat(Threat.SNATCH)
    }

    override fun onActivityChanged(activity: Activity) {
        val previous = _state.value.activity
        _state.update { it.copy(activity = activity) }

        // Walking straight into a run at night can be a flight response. Weak
        // evidence on its own, so it gets the longest confirmation window.
        if (previous == Activity.WALKING && activity == Activity.RUNNING) {
            log("Gait changed: walking to running", LogEntry.Severity.WARN)
            onThreat(Threat.SUDDEN_SPRINT)
        }
    }

    override fun onDarkAreaEntered() {
        if (_state.value.phase != Phase.MONITORING) return
        _state.update { it.copy(phase = Phase.CAUTION) }
        log("Ambient light below 5 lux — unlit stretch", LogEntry.Severity.WARN)
    }

    override fun onDarkAreaExited() {
        if (_state.value.phase == Phase.CAUTION) {
            _state.update { it.copy(phase = Phase.MONITORING) }
            log("Back in lit area", LogEntry.Severity.GOOD)
        }
    }

    override fun onReadings(accelerationG: Float, lightLux: Float) {
        _state.update { it.copy(accelerationG = accelerationG, lightLux = lightLux) }
    }

    // ---------------------------------------------------------- escalation

    private fun onThreat(threat: Threat) {
        val phase = _state.value.phase
        // Never interrupt an alert already in flight, and never start one before
        // the walk has begun.
        if (phase == Phase.CONFIRMING || phase == Phase.ALERTED || phase == Phase.IDLE) return

        _state.update {
            it.copy(
                phase = Phase.CONFIRMING,
                threat = threat,
                secondsRemaining = threat.countdownSeconds
            )
        }
        log("${threat.label} — checking in, ${threat.countdownSeconds}s to cancel", LogEntry.Severity.ALERT)
        startCountdown(threat)
    }

    private fun startCountdown(threat: Threat) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            var remaining = threat.countdownSeconds
            while (remaining > 0 && isActive) {
                delay(1_000)
                remaining--
                _state.update { it.copy(secondsRemaining = remaining) }
            }
            if (isActive) escalate(threat)
        }
    }

    /** User confirmed they are fine. Nothing was sent. */
    fun markSafe() {
        countdownJob?.cancel()
        _state.update {
            it.copy(phase = Phase.MONITORING, threat = null, secondsRemaining = 0)
        }
        log("Marked safe — no alert sent", LogEntry.Severity.GOOD)
    }

    /** User escalated deliberately, skipping the countdown. */
    fun raiseAlertNow() {
        countdownJob?.cancel()
        val threat = _state.value.threat ?: Threat.MANUAL
        scope.launch { escalate(threat) }
    }

    private suspend fun escalate(threat: Threat) {
        val snapshot = _state.value
        val payload = AlertPayload(
            threat = threat,
            latitude = snapshot.latitude,
            longitude = snapshot.longitude,
            accuracyM = snapshot.locationAccuracyM,
            atMs = System.currentTimeMillis()
        )

        val notified = dispatcher.dispatch(payload, contacts.active())

        _state.update {
            it.copy(
                phase = Phase.ALERTED,
                secondsRemaining = 0,
                contactsNotified = notified,
                sharingLiveLocation = true
            )
        }
        log("Emergency contacts notified: ${notified.joinToString()}", LogEntry.Severity.ALERT)
        log("Live location sharing on", LogEntry.Severity.ALERT)
    }

    /** Closes an alert the user has resolved. */
    fun standDown() {
        scope.launch {
            dispatcher.standDown(contacts.active())
            _state.update {
                it.copy(
                    phase = Phase.MONITORING,
                    threat = null,
                    contactsNotified = emptyList(),
                    sharingLiveLocation = false
                )
            }
            log("Stood down — contacts told you are safe", LogEntry.Severity.GOOD)
        }
    }

    /** Opt-in sharing offered during a dark stretch. */
    fun shareLocationFromCaution() {
        _state.update { it.copy(sharingLiveLocation = true) }
        log("Live location shared with emergency contacts", LogEntry.Severity.GOOD)
    }

    fun dismissCaution() {
        _state.update { it.copy(phase = Phase.MONITORING) }
        log("Dark-area advisory dismissed", LogEntry.Severity.INFO)
    }

    // ---------------------------------------------------------------- helpers

    private fun log(message: String, severity: LogEntry.Severity) {
        val entry = LogEntry(System.currentTimeMillis(), message, severity)
        _state.update { it.copy(log = (it.log + entry).takeLast(40)) }
    }

    suspend fun shutdown() {
        stopWalk()
        countdownJob?.cancelAndJoin()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
