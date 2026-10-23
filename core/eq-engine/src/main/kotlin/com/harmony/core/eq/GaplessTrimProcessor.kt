package com.harmony.core.eq

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Removes encoder delay and padding (gapless metadata) from PCM of any
 * encoding, as Media3's own TrimmingAudioProcessor does. DefaultAudioSink only
 * applies that one on its 16-bit path; [HiResEqAudioSink] hands it float, so it
 * trims with this instead.
 *
 * Unlike Media3's, it can move on to the next gapless item without a flush
 * ([startNextItem]): the processors after it keep their state across the
 * boundary, so the EQ's filters and limiter never restart mid-album.
 */
@UnstableApi
class GaplessTrimProcessor : BaseAudioProcessor() {
    private var trimStartFrames = 0
    private var trimEndFrames = 0
    private var reconfigured = false
    private var pendingTrimStartBytes = 0
    private var endBuffer = ByteArray(0)
    private var endBufferSize = 0
    private var dropPaddingAtEnd = false

    /** Delay and padding of the item about to be configured. */
    fun setTrimFrameCount(startFrames: Int, endFrames: Int) {
        trimStartFrames = startFrames.coerceAtLeast(0)
        trimEndFrames = endFrames.coerceAtLeast(0)
    }

    /**
     * A gapless transition to an item of the same format: what is held back
     * now is the previous item's padding, and goes; the next item's delay is
     * trimmed from its start.
     */
    fun startNextItem(startFrames: Int, endFrames: Int) {
        setTrimFrameCount(startFrames, endFrames)
        if (!isActive) return
        endBufferSize = 0
        pendingTrimStartBytes = trimStartFrames * inputAudioFormat.bytesPerFrame
        val endBytes = trimEndFrames * inputAudioFormat.bytesPerFrame
        if (endBuffer.size != endBytes) endBuffer = ByteArray(endBytes)
    }

    /**
     * Whether the next end of stream closes an item that another follows (its
     * padding is dropped), or the whole stream (it is kept, as Media3 does,
     * since a drain can also happen mid-item).
     */
    fun setDropPaddingAtEnd(drop: Boolean) {
        dropPaddingAtEnd = drop
    }

    // Always active, even with nothing to trim yet: a later gapless item may need it.
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        reconfigured = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        var remaining = limit - position
        if (remaining == 0) return

        val trimBytes = min(remaining, pendingTrimStartBytes)
        pendingTrimStartBytes -= trimBytes
        inputBuffer.position(position + trimBytes)
        if (pendingTrimStartBytes > 0) return
        remaining -= trimBytes

        // Keep endBuffer as full as possible: whatever is in it when the item
        // ends is the padding. Output only what overflows it.
        var bytesToOutput = endBufferSize + remaining - endBuffer.size
        val output = replaceOutputBuffer(bytesToOutput.coerceAtLeast(0))

        val fromEnd = bytesToOutput.coerceIn(0, endBufferSize)
        output.put(endBuffer, 0, fromEnd)
        bytesToOutput -= fromEnd

        val fromInput = bytesToOutput.coerceIn(0, remaining)
        inputBuffer.limit(inputBuffer.position() + fromInput)
        output.put(inputBuffer)
        inputBuffer.limit(limit)
        remaining -= fromInput

        endBufferSize -= fromEnd
        System.arraycopy(endBuffer, fromEnd, endBuffer, 0, endBufferSize)
        inputBuffer.get(endBuffer, endBufferSize, remaining)
        endBufferSize += remaining

        output.flip()
    }

    override fun getOutput(): ByteBuffer {
        if (super.isEnded() && endBufferSize > 0) {
            if (!dropPaddingAtEnd) replaceOutputBuffer(endBufferSize).put(endBuffer, 0, endBufferSize).flip()
            endBufferSize = 0
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && endBufferSize == 0

    override fun onFlush() {
        // A flush activates a configuration or follows a seek. The start is
        // trimmed after a configuration only: after a seek inside an item there
        // is no delay where playback resumes (Media3 leaves a pending trim
        // armed here; dropping it is the correct case of its TODO).
        if (reconfigured) {
            reconfigured = false
            pendingTrimStartBytes = trimStartFrames * inputAudioFormat.bytesPerFrame
            endBuffer = ByteArray(trimEndFrames * inputAudioFormat.bytesPerFrame)
        } else {
            pendingTrimStartBytes = 0
        }
        endBufferSize = 0
        dropPaddingAtEnd = false
    }

    override fun onReset() {
        endBuffer = ByteArray(0)
        endBufferSize = 0
        pendingTrimStartBytes = 0
        reconfigured = false
        dropPaddingAtEnd = false
    }
}
