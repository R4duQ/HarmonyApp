package com.harmony.core.remote.handoff

/** How a player's song stands, as far as a handoff can tell. */
enum class Readiness {
    /** Asked, not there yet. */
    NOT_YET,
    /** Loaded, paused where it was asked, the first sound ready. */
    READY,
    PLAYING,
    /** It said no (the song won't load, a player error). */
    FAILED,
    /** It doesn't answer: nothing is known. */
    UNREACHABLE,
}

/**
 * One side of a handoff: the phone's player or the computer's, seen from the
 * device moving the music. Commands are asked and return at once; what came
 * of them is read back ([readiness], [stopped]) on later ticks.
 */
interface HandoffPlayer {
    /** The song it has (to tell when the song changed under the handoff). */
    fun songId(): String?
    /** Where the song is now, ms (estimated between reports for a remote player). */
    fun positionMs(): Long
    fun isPlaying(): Boolean
    /** A crossfade is under way or about to start: a handoff waits for it to be over. */
    fun busy(): Boolean

    /** (Target) load the song paused at [positionMs], the first sound made ready. */
    fun prepare(positionMs: Long)
    /** (Target) how the prepared, then started, song stands. */
    fun readiness(): Readiness
    /** Play from where it is. */
    fun start()
    /** Stop sounding (a pause where it is); for the target, also letting go of a prepared song. */
    fun stop()
    /** (Source) whether the stop took: true, false (not yet), null (it doesn't answer). */
    fun stopped(): Boolean?
}

/** How a handoff ended. */
sealed interface HandoffResult {
    /** The target plays now ([playing] false: the music was paused and moved paused). */
    data class Moved(val atMs: Long, val playing: Boolean) : HandoffResult
    /** Nothing changed: the source plays (or stays paused) as before. */
    data class Stayed(val why: Why) : HandoffResult
    /**
     * The source was stopped and the target was told to play, then stopped
     * answering: whether it plays is unknown. The source stays silent (never
     * two devices playing at once); the person decides.
     */
    data class Unsure(val atMs: Long) : HandoffResult

    enum class Why { BUSY, NOT_READY, TOO_LATE, SONG_CHANGED, PAUSED_MEANWHILE, SOURCE_UNREACHABLE, TARGET_FAILED }
}

/**
 * Moves the music from [source] to [target] without a gap from loading and
 * without the two ever playing at once (Universal Queue, stage 7; design in
 * docs/UNIVERSAL-QUEUE.md §3.4):
 *
 *  1. wait for any crossfade to finish ([HandoffPlayer.busy]);
 *  2. PREPARE: the target loads the song paused [leadMs] ahead of the source;
 *  3. READY: the target has the first sound ready (else nothing changes);
 *  4. COMMIT, when the source reaches that point: the source stops (and must
 *     say so), then the target starts;
 *  5. STARTED: the target plays. If it says it can't, the source plays on
 *     from the same place; if it stops answering, nobody plays ([HandoffResult.Unsure]).
 *
 * The only silence is the time from the source's stop to the target's first
 * sound (a request on the home network). A paused source moves paused.
 *
 * Pure and driven by [tick] (every 20–50 ms) with the time in ms; never blocks.
 */
