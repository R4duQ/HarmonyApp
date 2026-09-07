package com.harmony.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.harmony.core.database.entity.AnalysisResultEntity
import com.harmony.core.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/**
 * Ready for Phase 5 (analysis pipeline) and Phase 6 (similarity index load).
 * Declared now so the energy-based smart playlists compile against it.
 */
@Dao
interface AnalysisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: AnalysisResultEntity)

    @Query("SELECT * FROM analysis_results WHERE songId = :songId")
    suspend fun bySongId(songId: Long): AnalysisResultEntity?

    @Query("SELECT songId, analyzedAtFileHash, analysisVersion FROM analysis_results")
    suspend fun analysisKeys(): List<AnalysisKeyRow>

    data class AnalysisKeyRow(val songId: Long, val analyzedAtFileHash: String, val analysisVersion: Int)

    @Query("SELECT songId, embeddingBlob FROM analysis_results")
    suspend fun allEmbeddings(): List<EmbeddingRow>

    data class EmbeddingRow(val songId: Long, val embeddingBlob: ByteArray)

    /**
     * Everything the similarity index needs, as one observable query: it
     * re-emits whenever analysis writes a row or a song is deleted, which is
     * what keeps the in-memory index in sync with zero explicit signaling.
     *
     * Carries the full perceptual profile (not just energy) so mood-filtered
     * Smart Shuffle can score real mood fit via MoodProfiles.score, instead
     * of the energy-only proxy the index originally shipped with.
     */
    @Query(
        """
        SELECT r.songId, r.embeddingBlob, r.energy, r.bpm, s.albumId,
               r.danceability, r.acousticness, r.instrumentalness, r.brightness,
               r.warmth, r.aggressiveness, r.calmness, r.happiness, r.sadness, r.tension
        FROM analysis_results r JOIN songs s ON s.id = r.songId
        """
    )
    fun observeIndexRows(): Flow<List<IndexRow>>

    data class IndexRow(
        val songId: Long,
        val embeddingBlob: ByteArray,
        val energy: Float,
        val bpm: Float?,
        val albumId: Long,
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
    ) {
        fun perceptualArray(): FloatArray = floatArrayOf(
            energy, danceability, acousticness, instrumentalness, brightness,
            warmth, aggressiveness, calmness, happiness, sadness, tension,
        )
    }

    @Query(
        """
        SELECT songId, energy, danceability, acousticness, instrumentalness,
               brightness, warmth, aggressiveness, calmness, happiness, sadness, tension
        FROM analysis_results
        """
    )
    fun observePerceptualRows(): Flow<List<PerceptualRow>>

    data class PerceptualRow(
        val songId: Long,
        val energy: Float, val danceability: Float, val acousticness: Float,
        val instrumentalness: Float, val brightness: Float, val warmth: Float,
        val aggressiveness: Float, val calmness: Float, val happiness: Float,
        val sadness: Float, val tension: Float,
    )

    @Query(
        """
        SELECT s.* FROM songs_effective s JOIN analysis_results r ON r.songId = s.id
        ORDER BY r.energy DESC LIMIT :limit
        """
    )
    fun observeHighestEnergy(limit: Int): Flow<List<SongEntity>>

    @Query(
        """
        SELECT s.* FROM songs_effective s JOIN analysis_results r ON r.songId = s.id
        ORDER BY r.energy ASC LIMIT :limit
        """
    )
    fun observeLowestEnergy(limit: Int): Flow<List<SongEntity>>
}
