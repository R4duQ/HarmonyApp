package com.harmony.feature.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Who may use the native download clients right now.
 *
 * A single download (the Downloads screen, a Soulseek transfer) has the
 * engines to itself, as before. Album work may run several tracks at once
 * when the engine says it can (Mornye's runtime takes its provider per
 * request, so two downloads no longer change each other's route): callers
 * that are all album transfers share the gate, up to the smallest [run]
 * `parallel` among them. Anything else finds the gate busy.
 *
 * A background transfer ([runInBackground]: an online mix preparing its next
 * song) enters only when nobody holds the gate, and gives way: any [run]
 * cancels it, waits for it to let go (its files tidied as for any cancel),
 * and takes the gate. The background caller gets
 * [DownloadEngineBusyException] and tries again later.
 */
internal object DownloadEngineGate {
    private val lock = Any()
    private var holders = 0
    private var limit = 1
    private var backgroundHeld = false
    private var backgroundJob: Job? = null

    @Volatile var albumOwnsTransfer: Boolean = false
        private set

    suspend fun <T> run(albumTransfer: Boolean = false, parallel: Int = 1, block: suspend () -> T): T {
        yieldBackground()
        enter(albumTransfer, parallel.coerceAtLeast(1))
        return try { block() } finally { leave() }
    }

    suspend fun <T> runInBackground(block: suspend () -> T): T = coroutineScope {
        val job = async(start = CoroutineStart.LAZY) { block() }
        synchronized(lock) {
            if (holders != 0) {
                job.cancel()
                throw DownloadEngineBusyException()
            }
            holders = 1
            limit = 1
            albumOwnsTransfer = false
            backgroundHeld = true
            backgroundJob = job
        }
        try {
            job.await()
        } catch (e: CancellationException) {
            // Cancelled by a download the user started, not by our own caller: tell it apart.
            if (job.isCancelled && currentCoroutineContext().isActive) throw DownloadEngineBusyException() else throw e
        } finally {
            synchronized(lock) {
                backgroundHeld = false
                backgroundJob = null
            }
            leave()
        }
    }

    /** Cancels a background holder and waits (a while) for it to let go. */
    private suspend fun yieldBackground() {
        val job = synchronized(lock) { backgroundJob.takeIf { backgroundHeld } } ?: return
        job.cancel(CancellationException("a download the user started takes the engine"))
        withTimeoutOrNull(YIELD_TIMEOUT_MS) {
            while (synchronized(lock) { backgroundHeld }) delay(YIELD_POLL_MS)
        }
    }

    private fun enter(albumTransfer: Boolean, parallel: Int) = synchronized(lock) {
        when {
            holders == 0 -> { holders = 1; limit = parallel; albumOwnsTransfer = albumTransfer }
            // Only album transfers stack, and only as far as every one of them allows.
            albumTransfer && albumOwnsTransfer && holders < minOf(limit, parallel) -> { holders++; limit = minOf(limit, parallel) }
            else -> throw DownloadEngineBusyException()
        }
    }

    private fun leave() = synchronized(lock) {
        holders--
        if (holders == 0) { albumOwnsTransfer = false; limit = 1 }
    }

    private const val YIELD_TIMEOUT_MS = 15_000L
    private const val YIELD_POLL_MS = 25L
}
class DownloadEngineBusyException : Exception("Another download is active. Wait for it to finish, then resume.")
