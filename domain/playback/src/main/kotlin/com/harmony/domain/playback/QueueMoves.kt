package com.harmony.domain.playback

/**
 * A move of one upcoming queue entry, checked against the player's queue as
 * it is when the move arrives, not as the screen last saw it.
 *
 * The queue screen works from a copy of the queue that can be a moment old:
 * the song may have changed under a drag, or Smart Shuffle may have appended
 * songs. Indices alone could then move the wrong entry, or put a song into
 * the history behind the one playing, where Next never reaches it.
 */
object QueueMoves {

    data class Move(val from: Int, val to: Int)

    /**
     * The move to make, or null when nothing should move.
     *
     * @param mediaIds the player's queue now, one id per entry (duplicates allowed).
     * @param current index of the entry playing, -1 when none.
     * @param expectedId the song the screen showed at [from]. Another song there
     *   means the queue changed under the gesture: the move is refused rather
     *   than moving an entry the user didn't touch.
     *
     * Only upcoming entries move, and only among the upcoming ones: the song
     * playing is never moved from here (it would restart nothing, but the
     * history and Previous would change), and a target at or before it lands
     * right after it instead.
     */
    fun resolve(mediaIds: List<String>, current: Int, from: Int, to: Int, expectedId: String?): Move? {
        if (from !in mediaIds.indices) return null
        if (expectedId != null && mediaIds[from] != expectedId) return null
        val firstUpcoming = if (current in mediaIds.indices) current + 1 else 0
        if (from < firstUpcoming) return null
        val target = to.coerceIn(firstUpcoming, mediaIds.lastIndex)
        return if (target == from) null else Move(from, target)
    }

    /**
     * The length of the Play next run (the entries right after [current] the
     * user queued with Play next) once [from] has moved to [to].
     *
     * The run is kept as a length, so without this a move across its edge
     * changed which songs it covered: dragging a list song above a queued one
     * pushed the queued one out of the run, and turning Smart Shuffle on then
     * removed it with the rest of the list. Membership follows the entry
     * instead: an entry dragged out of the run leaves it, one dragged into it
     * joins it, a move within it changes nothing.
     */
    fun runAfterMove(current: Int, runLength: Int, from: Int, to: Int): Int {
        if (runLength <= 0 || from == to) return runLength
        val runEnd = current + runLength
        val wasIn = from in (current + 1)..runEnd
        val isIn = to in (current + 1)..runEnd
        return runLength - (if (wasIn) 1 else 0) + (if (isIn) 1 else 0)
    }
}
