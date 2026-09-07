package com.harmony.domain.shuffle.repository

import com.harmony.core.model.MoodFilter
import com.harmony.core.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Implemented in :data:analysis (it owns the perceptual columns). Scores come
 * from MoodProfiles; the repository just supplies rows and resolves songs.
 */
interface MoodRepository {
    /** Songs ranked by mood membership, best first, score >= threshold. */
    fun observeSongsForMood(mood: MoodFilter, limit: Int = 200): Flow<List<Song>>

    /** The single strongest song for a mood, or null if nothing is analyzed. */
    suspend fun topSongForMood(mood: MoodFilter): Song?
}
