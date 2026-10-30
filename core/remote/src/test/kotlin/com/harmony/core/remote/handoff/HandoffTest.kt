package com.harmony.core.remote.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Stage 7: the handoff on a simulated clock, against two simulated players
 * with delays and a failure injected at every step. Whatever happens, the two
 * never play at once; a handoff that goes through leaves a gap of only the
 * stop and the start, and the music carries on from the same place.
 */
class HandoffTest {
    /** What can go wrong with one player. */
    enum class Fault { NONE, PREPARE_FAILS, PREPARE_HANGS, PREPARE_UNREACHABLE, START_FAILS, START_HANGS, START_UNREACHABLE, STOP_UNREACHABLE, STOP_HANGS }

    private class World(var now: Long = 0)

    /** A player: plays (its position moves with the clock), stops, prepares, all after a delay. */
    private class Fake(
        val world: World,
        val name: String,
        var song: String = "s1",
        var playing: Boolean = false,
        var position: Long = 0,
        val prepareMs: Long = 300,
        val startMs: Long = 30,
        val stopMs: Long = 30,
        val fault: Fault = Fault.NONE,
        var busyUntil: Long = 0,
    ) : HandoffPlayer {
        /** When this player was sounding: [from, to) intervals. */
        val sounding = mutableListOf<LongArray>()
        private var since = -1L
        private var lastAt = 0L
        private var pending: Pair<Long, () -> Unit>? = null
        private var ready = Readiness.NOT_YET
        private var stopState: Boolean? = true
        var startedAt = -1L
        var stoppedAt = -1L
        var startedFrom = -1L
        var stoppedAtPosition = -1L

        fun advance() {
            if (playing) position += world.now - lastAt
            lastAt = world.now
            pending?.let { (at, action) -> if (world.now >= at) { pending = null; action() } }
        }

        fun sound(p: Boolean) {
            if (p == playing) return
            playing = p
            if (p) since = world.now else { sounding += longArrayOf(since, world.now) }
        }

        fun close() { if (playing) sounding += longArrayOf(since, world.now) }

        override fun songId() = song
        override fun positionMs() = position
        override fun isPlaying() = playing
        override fun busy() = world.now < busyUntil

        override fun prepare(positionMs: Long) {
            ready = Readiness.NOT_YET
            sound(false)
            when (fault) {
                Fault.PREPARE_FAILS -> pending = world.now + prepareMs to { ready = Readiness.FAILED }
                Fault.PREPARE_HANGS -> pending = null
                Fault.PREPARE_UNREACHABLE -> pending = world.now + prepareMs to { ready = Readiness.UNREACHABLE }
                else -> pending = world.now + prepareMs to { position = positionMs; ready = Readiness.READY }
            }
        }

        override fun readiness() = ready

        override fun start() {
            when (fault) {
                Fault.START_FAILS -> pending = world.now + startMs to { ready = Readiness.FAILED }
                Fault.START_HANGS -> pending = null
                // It plays, but its answers are lost.
                Fault.START_UNREACHABLE -> pending = world.now + startMs to { start0(); ready = Readiness.UNREACHABLE }
                else -> pending = world.now + startMs to { start0(); ready = Readiness.PLAYING }
            }
        }

        private fun start0() {
            startedAt = world.now
            startedFrom = position
            sound(true)
        }

        override fun stop() {
            when (fault) {
                // Its answers are lost and it plays on.
                Fault.STOP_UNREACHABLE -> stopState = null
                Fault.STOP_HANGS -> stopState = false
                else -> {
                    stopState = false
                    pending = world.now + stopMs to {
                        if (playing) { stoppedAt = world.now; stoppedAtPosition = position }
                        sound(false)
                        ready = Readiness.NOT_YET
                        stopState = true
                    }
                }
            }
        }

        override fun stopped() = stopState
    }

    private fun run(source: Fake, target: Fake, world: World, events: (Long) -> Unit = {}, maxMs: Long = 60_000): HandoffResult {
        val h = Handoff(source, target, world.now)
        while (world.now < maxMs) {
            source.advance(); target.advance()
            events(world.now)
            h.tick(world.now)?.let { r ->
                // Let what was asked last happen, then look.
                repeat(20) { world.now += 25; source.advance(); target.advance() }
                source.close(); target.close()
                return r
            }
            world.now += 25
        }
        error("no result")
    }

