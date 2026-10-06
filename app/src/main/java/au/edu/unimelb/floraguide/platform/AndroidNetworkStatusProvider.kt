package au.edu.unimelb.floraguide.platform

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import au.edu.unimelb.floraguide.domain.repository.NetworkStatus
import au.edu.unimelb.floraguide.domain.repository.NetworkStatusProvider
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

/** No polling or network probe. ACCESS_NETWORK_STATE is declared in the app manifest. */
class AndroidNetworkStatusProvider(
    context: Context,
    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java),
) : NetworkStatusProvider {
    @SuppressLint("MissingPermission")
    override fun currentStatus(): NetworkStatus {
        val service = manager ?: return NetworkStatus.UNKNOWN
        return try {
            val network = service.activeNetwork ?: return NetworkStatus.UNAVAILABLE
            service.getNetworkCapabilities(network)?.toStatus() ?: NetworkStatus.UNKNOWN
        } catch (_: RuntimeException) {
            // Missing permission or unavailable platform service is not proof of no internet.
            NetworkStatus.UNKNOWN
        }
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun awaitUsableNetwork() {
        if (currentStatus() != NetworkStatus.UNAVAILABLE) return
        val service = manager ?: return
        callbackFlow<NetworkStatus> {
            val defaultNetwork = AtomicReference<Network?>(null)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    defaultNetwork.set(network)
                    // onAvailable alone does not mean that internet access is validated.
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (defaultNetwork.get() == network) trySend(capabilities.toStatus())
                }

                override fun onLost(network: Network) {
                    val current = defaultNetwork.get()
                    if (current == network && defaultNetwork.compareAndSet(current, null)) {
                        trySend(NetworkStatus.UNAVAILABLE)
                    }
                }
            }
            // Snapshot first. Registering the default callback then delivers the current network,
            // covering a reconnection between this snapshot and callback registration.
            trySend(currentStatus())
            try {
                service.registerDefaultNetworkCallback(callback)
            } catch (_: RuntimeException) {
                trySend(NetworkStatus.UNKNOWN)
                close()
                return@callbackFlow
            }
            awaitClose {
                // Called after success, timeout and ordinary navigation cancellation alike.
                runCatching { service.unregisterNetworkCallback(callback) }
            }
        }.first { it != NetworkStatus.UNAVAILABLE }
    }
}

private fun NetworkCapabilities.toStatus(): NetworkStatus =
    if (hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    ) NetworkStatus.AVAILABLE else NetworkStatus.UNAVAILABLE
