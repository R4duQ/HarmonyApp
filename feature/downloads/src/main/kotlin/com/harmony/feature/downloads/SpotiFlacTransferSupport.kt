package com.harmony.feature.downloads

import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The time a provider call gets, by what it is doing. */
internal data class TransferDeadlines(
    /** From the call to the first byte: the provider's own lookups, sign-in and stream negotiation. */
    val firstByteMs: Long,
    /** While transferring: the longest it may go without one more byte. */
    val stalledMs: Long,
    /** After the last byte: the runtime's own finishing (decryption, tags, cover). */
    val finalizeMs: Long,
    /** However steadily it is receiving, one call does not outlast this. */
    val longestMs: Long,
)

/**
 * Whether a provider call is still getting somewhere.
 *
 * The watchdog used to give a call a fixed time in all (95 s), so a large
 * hi-res file on a slow connection was stopped while it was downloading
 * normally. The time now depends on the phase:
 *
 *  - before the first byte, [TransferDeadlines.firstByteMs] in all;
 *  - while transferring, the clock restarts only when the byte count grows
 *    (a repeated count, a status change or a smaller count is not progress),
 *    and the call is stopped after [TransferDeadlines.stalledMs] without it;
 *  - once every byte is in, [TransferDeadlines.finalizeMs] for the runtime to
 *    finish;
 *  - and never longer than [TransferDeadlines.longestMs].
 */
internal class TransferActivity(private val deadlines: TransferDeadlines, private val now: () -> Long) {
    enum class Phase { WAITING, TRANSFERRING, FINALIZING }

    @Volatile private var startedAt = now()
    @Volatile private var lastProgressAt = startedAt
    @Volatile private var mostBytes = 0L
    @Volatile private var totalBytes = 0L

    /** A new provider call begins. */
    @Synchronized fun start() {
        startedAt = now()
        lastProgressAt = startedAt
        mostBytes = 0L
        totalBytes = 0L
    }

    /** The bytes received so far and the size expected (0 when unknown), as the runtime reports them. */
    @Synchronized fun note(downloadedBytes: Long, expectedBytes: Long = 0L) {
        if (expectedBytes > 0L) totalBytes = expectedBytes
        if (downloadedBytes > mostBytes) {
            mostBytes = downloadedBytes
            lastProgressAt = now()
        }
    }

    val receivedBytes: Long get() = mostBytes

    val phase: Phase
        get() = when {
            mostBytes <= 0L -> Phase.WAITING
            totalBytes > 0L && mostBytes >= totalBytes -> Phase.FINALIZING
            else -> Phase.TRANSFERRING
        }

    /** Null while the call may go on; otherwise why it is stopped. */
    @Synchronized fun verdict(): String? {
        val time = now()
        if (time - startedAt >= deadlines.longestMs) return "ran for ${deadlines.longestMs / 60_000} minutes"
        val quiet = time - lastProgressAt
        return when (phase) {
            Phase.WAITING -> "did not start within ${deadlines.firstByteMs / 1000}s".takeIf { quiet >= deadlines.firstByteMs }
            Phase.TRANSFERRING -> "received nothing for ${deadlines.stalledMs / 1000}s".takeIf { quiet >= deadlines.stalledMs }
            Phase.FINALIZING -> "did not finish within ${deadlines.finalizeMs / 1000}s of the last byte".takeIf { quiet >= deadlines.finalizeMs }
        }
    }
}

/** What a failed download was, as far as what to do next goes. */
internal enum class SpotiFlacFailureKind {
    /** The provider stopped answering or the transfer stalled. */
    TIMEOUT,
    /** The phone has no connection, or it dropped. */
    NO_NETWORK,
    /** The provider asked to be left alone for a while ([SpotiFlacFailures.retryAfterSeconds]). */
    RATE_LIMITED,
    /** The provider wants the user to verify or sign in. */
    VERIFICATION,
    /** No provider has this recording. */
    NOT_FOUND,
    /** The transfer did not match what was there before (a changed file, a refused range). */
    INTEGRITY,
    /** The phone could not store or convert it. */
    LOCAL,
    OTHER;