    private fun overlap(a: Fake, b: Fake): Long {
        var total = 0L
        for (x in a.sounding) for (y in b.sounding) total += (minOf(x[1], y[1]) - maxOf(x[0], y[0])).coerceAtLeast(0)
        return total
    }

    @Test fun aCleanHandoffLeavesOnlyTheStopAndStartAsAGapAndCarriesOnFromThePoint() {
        val w = World()
        val phone = Fake(w, "phone", playing = true, position = 30_000)
        val pc = Fake(w, "pc", prepareMs = 600)
        val r = run(phone, pc, w)
        assertTrue("moved: $r", r is HandoffResult.Moved && r.playing)
        assertEquals(0, overlap(phone, pc))
        val gap = pc.startedAt - phone.stoppedAt
        assertTrue("silence of the stop and the start only: $gap ms", gap in 0..100)
        assertTrue("from the same place: stopped at ${phone.stoppedAtPosition}, started at ${pc.startedFrom}",
            kotlin.math.abs(phone.stoppedAtPosition - pc.startedFrom) <= 60)
        assertTrue(pc.playing && !phone.playing)
    }

    @Test fun aPausedSongMovesPausedRightAway() {
        val w = World()
        val phone = Fake(w, "phone", playing = false, position = 42_000)
        val pc = Fake(w, "pc")
        val r = run(phone, pc, w)
        assertEquals(HandoffResult.Moved(42_000, playing = false), r)
        assertFalse(pc.playing)
        assertEquals(42_000, pc.position)
    }

    @Test fun aCrossfadeIsLetFinishFirst() {
        val w = World()
        val phone = Fake(w, "phone", playing = true, position = 10_000, busyUntil = 4_000)
        val pc = Fake(w, "pc")
        run(phone, pc, w)
        assertTrue("not before the fade ended: ${phone.stoppedAt}", phone.stoppedAt >= 4_000)
    }

    @Test fun aTargetThatCantLoadChangesNothing() {
        for (fault in listOf(Fault.PREPARE_FAILS, Fault.PREPARE_HANGS, Fault.PREPARE_UNREACHABLE)) {
            val w = World()
            val phone = Fake(w, "phone", playing = true)
            val pc = Fake(w, "pc", fault = fault)
            val r = run(phone, pc, w)
            assertEquals("$fault", HandoffResult.Stayed(HandoffResult.Why.NOT_READY), r)
            assertTrue("$fault: the phone never stopped", phone.playing && phone.sounding.size == 1)
            assertFalse(pc.playing)
        }
    }

    @Test fun aTargetThatSaysNoAfterTheStopHandsTheMusicBack() {
        for (fault in listOf(Fault.START_FAILS, Fault.START_HANGS)) {
            val w = World()
            val phone = Fake(w, "phone", playing = true)
            val pc = Fake(w, "pc", fault = fault)
            val r = run(phone, pc, w)
            assertEquals("$fault", HandoffResult.Stayed(HandoffResult.Why.TARGET_FAILED), r)
            assertTrue("$fault: back on the phone", phone.playing && !pc.playing)
        }
    }

    @Test fun aTargetThatStopsAnsweringAfterTheStopLeavesSilenceNotTwoPlayers() {
        val w = World()
        val phone = Fake(w, "phone", playing = true)
        val pc = Fake(w, "pc", fault = Fault.START_UNREACHABLE)
        val r = run(phone, pc, w)
        assertTrue("unsure: $r", r is HandoffResult.Unsure)
        assertFalse("the phone stays silent", phone.playing)
        assertEquals(0, overlap(phone, pc))
    }

    @Test fun aSourceThatCantBeStoppedIsNeverJoinedByTheTarget() {
        for (fault in listOf(Fault.STOP_UNREACHABLE, Fault.STOP_HANGS)) {
            val w = World()
            val pc = Fake(w, "pc", playing = true, fault = fault)
            val phone = Fake(w, "phone")
            val r = run(pc, phone, w)
            assertEquals("$fault", HandoffResult.Stayed(HandoffResult.Why.SOURCE_UNREACHABLE), r)
            assertFalse("$fault: the phone didn't start", phone.playing)
            assertEquals(0, overlap(pc, phone))
        }
    }

