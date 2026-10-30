package com.harmony.core.remote.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun media(song: String) = MediaRef("dev:phone:$song", "phone", song, song, "Artist")

/** An entry [id] playing song [song] (the id's first letter by default). */
internal fun entry(id: String, origin: Origin = Origin.LIST, song: String = id.take(1)) = QueueEntry(id, media(song), origin)

internal fun QueueState.ids() = entries.map { it.id }
internal fun QueueState.upcomingIds() = upcoming.map { it.id }

internal fun QueueState.must(op: QueueOp): QueueState = when (val r = apply(op)) {
    is ApplyResult.Applied -> r.state
    is ApplyResult.Refused -> throw AssertionError("refused ${r.why}: $op")
}

internal fun QueueState.refusal(op: QueueOp): Refusal? = (apply(op) as? ApplyResult.Refused)?.why

/** h1 (history), c (playing), then l1..l4 of the album. */
internal fun album() = QueueState().must(
    QueueOp.StartList(listOf("h1", "c", "l1", "l2", "l3", "l4").map { entry(it) }, startId = "h1"),
).must(QueueOp.SetCurrent("c"))

class QueueRulesTest {
    @Test fun playNextStacksInTheOrderChosen() {
        val q = album().must(QueueOp.PlayNext(listOf(entry("p1")))).must(QueueOp.PlayNext(listOf(entry("p2"))))
        assertEquals(listOf("p1", "p2", "l1", "l2", "l3", "l4"), q.upcomingIds())
        assertTrue(q["p1"]!!.priority && q["p1"]!!.origin == Origin.MANUAL)
    }

    @Test fun addGoesAfterTheUsersChoicesAndBeforeRecommendations() {
        val q = album()
            .must(QueueOp.AppendAuto(listOf(entry("a1"), entry("a2"))))
            .must(QueueOp.Add(listOf(entry("m1"))))
            .must(QueueOp.PlayNext(listOf(entry("p1"))))
            .must(QueueOp.AppendAuto(listOf(entry("a3"))))
        assertEquals(listOf("p1", "l1", "l2", "l3", "l4", "m1", "a1", "a2", "a3"), q.upcomingIds())
    }

    @Test fun shuffleMovesOnlyFreeEntries() {
        val q = album()
            .must(QueueOp.PlayNext(listOf(entry("p1"))))
            .must(QueueOp.Add(listOf(entry("m1"))))
            .must(QueueOp.AppendAuto(listOf(entry("a1"), entry("a2"))))
        // Upcoming: p1 l1 l2 l3 l4 m1 a1 a2; free: l1 l2 l3 l4 a1 a2.
        val on = q.must(QueueOp.SetShuffle(true, listOf("a2", "l3", "a1", "l1", "l4", "l2")))
        assertEquals(listOf("p1", "a2", "l3", "a1", "l1", "m1", "l4", "l2"), on.upcomingIds())
        assertEquals("history and the song playing untouched", listOf("h1", "c"), on.ids().take(2))
        assertEquals("c", on.currentId)
        val off = on.must(QueueOp.SetShuffle(false))
        assertEquals(q.ids(), off.ids())
        assertNull(off.unshuffled)
    }

    @Test fun aRecommendationTheUserMovedIsProtected() {
        val q = album().must(QueueOp.AppendAuto(listOf(entry("a1"), entry("a2"))))
            .must(QueueOp.Move("a2", afterId = "l1"))
        assertTrue(q["a2"]!!.pinned && q["a2"]!!.isProtected)
        val on = q.must(QueueOp.SetShuffle(true, listOf("a1", "l4", "l3", "l2", "l1")))
        assertEquals("the moved one stays where the user put it", 1, on.upcomingIds().indexOf("a2"))
    }

    @Test fun shuffleOffPutsBackTheOrderAroundWhatChangedMeanwhile() {
        val on = album().must(QueueOp.SetShuffle(true, listOf("l4", "l3", "l2", "l1")))
            .must(QueueOp.AppendAuto(listOf(entry("a1"))))
            .must(QueueOp.Remove(listOf("l3")))
            .must(QueueOp.Add(listOf(entry("m1"))))
        val off = on.must(QueueOp.SetShuffle(false))
        assertEquals(listOf("l1", "l2", "l4", "m1", "a1"), off.upcomingIds())
    }

    @Test fun theSameSongTwiceIsTwoEntries() {
        val q = album().must(QueueOp.Add(listOf(entry("x1", song = "X"), entry("x2", song = "X"))))
        assertEquals(q["x1"]!!.media.contentKey, q["x2"]!!.media.contentKey)
        val one = q.must(QueueOp.Remove(listOf("x1")))
        assertEquals(listOf("x2"), one.entries.filter { it.media.localId == "X" }.map { it.id })
        val moved = q.must(QueueOp.Move("x2", afterId = null))
        assertEquals(listOf("x2", "l1", "l2", "l3", "l4", "x1"), moved.upcomingIds())
    }

