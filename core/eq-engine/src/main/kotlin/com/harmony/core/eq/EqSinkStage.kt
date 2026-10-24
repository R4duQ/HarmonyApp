package com.harmony.core.eq

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/**
 * The state machine behind [HiResEqAudioSink]: gapless trimming and the EQ,
 * run ahead of a downstream sink that receives float PCM.
 *
 * It keeps the downstream sink's contract: a buffer the sink did not take in
 * full is offered again, unchanged, before anything else; timestamps run
 * continuously with the frames actually handed over; and when the format
 * really changes, everything of the old format is written before the sink is
 * reconfigured. Kept apart from Media3's AudioSink so it can be tested on the JVM.
 */
@UnstableApi
internal class EqSinkStage(
    private val equalizer: AudioProcessor,
    /** Whether [equalizer] can take this format at all. */
    private val supports: (AudioFormat) -> Boolean,
    /** Bit-perfect mode: PCM goes straight down, untouched. */
    private val passthrough: () -> Boolean,
    private val downstream: Downstream,
) {
    interface Downstream {
        fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?)
        fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean
    }

    private class Pending(val format: Format, val specifiedBufferSize: Int, val outputChannels: IntArray?)

    private val trimmer = GaplessTrimProcessor()
    private val pipeline = AudioProcessingPipeline(ImmutableList.of(trimmer, equalizer))

    private var configured = false

    /** Whether PCM goes through the EQ (otherwise everything passes straight down). */
    var processing = false
        private set
    private var inputFormat = AudioFormat.NOT_SET
    private var outputFrameBytes = 0
    private var outputSampleRate = 0

    /** Same format, next gapless item: the sink hears about it once our backlog is out. */
    private var softPending: Pending? = null

    /** A different format: drain everything first, then switch. */
    private var hardPending: Pending? = null

    private var output: ByteBuffer = EMPTY
    private var outputTimeUs = 0L
    private var baseTimeUs = C.TIME_UNSET
    private var framesOut = 0L
    private var draining = false
    private var endOfStreamDrained = false
    private var wedgedCalls = 0

    val hasPendingOutput: Boolean get() = output.hasRemaining()

    /** Nothing of ours is left to write. */
    val isIdle: Boolean get() = !processing || (endOfStreamDrained && !output.hasRemaining())

    fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val pending = Pending(format, specifiedBufferSize, outputChannels)
        val wanted = processingFormatOf(format)
        when {
            !configured || !processing -> apply(pending)  // nothing of ours in flight
            wanted != null && wanted == inputFormat -> {
                // Gapless, same format: keep the EQ running through the boundary.
                trimmer.startNextItem(format.encoderDelay, format.encoderPadding)
                softPending = pending
            }
            else -> hardPending = pending
        }
    }

    fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!processing) return downstream.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (!flushOutput()) return false

        softPending?.let {
            softPending = null
            downstream.configure(floatFormatOf(it.format), it.specifiedBufferSize, it.outputChannels)
        }
        hardPending?.let {
            if (!drain(dropPadding = true)) return false
            hardPending = null
            apply(it)
            if (!processing) return downstream.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        if (baseTimeUs == C.TIME_UNSET) baseTimeUs = presentationTimeUs
        val framesBefore = framesOut
        var consumed = false
        while (buffer.hasRemaining()) {
            val before = buffer.position()
            pipeline.queueInput(buffer)
            if (!takeOutput()) return false  // the sink is full: the rest of the input waits
            if (buffer.position() == before) break  // the pipeline takes nothing more right now
            consumed = true
        }
        if (!buffer.hasRemaining()) {
            wedgedCalls = 0
            return true
        }
        // Not taken in full, yet the sink took everything we had: if the
        // pipeline also took nothing and produced nothing, call after call, it
        // is wedged. Left alone, the sink would offer this buffer forever and
        // playback would freeze here until a seek flushed it. Flush it now
        // instead, as the seek would, and carry on from this buffer.
        if (!consumed && framesOut == framesBefore && !output.hasRemaining()) {
            if (++wedgedCalls >= WEDGED_AFTER_CALLS) {
                EqDebug.pipelineWedged(buffer.remaining(), presentationTimeUs)
                wedgedCalls = 0
                pipeline.flush()
            }
        } else {
            wedgedCalls = 0
        }
        return false
    }

    /** At the end of the stream: true once everything we hold has gone down to the sink. */
    fun drainForEndOfStream(): Boolean {
        if (!processing || endOfStreamDrained) return true
        if (!flushOutput()) return false
        softPending?.let {
            softPending = null
            downstream.configure(floatFormatOf(it.format), it.specifiedBufferSize, it.outputChannels)
        }
        if (!drain(dropPadding = false)) return false
        endOfStreamDrained = true
        return true
    }

    fun flush() {
        wedgedCalls = 0
        output = EMPTY
        draining = false
        endOfStreamDrained = false
        baseTimeUs = C.TIME_UNSET
        framesOut = 0
        val pending = hardPending ?: softPending
        hardPending = null
        softPending = null
        when {
            pending != null -> apply(pending)  // as a sink does: a flush activates the pending configuration
            processing -> pipeline.flush()
        }
    }

    fun reset() {
        pipeline.reset()
        configured = false
        processing = false
        inputFormat = AudioFormat.NOT_SET
        hardPending = null
        softPending = null
        output = EMPTY
        draining = false
        endOfStreamDrained = false
        baseTimeUs = C.TIME_UNSET
        framesOut = 0
    }

    private fun processingFormatOf(format: Format): AudioFormat? {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || passthrough()) return null
        val audioFormat = AudioFormat(format)
        return audioFormat.takeIf(supports)
    }

    private fun apply(pending: Pending) {
        val wanted = processingFormatOf(pending.format)
        output = EMPTY
        draining = false
        endOfStreamDrained = false
        baseTimeUs = C.TIME_UNSET
        framesOut = 0
        configured = true
        if (wanted == null) {
            processing = false
            inputFormat = AudioFormat.NOT_SET
            downstream.configure(pending.format, pending.specifiedBufferSize, pending.outputChannels)
            return
        }
        trimmer.setTrimFrameCount(pending.format.encoderDelay, pending.format.encoderPadding)
        val out = try {
            pipeline.configure(wanted)
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            throw IllegalStateException("EQ pipeline rejected $wanted", e)  // supports() said yes
        }
        pipeline.flush()
        processing = true
        inputFormat = wanted
        outputFrameBytes = out.bytesPerFrame
        outputSampleRate = out.sampleRate
        downstream.configure(floatFormatOf(pending.format), pending.specifiedBufferSize, pending.outputChannels)
    }

    private fun floatFormatOf(format: Format): Format {
        val out = pipeline.outputAudioFormat.takeUnless { it == AudioFormat.NOT_SET }
            ?: AudioFormat(format.sampleRate, format.channelCount, C.ENCODING_PCM_FLOAT)
        // Trimming is done here; the sink must not trim again.
        return format.buildUpon()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(out.encoding)
            .setSampleRate(out.sampleRate)
            .setChannelCount(out.channelCount)
            .setEncoderDelay(0)
            .setEncoderPadding(0)
            .build()
    }

    /** Takes the pipeline's next output and offers it downstream. */
    private fun takeOutput(): Boolean {
        output = pipeline.output
        // Timestamps follow what was actually handed over, so they stay
        // consistent with the frames the sink counts (trimming removes frames
        // the decoder's timestamps still include).
        outputTimeUs = baseTimeUs + framesOut * C.MICROS_PER_SECOND / outputSampleRate.coerceAtLeast(1)
        return flushOutput()
    }

    /** Offers the pending output again; true once the sink has taken all of it. */
    private fun flushOutput(): Boolean {
        if (!output.hasRemaining()) return true
        val before = output.remaining()
        val done = downstream.handleBuffer(output, outputTimeUs, 1)
        if (outputFrameBytes > 0) framesOut += (before - output.remaining()) / outputFrameBytes
        return done
    }

    private fun drain(dropPadding: Boolean): Boolean {
        if (!draining) {
            trimmer.setDropPaddingAtEnd(dropPadding)
            pipeline.queueEndOfStream()
            draining = true
        }
        if (baseTimeUs == C.TIME_UNSET) baseTimeUs = 0
        var guard = 0
        while (!pipeline.isEnded() && guard++ < MAX_DRAIN_STEPS) {
            if (!flushOutput()) return false
            if (!takeOutput()) return false
        }
        if (!flushOutput()) return false
        draining = false
        return true
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0)
        const val MAX_DRAIN_STEPS = 64

        /** Calls in a row with no progress at all before the pipeline counts as wedged. */
        const val WEDGED_AFTER_CALLS = 3
    }
}
