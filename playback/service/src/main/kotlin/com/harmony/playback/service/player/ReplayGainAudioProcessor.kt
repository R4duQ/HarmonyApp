package com.harmony.playback.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
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
        val output = replaceOutputBuffer(remaining)
        if (s == 1f) {
            output.put(inputBuffer)
        } else if (encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) {
                val sample = inputBuffer.float * s
                output.putFloat(sample.coerceIn(-1f, 1f))
            }
        } else {
            while (inputBuffer.remaining() >= 2) {
                val sample = inputBuffer.short.toFloat() * s
                output.putShort(
                    sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                )
            }
        }
        output.flip()
    }
}
