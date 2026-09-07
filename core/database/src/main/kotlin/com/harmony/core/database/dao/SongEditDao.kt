package com.harmony.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.harmony.core.database.entity.SongEditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongEditDao {

    @Upsert
    suspend fun upsert(edit: SongEditEntity)

    @Query("SELECT * FROM song_edits WHERE songId = :songId")
    suspend fun get(songId: Long): SongEditEntity?

    @Query("SELECT * FROM song_edits WHERE songId = :songId")
    fun observe(songId: Long): Flow<SongEditEntity?>

    /** Drops the override entirely, restoring whatever the file's tags say. */
    @Query("DELETE FROM song_edits WHERE songId = :songId")
    suspend fun clear(songId: Long)

    @Query("SELECT COUNT(*) FROM song_edits")
    fun count(): Flow<Int>
}