class Handoff(
    private val source: HandoffPlayer,
    private val target: HandoffPlayer,
    startedAt: Long,
    private val leadMs: Long = 1_500,
    private val maxBusyMs: Long = 15_000,
    private val readyTimeoutMs: Long = 8_000,
    private val stopTimeoutMs: Long = 2_000,
    private val startTimeoutMs: Long = 5_000,
    private val maxPrepares: Int = 3,
) {
    enum class Phase { WAITING, PREPARING, AT_POINT, STOPPING, STARTING, DONE }

    var phase = Phase.WAITING
        private set
    var result: HandoffResult? = null
        private set

    private val song = source.songId()
    private val wasPlaying = source.isPlaying()
    private var phaseSince = startedAt
    private val began = startedAt
    private var point = 0L
    private var prepares = 0

    /** Moves things along; returns the result once there is one. */
    fun tick(now: Long): HandoffResult? {
        if (result != null) return result
        when (phase) {
            Phase.WAITING -> {
                if (source.songId() != song) return stay(Why.SONG_CHANGED)
                if (wasPlaying && source.busy()) {
                    if (now - began > maxBusyMs) return stay(Why.BUSY)
                    return null
                }
                prepare(now)
            }
            Phase.PREPARING -> {
                if (source.songId() != song) return giveUp(Why.SONG_CHANGED)
                if (wasPlaying && !source.isPlaying()) return giveUp(Why.PAUSED_MEANWHILE)
                when (target.readiness()) {
                    Readiness.READY -> {
                        if (!wasPlaying) return finish(HandoffResult.Moved(point, playing = false))
                        // Ready after the source went past the point: a new point further on.
                        if (source.positionMs() > point + LATE_TOLERANCE_MS) {
                            if (prepares >= maxPrepares) return giveUp(Why.TOO_LATE)
                            prepare(now)
                            return null
                        }
                        enter(Phase.AT_POINT, now)
                        return tick(now)
                    }
                    Readiness.FAILED, Readiness.UNREACHABLE, Readiness.PLAYING -> return giveUp(Why.NOT_READY)
                    Readiness.NOT_YET -> if (now - phaseSince > readyTimeoutMs) return giveUp(Why.NOT_READY)
                }
            }
            Phase.AT_POINT -> {
                if (source.songId() != song) return giveUp(Why.SONG_CHANGED)
                if (!source.isPlaying()) return giveUp(Why.PAUSED_MEANWHILE)
                val at = source.positionMs()
                if (at > point + LATE_TOLERANCE_MS) {
                    // Missed (a stall, a slow tick): the target goes to a new point.
                    if (prepares >= maxPrepares) return giveUp(Why.TOO_LATE)
                    prepare(now)
                    return null
                }
                if (at >= point - EARLY_MS) {
                    source.stop()
                    enter(Phase.STOPPING, now)
                    return tick(now)
                }
            }
            Phase.STOPPING -> when (source.stopped()) {
                true -> {
                    target.start()
                    enter(Phase.STARTING, now)
                }
                null -> {
                    // The source may still be sounding: starting the target could make two.
                    target.stop()
                    return finish(HandoffResult.Stayed(Why.SOURCE_UNREACHABLE))
                }
                false -> if (now - phaseSince > stopTimeoutMs) {
                    target.stop()
                    return finish(HandoffResult.Stayed(Why.SOURCE_UNREACHABLE))
                }
            }
            Phase.STARTING -> when (target.readiness()) {
                Readiness.PLAYING -> return finish(HandoffResult.Moved(point, playing = true))
                Readiness.FAILED -> return rollBack()
                Readiness.UNREACHABLE -> if (now - phaseSince > startTimeoutMs) return finish(HandoffResult.Unsure(point))
                Readiness.NOT_YET, Readiness.READY -> if (now - phaseSince > startTimeoutMs) return rollBack()
            }
            Phase.DONE -> Unit
        }
        return result
    }

    private fun prepare(now: Long) {
        prepares++
        point = if (wasPlaying) source.positionMs() + leadMs * prepares else source.positionMs()
        target.prepare(point)
        enter(Phase.PREPARING, now)
    }

    /** The target answered but didn't play: it lets go, the source plays on from where it stopped. */
    private fun rollBack(): HandoffResult {
        target.stop()
        source.start()
        return finish(HandoffResult.Stayed(Why.TARGET_FAILED))
    }

    private fun giveUp(why: Why): HandoffResult {
        target.stop()
        return finish(HandoffResult.Stayed(why))
    }

    private fun stay(why: Why) = finish(HandoffResult.Stayed(why))

    private fun finish(r: HandoffResult): HandoffResult {
        result = r
        phase = Phase.DONE
        return r
    }

    private fun enter(p: Phase, now: Long) {
        phase = p
        phaseSince = now
    }

    private companion object {
        /** How far past the point the source may be and the handoff still go ahead (the sound repeats that much). */
        const val LATE_TOLERANCE_MS = 50L
        /** Commit this early: the stop and the start take a moment themselves. */
        const val EARLY_MS = 10L
    }
}

private typealias Why = HandoffResult.Why
