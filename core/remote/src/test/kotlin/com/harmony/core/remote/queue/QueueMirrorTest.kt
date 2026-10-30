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
}
