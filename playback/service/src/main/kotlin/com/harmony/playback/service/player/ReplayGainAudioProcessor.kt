package com.harmony.playback.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Applies ReplayGain as a linear scale on PCM samples.
 *
 * Sits inside the [androidx.media3.exoplayer.audio.DefaultAudioSink] processor
 * chain, i.e. after decoding and before resampling/output. Supports BOTH
 * 16-bit integer and 32-bit float PCM: with float output disabled on the sink
 * (required — see HarmonyPlayer — because DefaultAudioSink bypasses custom
 * processors entirely in float-output mode), the chain delivers 16-bit PCM,
 * which is the path that actually runs in practice. Math is done in float
 * either way; only the read/write width differs.
 *
 * Remains in the configured chain at 0 dB so changing gain mid-track works.
 * Unity gain copies the PCM bytes unchanged.
 */
@UnstableApi
class ReplayGainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var scale: Float = 1f

    private var encoding: Int = C.ENCODING_INVALID

    /**
     * Private copy of the current input, filled before [replaceOutputBuffer]
     * is called.
     *
     * Media3 can hand a processor a ByteBuffer that is the very same object
     * `replaceOutputBuffer()` is about to return. When that happened,
     * `output.put(inputBuffer)` threw
     * `IllegalArgumentException: The source buffer is this buffer`, ExoPlayer
     * reported a playback error, HarmonyPlayer's recovery skipped to the next
     * track, and the next track hit it again — the player raced forward
     * through hundreds of songs, restarting the audio every few hundred
     * milliseconds.
     *
     * Copying the input out first makes the aliasing irrelevant: whatever
     * buffer the pipeline hands us is drained before we ask for an output
     * buffer, so the two can never be read and cleared at the same time.
     * `replaceOutputBuffer()` calls `clear()` on its buffer, so checking for
     * aliasing AFTER calling it would already be too late — the input's
     * position and limit would be gone.
     */
    private var scratch: ByteBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    /** Set the gain for the current track. 0f = unity / bypass. */
    fun setGainDb(db: Float) {
        val safeDb = db.takeIf { it.isFinite() }?.coerceIn(-60f, 24f) ?: 0f
        scale = 10f.pow(safeDb / 20f)
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val s = scale
        val remaining = inputBuffer.remaining()

        // Drain the caller's buffer FIRST. See the note on `scratch`: the
        // buffer we are handed may be the same object replaceOutputBuffer()
        // returns, and that call clears it.
        if (scratch.capacity() < remaining) {
            scratch = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        }
        scratch.clear()
        scratch.put(inputBuffer)
        scratch.flip()

        val output = replaceOutputBuffer(remaining)
        if (s == 1f) {
            output.put(scratch)
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            while (scratch.remaining() >= 4) {
                val sample = scratch.float * s
                output.putFloat(sample.coerceIn(-1f, 1f))
            }
        } else {
            while (scratch.remaining() >= 2) {
                val sample = scratch.short.toFloat() * s
                output.putShort(
                    sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                )
            }
        }
        output.flip()
    }
}
