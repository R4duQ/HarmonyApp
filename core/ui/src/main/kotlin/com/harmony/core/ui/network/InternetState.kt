package com.harmony.core.ui.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

data class InternetState(val ready: Boolean = false, val secondsUntilReady: Int = 0) {
    val message: String get() = if (secondsUntilReady > 0)
        "Internet is back. Refreshing in $secondsUntilReady s…"
    else "Connect to the internet to use Discover and download music."
}

/** A second loss cancels recovery immediately; duplicate capability events never restart the clock. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun Flow<Boolean>.withInternetRecovery(): Flow<InternetState> = distinctUntilChanged().transformLatest { validated ->
    if (!validated) emit(InternetState()) else {
        for (seconds in 5 downTo 1) {
            emit(InternetState(secondsUntilReady = seconds))
            delay(1_000)
        }
        emit(InternetState(ready = true))
    }
}
