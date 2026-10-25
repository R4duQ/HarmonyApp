package com.harmony.domain.shuffle.brain

import com.harmony.core.model.BrainAuditReport
import com.harmony.core.model.SessionEvent
import com.harmony.core.model.TrackDecisionTrace
import com.harmony.domain.shuffle.model.ShuffleConfig

/**
 * Smart Shuffle's recommendation engine and its Diagnostic Brain, as the
 * queue manager sees it. Implemented in :data:shuffle on the native
 * SmartShuffleEngine (24-D acoustic vectors, the session vibe vector, the
 * multi-factor scorer, dithering and the five self-audits) with its habits
 * kept in SmartShuffleDatabase.
 *
 * Everything is offline and works from audio alone: no network and no genre
 * tags. Calls are suspend and may do I/O; the manager serializes them.
 */
interface ShuffleBrain {
    /** False when the native engine could not be loaded; the manager then falls back to random picks. */
    val available: Boolean

    /** Loads or refreshes the library snapshot when it is missing or out of date. */
    suspend fun prepare()

    /** The sliders and style: weights, temperature, energy and mood steering. */
    suspend fun applyConfig(config: ShuffleConfig)

    /** A song started playing (any mode): feeds the Markov habits; seeds S_t when there is none. */
    suspend fun onTrackStarted(songId: Long): SessionEvent

    /**
     * A song was left after [fraction] of it. Past half: S_t moves toward it;
     * under 20%: a fast skip pushes S_t away (the queue is not touched).
     * [shufflePick] says whether Smart Shuffle had queued it.
     */
    suspend fun onPlaybackOutcome(songId: Long, fraction: Float, atMillis: Long, shufflePick: Boolean): SessionEvent

    /** The listener chose this song by hand: S_t = normalize(0.85 v + 0.15 S_t-1). */
    suspend fun onManualSelect(songId: Long): SessionEvent

    /** A new session: S_t forgotten (habits kept). */
    suspend fun resetSession()

    /** Builds the next songs; see [QueueRequest]. */
    suspend fun generate(request: QueueRequest): QueuePlan

    /** One Sonic Adventure step from [fromId] toward [toId], at [t] (0..1) of the way. */
    suspend fun journeyStep(fromId: Long, toId: Long, t: Float, previousId: Long, exclude: Set<Long>): QueuePlan

    /** S_t (24 values), or null before the first song. */
    suspend fun sessionVector(): FloatArray?

    suspend fun librarySize(): Int

    /** The built-in synthetic self-test; the report ends with "RESULT: PASS" or "RESULT: FAIL". */
    suspend fun runSelfTest(): String
}

data class QueueRequest(
    val currentSongId: Long,
    /** Upcoming songs already in the queue, in order: the new ones follow them. */
    val prefixIds: List<Long>,
    /** Songs that must not be picked (recently played, already queued). */
    val excludeIds: Set<Long>,
    /** After a fast skip: what was queued before the re-rank (Check 2 compares against it). */
    val replacedTail: List<Long> = emptyList(),
    val count: Int,
    val seed: Long,
)

data class QueuePlan(
    val songIds: List<Long>,
    val traces: List<TrackDecisionTrace>,
    val audit: BrainAuditReport?,
) {
    companion object {
        val EMPTY = QueuePlan(emptyList(), emptyList(), null)
    }
}

/** Where the brain's Logcat table goes (tag SHUFFLE_BRAIN on Android). */
fun interface ShuffleBrainLog {
    fun write(lines: List<String>)

    companion object {
        val NONE = ShuffleBrainLog { }
    }
}
