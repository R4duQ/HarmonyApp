package com.harmony.domain.library.repository

import com.harmony.core.model.Song
import kotlinx.coroutines.flow.Flow

/** Aggregated listening behaviour used by Smart Shuffle v2. */
data class PlaybackBehaviorStats(
    val songId: Long,
    val completedPlays: Int,
    val skippedPlays: Int,
    val lastPlayedAt: Long?,
) {
    val totalEvents: Int get() = completedPlays + skippedPlays
    val completionRate: Float
        get() = if (totalEvents == 0) 0.55f else completedPlays.toFloat() / totalEvents
    val skipRate: Float
        get() = if (totalEvents == 0) 0f else skippedPlays.toFloat() / totalEvents
}

interface PlaybackHistoryRepository {
    /**
     * Record a playback event. [completed] should be true when the listener
     * heard "most" of the track (the caller uses a >=50% or >=4min rule, same
     * as scrobbling conventions); only completed plays count toward play
     * counts, so skipping through songs doesn't pollute Most Played.
     */
    suspend fun recordPlay(songId: Long, completed: Boolean, atMillis: Long = System.currentTimeMillis())

    fun observeRecentlyPlayed(limit: Int = 100): Flow<List<Song>>
    fun observeMostPlayed(limit: Int = 100): Flow<List<Song>>
    suspend fun playCount(songId: Long): Int
    suspend fun lastPlayed(songId: Long): Long?

    /** Batch behavioural snapshot so Smart Shuffle does one DB round-trip, not N queries per pick. */
    suspend fun behaviorStats(songIds: List<Long>): Map<Long, PlaybackBehaviorStats>

    /** Song ids played within the last [windowMillis]; Smart Shuffle's anti-repetition input. */
    suspend fun recentSongIds(windowMillis: Long): List<Long>

    /** Raw play events, newest first, for Discover's taste profile (recency, skips, replays). */
    suspend fun listeningEvents(limit: Int = 5_000): List<com.harmony.domain.library.discovery.ListeningEvent> = emptyList()
}
