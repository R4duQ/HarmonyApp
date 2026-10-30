package com.harmony.core.remote.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Thousands of random edits on random queues, checking after each one the
 * rules that must always hold, whatever the sequence.
 */
class QueuePropertyTest {
    private fun randomOp(q: QueueState, rnd: Random, next: () -> String): QueueOp {
        val up = q.upcoming.map { it.id }
        val all = q.entries.map { it.id }
        fun fresh(n: Int) = List(n) { val id = next(); entry(id, song = "s${rnd.nextInt(6)}") }
        return when (rnd.nextInt(10)) {
            0 -> QueueOp.PlayNext(fresh(1 + rnd.nextInt(2)))
            1 -> QueueOp.Add(fresh(1 + rnd.nextInt(2)))
            2 -> if (rnd.nextBoolean()) QueueOp.AppendAuto(fresh(1 + rnd.nextInt(3)))
            else QueueOp.Merge(fresh(1 + rnd.nextInt(3)).map { it.copy(origin = Origin.entries.random(rnd), pinned = rnd.nextBoolean()) })
            3, 4 -> if (up.isEmpty()) QueueOp.SetRepeat(QueueRepeat.OFF)
            else QueueOp.Move(up.random(rnd), if (rnd.nextInt(4) == 0) null else up.random(rnd))
            5 -> QueueOp.Remove(up.shuffled(rnd).take(rnd.nextInt(2)))
            6 -> QueueOp.SetShuffle(true, q.freeUpcoming.map { it.id }.shuffled(rnd))
            7 -> QueueOp.SetShuffle(false)
            8 -> if (all.isEmpty()) QueueOp.SetRepeat(QueueRepeat.ALL) else QueueOp.SetCurrent(all.random(rnd))
            else -> QueueOp.SetRepeat(QueueRepeat.entries.random(rnd))
        }
    }

    @Test fun theRulesHoldForAnySequence() {
        var applied = 0
        repeat(300) { seed ->
            val rnd = Random(seed)
            var n = 0
            val next = { "e${n++}" }
            var q = album()
            repeat(60) {
                val op = randomOp(q, rnd, next)
                val after = (q.apply(op) as? ApplyResult.Applied)?.state ?: return@repeat
                applied++
                check(q, op, after)
                q = after
            }
        }
        assertTrue("enough edits were exercised: $applied", applied > 10_000)
    }

    private fun check(before: QueueState, op: QueueOp, after: QueueState) {
        val where = "$op on ${before.ids()}"
        assertEquals("ids stay unique: $where", after.entries.size, after.entries.map { it.id }.toSet().size)
        if (op !is QueueOp.SetCurrent) {
            assertEquals("the song playing changes only by SetCurrent: $where", before.currentId, after.currentId)
            assertEquals("the history never changes: $where", before.history, after.history)
        }
        if (op !is QueueOp.Remove) {
            val protectedBefore = before.entries.filter { it.isProtected }.map { it.id }
            assertTrue("protected entries are only removed by Remove: $where", after.ids().containsAll(protectedBefore))
        }
        if (op is QueueOp.SetShuffle) {
            assertEquals("shuffle keeps every entry: $where", before.ids().sorted(), after.ids().sorted())
            before.upcoming.forEachIndexed { i, e ->
                if (e.isProtected) assertEquals("shuffle never moves a protected entry: $where", e.id, after.upcoming[i].id)
            }
        }
        if (op is QueueOp.Move) {
            assertTrue("a moved entry is protected: $where", after[op.entryId]!!.isProtected)
        }
        // The Play next run is one block right after the song playing.
        val flagged = after.upcoming.withIndex().filter { it.value.priority }.map { it.index }
        if (op is QueueOp.PlayNext || op is QueueOp.Move) {
            assertEquals("the Play next run is contiguous from the top: $where", flagged.indices.toList(), flagged)
        }
    }
}
