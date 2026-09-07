package com.harmony.data.library

import com.harmony.core.database.dao.HistoryDao
import com.harmony.core.model.Song
import com.harmony.data.library.mapper.toDomain
import com.harmony.domain.library.repository.FavoritesRepository
import com.harmony.domain.library.repository.PlaybackBehaviorStats
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackHistoryRepositoryImpl @Inject constructor(
    private val historyDao: HistoryDao,
) : PlaybackHistoryRepository {

    override suspend fun recordPlay(songId: Long, completed: Boolean, atMillis: Long) {
        // A playback transition can arrive after the listener deletes its file.
        historyDao.recordIfPresent(songId, atMillis, completed)
    }

    override fun observeRecentlyPlayed(limit: Int): Flow<List<Song>> =
        historyDao.observeRecentlyPlayed(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeMostPlayed(limit: Int): Flow<List<Song>> =
        historyDao.observeMostPlayed(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun playCount(songId: Long): Int = historyDao.playCount(songId)

    override suspend fun lastPlayed(songId: Long): Long? = historyDao.lastPlayed(songId)

    override suspend fun behaviorStats(songIds: List<Long>): Map<Long, PlaybackBehaviorStats> {
        if (songIds.isEmpty()) return emptyMap()
        return songIds.chunked(SQLITE_QUERY_CHUNK)
            .flatMap { historyDao.behaviorStats(it) }
            .associate { row ->
                row.songId to PlaybackBehaviorStats(
                    songId = row.songId,
                    completedPlays = row.completedPlays,
                    skippedPlays = row.skippedPlays,
                    lastPlayedAt = row.lastPlayedAt,
                )
            }
    }

    override suspend fun recentSongIds(windowMillis: Long): List<Long> =
        historyDao.recentSongIds(System.currentTimeMillis() - windowMillis)

    private companion object {
        const val SQLITE_QUERY_CHUNK = 900
    }
}

@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val historyDao: HistoryDao,
) : FavoritesRepository {

    override fun observeFavorites(): Flow<List<Song>> =
        historyDao.observeFavorites().map { rows -> rows.map { it.toDomain() } }

    override fun observeIsFavorite(songId: Long): Flow<Boolean> =
        historyDao.observeIsFavorite(songId)

    override suspend fun favoriteIds(songIds: List<Long>): Set<Long> {
        if (songIds.isEmpty()) return emptySet()
        return songIds.chunked(SQLITE_QUERY_CHUNK)
            .flatMap { historyDao.favoriteSongIds(it) }
            .toSet()
    }

    override suspend fun toggle(songId: Long) {
        historyDao.toggleFavorite(songId, System.currentTimeMillis())
    }

    private companion object {
        const val SQLITE_QUERY_CHUNK = 900
    }
}
