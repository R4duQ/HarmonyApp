package com.harmony.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.harmony.core.database.entity.DownloadRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DownloadRecordEntity)

    @Query("SELECT * FROM download_records ORDER BY downloadedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM download_records WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): DownloadRecordEntity?
}
