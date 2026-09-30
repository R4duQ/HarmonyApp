package com.harmony.feature.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [lookup] on its own job in [scope] and waits for it at most [timeoutMs].
 * Returns null when it fails or runs out of time.
 *
 * A blocking network call (DNS, HttpURLConnection) ignores coroutine
 * cancellation, so a plain withTimeout around it only returns once the call
 * itself gives up, which on a bad connection can take minutes. Waiting on a
 * separate job lets the caller move on at the deadline while the call finishes
 * in the background.
 */
internal suspend fun <T : Any> awaitWithin(
    timeoutMs: Long,
    scope: CoroutineScope,
    lookup: suspend () -> T?,
): T? {
    val job = scope.async { runCatching { lookup() }.getOrNull() }
    return try {
        withTimeoutOrNull(timeoutMs) { job.await() }.also { if (it == null) job.cancel() }
    } catch (cancelled: CancellationException) {
        job.cancel()
        throw cancelled
    }
}
