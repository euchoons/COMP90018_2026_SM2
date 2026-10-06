package au.edu.unimelb.floraguide.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import au.edu.unimelb.floraguide.domain.repository.NetworkStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AndroidNetworkStatusProviderTest {
    private class Fixture {
        val context = mockk<Context>()
        val manager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val callback = slot<ConnectivityManager.NetworkCallback>()
        val provider = AndroidNetworkStatusProvider(context, manager)
        init {
            every { manager.activeNetwork } returns null
            every { manager.registerDefaultNetworkCallback(capture(callback)) } returns Unit
            every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } returns Unit
        }
        fun capabilities(validated: Boolean): NetworkCapabilities = mockk<NetworkCapabilities>().also {
            every { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        }
    }

    @Test fun wifiWithoutValidationIsNotUsableInternet() {
        val f = Fixture()
        every { f.manager.activeNetwork } returns f.network
        every { f.manager.getNetworkCapabilities(f.network) } returns f.capabilities(false)
        assertEquals(NetworkStatus.UNAVAILABLE, f.provider.currentStatus())
    }

    @Test fun validationResumesTheWaitAndUnregistersTheCallback() = runTest {
        val f = Fixture()
        val task = async { f.provider.awaitUsableNetwork() }
        runCurrent()
        f.callback.captured.onAvailable(f.network)
        runCurrent()
        assertTrue(task.isActive)
        f.callback.captured.onCapabilitiesChanged(f.network, f.capabilities(true))
        runCurrent(); task.await()
        verify(exactly = 1) { f.manager.unregisterNetworkCallback(f.callback.captured) }
    }

    @Test fun deadlineCancelsTheWaitAndUnregistersTheCallback() = runTest {
        val f = Fixture()
        val task = async { withTimeoutOrNull(30_000) { f.provider.awaitUsableNetwork(); true } }
        runCurrent(); advanceTimeBy(29_999); runCurrent()
        assertTrue(task.isActive)
        advanceTimeBy(1); runCurrent()
        assertNull(task.await())
        verify(exactly = 1) { f.manager.unregisterNetworkCallback(f.callback.captured) }
    }

    @Test fun navigationCancellationAlsoUnregistersTheCallback() = runTest {
        val f = Fixture()
        val task = async { f.provider.awaitUsableNetwork() }
        runCurrent(); task.cancelAndJoin()
        verify(exactly = 1) { f.manager.unregisterNetworkCallback(f.callback.captured) }
    }

    @Test fun aPermissionFailureIsUnknownNotOffline() = runTest {
        val f = Fixture()
        every { f.manager.activeNetwork } throws SecurityException("Permission unavailable")
        assertEquals(NetworkStatus.UNKNOWN, f.provider.currentStatus())
        f.provider.awaitUsableNetwork()
        verify(exactly = 0) { f.manager.registerDefaultNetworkCallback(any()) }
    }

    @Test fun registrationFailureDoesNotInventAnOfflineResult() = runTest {
        val f = Fixture()
        every { f.manager.registerDefaultNetworkCallback(any()) } throws IllegalStateException("Service unavailable")
        val task = async { f.provider.awaitUsableNetwork() }
        runCurrent(); task.await()
        verify(exactly = 0) { f.manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }
}
