package com.harmony.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.harmony.core.database.entity.FavoriteEntity
import com.harmony.core.database.entity.PlayHistoryEntity
import com.harmony.core.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Insert
    suspend fun insert(event: PlayHistoryEntity)

    @Query("INSERT INTO play_history (songId, playedAt, completed) SELECT :songId, :atMillis, :completed WHERE EXISTS (SELECT 1 FROM songs WHERE id = :songId)")
    suspend fun recordIfPresent(songId: Long, atMillis: Long, completed: Boolean)

    @Query(
        """
        SELECT s.* FROM songs_effective s
        JOIN (SELECT songId, MAX(playedAt) AS lastAt FROM play_history GROUP BY songId) h
          ON h.songId = s.id
        ORDER BY h.lastAt DESC LIMIT :limit
        """
    )
    fun observeRecentlyPlayed(limit: Int): Flow<List<SongEntity>>

    @Query(
        """
        SELECT s.* FROM songs_effective s
        JOIN (SELECT songId, COUNT(*) AS plays FROM play_history WHERE completed = 1 GROUP BY songId) h
          ON h.songId = s.id
        ORDER BY h.plays DESC LIMIT :limit
        """
    )
    fun observeMostPlayed(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM play_history WHERE songId = :songId AND completed = 1")
    suspend fun playCount(songId: Long): Int

    @Query("SELECT MAX(playedAt) FROM play_history WHERE songId = :songId")
    suspend fun lastPlayed(songId: Long): Long?

    @Query("SELECT DISTINCT songId FROM play_history WHERE playedAt >= :sinceMillis")
    suspend fun recentSongIds(sinceMillis: Long): List<Long>

    data class BehaviorStatsRow(
        val songId: Long,
        val completedPlays: Int,
        val skippedPlays: Int,
        val lastPlayedAt: Long?,
    )

    @Query(
        """
        SELECT songId,
               SUM(CASE WHEN completed = 1 THEN 1 ELSE 0 END) AS completedPlays,
               SUM(CASE WHEN completed = 0 THEN 1 ELSE 0 END) AS skippedPlays,
               MAX(playedAt) AS lastPlayedAt
        FROM play_history
        WHERE songId IN (:songIds)
        GROUP BY songId
        """
    )
    suspend fun behaviorStats(songIds: List<Long>): List<BehaviorStatsRow>

    // -- Favorites -----------------------------------------------------------

    @Query(
        """
        SELECT s.* FROM songs_effective s JOIN favorites f ON f.songId = s.id
        ORDER BY f.addedAt DESC
        """
    )
    fun observeFavorites(): Flow<List<SongEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    fun observeIsFavorite(songId: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    suspend fun isFavorite(songId: Long): Boolean

    @Query("SELECT songId FROM favorites WHERE songId IN (:songIds)")
    suspend fun favoriteSongIds(songIds: List<Long>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun removeFavorite(songId: Long)

    @Query("INSERT OR IGNORE INTO favorites (songId, addedAt) SELECT :songId, :atMillis WHERE EXISTS (SELECT 1 FROM songs WHERE id = :songId)")
    suspend fun addFavoriteIfPresent(songId: Long, atMillis: Long)

    @Transaction
    suspend fun toggleFavorite(songId: Long, atMillis: Long) {
        if (isFavorite(songId)) removeFavorite(songId)
        else addFavoriteIfPresent(songId, atMillis)
    }
}
