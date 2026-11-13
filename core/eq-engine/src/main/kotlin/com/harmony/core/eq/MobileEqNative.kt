package com.harmony.core.eq

import java.nio.ByteBuffer

/**
 * The JNI surface of the EQ engine, libharmonyeq (Rust, native/harmonyeq),
 * which exports these by their JNI names.
 *
 * [handle] is the engine's address. Every per-buffer and per-frame call is
 * garbage-free: audio goes through direct ByteBuffers, and arrays are filled
 * in place.
 */
internal object MobileEqNative {
    init {
        System.loadLibrary("harmonyeq")
    }

    // Matches harmony::eq::SampleFormat.
    const val FORMAT_PCM_16 = 1
    const val FORMAT_PCM_24_PACKED = 2
    const val FORMAT_PCM_32 = 3
    const val FORMAT_FLOAT = 4

    const val BAND_COUNT = 10
    const val MAX_BARS = 64

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeConfigure(handle: Long, sampleRate: Int, channels: Int): Boolean
    @JvmStatic external fun nativeSetSampleRate(handle: Long, sampleRate: Int): Boolean
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeLatencyFrames(handle: Long): Int

    /** Converts and processes [frames] frames; returns the frames written to [output] as float. */
    @JvmStatic external fun nativeProcess(
        handle: Long,
        input: ByteBuffer,
        inputOffset: Int,
        frames: Int,
        format: Int,
        output: ByteBuffer,
        outputOffset: Int,
    ): Int

    /** Writes the audio the limiter's lookahead still holds; returns the frames written. */
    @JvmStatic external fun nativeDrain(handle: Long, output: ByteBuffer, outputOffset: Int, maxFrames: Int): Int

    @JvmStatic external fun nativeSetBandGain(handle: Long, band: Int, gainDb: Float)
    @JvmStatic external fun nativeSetBandGains(handle: Long, gainsDb: FloatArray)
    @JvmStatic external fun nativeSetEqEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun nativeSetAutoMode(handle: Long, enabled: Boolean)
    @JvmStatic external fun nativeSetUserPreamp(handle: Long, preampDb: Float)
    @JvmStatic external fun nativeSetLevelGain(handle: Long, gainDb: Float)
    @JvmStatic external fun nativeSetOutputDeviceType(handle: Long, deviceType: Int)
    @JvmStatic external fun nativeSetAnalyzerEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun nativeSetSpectrumBarCount(handle: Long, bars: Int)
    @JvmStatic external fun nativeTargetPreampDb(handle: Long): Float
    @JvmStatic external fun nativeBassDuckDb(handle: Long): Float
    @JvmStatic external fun nativeHarshDuckDb(handle: Long): Float
    @JvmStatic external fun nativeTonalFrozen(handle: Long): Boolean

    /** Times the engine healed itself from non-finite state (should stay 0). */
    @JvmStatic external fun nativeHealCount(handle: Long): Int

    /** Input samples that were NaN, infinite or beyond +24 dBFS, repaired before processing. */
    @JvmStatic external fun nativeSanitizedSamples(handle: Long): Int
    @JvmStatic external fun nativeLiveAutoOffsets(handle: Long, out: FloatArray)

    /**
     * The 60 fps snapshot: the ten effective band gains (sliders plus Auto's
     * live offsets), the FFT bars and their peak dots, in one call through
     * GetPrimitiveArrayCritical. Returns the live automatic preamp in dB.
     */
    @JvmStatic external fun nativePollDspTelemetry(
        handle: Long,
        effectiveGains10: FloatArray?,
        fftMagnitudes32: FloatArray?,
        fftPeaks32: FloatArray?,
    ): Float

    const val CLARITY_BANDS = 24

    /** Clarity Live: on/off (it runs while the EQ is on), intensity 0..1, balance -1..1. */
    @JvmStatic external fun nativeSetClarity(handle: Long, enabled: Boolean, intensity: Float, balance: Float)

    /** A new song starts on this engine: Clarity forgets the last one's balance. */
    @JvmStatic external fun nativeClarityNewTrack(handle: Long)

    /**
     * Clarity's live view, per band (24): the response it applies, the music's
     * ear-weighted level and the masking threshold, dB. Any array may be null.
     * Returns the level match in dB, NaN while Clarity is off and flat.
     */
    @JvmStatic external fun nativeClarityTelemetry(
        handle: Long,
        response24: FloatArray?,
        levels24: FloatArray?,
        masks24: FloatArray?,
    ): Float

    /** Fills [bars] and [peaks] (0..1) and returns how many bars were written. */
    @JvmStatic external fun nativePollSpectrum(handle: Long, bars: FloatArray?, peaks: FloatArray?): Int
}
