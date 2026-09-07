package com.harmony.core.dsp

import java.io.Closeable

/**
 * Kotlin handle over one native analysis session. NOT thread-safe by design —
 * one analyzer per song per worker thread; the bounded parallelism lives a
 * layer up in the batch use case.
 *
 * Usage:
 *   NativeAnalyzer(sampleRate).use { a ->
 *       while (decoding) a.process(chunk, samplesInChunk)
 *       val features = a.finish()   // null if the stream was empty/corrupt
 *   }
 */
class NativeAnalyzer(sampleRate: Int) : Closeable {

    private var handle: Long = nativeCreate(sampleRate)

    init {
        require(handle != 0L) { "Unsupported sample rate: $sampleRate" }
    }

    /** Feed mono float PCM in [-1, 1]. [count] may be less than the array size. */
    fun process(monoSamples: FloatArray, count: Int = monoSamples.size) {
        check(handle != 0L) { "Analyzer already closed" }
        nativeProcess(handle, monoSamples, count)
    }

    /** @return the raw feature array (layout: FeatureLayout in :domain:analysis), or null. */
    fun finish(): FloatArray? {
        check(handle != 0L) { "Analyzer already closed" }
        return nativeFinish(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeProcess(handle: Long, samples: FloatArray, count: Int)
    private external fun nativeFinish(handle: Long): FloatArray?
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("harmonydsp")
        }
    }
}
