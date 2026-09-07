package com.harmony.domain.playback

import com.harmony.core.model.EqSettings
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import kotlinx.coroutines.flow.StateFlow

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
