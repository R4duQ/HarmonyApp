package com.harmony.core.eq

import android.os.SystemClock
import android.util.Log

/**
 * Diagnostics for the playback chain, in Logcat under the tag `EQ_DEBUG`
 * (`adb logcat -s EQ_DEBUG`), so a stall or a silence can be matched to the
 * moment it happened:
 *
 *  - `NAN_HEAL`: the engine found non-finite state and healed it in place;
 *  - `CORRUPT_INPUT`: samples that were NaN, infinite or beyond +24 dBFS were repaired;
 *  - `PARTIAL_FRAME`: a buffer ended mid-frame; the bytes were carried over, not left behind;
 *  - `PIPELINE_WEDGED`: the EQ pipeline took no input with nothing to output, and was flushed;
 *  - `DECODER_ERROR`, `DECODER_SKIP`: a decode or container error, and the hop past it;
 *  - `PLAYBACK_STALL`: playback stood still at one position, and was nudged on.
 *
 * Every kind is rate-limited (one line per kind per second, with a count of
 * what was folded in), so a burst on the audio thread can't flood the log.
 * Times are given as the media position where the caller knows it, and as
 * seconds into the stream since the last flush otherwise.
 */
object EqDebug {
    const val TAG = "EQ_DEBUG"

    /** Set false to silence everything (it is cheap either way: nothing is formatted unless logged). */
    @Volatile
    var enabled: Boolean = true

    /** Called with every line that is logged; tests and in-app diagnostics can watch it. */
    @Volatile
    var listener: ((kind: String, message: String) -> Unit)? = null

    private const val MIN_INTERVAL_MS = 1_000L
    private val lastLogged = HashMap<String, Long>()
    private val folded = HashMap<String, Int>()

    fun nanHealed(times: Int, framesIntoStream: Long, sampleRate: Int) =
        log("NAN_HEAL", warn = true) {
            "engine healed non-finite state ${times}x at ${at(framesIntoStream, sampleRate)}; playback continued"
        }

    fun corruptInput(samples: Int, framesIntoStream: Long, sampleRate: Int) =
        log("CORRUPT_INPUT", warn = true) {
            "$samples NaN/Inf/out-of-range input samples repaired at ${at(framesIntoStream, sampleRate)}"
        }

    fun partialFrame(bytes: Int, frameBytes: Int) =
        log("PARTIAL_FRAME", warn = false) { "buffer ended mid-frame: $bytes of $frameBytes bytes carried over" }

    fun pipelineWedged(remainingBytes: Int, presentationTimeUs: Long) =
        log("PIPELINE_WEDGED", warn = true) {
            "EQ pipeline took none of $remainingBytes bytes at ${presentationTimeUs / 1000} ms with nothing to output; flushed it"
        }

    fun decoderError(code: String, positionMs: Long, detail: String?) =
        log("DECODER_ERROR", warn = true) { "$code at $positionMs ms${detail?.let { ": $it" }.orEmpty()}" }

    fun decoderSkip(fromMs: Long, toMs: Long) =
        log("DECODER_SKIP", warn = true) { "skipping past a bad stretch: $fromMs ms -> $toMs ms" }

    fun playbackStall(state: String, positionMs: Long, stuckMs: Long, nudgeToMs: Long) =
        log("PLAYBACK_STALL", warn = true) {
            "playback stood still at $positionMs ms for $stuckMs ms ($state); nudged to $nudgeToMs ms"
        }

    private fun at(frames: Long, sampleRate: Int): String =
        if (sampleRate > 0) "%.3f s into the stream".format(frames.toDouble() / sampleRate) else "frame $frames"

    private inline fun log(kind: String, warn: Boolean, message: () -> String) {
        if (!enabled) return
        val now = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
        val text: String
        synchronized(this) {
            val last = lastLogged[kind]
            if (last != null && now - last < MIN_INTERVAL_MS && now != 0L) {
                folded[kind] = (folded[kind] ?: 0) + 1
                return
            }
            lastLogged[kind] = now
            val extra = folded.remove(kind)?.let { " (+$it more since the last line)" }.orEmpty()
            text = message() + extra
        }
        if (warn) Log.w(TAG, "$kind $text") else Log.d(TAG, "$kind $text")
        listener?.invoke(kind, text)
    }
}
