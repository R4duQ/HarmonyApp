package com.harmony.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A snapshot of what the audio chain is currently pushing to the speaker.
 *
 * @param level 0..1 loudness, from RMS of the last measured window.
 * @param brightness 0..1 rough "how high is the content", from zero-crossing
 *   rate. Not a spectrum — see [AudioLevels] for why that's the right trade.
 */
data class AudioLevel(
    val level: Float = 0f,
    val brightness: Float = 0f,
)

/**
 * Process-wide publisher for the live audio level, written by the playback
 * chain and read by the player UI.
 *
 * A plain object rather than an injected singleton because the producer is
 * an AudioProcessor constructed by DefaultAudioSink's builder, well outside
 * Hilt's reach, and the consumer is a Composable in a different module. A
 * shared object is the only thing both ends can name without threading a
 * dependency through the audio sink.
 *
 * Two things this deliberately does NOT do:
 *
 * It doesn't run an FFT. A real spectrum on the audio thread means a
 * transform per buffer, and the seek bar renders ~40 bars — nowhere near
 * enough resolution to justify it. RMS plus zero-crossing rate gives
 * "loud/quiet" and "dark/bright" for a handful of adds and comparisons per
 * sample, which is what the animation actually consumes.
 *
 * It doesn't emit per buffer. The audio thread produces buffers far faster
 * than the display refreshes, and pushing every one into a StateFlow would
 * allocate and wake collectors hundreds of times a second for frames nobody
 * draws. [publish] is throttled by the producer instead.
 */
object AudioLevels {
    private val _current = MutableStateFlow(AudioLevel())
    val current: StateFlow<AudioLevel> = _current.asStateFlow()

    fun publish(level: Float, brightness: Float) {
        _current.value = AudioLevel(
            level = level.coerceIn(0f, 1f),
            brightness = brightness.coerceIn(0f, 1f),
        )
    }

    /** Called when playback stops so the bars settle instead of freezing mid-peak. */
    fun reset() {
        _current.value = AudioLevel()
    }
}
