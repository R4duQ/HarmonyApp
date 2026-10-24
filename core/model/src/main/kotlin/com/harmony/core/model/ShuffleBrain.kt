package com.harmony.core.model

/**
 * What Smart Shuffle's Diagnostic Brain reports, from the native engine
 * (core:dsp-native, SmartShuffleEngine) to the queue manager, Logcat and the
 * debug overlay. Plain values only, so every layer can hold them.
 */

/** The 24 acoustic dimensions every song is measured on. */
object AcousticDims {
    const val COUNT = 24
    const val BANDS = 10

    val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

    /** Short labels for the overlay's bars. */
    val LABELS: List<String> =
        BAND_LABELS + BAND_LABELS.map { "~$it" } + listOf("PUNCH", "BRIGHT", "CREST", "PULSE")

    /** The bands the session leans on most (largest positive values), strongest first. */
    fun dominantBands(vector: List<Float>, count: Int = 3): List<Int> =
        (0 until minOf(BANDS, vector.size))
            .filter { vector[it] > 0f }
            .sortedByDescending { vector[it] }
            .take(count)

    /** "Bass", "Warm", "Vocal", "Bright" or "Air", as the engine names its zones. */
    fun zoneWord(zone: Int): String = when (zone) {
        in 0..2 -> "Bass"
        3, 4 -> "Warm"
        5, 6 -> "Vocal"
        7, 8 -> "Bright"
        9 -> "Air"
        else -> "Mixed"
    }
}

enum class VectorQuality {
    /** Nothing measured yet: a placeholder direction. */
    NONE,
    /** Approximated from the older analysis (cold start). */
    FALLBACK,
    /** A real 24-D measurement. */
    REAL,
}

/** What a playback event did to the session vibe vector S_t. */
enum class SessionEvent {
    NONE,
    SEEDED,
    /** Played past half: S_t = normalize(0.60 v + 0.40 S_t-1). */
    PLAY_EMA,
    /** Skipped before 20%: S_t = normalize(S_t-1 - 0.35 v), and the tail is re-ranked. */
    FAST_SKIP,
    /** Picked by hand: S_t = normalize(0.85 v + 0.15 S_t-1). */
    MANUAL_ANCHOR,
    NEUTRAL,
}

enum class AuditStatus {
    PASS,
    /** The check failed and the brain repaired it. */
    HEALED,
    /** The check failed and could not be repaired (usually: the library is too small). */
    FAILED,
}

/** The five invariants, in the engine's order. */
enum class AuditCheckKind(val title: String, val code: String) {
    ANTI_CLUMPING("Anti-clumping", "ART"),
    SKIP_REPULSION("Skip repulsion", "SKIP"),
    ECHO_CHAMBER("Echo chamber", "ECHO"),
    VECTOR_INTEGRITY("Vector integrity", "VEC"),
    TRANSITION_JERK("Transition jerk", "JERK"),
}

data class AuditCheckResult(
    val kind: AuditCheckKind,
    val status: AuditStatus,
    /** See [AuditCheckKind]: min artist gap, tail share replaced, avg cosine, ||S_t||, largest jump. */
    val metric: Float,
    val threshold: Float,
    val corrections: Int,
    val detail: String,
)

data class BrainAuditReport(
    val generation: Int,
    val checks: List<AuditCheckResult>,
    val afterFastSkip: Boolean,
    val averagePairwiseSimilarity: Float,
    val artistEntropyBits: Float,
    val zoneEntropyBits: Float,
    val sessionNorm: Float,
    val nonFiniteValues: Int,
    /** Share of the library with a real 24-D vector, 0..100. */
    val realVectorPct: Float,
    val realTracks: Int,
    val fallbackTracks: Int,
    val placeholderTracks: Int,
    /** The current multiplier on the exploration weight (1 = none). */
    val exploreBoost: Float,
    val elapsedMs: Float,
) {
    val healthy: Boolean get() = checks.none { it.status == AuditStatus.FAILED }
    val healedCount: Int get() = checks.count { it.status == AuditStatus.HEALED }

    /** PASS, HEALED (n) or FAILED (n): the one-word verdict for logs and the overlay. */
    val verdict: String
        get() = when {
            !healthy -> "FAILED (${checks.count { it.status == AuditStatus.FAILED }})"
            healedCount > 0 -> "HEALED ($healedCount)"
            else -> "PASS"
        }
}

enum class ReasonTag {
    VIBE_MATCH,
    MARKOV_FAVORITE,
    BANDIT_GEM,
    POST_SKIP_PIVOT,
    PROVEN_FAVORITE,
    SONIC_ADVENTURE,
    COLD_START,
}

/** Why the brain queued a track. The four percentages sum to 100. */
data class TrackDecisionTrace(
    val trackId: Long,
    val rawScore: Float,
    val vibeSimilarityPct: Float,
    val markovHabitPct: Float,
    val userAffinityPct: Float,
    val explorationBonusPct: Float,
    /** RecencyCooldown(i): 1 = none. */
    val recencyPenaltyMultiplier: Float,
    /** 1 - ||v_prev - v||/2: 1 = seamless. */
    val transitionSmoothnessScore: Float,
    val cosineSimilarity: Float,
    val zone: Int,
    val quality: VectorQuality,
    val reason: ReasonTag,
    /** e.g. "94% Bass/Vibe Match", "Markov Favorite Transition", "Post-Skip Pivot (away from Bright)". */
    val primaryReasonTag: String,
)

/** Everything the overlay shows, published by the queue manager after every decision. */
data class ShuffleBrainTelemetry(
    /** S_t, 24 values in the engine's standardized space (empty before the first song). */
    val sessionVector: List<Float> = emptyList(),
    val hasSession: Boolean = false,
    val lastEvent: SessionEvent = SessionEvent.NONE,
    val audit: BrainAuditReport? = null,
    /** The upcoming queued tracks the brain chose, in queue order, with their reasons. */
    val upcoming: List<QueuedDecision> = emptyList(),
    val librarySize: Int = 0,
    val engineAvailable: Boolean = true,
    val updatedAtMillis: Long = 0L,
    val selfTestReport: String? = null,
)

data class QueuedDecision(
    val trace: TrackDecisionTrace,
    val title: String,
    val artist: String,
)
