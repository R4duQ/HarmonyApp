package com.harmony.feature.discover.provider

import com.harmony.core.ui.network.InternetState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Whether the device has validated internet, with the app's shared reconnect settle time. */
interface DiscoveryConnectivity { val state: StateFlow<InternetState> }

/** Whether a library URI can actually be opened (permission revoked, file moved…). */
fun interface LocalFileProbe { fun readable(uri: String): Boolean }

fun interface DiscoveryClock { fun now(): Long }

class SystemDiscoveryClock @Inject constructor() : DiscoveryClock {
    override fun now(): Long = System.currentTimeMillis()
}