    @Test fun aSongChangeOrAPauseMeanwhileCallsItOff() {
        run {
            val w = World()
            val phone = Fake(w, "phone", playing = true)
            val pc = Fake(w, "pc", prepareMs = 800)
            val r = run(phone, pc, w, events = { if (it == 400L) phone.song = "s2" })
            assertEquals(HandoffResult.Stayed(HandoffResult.Why.SONG_CHANGED), r)
            assertTrue(phone.playing && !pc.playing)
        }
        run {
            val w = World()
            val phone = Fake(w, "phone", playing = true)
            val pc = Fake(w, "pc", prepareMs = 800)
            val r = run(phone, pc, w, events = { if (it == 400L) phone.sound(false) })
            assertEquals(HandoffResult.Stayed(HandoffResult.Why.PAUSED_MEANWHILE), r)
            assertFalse(pc.playing)
        }
    }

    @Test fun aTargetReadyLateIsMovedToALaterPoint() {
        val w = World()
        val phone = Fake(w, "phone", playing = true, position = 0)
        // Slower to load than the first lead: the first point is missed.
        val pc = Fake(w, "pc", prepareMs = 2_000)
        val r = run(phone, pc, w)
        assertTrue("moved: $r", r is HandoffResult.Moved && r.playing)
        assertTrue("a later point than the first lead: ${(r as HandoffResult.Moved).atMs}", r.atMs > 1_500)
        assertTrue(kotlin.math.abs(phone.stoppedAtPosition - pc.startedFrom) <= 150)
    }

    /** Thousands of handoffs both ways, delays and faults at random: never two players at once, and every outcome as it says. */
    @Test fun neverTwoPlayersWhateverHappens() {
        val rnd = Random(7)
        val outcomes = HashMap<String, Int>()
        repeat(3_000) { round ->
            val w = World()
            val fromPc = rnd.nextBoolean()
            val sourceFault = if (rnd.nextInt(4) == 0) listOf(Fault.STOP_UNREACHABLE, Fault.STOP_HANGS).random(rnd) else Fault.NONE
            val targetFault = if (rnd.nextInt(2) == 0) Fault.entries.filter { !it.name.startsWith("STOP") }.random(rnd) else Fault.NONE
            val source = Fake(w, if (fromPc) "pc" else "phone", playing = rnd.nextInt(6) != 0, position = rnd.nextLong(0, 200_000),
                stopMs = rnd.nextLong(1, 200), fault = sourceFault, busyUntil = if (rnd.nextInt(5) == 0) rnd.nextLong(0, 20_000) else 0)
            val target = Fake(w, if (fromPc) "phone" else "pc", prepareMs = rnd.nextLong(50, 6_000), startMs = rnd.nextLong(1, 300), fault = targetFault)
            val wasPlaying = source.playing
            val pauseAt = if (rnd.nextInt(8) == 0) rnd.nextLong(0, 5_000) else -1
            val changeAt = if (rnd.nextInt(8) == 0) rnd.nextLong(0, 5_000) else -1
            val r = run(source, target, w, events = { t ->
                if (pauseAt >= 0 && t == pauseAt / 25 * 25) source.sound(false)
                if (changeAt >= 0 && t == changeAt / 25 * 25) source.song = "other"
            })
            outcomes.merge(r::class.simpleName + (if (r is HandoffResult.Stayed) "/" + r.why else ""), 1, Int::plus)
            assertEquals("round $round: both played at once ($r)", 0, overlap(source, target))
            when (r) {
                is HandoffResult.Moved -> {
                    assertFalse("round $round: source silent after a move", source.playing)
                    assertEquals("round $round", r.playing, target.playing)
                    // The stop itself takes a moment ([stopMs]: instant for the phone's own player, a
                    // request away for the computer): that much is heard twice, never skipped.
                    if (r.playing) assertTrue(
                        "round $round: carried on from the point (stopped at ${source.stoppedAtPosition}, started at ${target.startedFrom}, stop took ${source.stopMs})",
                        target.startedFrom <= source.stoppedAtPosition + 60 && source.stoppedAtPosition - target.startedFrom <= source.stopMs + 80,
                    )
                }
                is HandoffResult.Stayed -> {
                    assertFalse("round $round: target silent when nothing moved ($r)", target.playing && sourceFault == Fault.NONE)
                    if (wasPlaying && r.why == HandoffResult.Why.TARGET_FAILED) assertTrue("round $round: music back on the source", source.playing)
                }
                is HandoffResult.Unsure -> assertFalse("round $round: the source stays silent", source.playing)
            }
        }
        println("outcomes: $outcomes")
        assertTrue("moves happen: $outcomes", (outcomes["Moved"] ?: 0) > 500)
    }
}
