package com.harmony.domain.playback

import com.harmony.core.model.QueueOrigin
import com.harmony.core.model.QueueOrigin.AUTO
import com.harmony.core.model.QueueOrigin.LIST
import com.harmony.core.model.QueueOrigin.MANUAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueShadowTest {
    private fun shadow(vararg ids: String) = QueueShadow().apply { setAll(ids.map { it to null }) }
    private fun QueueShadow.origins() = entries.map { it.origin }
    private fun QueueShadow.idOf(i: Int) = entries[i].entryId

    @Test fun everyEntryHasItsOwnIdTheSameSongTwiceIncluded() {
        val s = shadow("a", "x", "x")
        s.insert(3, listOf("x"), MANUAL)
        assertEquals(4, s.entries.map { it.entryId }.toSet().size)
    }

    @Test fun insertsCarryTheirOrigin() {
        val s = shadow("a", "b")
        s.insert(1, listOf("n"), MANUAL)
        s.insert(3, listOf("p", "q"), AUTO)
        assertEquals(listOf(LIST, MANUAL, LIST, AUTO, AUTO), s.origins())
    }

    @Test fun aMovedEntryIsPinnedAndKeepsItsIdentity() {
        val s = shadow("a", "b", "c")
        s.insert(3, listOf("p"), AUTO)
        val id = s.idOf(3)
        s.move(3, 1)
        assertEquals(id, s.idOf(1))
        assertEquals(AUTO, s.entries[1].origin)
        assertTrue("a pick the user moved is protected", s.entries[1].pinned && s.entries[1].isProtected)
        assertFalse(s.entries[2].pinned)
    }

    @Test fun aReplacedRangeKeepsTheUsersEntriesAndTheirIds() {
        val s = shadow("now", "a", "b")
        s.insert(3, listOf("m"), MANUAL)
        val m = s.idOf(3)
        val a = s.idOf(1)
        // A shuffle: the same songs, another order.
        s.replace(1, 4, listOf("m", "b", "a"), LIST)
        assertEquals(listOf(LIST, MANUAL, LIST, LIST), s.origins())
        assertEquals(m, s.idOf(1))
        assertEquals("a list song shuffled is the same entry", a, s.idOf(3))
    }

    @Test fun aListSongComingBackWhereAPickOfTheSameSongWasIsAListSongAgain() {
        val s = shadow("now")
        s.insert(1, listOf("x", "y"), AUTO)
        s.replace(1, 3, listOf("x", "z"), LIST)
        assertEquals(listOf(LIST, LIST, LIST), s.origins())
        // While a protected pick carries over whatever the replacement is.
        s.insert(3, listOf("p"), AUTO)
        s.move(3, 1)
        s.replace(1, 4, listOf("x", "p", "z"), LIST)
        assertEquals(listOf(LIST, LIST, AUTO, LIST), s.origins())
        assertTrue(s.entries[2].pinned)
    }

    @Test fun aNewListKeepsTheUsersSongsStillToComeRightAfterTheSongStarted() {
        val s = shadow("h", "now", "a")
        s.insert(2, listOf("m1"), MANUAL)
        s.insert(4, listOf("m2"), MANUAL)
        s.insert(5, listOf("p"), AUTO)
        s.insert(0, listOf("old"), MANUAL) // already played: history, not kept
        // The queue: old h now m1 a m2 p, "now" playing (at 2).
        val m1 = s.idOf(3)
        val kept = s.startList(listOf("x", "y", "z"), 1, currentIndex = 2)
        assertEquals(listOf(3, 5), kept)
        assertEquals(listOf(LIST, LIST, MANUAL, MANUAL, LIST), s.origins())
        assertEquals("the same entry, not a copy", m1, s.idOf(2))
        assertEquals(s.entries, s.align(listOf("x", "y", "m1", "m2", "z")))
    }

    @Test fun clearingKeepsNothing() {
        val s = shadow("now")
        s.insert(1, listOf("m"), MANUAL)
        assertEquals(emptyList<Int>(), s.startList(emptyList(), 0, 0))
        assertTrue(s.entries.isEmpty())
    }

    @Test fun changesMadeElsewhereAreMatchedUp() {
        val s = shadow("a", "b")
        s.insert(2, listOf("m"), MANUAL)
        val m = s.idOf(2)
        // Android Auto played on: the first song dropped, two added at the end.
        val after = s.align(listOf("b", "m", "c", "d"))
        assertEquals(listOf(LIST, MANUAL, LIST, LIST), after.map { it.origin })
        assertEquals(m, after[1].entryId)
        // Reordered elsewhere: matched song by song.
        val reordered = s.align(listOf("m", "d", "b", "c"))
        assertEquals(m, reordered[0].entryId)
        assertEquals(MANUAL, reordered[0].origin)
        assertEquals(LIST, reordered[1].origin)
    }

    @Test fun aSongDroppedAtTheFrontAndAddedAtTheEndIsANewEntry() {
        val s = shadow("a", "b")
        s.insert(2, listOf("m"), MANUAL)
        val a = s.idOf(0)
        val after = s.align(listOf("b", "m", "a"))
        // Matched song by song (a reorder), not as "a" dropped and a new "a".
        assertEquals(a, after[2].entryId)
        assertNotEquals(QueueOrigin.MANUAL, after[2].origin)
    }

    @Test fun removalsTakeTheirEntries() {
        val s = shadow("a", "b", "c", "d")
        val d = s.idOf(3)
        s.remove(1, 3)
        assertEquals(2, s.entries.size)
        assertEquals(d, s.idOf(1))
    }
}
