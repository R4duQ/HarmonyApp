package com.harmony.domain.playback

import com.harmony.domain.playback.QueueMoves.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueMovesTest {
    // Index 1 plays; 0 is history. "x" is queued twice.
    private val queue = listOf("h", "now", "a", "x", "b", "x", "c")
    private val current = 1

    /** The queue after Player.moveMediaItem(from, to). */
    private fun List<String>.after(move: Move) = toMutableList().apply { add(move.to, removeAt(move.from)) }

    @Test fun movesUpAndDown() {
        val down = QueueMoves.resolve(queue, current, 2, 4, "a")!!
        assertEquals(listOf("h", "now", "x", "b", "a", "x", "c"), queue.after(down))
        val up = QueueMoves.resolve(queue, current, 6, 2, "c")!!
        assertEquals(listOf("h", "now", "c", "a", "x", "b", "x"), queue.after(up))
    }

    @Test fun firstUpcomingToLastAndBack() {
        val toEnd = QueueMoves.resolve(queue, current, 2, 6, "a")!!
        val moved = queue.after(toEnd)
        assertEquals(listOf("h", "now", "x", "b", "x", "c", "a"), moved)
        val back = QueueMoves.resolve(moved, current, 6, 2, "a")!!
        assertEquals(queue, moved.after(back))
    }

    @Test fun adjacentSwap() {
        val move = QueueMoves.resolve(queue, current, 4, 5, "b")!!
        assertEquals(listOf("h", "now", "a", "x", "x", "b", "c"), queue.after(move))
    }

    @Test fun onlyTheChosenCopyOfADuplicateMoves() {
        // The second x (index 5) to the front: the first x stays where it is.
        val move = QueueMoves.resolve(queue, current, 5, 2, "x")!!
        assertEquals(listOf("h", "now", "x", "a", "x", "b", "c"), queue.after(move))
        assertEquals(queue.size, queue.after(move).size)
    }

    @Test fun refusedWhenTheSongIsNoLongerThere() {
        // The screen saw "a" at 2; the queue has changed since.
        assertNull(QueueMoves.resolve(queue, current, 3, 5, "a"))
        assertNull(QueueMoves.resolve(queue, current, 9, 2, "a"))
        assertNull(QueueMoves.resolve(queue, current, -1, 2, null))
    }

    @Test fun neverMovesTheSongPlayingOrTheHistory() {
        assertNull(QueueMoves.resolve(queue, current, 1, 4, "now"))
        assertNull(QueueMoves.resolve(queue, current, 0, 4, "h"))
    }

    @Test fun aTargetAtOrBeforeTheSongPlayingLandsRightAfterIt() {
        // The song moved on under a drag to the top: the target index is now
        // the song playing. The entry goes right after it, not behind it.
        val nowAt2 = 2
        val move = QueueMoves.resolve(queue, nowAt2, 6, 2, "c")!!
        assertEquals(Move(6, 3), move)
        val moved = queue.after(move)
        assertEquals("a", moved[nowAt2])
        assertEquals("c", moved[nowAt2 + 1])
        assertEquals(Move(6, 2), QueueMoves.resolve(queue, current, 6, 0, "c"))
    }

    @Test fun targetPastTheEndIsTheEnd() {
        assertEquals(Move(2, 6), QueueMoves.resolve(queue, current, 2, 40, "a"))
    }

    @Test fun nothingToMove() {
        assertNull(QueueMoves.resolve(queue, current, 4, 4, "b"))
        assertNull(QueueMoves.resolve(queue, 6, 6, 6, "c"))
    }

    @Test fun noSongPlaying() {
        assertEquals(Move(0, 3), QueueMoves.resolve(listOf("a", "b", "c", "d"), -1, 0, 3, "a"))
    }

    @Test fun playNextRunFollowsItsEntries() {
        // Playing at 1; the run is 2..3 (two songs queued with Play next).
        val c = 1
        // Within the run: unchanged.
        assertEquals(2, QueueMoves.runAfterMove(c, 2, 2, 3))
        assertEquals(2, QueueMoves.runAfterMove(c, 2, 3, 2))
        // A list song dragged into the run, or just above its last entry: it joins.
        assertEquals(3, QueueMoves.runAfterMove(c, 2, 6, 2))
        assertEquals(3, QueueMoves.runAfterMove(c, 2, 6, 3))
        // Dropped right after the run: still the list.
        assertEquals(2, QueueMoves.runAfterMove(c, 2, 6, 4))
        // A queued song dragged out: it leaves.
        assertEquals(1, QueueMoves.runAfterMove(c, 2, 2, 5))
        assertEquals(1, QueueMoves.runAfterMove(c, 2, 3, 4))
        // Moves after the run don't touch it.
        assertEquals(2, QueueMoves.runAfterMove(c, 2, 5, 6))
        // No run: none appears.
        assertEquals(0, QueueMoves.runAfterMove(c, 0, 6, 2))
    }
}
