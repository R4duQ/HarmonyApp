package com.harmony.domain.similarity.repository

import com.harmony.domain.similarity.model.Neighbor
import com.harmony.domain.similarity.model.SimilarityOptions
import kotlinx.coroutines.flow.StateFlow

/**
 * Implemented by :data:similarity, which owns loading embeddings from Room
 * into the in-memory index and keeping it fresh as analysis progresses.
 */
interface SimilarityRepository {

    /** Number of indexed songs; 0 while analysis hasn't produced anything yet. */
    val indexedCount: StateFlow<Int>

    /** Nearest songs to [songId]. Empty if the song isn't analyzed yet. */
    suspend fun findSimilar(songId: Long, options: SimilarityOptions = SimilarityOptions()): List<Neighbor>

    /** Nearest songs to an arbitrary target vector (Journey Mode steering). */
    suspend fun findSimilarToVector(
        vector: FloatArray,
        options: SimilarityOptions = SimilarityOptions(),
    ): List<Neighbor>

    /** The stored embedding for a song, or null if not analyzed. */
    suspend fun embeddingOf(songId: Long): FloatArray?

    /** Perceptual energy of a song if analyzed (drives the Energy Slider without a DB hit). */
    suspend fun energyOf(songId: Long): Float?

    /** Local analyzed tempo, used by Smart Shuffle Flow mode. */
    suspend fun bpmOf(songId: Long): Float?
}
