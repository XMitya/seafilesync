package com.xmitya.seafilesync.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the current connection allows. */
data class NetworkState(
    val connected: Boolean,
    val metered: Boolean,
) {
    fun allowsSync(wifiOnly: Boolean): Boolean = connected && (!wifiOnly || !metered)
}

/**
 * Reports whether transfers should be running.
 *
 * "Metered" rather than "is it wifi": a phone's hotspot and some home connections are wifi but
 * charged by the byte, and the system already knows which is which. Asking about the transport
 * would get those cases wrong in the direction that costs the user money.
 */
class NetworkPolicy(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun current(): NetworkState {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return NetworkState(connected = false, metered = true)
        return capabilities.toState()
    }

    fun observe(): Flow<NetworkState> = callbackFlow {
        trySend(current())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Fires when a connection becomes metered without changing, which is exactly the
                // case a transport check would miss.
                trySend(capabilities.toState())
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun NetworkCapabilities.toState() = NetworkState(
        connected = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
