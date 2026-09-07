package com.harmony.domain.shuffle.model

import com.harmony.core.model.MoodFilter

/**
 * High-level Smart Shuffle personality. The sliders remain available for fine
 * tuning; choosing a style simply applies a sensible preset.
 */
enum class SmartShuffleStyle {
    BALANCED,
    FAMILIAR,
    DISCOVER,
    FLOW,
}

/**
 * Live Smart Shuffle configuration, owned by the coordinator and mutated by
 * the UI.
 *
 * The v2 controls intentionally stay human-readable:
 *  - familiarity: how strongly completed plays/favourites are rewarded;
 *  - discovery: how strongly never/rarely/recently-unheard songs are rewarded;
 *  - variety: how wide the weighted-random candidate spread is.
 *
 * Energy and mood remain optional musical biases. None of these are hard
 * filters, because hard filters make small libraries stall.
 */
data class ShuffleConfig(
    val energyTarget: Float? = null,
    /** Softmax temperature. Kept public for tests/Journey and derived from variety in the UI. */
    val temperature: Float = 0.30f,
    /** Songs played within this window are excluded from selection. */
    val recentWindowMillis: Long = 2 * 60 * 60 * 1000L,
    /** Optional mood constraint: candidates are re-scored toward this mood. */
    val moodFilter: MoodFilter? = null,
    val style: SmartShuffleStyle = SmartShuffleStyle.BALANCED,
    val familiarity: Float = 0.55f,
    val discovery: Float = 0.45f,
    val variety: Float = 0.45f,
)

/** A running Journey. */
data class JourneyState(
    /** Interpolation source (embedding of the song the journey started from). */
    val startEmbedding: FloatArray,
    /** Interpolation destination. */
    val destinationEmbedding: FloatArray,
    /** Total songs the journey should take to arrive. */
    val totalSteps: Int,
    /** Songs picked so far. */
    val step: Int = 0,
) {
    val progress: Float get() = if (totalSteps <= 0) 1f else (step.toFloat() / totalSteps).coerceIn(0f, 1f)
    val isComplete: Boolean get() = step >= totalSteps

    override fun equals(other: Any?) = this === other
    override fun hashCode() = step
}
