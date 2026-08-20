package au.edu.unimelb.nightwatch.core.contacts

import android.util.Log
import au.edu.unimelb.nightwatch.core.Threat

/** What gets sent when an alert fires. */
data class AlertPayload(
    val threat: Threat,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val atMs: Long
) {
    /** Plain maps link so a contact can open the location without the app. */
    val mapsUrl: String?
        get() = if (latitude != null && longitude != null)
            "https://maps.google.com/?q=$latitude,$longitude" else null

    fun message(userName: String = "Your contact"): String = buildString {
        append("$userName may need help — ${threat.label.lowercase()} detected ")
        append("and they did not respond to a check-in.")
        mapsUrl?.let { append("\nLast known location: $it") }
        append("\nIf you cannot reach them, call 000.")
    }
}

/**
 * Abstraction over delivery so the detection layer never knows how alerts travel.
 *
 * For Assignment 2 this is also where the Connectivity criterion is earned: swap
 * in an implementation that posts to your backend (Firebase Cloud Messaging or a
 * REST endpoint), which then pushes to contacts and opens a live-location link
 * rather than sending a single static position.
 */
interface AlertDispatcher {
    suspend fun dispatch(payload: AlertPayload, contacts: List<EmergencyContact>): List<String>
    suspend fun standDown(contacts: List<EmergencyContact>) {}
}

/** Safe default for development: logs instead of messaging real people. */
class LoggingAlertDispatcher : AlertDispatcher {

    override suspend fun dispatch(
        payload: AlertPayload,
        contacts: List<EmergencyContact>
    ): List<String> {
        contacts.forEach {
            Log.w(TAG, "ALERT -> ${it.name} (${it.phone}): ${payload.message()}")
        }
        return contacts.map { it.name }
    }

    override suspend fun standDown(contacts: List<EmergencyContact>) {
        contacts.forEach { Log.i(TAG, "STAND DOWN -> ${it.name}") }
    }

    private companion object { const val TAG = "Nightwatch/Alert" }
}

/**
 * Sketch of a real dispatcher. Left unwired on purpose.
 *
 * Two cautions before you enable anything like this:
 *  - Sending SMS silently needs the SEND_SMS permission, which Google Play treats
 *    as restricted and which will complicate submission. Handing a pre-filled
 *    message to the user's SMS app via an ACTION_SENDTO intent avoids that
 *    entirely, at the cost of needing one tap.
 *  - Test against your own numbers only. Repeated automated messages to anyone
 *    who has not consented is harassment, and a buggy loop sends a lot of them.
 */
class RemoteAlertDispatcher(
    private val endpoint: String
) : AlertDispatcher {

    override suspend fun dispatch(
        payload: AlertPayload,
        contacts: List<EmergencyContact>
    ): List<String> {
        // TODO: POST payload to `endpoint`; backend fans out to contacts and
        // returns a signed, expiring live-location URL.
        Log.i("Nightwatch/Alert", "Would POST to $endpoint: ${payload.message()}")
        return contacts.map { it.name }
    }
}
