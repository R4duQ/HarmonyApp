package com.harmony.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Harmony-owned provenance for files downloaded by an in-app download engine.
 *
 * This intentionally lives outside audio tags and outside SongEntity. Library
 * scanning is file-derived and may re-create SongEntity rows; provenance is a
 * Harmony concern and must survive a metadata rescan without being overwritten.
 */
@Entity(
    tableName = "download_records",
    indices = [
        Index(value = ["uri"], unique = true),
        Index("downloadedAt"),
        Index("source"),
    ],
)
data class DownloadRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val artist: String,
    val source: String,
    val provider: String?,
    val format: String?,
    val bitDepth: Int?,
    val sampleRateHz: Int?,
    val fileSizeBytes: Long,
    val downloadedAt: Long,
    val soulseekUsername: String?,
    val originalTrackId: String?,
    val isrc: String?,
)
