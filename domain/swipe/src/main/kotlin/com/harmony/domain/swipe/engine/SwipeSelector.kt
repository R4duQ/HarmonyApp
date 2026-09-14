package com.harmony.domain.swipe.engine

import com.harmony.domain.similarity.model.Neighbor
import com.harmony.domain.swipe.model.CandidateSource
import com.harmony.domain.swipe.model.SwipeCandidate
import com.harmony.domain.swipe.model.SwipeConfig
import com.harmony.domain.swipe.model.SwipeProfile
import kotlin.random.Random

/**
 * What the selector needs from the library.
 *
 * A port, not a concrete index, for one reason that matters: it lets the
 * whole selection policy be unit-tested against a handful of synthetic
 * vectors, with no analysis pipeline, no Room, and no Android. The real
 * implementation is a thin adapter over `FlatEmbeddingIndex`.
 */
interface SwipeCatalog {
    /** Every analyzed song eligible for the deck. */
    val songIds: List<Long>

    /** L2-normalized embedding, or null if the song is not in the index. */
    fun vectorOf(songId: Long): FloatArray?

    fun albumOf(songId: Long): Long

    /**
     * K nearest neighbours by cosine similarity.
     *
     * Maps onto `FlatEmbeddingIndex.findNearestVector`, which already applies
     * duplicate-recording dedupe and a per-album cap — both of which the deck
     * wants, or a swipe session would turn into one album's track listing.
     */
    fun nearest(query: FloatArray, k: Int, exclude: Set<Long>): List<Neighbor>

    /** Lifetime play count; 0 for never-played. Drives the long-tail preference. */
    fun playCountOf(songId: Long): Int
}

/**
 * Epsilon-greedy dealer.
 *
 * Exploitation is the obvious half: ask the index for the nearest neighbours
 * of the preference vector and deal one.
 *
 * Exploration is the half worth explaining. "Play something random" would be
 * exploration in the textbook sense and useless in practice — a death-metal
 * card in the middle of an ambient session is not a discovery, it is a swipe
 * left the user resents. So exploration here draws from the *far end* of a
 * deliberately wide neighbour query: still acoustically related, but past the
 * point where the recommendation is predictable. Within that tail, songs the
 * listener has rarely played are preferred, which is what makes this reach
 * the library's long tail rather than recycling familiar records.
 */
class SwipeSelector(
    private val catalog: SwipeCatalog,
    private val config: SwipeConfig = SwipeConfig(),
    private val random: Random = Random.Default,
) {

    /**
     * Deals one card, or null when nothing eligible remains.
     *
     * @param exclude everything already decided or already sitting in the deck.
     *   Cards below the top one must be excluded too, or the same song can be
     *   dealt twice into one stack.
     */
    fun next(profile: SwipeProfile, exclude: Set<Long>): SwipeCandidate? {
        val vector = profile.preferenceVector ?: return seed(exclude)
        val explore = random.nextFloat() < config.explorationRate
        // Try the chosen arm, then fall back to the other rather than
        // returning null: a deck that stalls because exploration found
        // nothing is worse than one that quietly exploits for a card.
        return if (explore) {
            exploreCandidate(vector, exclude) ?: exploitCandidate(vector, exclude)
        } else {
            exploitCandidate(vector, exclude) ?: exploreCandidate(vector, exclude)
        } ?: seed(exclude)
    }

    /**
     * Cold start: no likes yet, so there is no vector to steer by.
     *
     * Uniform random over the library. Any cleverness here (most played,
     * newest, highest energy) would bias the very first likes, and those
     * likes are what the entire session's vector is built from.
     */
    private fun seed(exclude: Set<Long>): SwipeCandidate? {
        val pool = catalog.songIds.filterNot { it in exclude }
        if (pool.isEmpty()) return null
        val songId = pool[random.nextInt(pool.size)]
        val vector = catalog.vectorOf(songId) ?: return null
        return SwipeCandidate(
            songId = songId,
            albumId = catalog.albumOf(songId),
            vector = vector,
            source = CandidateSource.SEED,
            similarityToProfile = 0f,
        )
    }

    /**
     * Nearest neighbours, picked from at random rather than strictly top-1.
     *
     * Strict top-1 would make the deck deterministic given a profile, so a
     * user who swiped left on the best match would see the second-best, then
     * the third — a descending staircase through the same neighbourhood.
     * Sampling the top [SwipeConfig.exploitPoolSize] keeps it varied while
     * staying firmly in "more of what you like" territory.
     */
    private fun exploitCandidate(vector: FloatArray, exclude: Set<Long>): SwipeCandidate? {
        val neighbors = catalog.nearest(vector, config.exploitPoolSize, exclude)
        val pick = neighbors.randomOrNull(random) ?: return null
        return pick.toCandidate(CandidateSource.EXPLOIT)
    }

    /**
     * The long-tail draw.
     *
     * Fetch a wide neighbour list, discard the leading third (that region is
     * exploitation's job), keep only what clears the acoustic floor, then
     * favour the least-played songs among what remains.
     */
    private fun exploreCandidate(vector: FloatArray, exclude: Set<Long>): SwipeCandidate? {
        val neighbors = catalog.nearest(vector, config.explorePoolSize, exclude)
        if (neighbors.isEmpty()) return null

        // nearest() returns descending similarity, so dropping the head moves
        // us away from the predictable picks without leaving the neighbourhood.
        val tailStart = neighbors.size / 3
        val tail = neighbors.drop(tailStart)
            .filter { it.similarity >= config.exploreFloor }
            .ifEmpty {
                // A narrow or tightly-clustered library can leave nothing past
                // the floor. Rather than give up on exploration entirely, take
                // the least-similar few that exist — still the far end, just
                // of a shorter list.
                neighbors.takeLast((neighbors.size / 3).coerceAtLeast(1))
            }

        // Weight toward the unfamiliar: among the tail, prefer low play counts.
        // Grouping by count and sampling within the lowest group keeps this
        // from becoming a strict ordering, which would replay the same
        // never-played songs every session in the same order.
        val leastPlayed = tail.groupBy { catalog.playCountOf(it.songId) }
            .minByOrNull { it.key }
            ?.value
            ?: return null
        val pick = leastPlayed.randomOrNull(random) ?: return null
        return pick.toCandidate(CandidateSource.EXPLORE)
    }

    private fun Neighbor.toCandidate(source: CandidateSource): SwipeCandidate? {
        val vector = catalog.vectorOf(songId) ?: return null
        return SwipeCandidate(
            songId = songId,
            albumId = albumId,
            vector = vector,
            source = source,
            similarityToProfile = similarity,
        )
    }

    private fun <T> List<T>.randomOrNull(random: Random): T? =
        if (isEmpty()) null else this[random.nextInt(size)]
}
