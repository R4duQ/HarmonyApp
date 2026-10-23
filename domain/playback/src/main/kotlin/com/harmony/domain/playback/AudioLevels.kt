package com.harmony.domain.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A snapshot of what the audio chain is currently pushing to the speaker.
 *
 * @param level 0..1 loudness.
 * @param brightness 0..1, how high the content sits (the spectrum's centre of energy).
 */
data class AudioLevel(
    val level: Float = 0f,
    val brightness: Float = 0f,
)

/**
 * One frame of the equalizer engine's spectrum analyser: [bars] logarithmic
 * bars from 20 Hz to 20 kHz, 0..1 each, and their falling peak dots, with the
 * engine's ballistics already applied.
 */
class SpectrumFrame(val bars: FloatArray, val peaks: FloatArray) {
    companion object {
        val EMPTY = SpectrumFrame(FloatArray(AudioLevels.SPECTRUM_BARS), FloatArray(AudioLevels.SPECTRUM_BARS))
    }
}

/**
 * Process-wide publisher for what the music sounds like right now, written by
 * the playback service and read by the player UI.
 *
 * A plain object rather than an injected singleton because the consumers are
 * Composables in other modules and the producer lives in the playback
 * service; a shared object is the one thing both ends can name.
 *
 * The producer is the native EQ engine's analyser (core:eq-engine): a 2048-point
 * FFT read at the playback rate, so the bars move with what is heard rather
 * than with what the decoder is working on. The service polls it about 60
 * times a second, and only while something here is collected (see [wanted]).
 */
object AudioLevels {
    private val _current = MutableStateFlow(AudioLevel())
    val current: StateFlow<AudioLevel> = _current.asStateFlow()

    private val _spectrum = MutableStateFlow(SpectrumFrame.EMPTY)

    /** The analyser, about 60 frames a second while collected. */
    val spectrum: StateFlow<SpectrumFrame> = _spectrum.asStateFlow()

    /** True while a screen shows the level or the spectrum: the service only measures then. */
    val wanted: Flow<Boolean> =
        combine(_current.subscriptionCount, _spectrum.subscriptionCount) { a, b -> a > 0 || b > 0 }
            .distinctUntilChanged()

    fun publish(level: Float, brightness: Float) {
        _current.value = AudioLevel(
            level = level.coerceIn(0f, 1f),
            brightness = brightness.coerceIn(0f, 1f),
        )
    }

    fun publishSpectrum(frame: SpectrumFrame) {
        _spectrum.value = frame
    }

    /** Called when playback stops so the bars settle instead of freezing mid-peak. */
    fun reset() {
        _current.value = AudioLevel()
        _spectrum.value = SpectrumFrame.EMPTY
    }

    const val SPECTRUM_BARS = 32
}
