package au.edu.unimelb.nightwatch.core

import au.edu.unimelb.nightwatch.core.detect.Activity

/**
 * What the sensing layer thinks happened. Each threat carries its own countdown
 * because confidence differs: a fall with post-impact stillness is a strong
 * signal and gets a short window, while an unexpected sprint is weak evidence
 * and gets a long one before anything is sent.
 */
enum class Threat(
    val label: String,
    val prompt: String,
    val countdownSeconds: Int
) {
    FALL(
        label = "Possible fall",
        prompt = "A hard impact followed by no movement was detected.",
        countdownSeconds = 15
    ),
    SNATCH(
        label = "Phone may have been snatched",
        prompt = "A violent pull and twist were detected.",
        countdownSeconds = 10
    ),
    DISTRESS_SOUND(
        label = "Distress sound",
        prompt = "A shout or scream was detected nearby.",
        countdownSeconds = 12
    ),
    SUDDEN_SPRINT(
        label = "Sudden running",
        prompt = "You started running unexpectedly.",
        countdownSeconds = 20
    ),
    MANUAL(
        label = "Alert raised by you",
        prompt = "You raised an alert.",
        countdownSeconds = 0
    )
}

enum class Phase {
    /** Walk not started. */
    IDLE,

    /** Sensing, nothing unusual. */
    MONITORING,

    /** Low-light stretch. Advisory only: nothing is sent without consent. */
    CAUTION,

    /** Threat detected, countdown running, user can cancel. */
    CONFIRMING,

    /** Countdown expired or user escalated. Contacts notified. */
    ALERTED
}

/** Immutable snapshot rendered by the UI. */
data class SafetyState(
    val phase: Phase = Phase.IDLE,
    val activity: Activity = Activity.STILL,
    val threat: Threat? = null,
    val secondsRemaining: Int = 0,
    val accelerationG: Float = 1f,
    val soundDb: Float = 45f,
    val lightLux: Float = 40f,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,
    val elapsedSeconds: Long = 0,
    val contactsNotified: List<String> = emptyList(),
    val sharingLiveLocation: Boolean = false,
    val log: List<LogEntry> = emptyList()
) {
    val isMonitoring: Boolean get() = phase != Phase.IDLE
}

data class LogEntry(
    val atMs: Long,
    val message: String,
    val severity: Severity
) {
    enum class Severity { INFO, GOOD, WARN, ALERT }
}
