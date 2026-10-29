package com.harmony.feature.player

import com.harmony.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueueKeysTest {
    private fun song(id: Long) = Song(id, "file:///$id", "S$id", "Artist", "Album", 1, null, null, null, null, null, null,
        180_000, null, null, null, null, null, null, null, null)

    private val a = song(1)
    private val b = song(2)
    private val c = song(3)
    private val x = song(9)

    @Test fun keysAreUniqueEvenForTheSameSong() {
        val entries = QueueKeys().entriesFor(listOf(x, a, x, x))
        assertEquals(4, entries.map { it.key }.toSet().size)
    }

    @Test fun theSongMovingOnKeepsTheRestOfTheKeys() {
        val keys = QueueKeys()
        val before = keys.entriesFor(listOf(x, a, x, b))
        // The first x started playing; a song was appended.
        val after = keys.entriesFor(listOf(a, x, b, c))
        assertEquals(before.drop(1).map { it.key }, after.take(3).map { it.key })
        assertEquals(4, after.map { it.key }.toSet().size)
    }

    @Test fun aMoveMadeOnTheSheetKeepsEveryKey() {
        val keys = QueueKeys()
        val before = keys.entriesFor(listOf(x, a, x, b))
        // The second x to the top.
        val moved = before.moved(2, 0)
        keys.onMoved(moved)
        val answer = keys.entriesFor(listOf(x, x, a, b))
        assertEquals(moved.map { it.key }, answer.map { it.key })
        // The row that moved is the second x's, not the first's.
        assertEquals(before[2].key, answer[0].key)
        assertNotEquals(before[0].key, answer[0].key)
    }

    @Test fun otherChangesKeepKeysBySong() {
        val keys = QueueKeys()
        val before = keys.entriesFor(listOf(a, b, c))
        // b removed.
        val removed = keys.entriesFor(listOf(a, c))
        assertEquals(listOf(before[0].key, before[2].key), removed.map { it.key })
        // Moved from elsewhere (a car, the computer).
        val swapped = keys.entriesFor(listOf(c, b, a))
        assertEquals(listOf(before[2].key, before[0].key), listOf(swapped[0].key, swapped[2].key))
    }

    @Test fun movedKeepsTheOthersInOrder() {
        assertEquals(listOf(2, 3, 1, 4), listOf(1, 2, 3, 4).moved(0, 2))
        assertEquals(listOf(4, 1, 2, 3), listOf(1, 2, 3, 4).moved(3, 0))
        assertEquals(listOf(1, 2, 3, 4), listOf(1, 2, 3, 4).moved(1, 1))
        assertEquals(listOf(1, 2, 3, 4), listOf(1, 2, 3, 4).moved(1, 9))
    }
}
