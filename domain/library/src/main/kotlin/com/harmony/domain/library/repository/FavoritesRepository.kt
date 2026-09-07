package com.harmony.domain.library.repository

import com.harmony.core.model.Song
import kotlinx.coroutines.flow.Flow

interface FavoritesRepository {
    fun observeFavorites(): Flow<List<Song>>
    fun observeIsFavorite(songId: Long): Flow<Boolean>
    /** Batch read for Smart Shuffle scoring. */
    suspend fun favoriteIds(songIds: List<Long>): Set<Long>
    suspend fun toggle(songId: Long)
}
