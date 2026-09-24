package com.harmony.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.network.InternetMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * Whether Home should present Discover as reachable, from the app's one
 * shared [InternetMonitor] — the same source Discover and Downloads use, so
 * Home never disagrees with them.
 *
 * Two adjustments for a page that is not itself online:
 *  - the monitor's five-second "internet is back" countdown counts as
 *    online (the connection is there; Discover handles the wait itself);
 *  - "offline" is only reported once it has lasted a moment. The monitor
 *    starts from a not-ready default before its first network callback,
 *    and without this every cold start flashed an offline notice on Home.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun rememberHomeOnline(): Boolean? {
    val context = LocalContext.current
    val flow = remember(context) {
        InternetMonitor.get(context).state
            .map { it.ready || it.secondsUntilReady > 0 }
            .distinctUntilChanged()
            .transformLatest { connected ->
                if (!connected) delay(OFFLINE_SETTLE_MS)
                emit(connected)
            }
    }
    val online by flow.collectAsStateWithLifecycle(initialValue = null)
    return online
}

private const val OFFLINE_SETTLE_MS = 1_500L