    /**
     * Whether what was received is worth keeping for the next attempt: only
     * when the same file will be asked for again. After an integrity failure
     * the partial file is the problem and must go.
     */
    val keepsPartial: Boolean get() = this == TIMEOUT || this == NO_NETWORK || this == RATE_LIMITED

    /** Whether trying again later, unchanged, can work. */
    val retriable: Boolean get() = keepsPartial || this == INTEGRITY
}

internal object SpotiFlacFailures {
    fun kind(errorType: String?, message: String? = null): SpotiFlacFailureKind {
        val type = errorType.orEmpty().trim().lowercase(Locale.ROOT)
        val text = message.orEmpty().lowercase(Locale.ROOT)
        return when {
            type == "verification_required" || type == "provider_reauth_required" -> SpotiFlacFailureKind.VERIFICATION
            type == "rate_limit" || type == "too_many_requests" -> SpotiFlacFailureKind.RATE_LIMITED
            type == "integrity_failed" -> SpotiFlacFailureKind.INTEGRITY
            type == "provider_timeout" || type == "timeout" -> SpotiFlacFailureKind.TIMEOUT
            type == "network_unavailable" || type == "network" || type == "transient_network" -> SpotiFlacFailureKind.NO_NETWORK
            type == "not_found" || type == "unsupported_lossless_quality" || type == "not_lossless_flac" -> SpotiFlacFailureKind.NOT_FOUND
            type == "storage_error" || type == "permission" || type == "missing_output" ||
                type.endsWith("_finalization_failed") || type == "flac_quality_limit_failed" -> SpotiFlacFailureKind.LOCAL
            // Untyped failures: go by what the message says, the way the runtime's own mapping does.
            "429" in text || "rate limit" in text || "too many requests" in text -> SpotiFlacFailureKind.RATE_LIMITED
            "timed out" in text || "timeout" in text -> SpotiFlacFailureKind.TIMEOUT
            "unable to resolve host" in text || "network" in text || "connection" in text -> SpotiFlacFailureKind.NO_NETWORK
            else -> SpotiFlacFailureKind.OTHER
        }
    }

    /**
     * How long the provider asked to be left alone, in seconds: its own
     * `retry_after_seconds` when it gave one, else [fallbackSeconds], doubled
     * for each retry already made; always within [minSeconds]..[maxSeconds].
     */
    fun retryAfterSeconds(
        advertised: Long,
        retryOrdinal: Int,
        fallbackSeconds: Long,
        minSeconds: Long,
        maxSeconds: Long,
    ): Long {
        if (advertised > 0L) return advertised.coerceIn(minSeconds, maxSeconds)
        return (fallbackSeconds shl retryOrdinal.coerceIn(0, 2)).coerceAtMost(maxSeconds)
    }
}

/**
 * One call into the native runtime, watched.
 *
 * The call blocks a worker thread for as long as the runtime works. This
 * waits for it in short ticks so that three things can end the wait: the
 * call returning, the caller being cancelled, and [TransferActivity] deciding
 * the call is going nowhere. In the last two the runtime is told to stop
 * ([stopNative]) and is given [letGoMs] to return; what it returns after that
 * is never used, and the thread it holds is the runtime's to give back.
 */
internal class WatchedCall(
    private val tickMs: Long,
    private val letGoMs: Long,
) {
    sealed interface Result {
        /** The runtime answered. */
        class Returned(val raw: String) : Result

        /** The watchdog stopped it; [reason] says why. */
        class Stopped(val reason: String) : Result

        /** The caller was cancelled. */
        object Cancelled : Result
    }

    fun await(
        call: Future<String>,
        activity: TransferActivity,
        isCancelled: () -> Boolean,
        stopNative: () -> Unit,
    ): Result {
        activity.start()
        while (true) {
            try {
                return Result.Returned(call.get(tickMs, TimeUnit.MILLISECONDS))
            } catch (_: TimeoutException) {
                val cancelled = isCancelled()
                val verdict = if (cancelled) null else activity.verdict() ?: continue
                runCatching { stopNative() }
                // A moment to let go of its files; a late answer is not looked at.
                runCatching { call.get(letGoMs, TimeUnit.MILLISECONDS) }
                call.cancel(true)
                return if (cancelled) Result.Cancelled else Result.Stopped(verdict!!)
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
        }
    }
}