    @Test fun anIdAlreadyQueuedIsRefused() {
        assertEquals(Refusal.DUPLICATE_ID, album().refusal(QueueOp.Add(listOf(entry("l1")))))
        assertEquals(Refusal.DUPLICATE_ID, album().refusal(QueueOp.Add(listOf(entry("n"), entry("n")))))
    }

    @Test fun moveRules() {
        val q = album()
        assertEquals(Refusal.CURRENT, q.refusal(QueueOp.Move("c", "l2")))
        assertEquals(Refusal.NOT_UPCOMING, q.refusal(QueueOp.Move("h1", "l2")))
        assertEquals(Refusal.NOT_UPCOMING, q.refusal(QueueOp.Move("l2", "h1")))
        assertEquals(Refusal.UNKNOWN_ENTRY, q.refusal(QueueOp.Move("gone", null)))
        assertEquals(Refusal.UNKNOWN_ENTRY, q.refusal(QueueOp.Move("l2", "gone")))
        assertEquals(listOf("l4", "l1", "l2", "l3"), q.must(QueueOp.Move("l4", null)).upcomingIds())
        assertEquals(listOf("l2", "l3", "l4", "l1"), q.must(QueueOp.Move("l1", "l4")).upcomingIds())
        assertEquals(listOf("l2", "l1", "l3", "l4"), q.must(QueueOp.Move("l1", "l2")).upcomingIds())
    }

    @Test fun thePlayNextRunFollowsItsEntries() {
        val q = album().must(QueueOp.PlayNext(listOf(entry("p1"), entry("p2"))))
        // A list song dragged to the top joins the run; a queued one dragged out leaves it.
        val joined = q.must(QueueOp.Move("l3", null))
        assertEquals(listOf(true, true, true, false), joined.upcoming.take(4).map { it.priority })
        val left = q.must(QueueOp.Move("p1", "l2"))
        assertEquals(listOf("p2", "l1", "l2", "p1"), left.upcomingIds().take(4))
        assertFalse(left["p1"]!!.priority)
        assertTrue("still the user's: protected", left["p1"]!!.isProtected)
        // The next Play next goes after what is left of the run.
        assertEquals(listOf("p2", "p3", "l1"), left.must(QueueOp.PlayNext(listOf(entry("p3")))).upcomingIds().take(3))
    }

    @Test fun removeAndClearAreExplicitAndNeverTakeTheSongPlaying() {
        val q = album().must(QueueOp.PlayNext(listOf(entry("p1"))))
        assertEquals(Refusal.CURRENT, q.refusal(QueueOp.Remove(listOf("c"))))
        val cleared = q.must(QueueOp.Remove(q.upcomingIds()))
        assertEquals(emptyList<String>(), cleared.upcomingIds())
        assertEquals("removing twice is fine", cleared, cleared.must(QueueOp.Remove(listOf("p1"))))
    }

    @Test fun startingAListKeepsWhatTheUserQueued() {
        val q = album().must(QueueOp.PlayNext(listOf(entry("p1")))).must(QueueOp.Add(listOf(entry("m1"))))
        val next = q.must(QueueOp.StartList(listOf("b1", "b2", "b3").map { entry(it) }, startId = "b2"))
        assertEquals(listOf("b1", "b2", "p1", "m1", "b3"), next.ids())
        assertEquals("b2", next.currentId)
    }

    @Test fun startingAListWhileShufflingShufflesItsRest() {
        val on = album().must(QueueOp.SetShuffle(true, listOf("l2", "l1", "l4", "l3")))
        val next = on.must(QueueOp.StartList(listOf("b1", "b2", "b3", "b4").map { entry(it) }, "b1", order = listOf("b4", "b2", "b3")))
        assertEquals(listOf("b1", "b4", "b2", "b3"), next.ids())
        assertTrue(next.shuffle)
        assertEquals(listOf("b2", "b3", "b4"), next.must(QueueOp.SetShuffle(false)).upcomingIds())
    }

    @Test fun setCurrentEndsAnEntrysPlayNextTurn() {
        val q = album().must(QueueOp.PlayNext(listOf(entry("p1"), entry("p2")))).must(QueueOp.SetCurrent("p1"))
        assertFalse(q["p1"]!!.priority)
        assertEquals(listOf("p2", "p3"), q.must(QueueOp.PlayNext(listOf(entry("p3")))).upcomingIds().take(2))
    }

    @Test fun whatPlaysNext() {
        val q = album()
        assertEquals("l1", q.nextId())
        assertEquals("c", q.must(QueueOp.SetRepeat(QueueRepeat.ONE)).nextId())
        val last = q.must(QueueOp.SetCurrent("l4"))
        assertNull(last.nextId())
        assertEquals("h1", last.must(QueueOp.SetRepeat(QueueRepeat.ALL)).nextId())
    }

    @Test fun ownerChangesRaiseTheEpoch() {
        val q = album().must(QueueOp.SetOwner("pc"))
        assertEquals("pc", q.owner)
        assertNotEquals(album().epoch, q.epoch)
    }
}
