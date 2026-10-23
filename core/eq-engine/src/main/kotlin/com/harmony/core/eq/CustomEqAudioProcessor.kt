package com.harmony.core.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [androidx.media3.common.audio.AudioProcessor] running Harmony's
 * native float equalizer chain (see MobileEqEngine.h).
 *
 * Takes 16-bit, packed 24-bit, 32-bit integer or float PCM at any rate from
 * 8 kHz to 384 kHz and 1 to 8 channels, and always outputs
 * [C.ENCODING_PCM_FLOAT] at the same rate and channel count: no resampling,
 * and no requantisation after the EQ.
 *
 * Where to put it. In Media3 1.5 [androidx.media3.exoplayer.audio.DefaultAudioSink]
 * either skips custom processors entirely (float output enabled) or converts
 * every input to 16-bit before them (float output disabled), which throws away
 * 24-bit detail before the EQ ever sees it. For hi-res, run this processor
 * through [HiResEqAudioSink], which applies it ahead of a float-output sink.
 * In a plain DefaultAudioSink chain it still works, on 16-bit input.
 *
 * Threads. The audio thread drives the [AudioProcessor] methods. The setters
 * and [pollSpectrum] may be called from any thread (one thread for
 * [pollSpectrum]): they only store atomics in the engine and never block audio.
 *
 * Latency. The limiter looks 2 ms ahead. The engine keeps output sample-aligned
 * with input (it holds those frames back after a flush and releases them at the
 * end of the stream), so nothing is inserted or lost and gapless playback stays
 * gapless.
 *
 * Call [release] when the player is released; the native engine lives until then.
 */
@UnstableApi
class CustomEqAudioProcessor : BaseAudioProcessor() {

    private var handle: Long = MobileEqNative.nativeCreate()

    /**
     * Bit-perfect USB mode (see [AudioOutputMonitor.setBitPerfectUsbMode]): the
     * processor becomes inactive and PCM passes untouched. It takes effect at
     * the next configuration, so re-prepare playback (or move to the next
     * track) after changing it.
     */
    @Volatile
    var bitPerfectPassthrough: Boolean = false

    private var formatCode = 0
    private var configuredRate = 0
    private var configuredChannels = 0
    private var tailWritten = false
    private var heapInputCopy: ByteBuffer = EMPTY_DIRECT

    // ---- Controls ------------------------------------------------------------------------

    /** Gain of one band, -12..+12 dB. Bands: 31, 62, 125, 250, 500 Hz, 1, 2, 4, 8, 16 kHz. */
    fun setBandGain(band: Int, gainDb: Float) = MobileEqNative.nativeSetBandGain(checkedHandle(), band, gainDb)

    /** All ten band gains at once (dB, -12..+12). */
    fun setBandGains(gainsDb: FloatArray) {
        require(gainsDb.size >= BAND_COUNT) { "Expected $BAND_COUNT gains" }
        MobileEqNative.nativeSetBandGains(checkedHandle(), gainsDb)
    }

    /** EQ on, or flat. The high-pass, saturator and limiter protect the output either way. */
    fun setEqEnabled(enabled: Boolean) = MobileEqNative.nativeSetEqEnabled(checkedHandle(), enabled)

    /** Winamp-style Auto: follow [setAutoTargetGains], morphing over 400 ms, with dynamic bass. */
    fun setAutoMode(enabled: Boolean) = MobileEqNative.nativeSetAutoMode(checkedHandle(), enabled)

    /** The curve Auto morphs to (call it as each track starts). */
    fun setAutoTargetGains(gainsDb: FloatArray) {
        require(gainsDb.size >= BAND_COUNT) { "Expected $BAND_COUNT gains" }
        MobileEqNative.nativeSetAutoTargetGains(checkedHandle(), gainsDb)
    }

    /** A manual preamp on top of the automatic compensation, -24..+12 dB. */
    fun setPreampDb(preampDb: Float) = MobileEqNative.nativeSetUserPreamp(checkedHandle(), preampDb)

    /**
     * Loudness levelling for the track (ReplayGain), -30..+15 dB. Applies with
     * the EQ off too; the limiter keeps a lift from clipping.
     */
    fun setLevelGainDb(gainDb: Float) = MobileEqNative.nativeSetLevelGain(checkedHandle(), gainDb)

    /** Where the sound goes; [AudioOutputMonitor] calls this on every routing change. */
    fun setOutputDevice(device: OutputDeviceType) =
        MobileEqNative.nativeSetOutputDevice(checkedHandle(), device.engineCode)

    /** Stop feeding the spectrum analyzer while nothing shows it. */
    fun setAnalyzerEnabled(enabled: Boolean) = MobileEqNative.nativeSetAnalyzerEnabled(checkedHandle(), enabled)

    /** 8..64 logarithmic bars between 20 Hz and 20 kHz. */
    fun setSpectrumBarCount(bars: Int) = MobileEqNative.nativeSetSpectrumBarCount(checkedHandle(), bars)

