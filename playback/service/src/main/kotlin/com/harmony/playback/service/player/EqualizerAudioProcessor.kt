package com.harmony.playback.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.harmony.core.model.EqSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 10-band graphic EQ + bass/treble shelves as a PCM AudioProcessor.
 *
 * Distortion-free by design (added after real-world listening showed harsh
 * artifacts when boosting):
 *
 *  1. AUTO HEADROOM PRE-AMP. Boosting a band pushes already-loud samples past
 *     full scale; the old code hard-clamped them (`coerceIn`), i.e. HARD
 *     CLIPPING — squared-off waveforms, audible as crunchy distortion,
 *     worst exactly in the common case (bass boost on loud music). Now the
 *     total output is automatically attenuated by the worst-case boost
 *     (max positive band gain + max positive shelf gain), so boosted
 *     frequencies rise INTO reserved headroom instead of into the ceiling.
 *     This is the same "auto pre-amp" approach used by Wavelet/pro EQs. The
 *     audible trade: heavy boosts make overall playback slightly quieter —
 *     correct behavior; turn the volume up, cleanly.
 *
 *  2. SMOOTHED GAIN CHANGES. The pre-amp target moves with a short exponential
 *     ramp (~10 ms time constant) applied per sample, so dragging a dial
 *     mid-song glides rather than stepping ("zipper" artifacts). Biquad
 *     STATE also survives coefficient swaps (unchanged), keeping filter
 *     changes click-free.
 *
 *  3. SOFT LIMITER as the final safety net. Anything that still exceeds
 *     ~0.92 full scale (e.g. pathological inter-band summation, or ReplayGain
 *     boost stacking upstream) is rounded off with a tanh knee instead of
 *     squared off by a clamp. Below the knee the signal passes bit-exact.
 *
 * Filter design: RBJ peaking EQ per band, low-shelf @100 Hz, high-shelf
 * @8 kHz. Supports 16-bit int and float PCM input (16-bit is the real path:
 * DefaultAudioSink bypasses custom processors entirely in float-output mode,
 * so the sink runs with float output disabled); all math is float internally.
 *
 * Concurrency: UI thread calls [apply]; audio thread reads volatile
 * references — no locks, no allocation on the audio path.
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    private class Coeffs(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

    /** One filter's state per channel: [channel][2]. */
    private class Stage(val coeffs: Coeffs) {
        lateinit var state: Array<FloatArray>
        fun ensureChannels(channels: Int) {
            if (!::state.isInitialized || state.size != channels) {
                state = Array(channels) { FloatArray(2) }
            }
        }

        /** Whether this stage's state was sized for [channels]. */
        fun fits(channels: Int) = ::state.isInitialized && state.size >= channels

        fun process(x: Float, ch: Int): Float {
            val s = state[ch]
            val y = coeffs.b0 * x + s[0]
            s[0] = coeffs.b1 * x - coeffs.a1 * y + s[1]
            s[1] = coeffs.b2 * x - coeffs.a2 * y
            return y
        }
    }

    @Volatile
    private var stages: Array<Stage> = emptyArray()

    @Volatile
    private var enabled = false

    /** Auto pre-amp target (linear); audio thread eases toward it. */
    @Volatile
    private var targetPreamp = 1f

    /** Current smoothed pre-amp, owned by the audio thread only. */
    /** See ReplayGainAudioProcessor.scratch — private copy of the input. */
    private var scratch: ByteBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    private var currentPreamp = 1f

    // Volatile because onConfigure runs on the audio thread while apply()
    // runs on the UI thread. Without it the UI thread could see sampleRate
    // already set but channelCount still 0 and size the filter state for one
    // channel, which the audio thread then indexes as stereo.
    @Volatile
    private var sampleRate = 0

    @Volatile
    private var channelCount = 0

    private var encoding: Int = C.ENCODING_INVALID

    @Volatile
    private var pendingSettings: EqSettings = EqSettings()

    fun apply(settings: EqSettings) {
        if (settings == pendingSettings) return // skip redundant rebuild churn
        pendingSettings = settings
        enabled = settings.enabled && !settings.isFlat()
        if (sampleRate > 0) rebuild()
    }

    private fun EqSettings.isFlat() =
        bandGainsDb.all { it == 0f } && bassBoostDb == 0f && trebleBoostDb == 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        rebuild()
        return inputAudioFormat
    }

    // Synchronized because both threads can reach it: the UI thread through
    // apply() when a dial moves, the audio thread through onConfigure() when
    // a track changes format. Two interleaved rebuilds could otherwise
    // publish filters sized for the wrong channel layout.
    @Synchronized
    private fun rebuild() {
        val s = pendingSettings
        val list = ArrayList<Stage>(EqSettings.BAND_COUNT + 2)
        val sr = sampleRate.toFloat()

        s.bandGainsDb.forEachIndexed { i, gainRaw ->
            val gain = gainRaw.coerceIn(-EqSettings.MAX_GAIN_DB, EqSettings.MAX_GAIN_DB)
            val fc = EqSettings.BAND_CENTERS_HZ[i]
            if (gain != 0f && fc < sr / 2f) list += Stage(peaking(sr, fc, Q_BAND, gain))
        }
        val bassDb = s.bassBoostDb.coerceIn(0f, EqSettings.MAX_GAIN_DB)
        if (bassDb > 0f) list += Stage(lowShelf(sr, 100f, bassDb))
        val trebleDb = s.trebleBoostDb.coerceIn(0f, EqSettings.MAX_GAIN_DB)
        if (trebleDb > 0f && 8000f < sr / 2f) list += Stage(highShelf(sr, 8000f, trebleDb))

        list.forEach { it.ensureChannels(channelCount.coerceAtLeast(1)) }
        stages = list.toTypedArray()

        // Headroom = worst-case simultaneous boost: the largest positive band
        // gain plus the largest positive shelf gain (a shelf can overlap a
        // band's frequency range, so their boosts can genuinely sum).
        val maxBandBoost = (s.bandGainsDb.maxOrNull() ?: 0f).coerceAtLeast(0f)
        val maxShelfBoost = maxOf(bassDb, trebleDb, 0f)
        val headroomDb = maxBandBoost + maxShelfBoost
        targetPreamp = 10f.pow(-headroomDb / 20f)
    }

    override fun isActive(): Boolean = super.isActive() && enabled && stages.isNotEmpty()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val localStages = stages
        val channels = channelCount
        val remaining = inputBuffer.remaining()

        // Drain the caller's buffer BEFORE asking for an output buffer. Media3
        // can hand a processor the same object replaceOutputBuffer() returns,
        // and that call clears it — so `output.put(inputBuffer)` below used to
        // be able to throw "The source buffer is this buffer" exactly the way
        // ReplayGainAudioProcessor did, taking the whole player down with it.
        // Same fix, same reason; see the note on `scratch` there.
        if (scratch.capacity() < remaining) {
            scratch = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        }
        scratch.clear()
        scratch.put(inputBuffer)
        scratch.flip()

        val output = replaceOutputBuffer(remaining)

        // Pass through untouched rather than risk an out-of-bounds read on
        // the audio thread. If a rebuild raced a format change the filter
        // state may be sized for a different channel layout, and an
        // ArrayIndexOutOfBoundsException here kills the whole process. A few
        // unequalised buffers during a format switch is the cheaper failure.
        if (channels <= 0 || localStages.any { !it.fits(channels) }) {
            output.put(scratch)
            output.flip()
            return
        }

        var frameCh = 0
        var preamp = currentPreamp
        val target = targetPreamp
        // ~10 ms exponential ease at 48 kHz (per FRAME, so channel-coherent).
        val ease = PREAMP_EASE

        if (encoding == C.ENCODING_PCM_FLOAT) {
            while (scratch.remaining() >= 4) {
                if (frameCh == 0) preamp += (target - preamp) * ease
                var sample = scratch.float
                for (stage in localStages) sample = stage.process(sample, frameCh)
                output.putFloat(softLimit(sample * preamp))
                frameCh = (frameCh + 1) % channels
            }
        } else {
            while (scratch.remaining() >= 2) {
                if (frameCh == 0) preamp += (target - preamp) * ease
                var sample = scratch.short / 32768f
                for (stage in localStages) sample = stage.process(sample, frameCh)
                output.putShort(
                    (softLimit(sample * preamp) * 32767f).roundToInt().toShort()
                )
                frameCh = (frameCh + 1) % channels
            }
        }
        currentPreamp = preamp
        output.flip()
    }

    /**
     * Transparent below [LIMIT_KNEE]; above it, a tanh knee rounds the wave
     * gently into +/-1.0 instead of squaring it off. Hard clipping creates
     * harsh odd harmonics (the "crunch"); a soft knee at these levels is at
     * worst a subtle warmth on peaks that would otherwise have distorted.
     */
    private fun softLimit(x: Float): Float {
        val a = if (x >= 0f) x else -x
        if (a <= LIMIT_KNEE) return x
        val span = 1f - LIMIT_KNEE
        val shaped = LIMIT_KNEE + span * tanh((a - LIMIT_KNEE) / span)
        return if (x >= 0f) shaped else -shaped
    }

    // ---- RBJ cookbook designs ----

    private fun peaking(sr: Float, fc: Float, q: Float, gainDb: Float): Coeffs {
        val a = 10f.pow(gainDb / 40f)
        val w = 2f * Math.PI.toFloat() * fc / sr
        val alpha = sin(w) / (2f * q)
        val a0 = 1 + alpha / a
        return Coeffs(
            b0 = (1 + alpha * a) / a0,
            b1 = (-2 * cos(w)) / a0,
            b2 = (1 - alpha * a) / a0,
            a1 = (-2 * cos(w)) / a0,
            a2 = (1 - alpha / a) / a0,
        )
    }

    private fun lowShelf(sr: Float, fc: Float, gainDb: Float): Coeffs {
        val a = 10f.pow(gainDb / 40f)
        val w = 2f * Math.PI.toFloat() * fc / sr
        val cw = cos(w)
        val alpha = sin(w) / 2f * sqrt(2f)
        val a0 = (a + 1) + (a - 1) * cw + 2 * sqrt(a) * alpha
        return Coeffs(
            b0 = (a * ((a + 1) - (a - 1) * cw + 2 * sqrt(a) * alpha)) / a0,
            b1 = (2 * a * ((a - 1) - (a + 1) * cw)) / a0,
            b2 = (a * ((a + 1) - (a - 1) * cw - 2 * sqrt(a) * alpha)) / a0,
            a1 = (-2 * ((a - 1) + (a + 1) * cw)) / a0,
            a2 = ((a + 1) + (a - 1) * cw - 2 * sqrt(a) * alpha) / a0,
        )
    }

    private fun highShelf(sr: Float, fc: Float, gainDb: Float): Coeffs {
        val a = 10f.pow(gainDb / 40f)
        val w = 2f * Math.PI.toFloat() * fc / sr
        val cw = cos(w)
        val alpha = sin(w) / 2f * sqrt(2f)
        val a0 = (a + 1) - (a - 1) * cw + 2 * sqrt(a) * alpha
        return Coeffs(
            b0 = (a * ((a + 1) + (a - 1) * cw + 2 * sqrt(a) * alpha)) / a0,
            b1 = (-2 * a * ((a - 1) + (a + 1) * cw)) / a0,
            b2 = (a * ((a + 1) + (a - 1) * cw - 2 * sqrt(a) * alpha)) / a0,
            a1 = (2 * ((a - 1) - (a + 1) * cw)) / a0,
            a2 = ((a + 1) - (a - 1) * cw - 2 * sqrt(a) * alpha) / a0,
        )
    }

    private companion object {
        const val Q_BAND = 1.1f
        const val LIMIT_KNEE = 0.92f
        const val PREAMP_EASE = 0.002f // ~10 ms time constant at 48 kHz
    }
}
