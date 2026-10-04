package com.harmony.playback.service.player

import kotlin.math.abs

/**
 * The arithmetic of a crossfade handoff, kept free of ExoPlayer so it can be
 * tested on its own.
 *
 * At the end of a crossfade two players play the same song: the second
 * player, audible, and the session player, muted, which has just jumped into
 * that song. They only sound like one song when their positions match. A
 * swap between them while they are 100 ms apart is heard as the music
 * hitching back or skipping forward, so the session player is first brought
 * into step by playing it a little faster or slower while it is still muted.
 */
internal object HandoffAlignment {
    /** Close enough to swap: below this, the two copies blur into one. */
    const val ALIGNED_MS = 12L

    /** The fastest the muted player is pushed or held back, as a fraction of normal speed. */
    const val MAX_NUDGE = 0.25f

    /** Proportional gain: a 100 ms gap is closed at 20 % faster or slower speed. */
    private const val GAIN_MS = 500f

    /** Past this the gap is closed by seeking again rather than by speed. */
    const val RESEEK_OVER_MS = 700L

    const val MIN_LEAD_MS = 40L
    const val MAX_LEAD_MS = 1_500L

    /**
     * Speed factor for the session player, given how far it is ahead of the
     * second player ([offsetMs] > 0) or behind it (< 0); null once aligned.
     * A player that is behind runs faster, one that is ahead runs slower, and
     * the push shrinks as the gap closes so it settles instead of overshooting.
     */
    fun catchUpFactor(offsetMs: Long): Float? {
        if (abs(offsetMs) <= ALIGNED_MS) return null
        return 1f + (-offsetMs / GAIN_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE)
    }

    /**
     * The lead to aim with at the next handoff. The session player was aimed
     * [leadMs] ahead of the second player and landed [offsetMs] away from it;
     * half of the error is taken on board each time, so one odd handoff
     * doesn't throw the next one off.
     */
    fun nextLead(leadMs: Long, offsetMs: Long): Long =
        (leadMs - offsetMs / 2).coerceIn(MIN_LEAD_MS, MAX_LEAD_MS)

    /** The middle of three readings, so one late position update doesn't count. */
    fun median(a: Long, b: Long, c: Long): Long = maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    /**
     * Gains at [fraction] of the swap: (incoming, outgoing), always adding up
     * to one.
     *
     * The two players carry the same song, a few milliseconds apart, so their
     * signals add like one signal, not like two unrelated ones. Equal-power
     * gains (sine and cosine) are right for unrelated songs, but on two copies
     * of one they sum to 1.41 times the level halfway through: 3 dB louder,
     * and over full scale on a loud master, which is heard as distortion at
     * the end of every crossfade. Equal gains never exceed the song itself.
     */
    fun swapGains(fraction: Float): Pair<Float, Float> {
        val f = fraction.coerceIn(0f, 1f)
        return f to 1f - f
    }
}
