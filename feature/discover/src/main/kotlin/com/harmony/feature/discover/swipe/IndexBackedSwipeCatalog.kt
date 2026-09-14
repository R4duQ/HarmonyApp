package com.harmony.feature.discover.swipe

import com.harmony.domain.similarity.index.FlatEmbeddingIndex
import com.harmony.domain.similarity.model.Neighbor
import com.harmony.domain.swipe.engine.SwipeCatalog

/**
 * Binds the swipe deck to Harmony's existing acoustic index.
 *
 * This is the only place the deck touches real library data, which is why the
 * engine module stays testable with two-dimensional toy vectors.
 *
 * On FAISS/ChromaDB: not used, and not an oversight. `FlatEmbeddingIndex`
 * already does exactly the query this needs — cosine KNN over L2-normalized
 * embeddings — and its own header lays out the reasoning: at ~20k songs by 48
 * dims a full scan is around a million multiply-adds, comfortably under a
 * millisecond, against a deck that issues one query per human swipe. An ANN
 * index would add a native dependency, a build step, and approximation error
 * to accelerate something already faster than the animation it feeds. It also
 * gives two behaviours for free that the deck genuinely needs and a raw FAISS
 * index would not: duplicate-recording dedupe, and a per-album cap that keeps
 * a session from collapsing into one record's track listing.
 *
 * @param snapshotSongIds ids currently eligible for the deck. Passed in rather
 *   than read from the index because eligibility is a library question (is the
 *   file still present, is it excluded from Discover) and the index only knows
 *   about vectors.
 * @param playCounts lifetime counts from the play-history table. Absent means
 *   never played, which is precisely what exploration is hunting for.
 */
class IndexBackedSwipeCatalog(
    private val index: FlatEmbeddingIndex,
    private val snapshotSongIds: List<Long>,
    private val albumIds: Map<Long, Long>,
    private val playCounts: Map<Long, Int>,
) : SwipeCatalog {

    override val songIds: List<Long> = snapshotSongIds.filter(index::contains)

    override fun vectorOf(songId: Long): FloatArray? = vectorCache[songId]

    override fun albumOf(songId: Long): Long = albumIds[songId] ?: 0L

    override fun playCountOf(songId: Long): Int = playCounts[songId] ?: 0

    override fun nearest(query: FloatArray, k: Int, exclude: Set<Long>): List<Neighbor> =
        index.findNearestVector(
            query = query,
            k = k,
            exclude = exclude,
            // Synthetic query vector, so there is no "the song itself" to
            // dedupe against; the default 2f disables it. Duplicate
            // *recordings* are still handled by maxPerAlbum below plus the
            // deck's own exclusion set.
            maxPerAlbum = MAX_PER_ALBUM,
        )

    /**
     * Vectors for the eligible songs.
     *
     * Built once per session from the index's own neighbour results rather
     * than kept in sync with it: a swipe session is short, and a vector that
     * changes mid-session (a re-analysis landing) would mean the profile was
     * blended from a vector that no longer exists.
     */
    private val vectorCache: Map<Long, FloatArray> = buildVectorCache()

    private fun buildVectorCache(): Map<Long, FloatArray> {
        val cache = HashMap<Long, FloatArray>(songIds.size)
        songIds.forEach { id ->
            index.vectorOf(id)?.let { cache[id] = it }
        }
        return cache
    }

    private companion object {
        /**
         * Two tracks per album per query. Higher and a session spent liking
         * one record starts dealing that record back; lower and coherent
         * albums get under-represented in a way that feels arbitrary.
         */
        const val MAX_PER_ALBUM = 2
    }
}
