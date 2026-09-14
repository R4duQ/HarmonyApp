package com.harmony.domain.swipe.engine

import kotlin.math.sqrt

/**
 * The two formulas the recommender rests on.
 *
 * Kept separate from the engine so they can be tested against hand-computed
 * values, and so the engine reads as policy rather than arithmetic.
 *
 * On SIMD: there is none here, and that is a deliberate call. The library
 * index this feeds is `FlatEmbeddingIndex`, whose own header explains why
 * brute force wins at this scale — 20k songs x 48 dims is ~1M multiply-adds,
 * well under a millisecond. The swipe deck does *one* such query per card,
 * roughly one per second of human interaction. A hand-vectorized NEON kernel
 * would be optimizing something that already costs less than the frame it
 * renders in, at the price of a native dependency and two code paths. The
 * existing `distance_kernels.cpp` remains the right home if a future workload
 * ever needs batched scoring.
 */
object SwipeVectorMath {

    /**
     * Cosine similarity: cos(theta) = (a . b) / (||a|| * ||b||)
     *
     * Computes the norms rather than assuming unit length. Callers here do
     * feed normalized vectors, but a silently un-normalized input would
     * produce plausible-looking wrong scores instead of an obvious failure,
     * and this is cheap enough (one extra pass over ~48 floats) that buying
     * that safety is free.
     *
     * Returns 0 when either vector has zero magnitude — the geometric answer
     * is undefined, and 0 ("unrelated") is the only value that keeps ranking
     * monotonic without inventing a preference.
     *
     * @throws IllegalArgumentException on dimension mismatch, which is always
     *   a wiring bug rather than a data condition.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) {
            "Vector dimension mismatch: ${a.size} vs ${b.size}"
        }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA == 0f || normB == 0f) return 0f
        return dot / (sqrt(normA) * sqrt(normB))
    }

    /**
     * Exponential moving average update of the preference vector.
     *
     *     v_new = normalize( (1 - alpha) * v_old + alpha * v_liked )
     *
     * Each past like decays geometrically: after n further likes, a track's
     * influence is alpha * (1 - alpha)^n. At alpha = 0.20 the most recent
     * like carries 20% of the profile, the one before it 16%, and anything
     * more than ~20 likes back is under 1%. That is the "slightly more weight
     * to what was just liked" the deck needs — recent enough to follow a mood
     * as it shifts, slow enough that one outlier cannot hijack the session.
     *
     * The renormalization at the end is not cosmetic. `FlatEmbeddingIndex`
     * documents that it treats cosine similarity as a plain dot product
     * because its stored vectors are unit length; feeding it a query whose
     * magnitude drifts would scale every score by that magnitude. Ranking
     * within a single query would survive, but the absolute values would not
     * — and [SwipeConfig.exploreFloor] compares against an absolute value.
     *
     * The first like has no average to blend into, so it seeds the profile
     * outright rather than being averaged against nothing.
     */
    fun updateUserVector(current: FloatArray?, likedVector: FloatArray, alpha: Float): FloatArray {
        require(alpha > 0f && alpha <= 1f) { "alpha must be in (0, 1], was $alpha" }
        if (current == null) return normalize(likedVector)
        require(current.size == likedVector.size) {
            "Vector dimension mismatch: ${current.size} vs ${likedVector.size}"
        }
        val blended = FloatArray(current.size)
        for (i in blended.indices) {
            blended[i] = (1f - alpha) * current[i] + alpha * likedVector[i]
        }
        return normalize(blended)
    }

    /**
     * Scales to unit length, or returns a copy unchanged when the magnitude
     * is zero — dividing by it would produce NaNs that then poison every
     * downstream similarity silently.
     */
    fun normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (value in vector) sumSquares += value * value
        if (sumSquares == 0f) return vector.copyOf()
        val inverseNorm = 1f / sqrt(sumSquares)
        val result = FloatArray(vector.size)
        for (i in vector.indices) result[i] = vector[i] * inverseNorm
        return result
    }
}
