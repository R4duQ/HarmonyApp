package com.harmony.playback.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.harmony.domain.playback.AudioLevels
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Measures the PCM going past and publishes it to [AudioLevels]. Changes
 * nothing about the audio.
 *
 * It exists as its OWN processor rather than a few extra lines inside
 * EqualizerAudioProcessor for one decisive reason: that processor reports
 * `isActive() = false` whenever the EQ is flat or off, and an inactive
 * processor is skipped by DefaultAudioSink entirely. Measuring there would
 * have produced a visualiser that only moved while the equaliser happened
 * to be engaged, which is the wrong coupling and a confusing bug to hit.
 * This one is unconditionally active.
 *
 * That does mean it copies every buffer, which is the honest cost of the
 * feature. The measurement itself is deliberately cheap — see [publish] —
 * so the copy dominates, and it's the same copy ReplayGain already performs
 * at unity gain.
 */
@UnstableApi
class LevelMeterAudioProcessor : BaseAudioProcessor() {

    private var encoding: Int = C.ENCODING_INVALID
    private var sampleRate: Int = 0

    private var scratch: ByteBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    // Accumulators for the current measurement window.
    private var sumSquares = 0.0
    private var crossings = 0
    private var counted = 0
    private var lastSign = 0

    // Smoothed output. Raw per-window RMS jitters hard enough to look like
    // noise on screen; the asymmetric coefficients below give a meter that
    // snaps up on a transient and eases down after it, which is how a level
    // meter is expected to behave and, not coincidentally, how the ear
    // perceives the envelope it's tracking.
    private var smoothedLevel = 0f
    private var smoothedBrightness = 0f

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    // Unconditionally active — the whole point. super.isActive() still gates
    // on having been configured with a usable format.
    override fun isActive(): Boolean = super.isActive() && encoding != C.ENCODING_INVALID

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        // Drain the caller's buffer first: Media3 may hand us the same
        // object replaceOutputBuffer() is about to return and clear.
        if (scratch.capacity() < remaining) {
            scratch = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        }
        scratch.clear()
        scratch.put(inputBuffer)
        scratch.flip()

        measure(scratch)

        scratch.rewind()
        replaceOutputBuffer(remaining).put(scratch).flip()
    }

    private fun measure(buffer: ByteBuffer) {
        val mark = buffer.position()
        if (encoding == C.ENCODING_PCM_FLOAT) {
            while (buffer.remaining() >= 4) accumulate(buffer.float)
        } else {
            while (buffer.remaining() >= 2) accumulate(buffer.short / 32768f)
        }
        buffer.position(mark)

        // One window ~= 40ms of audio. Fast enough that the bars track the
        // beat, slow enough that we aren't emitting far more often than the
        // display can draw.
        val windowSamples = (sampleRate.coerceAtLeast(8000) * 0.04f).toInt()
        if (counted >= windowSamples) publish()
    }

    private fun accumulate(sample: Float) {
        sumSquares += (sample * sample).toDouble()
        val sign = if (sample >= 0f) 1 else -1
        if (lastSign != 0 && sign != lastSign) crossings++
        lastSign = sign
        counted++
    }

    private fun publish() {
        val rms = sqrt(sumSquares / counted).toFloat()
        // Music sits well below full scale, so a linear RMS barely leaves the
        // floor. sqrt expands the quiet end, where nearly all the visible
        // movement in a track actually lives.
        val target = sqrt(rms.coerceIn(0f, 1f)) * 1.35f

        // Zero-crossing rate as a stand-in for spectral centroid: a bright,
        // cymbal-heavy passage crosses zero far more often than a bassline.
        // Normalised against 4 kHz, comfortably above where music's crossing
        // rate lands, so the result stays inside 0..1 without clipping for
        // ordinary material.
        val zcr = crossings.toFloat() / counted.toFloat() * sampleRate.coerceAtLeast(1) / 2f
        val targetBrightness = (zcr / 4000f).coerceIn(0f, 1f)

        smoothedLevel = smooth(smoothedLevel, target, attack = 0.55f, release = 0.12f)
        smoothedBrightness = smooth(smoothedBrightness, targetBrightness, 0.30f, 0.14f)
        AudioLevels.publish(smoothedLevel, smoothedBrightness)

        sumSquares = 0.0
        crossings = 0
        counted = 0
    }

    private fun smooth(current: Float, target: Float, attack: Float, release: Float): Float {
        val coefficient = if (target > current) attack else release
        return current + (target - current) * coefficient
    }

    override fun onFlush() {
        resetWindow()
    }

    override fun onReset() {
        resetWindow()
        scratch = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        encoding = C.ENCODING_INVALID
        AudioLevels.reset()
    }

    private fun resetWindow() {
        sumSquares = 0.0
        crossings = 0
        counted = 0
        lastSign = 0
        smoothedLevel = 0f
        smoothedBrightness = 0f
        AudioLevels.reset()
    }
}
