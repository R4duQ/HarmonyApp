package com.harmony.domain.shuffle.engine

import com.harmony.core.model.MoodFilter

/**
 * Mood scoring: each mood is an anchor point over a subset of the perceptual
 * axes plus per-axis weights; a song's membership score is a weighted
 * closeness to that anchor in [0, 1]. Scores rank songs for mood playlists
 * and bias Smart Shuffle — never hard-classify. A song can be 0.7 Workout
 * and 0.6 Party at once, which matches how moods actually overlap.
 *
 * Axis order matches PerceptualProfile:
 *   energy, danceability, acousticness, instrumentalness, brightness,
 *   warmth, aggressiveness, calmness, happiness, sadness, tension
 *
 * Weight 0 = axis irrelevant to that mood. Anchors are editorial judgment,
 * intentionally centralized here so tuning mood feel is a one-file change.
 */
object MoodProfiles {

    private const val AXES = 11

    private class Profile(val target: FloatArray, val weight: FloatArray) {
        init { require(target.size == AXES && weight.size == AXES) }
    }

    //                                     ene  dan  aco  ins  bri  war  agg  cal  hap  sad  ten
    private val profiles = mapOf(
        MoodFilter.HAPPY to Profile(
            floatArrayOf(0.7f, 0.7f, 0.0f, 0.0f, 0.7f, 0.0f, 0.0f, 0.0f, 0.9f, 0.1f, 0.0f),
            floatArrayOf(0.5f, 0.5f, 0.0f, 0.0f, 0.3f, 0.0f, 0.0f, 0.0f, 1.0f, 0.7f, 0.0f)),
        MoodFilter.SAD to Profile(
            floatArrayOf(0.2f, 0.1f, 0.6f, 0.0f, 0.2f, 0.5f, 0.1f, 0.6f, 0.1f, 0.9f, 0.0f),
            floatArrayOf(0.5f, 0.3f, 0.3f, 0.0f, 0.3f, 0.2f, 0.4f, 0.4f, 0.8f, 1.0f, 0.0f)),
        MoodFilter.CALM to Profile(
            floatArrayOf(0.15f, 0.1f, 0.6f, 0.0f, 0.2f, 0.6f, 0.05f, 0.9f, 0.0f, 0.0f, 0.1f),
            floatArrayOf(0.7f, 0.3f, 0.3f, 0.0f, 0.3f, 0.3f, 0.7f, 1.0f, 0.0f, 0.0f, 0.5f)),
        MoodFilter.DARK to Profile(
            floatArrayOf(0.4f, 0.2f, 0.1f, 0.0f, 0.15f, 0.3f, 0.5f, 0.2f, 0.05f, 0.6f, 0.8f),
            floatArrayOf(0.2f, 0.2f, 0.2f, 0.0f, 0.6f, 0.2f, 0.4f, 0.2f, 0.8f, 0.4f, 0.8f)),
        MoodFilter.AGGRESSIVE to Profile(
            floatArrayOf(0.9f, 0.4f, 0.0f, 0.0f, 0.7f, 0.1f, 0.95f, 0.05f, 0.0f, 0.0f, 0.8f),
            floatArrayOf(0.6f, 0.0f, 0.3f, 0.0f, 0.3f, 0.2f, 1.0f, 0.7f, 0.0f, 0.0f, 0.5f)),
        MoodFilter.RELAXING to Profile(
            floatArrayOf(0.2f, 0.2f, 0.6f, 0.3f, 0.25f, 0.7f, 0.05f, 0.85f, 0.4f, 0.2f, 0.1f),
            floatArrayOf(0.7f, 0.2f, 0.4f, 0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 0.2f, 0.2f, 0.6f)),
        MoodFilter.EPIC to Profile(
            floatArrayOf(0.8f, 0.3f, 0.2f, 0.6f, 0.6f, 0.3f, 0.5f, 0.1f, 0.4f, 0.3f, 0.7f),
            floatArrayOf(0.8f, 0.1f, 0.2f, 0.4f, 0.3f, 0.1f, 0.3f, 0.5f, 0.1f, 0.1f, 0.7f)),
        MoodFilter.EMOTIONAL to Profile(
            floatArrayOf(0.4f, 0.2f, 0.6f, 0.1f, 0.35f, 0.6f, 0.1f, 0.4f, 0.3f, 0.7f, 0.4f),
            floatArrayOf(0.2f, 0.3f, 0.4f, 0.4f, 0.2f, 0.4f, 0.5f, 0.2f, 0.2f, 0.8f, 0.3f)),
        MoodFilter.FOCUS to Profile(
            floatArrayOf(0.3f, 0.2f, 0.4f, 0.9f, 0.3f, 0.5f, 0.05f, 0.7f, 0.2f, 0.2f, 0.2f),
            floatArrayOf(0.5f, 0.4f, 0.2f, 1.0f, 0.2f, 0.2f, 0.8f, 0.6f, 0.1f, 0.1f, 0.4f)),
        MoodFilter.WORKOUT to Profile(
            floatArrayOf(0.95f, 0.8f, 0.0f, 0.0f, 0.7f, 0.1f, 0.6f, 0.0f, 0.5f, 0.0f, 0.4f),
            floatArrayOf(1.0f, 0.8f, 0.4f, 0.0f, 0.2f, 0.1f, 0.4f, 0.8f, 0.1f, 0.3f, 0.1f)),
        MoodFilter.PARTY to Profile(
            floatArrayOf(0.85f, 0.95f, 0.0f, 0.0f, 0.65f, 0.2f, 0.3f, 0.0f, 0.8f, 0.0f, 0.2f),
            floatArrayOf(0.7f, 1.0f, 0.4f, 0.2f, 0.2f, 0.1f, 0.1f, 0.6f, 0.5f, 0.4f, 0.1f)),
    )

    /**
     * Membership score in [0, 1] for [perceptual] (11 axes, order as above).
     */
    fun score(mood: MoodFilter, perceptual: FloatArray): Float {
        require(perceptual.size == AXES)
        val p = profiles.getValue(mood)
        var weighted = 0f
        var weightSum = 0f
        for (i in 0 until AXES) {
            val w = p.weight[i]
            if (w == 0f) continue
            weighted += w * (1f - kotlin.math.abs(perceptual[i] - p.target[i]))
            weightSum += w
        }
        return if (weightSum > 0f) (weighted / weightSum).coerceIn(0f, 1f) else 0f
    }

    /** The mood's expected energy — used as a cheap in-index bias. */
    fun energyPrior(mood: MoodFilter): Float = profiles.getValue(mood).target[0]

    /** Sensible default cutoff for "belongs to this mood" list membership. */
    const val MEMBERSHIP_THRESHOLD = 0.62f
}
