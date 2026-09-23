package com.harmony.feature.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * Whether Home should present Discover as reachable: a validated internet
 * connection on the default network.
 *
 * Registered only while Home is on screen (collectAsStateWithLifecycle), and
 * "offline" is only reported once it has lasted a moment, so a network
 * hand-over (Wi-Fi to mobile) does not flash an offline notice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun rememberHomeOnline(): Boolean? {
    val context = LocalContext.current.applicationContext
    val flow = remember(context) {
        connectivity(context)
            .distinctUntilChanged()
            .transformLatest { connected ->
                if (!connected) delay(OFFLINE_SETTLE_MS)
                emit(connected)
            }
    }
    val online by flow.collectAsStateWithLifecycle(initialValue = null)
    return online
}

private fun connectivity(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    if (manager == null) {
        trySend(true)
        awaitClose { }
        return@callbackFlow
    }
    fun NetworkCapabilities?.isInternet() = this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    trySend(manager.getNetworkCapabilities(manager.activeNetwork).isInternet())
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trySend(capabilities.isInternet())
        }
        override fun onLost(network: Network) {
            trySend(false)
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}

private const val OFFLINE_SETTLE_MS = 1_500L
