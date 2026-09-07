package com.harmony.core.ui.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** One application-scoped observer: navigation and rotation do not restart the five-second recovery. */
class InternetMonitor private constructor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = callbackFlow {
        if (connectivity == null) { trySend(false); close(); return@callbackFlow }
        var current = connectivity.activeNetwork
        fun NetworkCapabilities?.isInternet() = this != null &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        trySend(connectivity.getNetworkCapabilities(current).isInternet())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (current != network) { current = network; trySend(false) }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == current) trySend(capabilities.isInternet())
            }
            override fun onLost(network: Network) {
                if (network == current) { current = null; trySend(false) }
            }
        }
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.flowOn(Dispatchers.Main.immediate).withInternetRecovery()
        .stateIn(scope, SharingStarted.Eagerly, InternetState())

    companion object {
        @Volatile private var instance: InternetMonitor? = null
        fun get(context: Context): InternetMonitor = instance ?: synchronized(this) {
            instance ?: InternetMonitor(context.applicationContext).also { instance = it }
        }
    }
}
