package com.harmony.core.eq

import java.nio.ByteBuffer

/**
 * The JNI surface of MobileEqEngine (eq_jni.cpp registers these by name).
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
    @JvmStatic external fun nativeSetAutoTargetGains(handle: Long, gainsDb: FloatArray)
    @JvmStatic external fun nativeSetEqEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun nativeSetAutoMode(handle: Long, enabled: Boolean)
    @JvmStatic external fun nativeStartTrack(handle: Long, knownLevelsDb: FloatArray?)
    @JvmStatic external fun nativeSongLevels(handle: Long, out: FloatArray): Float
    @JvmStatic external fun nativeListenCorrection(handle: Long, out: FloatArray)
    @JvmStatic external fun nativeAutoCurve(handle: Long, out: FloatArray)
    @JvmStatic external fun nativeSetUserPreamp(handle: Long, preampDb: Float)
    @JvmStatic external fun nativeSetLevelGain(handle: Long, gainDb: Float)
    @JvmStatic external fun nativeSetOutputDevice(handle: Long, device: Int)
    @JvmStatic external fun nativeSetAnalyzerEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun nativeSetSpectrumBarCount(handle: Long, bars: Int)
    @JvmStatic external fun nativeTargetPreampDb(handle: Long): Float
    @JvmStatic external fun nativeBassDuckDb(handle: Long): Float

    /** Fills [bars] and [peaks] (0..1) and returns how many bars were written. */
    @JvmStatic external fun nativePollSpectrum(handle: Long, bars: FloatArray?, peaks: FloatArray?): Int
}
