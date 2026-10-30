package com.harmony.core.remote.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueMirrorTest {
    private fun item(song: String, origin: Origin = Origin.LIST, priority: Boolean = false) =
        MirrorItem(media(song), origin, priority)

    private fun QueueState.applyAll(ops: List<QueueOp>) = ops.fold(this) { s, op -> s.must(op) }

    private fun QueueState.songs() = entries.map { it.media.localId }

    @Test fun mirrorOpsReachAnyTargetExactly() {
        val rnd = Random(7)
        repeat(2_000) { round ->
            var n = 0
            fun e(origin: Origin = Origin.entries.random(rnd)) =
                QueueEntry("e${round}-${n++}", media("s${rnd.nextInt(8)}"), origin, rnd.nextBoolean(), rnd.nextBoolean())
            val start = List(rnd.nextInt(12)) { e() }
            val shared = QueueState(start, start.getOrNull(rnd.nextInt(start.size + 1))?.id, owner = if (rnd.nextBoolean()) "phone" else "pc")
            // A target: some kept (shuffled, flags changed), some new.
            val kept = start.shuffled(rnd).take(rnd.nextInt(start.size + 1))
                .map { if (rnd.nextInt(4) == 0) it.copy(origin = Origin.entries.random(rnd), priority = !it.priority) else it }
            val target = (kept + List(rnd.nextInt(5)) { e() }).shuffled(rnd)
            if (target.isEmpty()) return@repeat
            // A player with songs always has one playing.
            val current = target.random(rnd).id
            val ops = shared.mirrorOps(target, current, "phone")
            val after = shared.applyAll(ops)
            assertEquals("round $round", target, after.entries)
            assertEquals(current, after.currentId)
            assertEquals("phone", after.owner)
            assertTrue("nothing more to do: round $round", after.mirrorOps(target, current, "phone").isEmpty())
        }
    }

    @Test fun oneSongMovedIsOneOp() {
        val mirror = QueueMirror("phone")
        val songs = listOf("a", "b", "c", "d", "e").map { item(it) }
        val shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 0))
        assertEquals(listOf("a", "b", "c", "d", "e"), shared.songs())
        // "e" dragged up to play next.
        val moved = listOf("a", "e", "b", "c", "d").map { item(it) }
        val ops = mirror.ops(shared, moved, 0)
        assertEquals(1, ops.size)
        assertTrue(ops[0] is QueueOp.Reposition)
        assertEquals(listOf("a", "e", "b", "c", "d"), shared.applyAll(ops).songs())
    }

    @Test fun theSongMovingOnIsOneOp() {
        val mirror = QueueMirror("phone")
        val songs = listOf("a", "b", "c").map { item(it) }
        val shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 0))
        val ops = mirror.ops(shared, songs, 1)
        assertEquals(listOf<QueueOp>(QueueOp.SetCurrent(shared.entries[1].id)), ops)
    }

    @Test fun twoCopiesOfASongStayTwoEntries() {
        val mirror = QueueMirror("phone")
        val songs = listOf("a", "x", "b", "x").map { item(it) }
        val shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 0))
        assertEquals(4, shared.entries.map { it.id }.toSet().size)
        // One x removed on the phone: one Remove, the other x stays.
        val ops = mirror.ops(shared, listOf("a", "x", "b").map { item(it) }, 0)
        assertEquals(1, ops.size)
        assertEquals(listOf("a", "x", "b"), shared.applyAll(ops).songs())
    }

    @Test fun originsAndThePlayNextRunAreMirrored() {
        val mirror = QueueMirror("phone")
        val songs = listOf(item("a"), item("p", Origin.MANUAL, priority = true), item("b"), item("r", Origin.AUTO))
        val shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 0))
        assertEquals(listOf(Origin.LIST, Origin.MANUAL, Origin.LIST, Origin.AUTO), shared.entries.map { it.origin })
        assertTrue(shared.entries[1].priority)
        assertTrue(shared.entries[1].isProtected)
    }

    @Test fun onlyAWindowIsMirrored() {
        val mirror = QueueMirror("phone", historyLimit = 2, upcomingLimit = 3)
        val songs = (1..20).map { item("s$it") }
        val shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 10))
        assertEquals(listOf("s9", "s10", "s11", "s12", "s13", "s14"), shared.songs())
        assertEquals("s11", shared.current!!.media.localId)
        // Playing on: the window slides, the oldest go.
        val next = shared.applyAll(mirror.ops(shared, songs, 11))
        assertEquals(listOf("s10", "s11", "s12", "s13", "s14", "s15"), next.songs())
    }

    @Test fun onlyTheDevicePlayingTheQueueMayMirrorIt() {
        val c = QueueCoordinator(QueueState())
        val mirror = QueueMirror("phone")
        val ops = mirror.ops(c.state, listOf(item("a")), 0)
        ops.forEachIndexed { i, op -> c.submit(QueueCommand("m$i", "phone", op, c.state.epoch)) }
        assertEquals(listOf("a"), c.state.songs())
        val stray = c.submit(QueueCommand("x", "pc", QueueOp.Place(listOf(entry("z")), null), c.state.epoch))
        assertEquals(Refusal.STALE_EPOCH, (stray as CommandOutcome.Refused).why)
    }

    @Test fun editsFromTheComputerBecomePlayerEdits() {
        val mirror = QueueMirror("phone")
        val songs = listOf("a", "b", "c", "d", "e").map { item(it) }
        val shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 1)) // b plays
        val id = shared.entries.associate { it.media.localId to it.id }
        // "e" to play next: after b.
        assertEquals(listOf(PlayerEdit.Move(4, 2, "dev:phone:e")), mirror.edits(QueueOp.Move(id.getValue("e"), null), songs, 1))
        // "c" after "d".
        assertEquals(listOf(PlayerEdit.Move(2, 3, "dev:phone:c")), mirror.edits(QueueOp.Move(id.getValue("c"), id.getValue("d")), songs, 1))
        // Never the song playing, nor into the history.
        assertEquals(emptyList<PlayerEdit>(), mirror.edits(QueueOp.Move(id.getValue("b"), id.getValue("d")), songs, 1))
        assertEquals(emptyList<PlayerEdit>(), mirror.edits(QueueOp.Move(id.getValue("d"), id.getValue("a")), songs, 1))
        // Removals from the end first; the song playing stays.
        assertEquals(
            listOf(PlayerEdit.Remove(4, "dev:phone:e"), PlayerEdit.Remove(2, "dev:phone:c")),
            mirror.edits(QueueOp.Remove(listOf(id.getValue("c"), id.getValue("b"), id.getValue("e"))), songs, 1),
        )
        assertEquals(listOf(PlayerEdit.Play(3, "dev:phone:d")), mirror.edits(QueueOp.SetCurrent(id.getValue("d")), songs, 1))
        assertEquals("unknown entries ask nothing", emptyList<PlayerEdit>(), mirror.edits(QueueOp.Move("nope", null), songs, 1))
    }

    @Test fun aMoveMadeOnTheComputerStaysProtected() {
        val mirror = QueueMirror("phone")
        val songs = listOf("a", "b", "c", "r").map { item(it) } + item("x", Origin.AUTO)
        var shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 0))
        val x = shared.entries.last().id
        shared = shared.must(QueueOp.Move(x, null)) // on the computer: pinned
        // The phone applies it; its own list knows nothing of pins.
        val moved = listOf(songs[0], songs[4], songs[1], songs[2], songs[3])
        val ops = mirror.ops(shared, moved, 0)
        assertEquals(emptyList<QueueOp>(), ops)
        assertTrue(shared[x]!!.pinned)
    }

    @Test fun thePlayersOwnEntryIdsTellTheSameSongTwiceApart() {
        val mirror = QueueMirror("phone")
        fun at(song: String, entry: Long, origin: Origin = Origin.LIST, pinned: Boolean = false) =
            MirrorItem(media(song), origin, pinned = pinned, entry = entry)
        // "x" twice: the user's (entry 2) and the list's (entry 4).
        val songs = listOf(at("a", 1), at("x", 2, Origin.MANUAL), at("b", 3), at("x", 4))
        val shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 0))
        val users = shared.entries[1]
        assertEquals(Origin.MANUAL, users.origin)
        // The list's "x" dragged up above the user's: by song alone this reads as
        // the user's moving down; by entry it is the list's moving up, now pinned.
        val moved = listOf(at("a", 1), at("x", 4, pinned = true), at("x", 2, Origin.MANUAL), at("b", 3))
        val after = shared.applyAll(mirror.ops(shared, moved, 0))
        assertEquals(listOf("a", "x", "x", "b"), after.songs())
        assertEquals("the user's entry is the same shared entry", users.id, after.entries[2].id)
        assertEquals(Origin.MANUAL, after.entries[2].origin)
        assertTrue("the moved one is pinned", after.entries[1].pinned && after.entries[1].origin == Origin.LIST)
        assertTrue("in step: nothing more to send", mirror.ops(after, moved, 0).isEmpty())
    }

    @Test fun aSongAddedOnTheComputerIsInsertedAndKeepsItsSharedEntry() {
        val mirror = QueueMirror("phone")
        fun at(song: String, entry: Long, origin: Origin = Origin.LIST, priority: Boolean = false) =
            MirrorItem(media(song), origin, priority = priority, entry = entry)
        // b plays; m was queued by the user (Play next); x and y are Smart Shuffle's.
        val songs = listOf(at("a", 1), at("b", 2), at("m", 3, Origin.MANUAL, priority = true), at("x", 4, Origin.AUTO), at("y", 5, Origin.AUTO))
        var shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 1))
        val pcSong = MediaRef("pc:pc-1:t9", "pc-1", "t9", "From the PC", "Someone")
        val add = QueueOp.Add(listOf(QueueEntry("pc-entry", pcSong, Origin.MANUAL)))
        shared = shared.must(add)
        // Add: after the user's songs, before Smart Shuffle's.
        assertEquals(listOf(PlayerEdit.Insert(3, pcSong, next = false)), mirror.edits(add, songs, 1))
        // The phone's player puts it there as its own new entry (11).
        val after = songs.toMutableList().apply { add(3, MirrorItem(pcSong, Origin.MANUAL, entry = 11)) }
        assertEquals("the same entry as on the computer: nothing to send", emptyList<QueueOp>(), mirror.ops(shared, after, 1))
        // Play next from the computer: after the Play next run.
        val next = QueueOp.PlayNext(listOf(QueueEntry("pc-next", pcSong.copy(contentKey = "pc:pc-1:t8", localId = "t8"), Origin.MANUAL, priority = true)))
        val edits = mirror.edits(next, after, 1)
        assertEquals(1, edits.size)
        assertEquals(3, (edits[0] as PlayerEdit.Insert).at)
        assertTrue((edits[0] as PlayerEdit.Insert).next)
        // Already there (an echo): nothing.
        assertEquals(emptyList<PlayerEdit>(), mirror.edits(add, after, 1))
    }

    @Test fun anInsertThePlayerNeverShowsIsUndoneNotKeptForever() {
        val mirror = QueueMirror("phone")
        val songs = listOf(MirrorItem(media("a"), Origin.LIST, entry = 1), MirrorItem(media("b"), Origin.LIST, entry = 2))
        var shared = QueueState().applyAll(mirror.ops(QueueState(), songs, 0))
        val add = QueueOp.Add(listOf(QueueEntry("pc-entry", MediaRef("pc:pc-1:t9", "pc-1", "t9", "T", "A"), Origin.MANUAL)))
        shared = shared.must(add)
        mirror.edits(add, songs, 0)
        // The player refused it: the shared queue goes back to the player's.
        shared = shared.applyAll(mirror.ops(shared, songs, 0))
        assertEquals(listOf("a", "b"), shared.songs())
        // The same song added by the phone itself later is a new entry, not the old one.
        val later = songs + MirrorItem(MediaRef("pc:pc-1:t9", "pc-1", "t9", "T", "A"), Origin.MANUAL, entry = 7)
        shared = shared.applyAll(mirror.ops(shared, later, 0))
        assertTrue(shared.entries.none { it.id == "pc-entry" })
    }
}
