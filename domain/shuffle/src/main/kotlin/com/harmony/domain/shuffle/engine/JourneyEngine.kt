package com.harmony.domain.shuffle.engine

import com.harmony.domain.analysis.embedding.EmbeddingBuilder
import com.harmony.domain.shuffle.model.JourneyState
import com.harmony.domain.shuffle.model.ShuffleConfig
import com.harmony.domain.similarity.model.SimilarityOptions
import com.harmony.domain.similarity.repository.SimilarityRepository
import javax.inject.Inject

/**
 * Journey Mode: playback that drifts from where you are to where you want
 * to be — Ambient -> Lo-fi -> Jazz -> ... — without ever jumping.
 *
 * Mechanism: the journey is a straight line in embedding space. For pick
 * number s of N, the steering target is
 *
 *     target(s) = normalize( (1 - t) * start + t * destination ),  t = s / N
 *
 * and we ask the index for songs nearest to that *synthetic* vector, then
 * softmax-sample among them (same sampler as Smart Shuffle, so both modes
 * share one "feels natural" tuning).
 *
 * Why interpolate targets instead of chaining "next similar" hops toward the
 * goal: chained hops accumulate drift (each hop optimizes locally and the
 * walk wanders), and the arrival time is unbounded. Interpolation guarantees
 * monotonic progress and arrival in exactly N songs, and — because the actual
 * played song is always a real library track near the line — every
 * consecutive pair remains audibly related. Gradualness falls out of the
 * geometry rather than being enforced by rules.
 *
 * The line lives in the full 48-dim space, so the transition morphs timbre,
 * rhythm, and mood together — an ambient->metal journey passes through
 * whatever the LIBRARY has between them (post-rock, industrial...), which is
 * exactly the organic feel the spec sketches.
 */
class JourneyEngine @Inject constructor(
    private val similarityRepository: SimilarityRepository,
    private val sampler: SmartShuffleEngine,
) {

    /**
     * @return the started state, or null if either endpoint isn't analyzed yet.
     */
    suspend fun start(fromSongId: Long, toSongId: Long, totalSteps: Int): JourneyState? {
        val start = similarityRepository.embeddingOf(fromSongId) ?: return null
        val dest = similarityRepository.embeddingOf(toSongId) ?: return null
        return JourneyState(
            startEmbedding = start,
            destinationEmbedding = dest,
            totalSteps = totalSteps.coerceIn(MIN_STEPS, MAX_STEPS),
        )
    }

    /**
     * Picks the next song along the journey.
     * @return (songId, advancedState) or null if the index has no candidates.
     */
    suspend fun pickNext(
        state: JourneyState,
        config: ShuffleConfig,
        excludeSongIds: Set<Long>,
    ): Pair<Long, JourneyState>? {
        val t = ((state.step + 1).toFloat() / state.totalSteps).coerceIn(0f, 1f)
        val target = interpolate(state.startEmbedding, state.destinationEmbedding, t)

        val neighbors = similarityRepository.findSimilarToVector(
            vector = target,
            options = SimilarityOptions(
                k = SmartShuffleEngine.CANDIDATE_POOL,
                excludeSongIds = excludeSongIds,
            ),
        )
        // Journey ignores the energy slider: the trajectory IS the energy
        // curve. Mood filter likewise — you asked to travel, not to stay.
        val pick = sampler.sample(neighbors, config.copy(energyTarget = null, moodFilter = null))
            ?: return null
        return pick to state.copy(step = state.step + 1)
    }

    companion object {
        const val MIN_STEPS = 5
        const val MAX_STEPS = 100

        fun interpolate(a: FloatArray, b: FloatArray, t: Float): FloatArray {
            require(a.size == b.size)
            val out = FloatArray(a.size)
            for (i in a.indices) out[i] = a[i] * (1 - t) + b[i] * t
            return EmbeddingBuilder.l2Normalize(out)
        }
    }
}
