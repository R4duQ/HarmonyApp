package com.harmony.core.remote.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The coordinator (the computer) and three devices editing the same queue at
 * once: the computer's own screen (no network in between) and two phones on a
 * bad Wi-Fi that loses, duplicates, delays and reorders messages, with
 * outages in the middle. Deterministic per seed.
 *
 * After the network heals, every device must show exactly the coordinator's
 * queue, every command must have been applied at most once, and no song the
 * user queued may be missing unless the user removed it.
 */
class QueueNetworkSimulationTest {
    private sealed interface Msg {
        val to: String
        data class Send(override val to: String, val from: String, val command: QueueCommand) : Msg
        data class Answer(override val to: String, val commandId: String, val outcome: CommandOutcome) : Msg
        data class Pull(override val to: String, val from: String, val revision: Long) : Msg
        data class Delta(override val to: String, val delta: QueueDelta) : Msg
    }

    private class Net(val rnd: Random, var loss: Double, val dup: Double, val maxDelay: Int) {
        val inFlight = mutableListOf<Pair<Int, Msg>>()
        var down = false
        var sent = 0

        fun send(now: Int, m: Msg, lossless: Boolean) {
            sent++
            if (!lossless && (down || rnd.nextDouble() < loss)) return
            val copies = if (!lossless && rnd.nextDouble() < dup) 2 else 1
            repeat(copies) { inFlight += (now + if (lossless) 0 else rnd.nextInt(maxDelay + 1)) to m }
        }

        fun due(now: Int): List<Msg> {
            val ready = inFlight.filter { it.first <= now }
            inFlight.removeAll(ready)
            // Same-tick arrivals in any order: the network reorders.
            return ready.map { it.second }.shuffled(rnd)
        }
    }

    private fun run(seed: Int, logLimit: Int) {
        val rnd = Random(seed)
        val coordinator = QueueCoordinator(album(), shuffler = { it.shuffled(rnd) }, logLimit = logLimit)
        val devices = listOf("pc", "phone", "tablet")
        val replicas = devices.associateWith { d -> var n = 0; QueueReplica(d) { "$d-${n++}" } }
        replicas.values.forEach { it.onDelta(coordinator.since(-1)) }
        val net = Net(rnd, loss = 0.25, dup = 0.15, maxDelay = 6)
        val issued = mutableListOf<QueueCommand>()

        fun lossless(d: String) = d == "pc"

        fun step(now: Int, edits: Boolean) {
            if (edits) for ((d, r) in replicas) {
                if (rnd.nextInt(3) != 0) continue
                val op = randomOp(r.view, rnd, d, now)
                issued += r.issue(op)
            }
            // Every few ticks each device resends what is unanswered and asks what it missed.
            for ((d, r) in replicas) {
                if (now % 3 != devices.indexOf(d) % 3) continue
                r.outbox.forEach { net.send(now, Msg.Send("coordinator", d, it), lossless(d)) }
                net.send(now, Msg.Pull("coordinator", d, r.revision), lossless(d))
            }
            for (m in net.due(now)) when (m) {
                is Msg.Send -> {
                    val outcome = coordinator.submit(m.command)
                    net.send(now, Msg.Answer(m.from, m.command.id, outcome), lossless(m.from))
                }
                is Msg.Pull -> net.send(now, Msg.Delta(m.from, coordinator.since(m.revision)), lossless(m.from))
                is Msg.Answer -> replicas.getValue(m.to).onOutcome(m.commandId, m.outcome)
                is Msg.Delta -> replicas.getValue(m.to).onDelta(m.delta) // a gap is simply asked again next pull
            }
        }

        var now = 0
        repeat(400) {
            // Two outages: everything on the Wi-Fi is lost for a while.
            net.down = now in 80..120 || now in 250..300
            step(now++, edits = true)
        }
        // Healed (a slower, cleaner network): no more edits; the devices catch up on their own.
        net.down = false
        net.loss = 0.05
        var settled = false
        for (i in 0 until 300) {
            step(now++, edits = false)
            if (replicas.values.all { it.outbox.isEmpty() && it.revision == coordinator.revision }) {
                settled = true
                break
            }
        }
        // Late copies still on the way (old answers, old deltas) must change nothing.
        repeat(20) { step(now++, edits = false) }
        assertTrue("seed $seed: every device caught up after the outages", settled)

        val final = coordinator.state
        for ((d, r) in replicas) {
            assertEquals("seed $seed: $d shows the coordinator's queue", final, r.view)
            assertEquals("seed $seed: $d confirmed", final, r.confirmed)
        }
        assertEquals("ids unique", final.entries.size, final.ids().toSet().size)

        if (logLimit == Int.MAX_VALUE) {
            val log = (coordinator.since(0) as QueueDelta.Ops).ops
            assertEquals("seed $seed: no command applied twice", log.size, log.map { it.commandId }.toSet().size)
            val removed = log.flatMap { (it.op as? QueueOp.Remove)?.ids.orEmpty() }.toSet()
            val queued = log.flatMap { entryIds(it.op) }
            val missing = queued.filter { it !in final.ids() && it !in removed }
            assertEquals("seed $seed: what the user queued is only gone if removed", emptyList<String>(), missing)
            assertTrue("seed $seed: user edits went through", queued.size > 20)
        }
        assertTrue("the simulation did something", issued.size > 100 && coordinator.revision > 50)
    }

    private fun entryIds(op: QueueOp) = when (op) {
        is QueueOp.PlayNext -> op.entries.map { it.id }
        is QueueOp.Add -> op.entries.map { it.id }
        else -> emptyList()
    }

    private var serial = 0

    private fun randomOp(q: QueueState, rnd: Random, device: String, now: Int): QueueOp {
        val up = q.upcoming.map { it.id }
        fun fresh(n: Int) = List(n) { entry("$device-e${serial++}-$now", song = "s${rnd.nextInt(5)}") }
        return when (rnd.nextInt(12)) {
            0, 1 -> QueueOp.PlayNext(fresh(1))
            2, 3 -> QueueOp.Add(fresh(1 + rnd.nextInt(2)))
            4 -> QueueOp.AppendAuto(fresh(2))
            5, 6 -> if (up.isEmpty()) QueueOp.AppendAuto(fresh(1)) else QueueOp.Move(up.random(rnd), up.randomOrNull(rnd))
            7 -> QueueOp.Remove(up.shuffled(rnd).take(1))
            8 -> QueueOp.SetShuffle(!q.shuffle)
            9 -> q.nextId()?.let { QueueOp.SetCurrent(it) } ?: QueueOp.SetRepeat(QueueRepeat.ALL)
            10 -> QueueOp.SetOwner(device)
            else -> QueueOp.SetRepeat(QueueRepeat.entries.random(rnd))
        }
    }

    @Test fun convergesOnABadNetworkWithAFullLog() {
        for (seed in 1..25) run(seed, logLimit = Int.MAX_VALUE)
    }

    @Test fun convergesWhenDevicesFallBehindTheLog() {
        for (seed in 100..124) run(seed, logLimit = 8)
    }
}
