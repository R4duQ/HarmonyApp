package com.harmony.core.eq

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * The audio sink that keeps hi-res audio hi-res through the EQ.
 *
 * Media3 1.5's DefaultAudioSink cannot do this with a custom processor: with
 * float output enabled it bypasses custom AudioProcessors altogether, and with
 * it disabled it converts every input to 16-bit before them. So this sink runs
 * the EQ itself, ahead of a float-output DefaultAudioSink:
 *
 *   decoder PCM (16/24/32-bit or float, 44.1-384 kHz)
 *     -> gapless trimming -> [CustomEqAudioProcessor] -> 32-bit float
 *     -> DefaultAudioSink (float output) -> AudioTrack at the source rate
 *
 * Nothing is resampled here, so a USB DAC that accepts 96 or 192 kHz gets the
 * file's own rate.
 *
 * Use it from your RenderersFactory:
 * ```
 * override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
 *     enableAudioTrackPlaybackParams: Boolean): AudioSink =
 *     HiResEqAudioSink.create(context, equalizer)
 * ```
 *
 * Playback speed: on the float path, DefaultAudioSink changes speed through
 * AudioTrack's own playback parameters (enabled by [create]) rather than
 * Sonic. Channel remapping requested by a decoder (outputChannels) is ignored
 * on that path, as it is in DefaultAudioSink's own float path.
 */
@UnstableApi
class HiResEqAudioSink(
    sink: AudioSink,
    val equalizer: CustomEqAudioProcessor,
) : ForwardingAudioSink(sink) {

    private val stage = EqSinkStage(
        equalizer,
        supports = equalizer::supports,
        passthrough = { equalizer.bitPerfectPassthrough },
        downstream = object : EqSinkStage.Downstream {
            override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) =
                sink.configure(format, specifiedBufferSize, outputChannels)

            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int) =
                sink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        },
    )

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        stage.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean =
        stage.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun playToEndOfStream() {
        // Called repeatedly until isEnded(): finish our own backlog first.
        if (stage.drainForEndOfStream()) super.playToEndOfStream()
    }

    override fun isEnded(): Boolean = stage.isIdle && super.isEnded()

    override fun hasPendingData(): Boolean = stage.hasPendingOutput || super.hasPendingData()

    override fun flush() {
        stage.flush()
        super.flush()
    }

    override fun reset() {
        stage.reset()
        super.reset()
    }

    companion object {
        /** A HiResEqAudioSink over a float-output DefaultAudioSink. */
        @JvmStatic
        fun create(context: Context, equalizer: CustomEqAudioProcessor): HiResEqAudioSink =
            HiResEqAudioSink(
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true)
                    .build(),
                equalizer,
            )
    }
}
