package au.edu.unimelb.nightwatch.core.contacts

/**
 * Someone the user has chosen to notify if a threat is detected.
 *
 * The app only ever *informs* these people and shares a location link; it never
 * asks them to intervene. The safety advice surfaced alongside an alert is to
 * contact emergency services (000 in Australia), not to attend in person.
 */
data class EmergencyContact(
    val id: String,
    val name: String,
    val phone: String,
    /** Set false to keep a contact configured but temporarily skipped. */
    val enabled: Boolean = true
) {
    val initials: String
        get() = name.trim().split(" ")
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
}

/**
 * Replace with DataStore or Room persistence. Seeded so the app is demonstrable
 * on first launch without any setup.
 */
class ContactRepository {

    private val contacts = mutableListOf(
        EmergencyContact("1", "Maya K.", "+61400000001"),
        EmergencyContact("2", "Jordan D.", "+61400000002"),
        EmergencyContact("3", "Alex L.", "+61400000003")
    )

    fun all(): List<EmergencyContact> = contacts.toList()

    fun active(): List<EmergencyContact> = contacts.filter { it.enabled }

    fun add(contact: EmergencyContact) { contacts += contact }

    fun remove(id: String) { contacts.removeAll { it.id == id } }
}
