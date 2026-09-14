package com.harmony.core.model

enum class RepeatMode { OFF, ONE, ALL }

enum class ShuffleMode {
    OFF,
    /** Plain random shuffle over the queue source. */
    RANDOM,
    /** Embedding-driven shuffle (Phase 7). Declared now so the playback API is stable. */
    SMART,
    /** Gradual mood-trajectory playback (Phase 7). */
    JOURNEY,
}

/** ReplayGain application mode. */
enum class ReplayGainMode { OFF, TRACK, ALBUM }

/**
 * Immutable snapshot of the player, exposed to the UI as a StateFlow.
 * Everything the mini-player / now-playing screen needs lives here so
 * screens observe exactly one flow.
 */
data class PlayerState(
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    val playbackSpeed: Float = 1.0f,
    /** True = tempo changes preserve pitch (time-stretch); false = vinyl-style pitch shift. */
    val pitchCorrection: Boolean = true,
    val sleepTimerRemainingMs: Long? = null,
    val crossfadeSeconds: Int = 0,
    val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
    /**
     * How many songs immediately after [queueIndex] were explicitly queued
     * by the user ("play next"), as opposed to being part of the original
     * queue or auto-appended by Smart Shuffle. Purely a UI signal, so the
     * queue screen can show a "Next up" section — the player itself makes
     * no distinction between prioritized and ordinary queue entries.
     */
    val playNextCount: Int = 0,
    /**
     * True when the current song began because the previous one ended and
     * playback rolled into it, rather than because the user chose it.
     *
     * Smart Shuffle's handover treats "a song it did not queue started" as
     * an intentional change of direction and clears the tail. Advancing
     * normally into a song the user had queued looks identical from the
     * queue's point of view, so without this flag the handover deletes the
     * rest of what they queued — the reason manually queued songs stopped
     * after the first one.
     */
    val advancedAutomatically: Boolean = false,
    /** Where audio is currently being routed (speaker / wired / Bluetooth…). */
    val audioOutput: AudioOutput = AudioOutput(),
    /**
     * The next queued song, but only surfaced once we're within the
     * crossfade window of the current track's end (null otherwise). Purely
     * a UI signal — computed client-side from position/duration/queue/
     * crossfadeSeconds, which PlaybackConnection already tracks — so the
     * Now Playing screen can show "up next" starting exactly when the
     * audio overlap begins, without needing any new plumbing from the
     * session-side crossfade engine itself.
     */
    val upcomingSong: Song? = null,
)
