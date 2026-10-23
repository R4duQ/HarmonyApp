package com.harmony.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the audio chain is pushing to the speaker, reduced to two numbers.
 *
 * @param level 0..1 loudness.
 * @param brightness 0..1, how high the content sits (the spectrum's centre of energy).
 */
data class AudioLevel(
    val level: Float = 0f,
    val brightness: Float = 0f,
)

/** Reads the playing EQ engine's analyser into [bars] and [peaks]; returns how many bars it wrote. */
fun interface SpectrumSource {
    fun poll(bars: FloatArray, peaks: FloatArray?): Int
}

/**
 * The live spectrum, from the native EQ engine (core:eq-engine) to the screens.
 *
 * The UI polls it at 60 fps, once per frame (withFrameNanos), into arrays it
 * allocated once: [poll] runs the engine's Hann-windowed 2048-point FFT, the
 * logarithmic bars and their ballistics in C++ and only copies the 0..1
 * heights out. Nothing is allocated per frame and the audio thread never
 * waits for it.
 *
 * Every poll happens on the main thread, which keeps the engine's analyser to
 * the single reader it expects. The playback service sets [source] and runs
 * the analyser only while a screen is [watching].
 */
object AudioLevels {
    /** The playing engine's analyser; set by the playback service, null without one. */
    @Volatile
    var source: SpectrumSource? = null

    private val _watchers = MutableStateFlow(0)

    /** How many screens are polling right now. */
    val watchers: StateFlow<Int> = _watchers.asStateFlow()

    /** A screen starts polling (pair with [stopWatching]). */
    fun startWatching() = _watchers.update { it + 1 }

    fun stopWatching() = _watchers.update { (it - 1).coerceAtLeast(0) }

    /**
     * Main thread, once per frame: fills [bars] (and [peaks], when given; both
     * at least [SPECTRUM_BARS] long) with 0..1 heights and returns how many it
     * wrote; 0 when nothing plays.
     */
    fun poll(bars: FloatArray, peaks: FloatArray?): Int =
        source?.let { runCatching { it.poll(bars, peaks) }.getOrDefault(0) } ?: 0

    /** Loudness and brightness (the centre of energy) of a polled frame of [count] bars. */
    fun levelOf(bars: FloatArray, count: Int): AudioLevel {
        if (count <= 0) return AudioLevel()
        var sum = 0f
        var energy = 0f
        var moment = 0f
        for (i in 0 until count) {
            val v = bars[i]
            sum += v
            energy += v * v
            moment += v * v * i
        }
        val level = ((sum / count - 0.12f) / 0.6f).coerceIn(0f, 1f)
        val centre = if (energy > 1e-6f) moment / energy / (count - 1).coerceAtLeast(1) else 0f
        return AudioLevel(level, centre.coerceIn(0f, 1f))
    }

    /** Bars per frame: the engine's 20 Hz – 20 kHz range in logarithmic steps. */
    const val SPECTRUM_BARS = 32
}
