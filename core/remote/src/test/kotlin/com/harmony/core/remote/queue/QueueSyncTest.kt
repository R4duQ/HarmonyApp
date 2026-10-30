package com.harmony.core.remote.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSyncTest {
    private fun ids(prefix: String): () -> String {
        var n = 0
        return { "$prefix-${n++}" }
    }

    private fun connected(vararg devices: String): Pair<QueueCoordinator, List<QueueReplica>> {
        val coordinator = QueueCoordinator(album(), shuffler = { it.reversed() })
        val replicas = devices.map { QueueReplica(it, ids(it)).apply { onDelta(coordinator.since(-1)) } }
        return coordinator to replicas
    }

    /** Sends everything waiting, answers it, and brings the replica up to date. */
    private fun QueueReplica.sync(c: QueueCoordinator) {
        outbox.forEach { onOutcome(it.id, c.submit(it)) }
        check(onDelta(c.since(revision)))
    }

    @Test fun anEditShowsAtOnceAndIsConfirmedLater() {
        val (c, r) = connected("phone")
        val phone = r[0]
        phone.issue(QueueOp.PlayNext(listOf(entry("p1"))))
        assertEquals("p1", phone.view.upcomingIds().first())
        assertEquals("not confirmed yet", "l1", phone.confirmed.upcomingIds().first())
        phone.sync(c)
        assertEquals(c.state, phone.confirmed)
        assertTrue(phone.outbox.isEmpty())
    }

    @Test fun aResentCommandIsAppliedOnce() {
        val (c, r) = connected("phone")
        val cmd = r[0].issue(QueueOp.PlayNext(listOf(entry("p1"))))
        val first = c.submit(cmd)
        // The answer was lost; the phone sends it again, and again.
        val again = c.submit(cmd)
        c.submit(cmd)
        assertEquals(first, again)
        assertEquals(1, c.state.entries.count { it.id == "p1" })
        assertEquals(1L, c.revision)
    }

    @Test fun aLostAnswerIsSettledByTheLog() {
        val (c, r) = connected("phone")
        val phone = r[0]
        val cmd = phone.issue(QueueOp.Add(listOf(entry("m1"))))
        c.submit(cmd) // answer lost
        assertTrue(phone.onDelta(c.since(phone.revision)))
        assertTrue("seen applied in the log: nothing left to send", phone.outbox.isEmpty())
        assertEquals(c.state, phone.view)
    }

    @Test fun editsMadeOfflineKeepTheirMeaningAfterTheOtherDeviceChangedTheQueue() {
        val (c, r) = connected("phone", "pc")
        val (phone, pc) = r
        // The phone is offline: it queues a song after l3 and removes l1.
        phone.issue(QueueOp.Move("l4", afterId = "l2"))
        phone.issue(QueueOp.Remove(listOf("l1")))
        // Meanwhile on the computer: two songs played next, l2 moved to the end.
        pc.issue(QueueOp.PlayNext(listOf(entry("p1"), entry("p2"))))
        pc.issue(QueueOp.Move("l2", afterId = "l4"))
        pc.sync(c)
        // The phone is back.
        phone.sync(c)
        pc.sync(c)
        assertEquals(listOf("p1", "p2", "l3", "l2", "l4"), c.state.upcomingIds())
        assertEquals(c.state, phone.view)
        assertEquals(c.state, pc.view)
    }

    @Test fun twoDevicesMovingTheSameEntryTheLastToArriveWins() {
        val (c, r) = connected("phone", "pc")
        val (phone, pc) = r
        phone.issue(QueueOp.Move("l1", afterId = "l4"))
        pc.issue(QueueOp.Move("l1", afterId = "l2"))
        phone.sync(c)
        pc.sync(c)
        phone.sync(c)
        assertEquals(listOf("l2", "l1", "l3", "l4"), c.state.upcomingIds())
        assertEquals(c.state, phone.view)
    }

    @Test fun aMoveOfAnEntryRemovedElsewhereIsRefusedAndTheViewHeals() {
        val (c, r) = connected("phone", "pc")
        val (phone, pc) = r
        pc.issue(QueueOp.Remove(listOf("l3")))
        pc.sync(c)
        val move = phone.issue(QueueOp.Move("l3", afterId = null))
        assertEquals("l3", phone.view.upcomingIds().first())
        val outcome = c.submit(move)
        assertEquals(Refusal.UNKNOWN_ENTRY, (outcome as CommandOutcome.Refused).why)
        phone.onOutcome(move.id, outcome)
        phone.onDelta(c.since(phone.revision))
        assertEquals(c.state, phone.view)
        assertFalse("l3" in phone.view.ids())
    }

    @Test fun aCommandFromBeforeAHandoffIsRefused() {
        val (c, r) = connected("phone", "pc")
        val (phone, pc) = r
        // The phone plays; then playback moves to the computer.
        phone.issue(QueueOp.SetOwner("phone")); phone.sync(c)
        pc.sync(c)
        pc.issue(QueueOp.SetOwner("pc")); pc.sync(c)
        // A late "next" from the phone, made before it heard of the handoff.
        val late = QueueCommand("late", "phone", QueueOp.SetCurrent("l1"), epoch = 1)
        assertEquals(Refusal.STALE_EPOCH, (c.submit(late) as CommandOutcome.Refused).why)
        assertEquals("c", c.state.currentId)
        // Once up to date, the phone's "next" counts again.
        phone.sync(c)
        phone.issue(QueueOp.SetCurrent("l1")); phone.sync(c)
        assertEquals("l1", c.state.currentId)
    }

    @Test fun twoHandoffsAtOnceOnlyOneWins() {
        val (c, r) = connected("phone", "pc")
        val (phone, pc) = r
        phone.issue(QueueOp.SetOwner("phone"))
        pc.issue(QueueOp.SetOwner("pc"))
        phone.sync(c)
        pc.sync(c)
        phone.sync(c)
        assertEquals("phone", c.state.owner)
        assertEquals(1L, c.state.epoch)
        assertEquals(c.state, pc.view)
    }

    @Test fun shuffleIsDecidedOnceAndEveryDeviceGetsTheSameOrder() {
        val (c, r) = connected("phone", "pc")
        val (phone, pc) = r
        phone.issue(QueueOp.SetShuffle(true))
        assertEquals("no guessing before the coordinator decides", c.state.upcomingIds(), phone.view.upcomingIds())
        phone.sync(c)
        pc.sync(c)
        assertEquals(listOf("l4", "l3", "l2", "l1"), c.state.upcomingIds())
        assertEquals(c.state, phone.view)
        assertEquals(c.state, pc.view)
    }

    @Test fun farBehindGetsTheWholeQueue() {
        val c = QueueCoordinator(album(), logLimit = 4)
        val phone = QueueReplica("phone", ids("phone"))
        phone.onDelta(c.since(-1))
        val pc = QueueReplica("pc", ids("pc"))
        pc.onDelta(c.since(-1))
        repeat(10) { pc.issue(QueueOp.AppendAuto(listOf(entry("a$it")))); pc.sync(c) }
        val delta = c.since(phone.revision)
        assertTrue(delta is QueueDelta.Snapshot)
        phone.onDelta(delta)
        assertEquals(c.state, phone.view)
        assertEquals(c.revision, phone.revision)
    }

    @Test fun aGapInTheOpsIsNoticed() {
        val (c, r) = connected("phone")
        val phone = r[0]
        c.submit(QueueCommand("x", "pc", QueueOp.AppendAuto(listOf(entry("a1"))), 0))
        c.submit(QueueCommand("y", "pc", QueueOp.AppendAuto(listOf(entry("a2"))), 0))
        val onlyTheSecond = QueueDelta.Ops(1, (c.since(0) as QueueDelta.Ops).ops.drop(1))
        assertFalse(phone.onDelta(onlyTheSecond))
        assertEquals("nothing applied out of order", 0L, phone.revision)
        assertTrue(phone.onDelta(c.since(phone.revision)))
        assertEquals(c.state, phone.view)
    }
}
