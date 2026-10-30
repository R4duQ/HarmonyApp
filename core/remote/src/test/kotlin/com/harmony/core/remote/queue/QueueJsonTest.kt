package com.harmony.core.remote.queue

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueJsonTest {
    private val ops = listOf(
        QueueOp.PlayNext(listOf(entry("p1"))),
        QueueOp.Add(listOf(entry("m1"), entry("m2", song = "X"))),
        QueueOp.AppendAuto(listOf(entry("a1", Origin.AUTO))),
        QueueOp.Merge(listOf(entry("x1", Origin.MANUAL).copy(pinned = true), entry("x2", Origin.AUTO))),
        QueueOp.StartList(listOf(entry("b1"), entry("b2")), "b1", listOf("b2")),
        QueueOp.StartList(listOf(entry("b1")), "b1"),
        QueueOp.Move("l1", "l3"),
        QueueOp.Move("l1", null),
        QueueOp.Remove(listOf("l1", "l2")),
        QueueOp.SetCurrent("l2"),
        QueueOp.SetShuffle(true, listOf("l2", "l1")),
        QueueOp.SetShuffle(false),
        QueueOp.SetRepeat(QueueRepeat.ONE),
        QueueOp.SetOwner("pc"),
        QueueOp.Place(listOf(entry("n1", Origin.AUTO).copy(priority = true)), "l2"),
        QueueOp.Place(listOf(entry("n2")), null),
        QueueOp.Reposition("l1", null),
        QueueOp.Reposition("l1", "l4"),
        QueueOp.SetFlags("l1", Origin.MANUAL, pinned = true, priority = false),
    )

    /** Through text, as on the wire. */
    private fun wire(o: JSONObject) = JSONObject(o.toString())

    @Test fun everyOpRoundTrips() {
        for (op in ops) assertEquals(op, QueueJson.op(wire(QueueJson.op(op))))
    }

    @Test fun stateCommandsOutcomesAndDeltasRoundTrip() {
        val state = album().must(QueueOp.PlayNext(listOf(entry("p1"))))
            .must(QueueOp.Move("l3", "l1")).must(QueueOp.SetShuffle(true, listOf("l4", "l2", "l1")))
            .must(QueueOp.SetOwner("pc")).must(QueueOp.SetRepeat(QueueRepeat.ALL))
        assertEquals(state, QueueJson.state(wire(QueueJson.state(state))))
        assertEquals(QueueState(), QueueJson.state(wire(QueueJson.state(QueueState()))))

        val cmd = QueueCommand("id-1", "phone", ops[0], epoch = 3)
        assertEquals(cmd, QueueJson.command(wire(QueueJson.command(cmd))))

        for (o in listOf(CommandOutcome.Applied(7), CommandOutcome.Refused(Refusal.STALE_EPOCH, 9))) {
            assertEquals(o, QueueJson.outcome(wire(QueueJson.outcome(o))))
        }
        val missed = QueueDelta.Ops(4, listOf(LoggedOp(5, "c1", ops[5]), LoggedOp(6, "c2", ops[9])))
        assertEquals(missed, QueueJson.delta(wire(QueueJson.delta(missed))))
        val snap = QueueDelta.Snapshot(12, state)
        assertEquals(snap, QueueJson.delta(wire(QueueJson.delta(snap))))
    }

    @Test fun unknownOrBrokenInputIsRefusedNotGuessed() {
        assertNull(QueueJson.op(JSONObject().put("op", "teleport")))
        assertNull(QueueJson.op(JSONObject().put("op", "move")))
        assertNull(QueueJson.command(JSONObject().put("id", "x")))
        val future = JSONObject().put("from", 1).put("ops", org.json.JSONArray().put(
            JSONObject().put("revision", 2).put("command", "c").put("change", JSONObject().put("op", "teleport")),
        ))
        assertNull("an op this version can't read: ask for a snapshot instead", QueueJson.delta(future))
    }
}
