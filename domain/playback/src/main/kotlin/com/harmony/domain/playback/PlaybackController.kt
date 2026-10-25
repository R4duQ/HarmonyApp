package com.harmony.domain.playback

import com.harmony.core.model.EqSettings
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The single abstraction the rest of the app uses to drive playback.
 *
 * Implemented in :playback:service by a MediaController-backed class.
 * Feature modules and use cases depend only on this interface, which keeps
 * every ViewModel testable with a plain fake and keeps Media3 types out of
 * the domain and UI layers entirely.
 */
interface PlaybackController {

    /** Hot state stream; always emits the latest snapshot immediately on collect. */
    val playerState: StateFlow<PlayerState>

    /**
     * One event each time playback moves from a song to another, saying how
     * much of the song that was left had been heard. Smart Shuffle learns
     * from it (a play past half, a fast skip under 20%). Hot: only what
     * happens while collecting is seen.
     */
    val playOutcomes: Flow<PlayOutcome> get() = emptyFlow()

    /**
     * Shuffle turned on from outside the app (a Bluetooth or notification
     * shuffle button, a watch): the player's own shuffle is switched straight
     * back off and the request lands here, for SmartQueueManager to act on.
     * Harmony orders the queue itself and never lets ExoPlayer shuffle it.
     */
    val externalShuffleRequests: Flow<Unit> get() = emptyFlow()

    // -- Queue --------------------------------------------------------------
    /** Replace the queue with [songs] and start playing from [startIndex]. */
    suspend fun setQueue(songs: List<Song>, startIndex: Int = 0, playWhenReady: Boolean = true)
    suspend fun addToQueue(song: Song)

    /** Append many songs in one operation (see [removeQueueRange] for why bulk matters). */
    suspend fun addToQueueAll(songs: List<Song>)
    suspend fun addNext(song: Song)
    suspend fun removeFromQueue(index: Int)

    /** Removes every occurrence of confirmed-deleted files in one controller operation. */
    suspend fun removeSongsByUri(uris: Set<String>) {
        playerState.value.queue.mapIndexedNotNull { index, song -> index.takeIf { song.uri in uris } }
            .asReversed().forEach { removeFromQueue(it) }
    }

    /**
     * Remove queue entries in [fromIndex, toIndexExclusive) in one operation.
     * Exists because trimming a long tail one index at a time means hundreds
     * of round trips to the playback service, which is slow enough to be
     * visible; this is a single call.
     */
    suspend fun removeQueueRange(fromIndex: Int, toIndexExclusive: Int)

    /**
     * Replaces queue entries [fromIndex, toIndexExclusive) with [songs] in one
     * operation (Media3's replaceMediaItems), leaving everything before
     * [fromIndex] (the history and the song playing) untouched. The default
     * is for simple fakes and only right for a range reaching the end.
     */
    suspend fun replaceQueueRange(fromIndex: Int, toIndexExclusive: Int, songs: List<Song>) {
        removeQueueRange(fromIndex, toIndexExclusive)
        if (songs.isNotEmpty()) addToQueueAll(songs)
    }
    suspend fun moveQueueItem(from: Int, to: Int)

    // -- Transport ----------------------------------------------------------
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun skipToNext()
    fun skipToPrevious()
    fun skipToQueueItem(index: Int)

    // -- Modes --------------------------------------------------------------
    fun setRepeatMode(mode: RepeatMode)
    fun setShuffleMode(mode: ShuffleMode)
    fun setPlaybackSpeed(speed: Float)
    fun setPitchCorrection(enabled: Boolean)
    fun setCrossfade(seconds: Int)
    fun setReplayGainMode(mode: ReplayGainMode)
    fun setEqualizer(settings: EqSettings)

    // -- Sleep timer --------------------------------------------------------
    /** Pass null to cancel. If [finishTrack] is true, playback stops at track end after expiry. */
    fun setSleepTimer(durationMs: Long?, finishTrack: Boolean = false)
}

/** How a song ended: heard to the end, or left after [listenedMs] of [durationMs]. */
data class PlayOutcome(
    val songId: Long,
    /** Time actually heard (seeking does not count). */
    val listenedMs: Long,
    val durationMs: Long,
    /** Playback went back to an earlier queue entry (previous, or a tap above): not a verdict on the song. */
    val movedBackward: Boolean = false,
    /** The player moved on by itself (the song ended). */
    val endedNaturally: Boolean = false,
    val atMillis: Long = System.currentTimeMillis(),
) {
    /** Share of the song heard, 0..1. Four minutes counts as a full play, as in the play history. */
    val fraction: Float
        get() {
            if (endedNaturally) return 1f
            val heard = if (durationMs > 0) listenedMs.toFloat() / durationMs else 0f
            return if (listenedMs >= FULL_PLAY_MS) maxOf(heard, 0.55f).coerceIn(0f, 1f) else heard.coerceIn(0f, 1f)
        }

    companion object {
        const val FULL_PLAY_MS = 4 * 60 * 1000L
    }
}