    /**
     * For a 60 fps UI: fills [bars] and [peaks] (allocate both once, at least
     * the bar count long) with 0..1 heights, ballistics already applied, and
     * returns the number of bars. Garbage-free; never blocks the audio thread.
     */
    fun pollSpectrum(bars: FloatArray, peaks: FloatArray?): Int =
        MobileEqNative.nativePollSpectrum(checkedHandle(), bars, peaks)

    /** The automatic preamp being applied, in dB. */
    fun currentPreampDb(): Float = MobileEqNative.nativeTargetPreampDb(checkedHandle())

    /** How far Auto's dynamic bass is pulling the 31/62 Hz boost down right now, in dB. */
    fun currentBassDuckDb(): Float = MobileEqNative.nativeBassDuckDb(checkedHandle())

    /** Frees the native engine. Call once the player is released; the processor is unusable after. */
    fun release() {
        val engine = handle
        handle = 0
        if (engine != 0L) MobileEqNative.nativeDestroy(engine)
    }

    // ---- AudioProcessor ------------------------------------------------------------------

    /** Whether this processor can take [format] (before bit-perfect mode is considered). */
    fun supports(format: AudioFormat): Boolean =
        engineFormatOf(format.encoding) != 0 &&
            format.channelCount in 1..MAX_CHANNELS &&
            format.sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!supports(inputAudioFormat)) throw UnhandledAudioFormatException(inputAudioFormat)
        // Bit-perfect: stay out of the way, and the original PCM reaches the DAC.
        if (bitPerfectPassthrough) return AudioFormat.NOT_SET
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun onFlush() {
        // A flush follows every configure() (it activates the new format) and
        // every seek. A new rate rebuilds every coefficient, time constant,
        // lookahead and FFT bin map in the engine (the native equivalent of a
        // setSampleRate); the same format only clears the filter state.
        if (!isActive) return
        val format = inputAudioFormat
        formatCode = engineFormatOf(format.encoding)
        if (format.sampleRate != configuredRate || format.channelCount != configuredChannels) {
            check(MobileEqNative.nativeConfigure(checkedHandle(), format.sampleRate, format.channelCount)) {
                "MobileEqEngine rejected $format"
            }
            configuredRate = format.sampleRate
            configuredChannels = format.channelCount
        } else {
            MobileEqNative.nativeReset(checkedHandle())
        }
        tailWritten = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        if (frames == 0) return
        val inputBytes = frames * inputAudioFormat.bytesPerFrame

        // The engine reads native memory: copy the rare heap buffer into a direct one.
        val source: ByteBuffer
        val sourceOffset: Int
        if (inputBuffer.isDirect) {
            source = inputBuffer
            sourceOffset = position
        } else {
            if (heapInputCopy.capacity() < inputBytes) {
                heapInputCopy = ByteBuffer.allocateDirect(inputBytes).order(ByteOrder.nativeOrder())
            }
            heapInputCopy.clear()
            heapInputCopy.put(inputBuffer.duplicate().apply { limit(position + inputBytes) })
            source = heapInputCopy
            sourceOffset = 0
        }

        val output = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)
        val produced = MobileEqNative.nativeProcess(
            checkedHandle(), source, sourceOffset, frames, formatCode, output, 0,
        )
        inputBuffer.position(position + inputBytes)
        output.position(produced * outputAudioFormat.bytesPerFrame)
        output.flip()
    }

    override fun getOutput(): ByteBuffer {
        // Once all input is in and the last output taken, release what the
        // limiter's lookahead still holds: the stream's final 2 ms.
        if (super.isEnded() && !tailWritten) {
            tailWritten = true
            val latency = MobileEqNative.nativeLatencyFrames(checkedHandle())
            if (latency > 0) {
                val output = replaceOutputBuffer(latency * outputAudioFormat.bytesPerFrame)
                val produced = MobileEqNative.nativeDrain(checkedHandle(), output, 0, latency)
                output.position(produced * outputAudioFormat.bytesPerFrame)
                output.flip()
            }
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && tailWritten

    override fun onReset() {
        configuredRate = 0
        configuredChannels = 0
        formatCode = 0
        tailWritten = false
        heapInputCopy = EMPTY_DIRECT
    }

    private fun checkedHandle(): Long {
        val engine = handle
        check(engine != 0L) { "CustomEqAudioProcessor was released" }
        return engine
    }

    companion object {
        const val BAND_COUNT = MobileEqNative.BAND_COUNT
        const val MAX_BARS = MobileEqNative.MAX_BARS
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 384_000
        const val MAX_CHANNELS = 8

        /** Band centre frequencies, in Hz. */
        val BAND_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

        private val EMPTY_DIRECT: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        internal fun engineFormatOf(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> MobileEqNative.FORMAT_PCM_16
            C.ENCODING_PCM_24BIT -> MobileEqNative.FORMAT_PCM_24_PACKED
            C.ENCODING_PCM_32BIT -> MobileEqNative.FORMAT_PCM_32
            C.ENCODING_PCM_FLOAT -> MobileEqNative.FORMAT_FLOAT
            else -> 0
        }
    }
}
