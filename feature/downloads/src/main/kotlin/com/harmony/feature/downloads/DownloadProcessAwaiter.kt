package com.harmony.feature.downloads

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Cancellation and timeout must stop the encoder before the staging directory is removed. */
internal suspend fun awaitDownloadProcess(process: Process, timeoutMs: Long): Boolean = try {
    withTimeoutOrNull(timeoutMs) {
        while (process.isAlive) delay(100)
        true
    } ?: false
} finally {
    if (process.isAlive) {
        runCatching { process.destroy() }
        runCatching { if (process.isAlive) process.destroyForcibly() }
    }
}
