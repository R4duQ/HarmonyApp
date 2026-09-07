package com.harmony.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema notes (delta vs. the Phase 1 draft):
 *  - songs gained replayGainTrackDb/replayGainAlbumDb — Phase 3's tag parsers
 *    produce them and playback consumes them, so they belong on the song row.
 *  - Indices are declared on every column used in a WHERE/ORDER BY below;
 *    at 20k rows an unindexed LIKE scan is the difference between instant
 *    search and a visible stutter.
 *  - analysis_results is created now (empty until Phase 5) so Phase 5 ships
 *    no migration, and the smart-playlist energy queries can already compile.
 */

@Entity(
    tableName = "songs",
    indices = [
        Index("albumId"),
        Index("artist"),
        Index("title"),
        Index("genre"),
        Index("dateAdded"),
        Index("uri", unique = true),
    ],
)
data class SongEntity(
    @PrimaryKey val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String?,
    val composer: String?,
    val year: Int?,
    val genre: String?,
    val discNumber: Int?,
    val trackNumber: Int?,
    val durationMs: Long,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val fileSizeBytes: Long,
    val fileHash: String,
    val lastModified: Long,
    val dateAdded: Long,
    val storageVolume: String,
    val embeddedLyrics: String?,
    val artworkUri: String?,
    val replayGainTrackDb: Float?,
    val replayGainAlbumDb: Float?,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val albumArtist: String?,
    val year: Int?,
    val artworkUri: String?,
)

@Entity(tableName = "artists", indices = [Index("name", unique = true)])
data class ArtistEntity(
    @PrimaryKey val id: Long,
    val name: String,
)

@Entity(
    tableName = "analysis_results",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AnalysisResultEntity(
    @PrimaryKey val songId: Long,
    val analyzedAtFileHash: String,
    val analysisVersion: Int,
    // Rhythm
    val bpm: Float?,
    val beatConfidence: Float?,
    // Harmony
    val musicalKey: String?,
    val isMajor: Boolean?,
    // Dynamics
    val rms: Float,
    val lufs: Float,
    val peakLevel: Float,
    val dynamicRange: Float,
    // Frequency bands
    val bassEnergy: Float,
    val midEnergy: Float,
    val trebleEnergy: Float,
    // Perceptual (0..1)
    val energy: Float,
    val danceability: Float,
    val acousticness: Float,
    val instrumentalness: Float,
    val brightness: Float,
    val warmth: Float,
    val aggressiveness: Float,
    val calmness: Float,
    val happiness: Float,
    val sadness: Float,
    val tension: Float,
    /** L2-normalized FloatArray serialized little-endian; see EmbeddingCodec (Phase 5/6). */
    val embeddingBlob: ByteArray,
) {
    // ByteArray forces manual equals/hashCode; identity by songId is correct here.
    override fun equals(other: Any?): Boolean =
        other is AnalysisResultEntity && other.songId == songId
    override fun hashCode(): Int = songId.hashCode()
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index("songId"), Index(value = ["playlistId", "position"])],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

@Entity(
    tableName = "play_history",
    indices = [Index("songId"), Index("playedAt")],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
    val completed: Boolean,
)

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FavoriteEntity(
    @PrimaryKey val songId: Long,
    val addedAt: Long,
)
