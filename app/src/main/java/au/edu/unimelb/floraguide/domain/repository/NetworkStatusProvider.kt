package au.edu.unimelb.floraguide.domain.repository

/** UNKNOWN means the platform could not inspect connectivity, not that the device is offline. */
enum class NetworkStatus { AVAILABLE, UNAVAILABLE, UNKNOWN }

interface NetworkStatusProvider {
    fun currentStatus(): NetworkStatus

    /**
     * Waits while the default network is known to be unusable. The caller owns the timeout.
     * UNKNOWN permits the real request; network inspection must not invent an offline result.
     * Implementations must release listeners when this wait completes or is cancelled.
     */
    suspend fun awaitUsableNetwork()
}
