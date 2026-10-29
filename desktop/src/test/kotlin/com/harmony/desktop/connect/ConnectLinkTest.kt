package com.harmony.desktop.connect

import com.harmony.core.remote.ConnectClient
import com.harmony.core.remote.ConnectSessionEnded
import com.harmony.core.remote.ControlAction
import com.harmony.core.remote.ControlRequest
import com.harmony.core.remote.LinkEnd
import com.harmony.core.remote.PhoneLink
import com.harmony.core.remote.PhoneSessions
import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteRequest
import com.harmony.core.remote.RemoteState
import com.harmony.core.remote.RemoteTrack
import com.harmony.desktop.engine.AudioEngine
import com.harmony.desktop.engine.CountingSink
import com.harmony.desktop.engine.DecodeStream
import com.harmony.desktop.engine.Decoder
import com.harmony.desktop.engine.EngineStatus
import com.harmony.desktop.player.PlayerController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramSocket
import java.util.Collections

/**
 * The phone session as the computer sees it: connected only once the phone's
 * requests arrive, "reconnecting" when they stop, gone when they stay away,
 * and nothing from an old session able to come back and take over.
 */
class ConnectLinkTest {
    // ---- The session rules, on a clock the test moves --------------------------------

    private class Clock(var now: Long = 1_000)

    private fun sessions(clock: Clock, links: MutableList<PhoneLink> = mutableListOf()) =
        PhoneSessions(suspectAfterMs = 6_000, lostAfterMs = 35_000, onLink = { links += it }).also { it.tick(clock.now) }

    @Test fun `connected only once the phone has played, and a single slow read is not a disconnect`() {
        val clock = Clock()
        val links = mutableListOf<PhoneLink>()
        val s = sessions(clock, links)
        assertEquals(PhoneLink.None, s.link)
        s.begin("tok-a", "s1", "Pixel A", clock.now)
        val connected = s.link as PhoneLink.Connected
        assertEquals("Pixel A", connected.phone)
        // Reads every half second, then one that takes the phone's whole 4 s timeout and its pause.
        repeat(10) { clock.now += 500; s.tick(clock.now); assertEquals(PhoneSessions.Verdict.Current, s.check("tok-a", "s1", clock.now)) }
        for (step in 1..5) { clock.now += 1_000; s.tick(clock.now) } // 5 s of silence
        assertEquals(connected, s.link)
        s.check("tok-a", "s1", clock.now)
        assertEquals(listOf(connected), links)
    }

    @Test fun `silence turns into reconnecting, an answer back into connected, a long silence ends it`() {
        val clock = Clock()
        val links = mutableListOf<PhoneLink>()
        val s = sessions(clock, links)
        s.begin("tok-a", "s1", "Pixel A", clock.now)
        val id = (s.link as PhoneLink.Connected).session
        repeat(6) { clock.now += 1_000; assertNull(s.tick(clock.now)) }
        assertEquals(PhoneLink.Reconnecting("Pixel A", id), s.link)
        // The phone is back: the same session, connected again.
        assertEquals(PhoneSessions.Verdict.Current, s.check("tok-a", "s1", clock.now))
        assertEquals(PhoneLink.Connected("Pixel A", id), s.link)
        // Then gone for good.
        var lost: String? = null
        for (second in 1..35) { clock.now += 1_000; lost = s.tick(clock.now) ?: lost }
        assertEquals("Pixel A", lost)
        assertEquals(PhoneLink.None, s.link)
        assertEquals(
            listOf(PhoneLink.Connected("Pixel A", id), PhoneLink.Reconnecting("Pixel A", id), PhoneLink.Connected("Pixel A", id), PhoneLink.Reconnecting("Pixel A", id), PhoneLink.None),
            links,
        )
        // Its late reads are told the session is over, so the phone ends its side too.
        assertEquals(PhoneSessions.Verdict.Ended(LinkEnd.LOST), s.check("tok-a", "s1", clock.now))
    }

    @Test fun `a computer waking from sleep waits for the phone instead of dropping it at once`() {
        val clock = Clock()
        val s = sessions(clock)
        s.begin("tok-a", "s1", "Pixel A", clock.now)
        clock.now += 2 * 60 * 60 * 1000L // two hours asleep, no ticks
        assertNull(s.tick(clock.now))
        assertTrue(s.link is PhoneLink.Reconnecting)
        // The phone gets the rest of the window to answer.
        clock.now += 20_000
        assertNull(s.tick(clock.now))
        assertEquals(PhoneSessions.Verdict.Current, s.check("tok-a", "s1", clock.now))
        assertTrue(s.link is PhoneLink.Connected)
    }

    @Test fun `requests from an old or another session can't take over or end the current one`() {
        val clock = Clock()
        val s = sessions(clock)
        s.begin("tok-a", "s1", "Pixel A", clock.now)
        val first = (s.link as PhoneLink.Connected).session
        // The same session playing the next song is the same session.
        assertNull(s.begin("tok-a", "s1", "Pixel A", clock.now))
        assertEquals(first, (s.link as PhoneLink.Connected).session)
        // Phone B takes over.
        assertEquals("Pixel A", s.begin("tok-b", "s9", "Pixel B", clock.now))
        val second = (s.link as PhoneLink.Connected).session
        assertTrue(second > first)
        assertEquals(PhoneSessions.Verdict.Ended(LinkEnd.REPLACED), s.check("tok-a", "s1", clock.now))
        // Phone A starting a new session of its own (new id) is a real takeover again.
        assertEquals("Pixel B", s.begin("tok-a", "s2", "Pixel A", clock.now))
        // ...and the old id of A stays refused.
        assertEquals(PhoneSessions.Verdict.Ended(LinkEnd.REPLACED), s.check("tok-a", "s1", clock.now))
        // A phone from before session ids: its token alone is its session.
        assertEquals("Pixel A", s.begin("tok-old", null, "Old phone", clock.now))
        assertEquals(PhoneSessions.Verdict.Current, s.check("tok-old", null, clock.now))
    }

    // ---- The whole way: phone requests over HTTP, the host, the player ---------------

    private val closers = mutableListOf<AutoCloseable>()

    @After fun tearDown() = closers.reversed().forEach { runCatching { it.close() } }

    /** A long, silent song: the player plays it as if it came from a phone. */
    private class SilentDecoder : Decoder {
        override fun open(source: String, startMs: Long, sampleRate: Int, channels: Int) = object : DecodeStream {
            override val input = java.io.ByteArrayInputStream(ByteArray(sampleRate * channels * 2 * 60))
            override fun finish(): String? = null
            override fun close() {}
        }
    }

    private class Pc(val host: ConnectHost, val player: PlayerController, val engine: AudioEngine, val links: MutableList<PhoneLink>, val log: MutableList<String>) {
        val port get() = host.state.value.port
        val code get() = host.state.value.code
    }

    private fun pc(suspect: Long = 600, lost: Long = 2_000, tick: Long = 50): Pc {
        val engine = AudioEngine({ CountingSink(realTime = true) }, SilentDecoder())
        val player = PlayerController(engine)
        val udp = DatagramSocket().use { it.localPort }
        val log = Collections.synchronizedList(mutableListOf<String>())
        val host = ConnectHost(
            "pc-1", { "Test PC" }, player, emptyMap(), {}, httpPort = 0, discoveryPort = udp,
            suspectAfterMs = suspect, lostAfterMs = lost, tickMs = tick, log = { log += it },
        )
        val links = Collections.synchronizedList(mutableListOf<PhoneLink>())
        host.start()
        // The host's own flow, as the screen collects it.
        val watcher = Thread {
            var last: PhoneLink? = null
            while (!Thread.currentThread().isInterrupted) {
                val l = host.link.value
                if (l != last) { links += l; last = l }
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        closers += AutoCloseable { watcher.interrupt(); host.close(); engine.stop() }
        return Pc(host, player, engine, links, log)
    }

    private fun phone(pc: Pc, name: String, session: String? = "session-$name"): ConnectClient {
        val pairing = ConnectClient("127.0.0.1", pc.port)
        val token = pairing.pair(pc.code, name)
        return ConnectClient("127.0.0.1", pc.port, token, session = session)
    }

    // Length and quality given, so the player has nothing to ask ffprobe about.
    private fun track(id: String) = RemoteTrack(id, "Song $id", "Artist", "Album", 60_000, "http://192.0.2.1/track/$id", quality = "FLAC 16/44.1")

    private fun waitFor(what: String, timeoutMs: Long = 5_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (check()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for $what")
    }

    private fun expectEnded(reason: String, call: () -> Unit) {
        try {
            call()
            fail("expected the computer to say the session ended ($reason)")
        } catch (e: ConnectSessionEnded) {
            assertEquals(reason, e.reason)
        }
    }

    @Test fun `1 the phone disconnecting is shown at once`() {
        val pc = pc()
        val a = phone(pc, "Pixel A")
        a.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A"))
        assertTrue(pc.host.link.value is PhoneLink.Connected)
        assertEquals("Pixel A", pc.player.phone.value)
        a.disconnect()
        assertEquals(PhoneLink.None, pc.host.link.value)
        assertNull(pc.player.phone.value)
        assertTrue(pc.log.any { "ended: disconnected_by_phone" in it })
    }

    @Test fun `2-4 a phone that vanishes is shown reconnecting, then let go, and its late reads are refused`() {
        // Wi-Fi off on the phone, the app killed, or this computer's own network gone: the
        // computer can't tell them apart. All it sees is the phone's reads stopping.
        val pc = pc(suspect = 600, lost = 2_000)
        val a = phone(pc, "Pixel A")
        a.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A"))
        waitFor("playing") { pc.engine.state.value.status == EngineStatus.PLAYING }
        repeat(3) { a.status(); Thread.sleep(200) }
        a.status()
        val silentFrom = System.nanoTime()
        waitFor("reconnecting") { pc.host.link.value is PhoneLink.Reconnecting }
        val toReconnecting = (System.nanoTime() - silentFrom) / 1_000_000
        assertTrue("reconnecting after ${toReconnecting} ms", toReconnecting in 600..900)
        // Still the phone's music, still playing: a pause in the reads is not the end.
        assertEquals("Pixel A", pc.player.phone.value)
        assertEquals(EngineStatus.PLAYING, pc.engine.state.value.status)
        waitFor("let go") { pc.host.link.value == PhoneLink.None }
        val toNone = (System.nanoTime() - silentFrom) / 1_000_000
        assertTrue("let go after ${toNone} ms", toNone in 2_000..2_400)
        assertNull(pc.player.phone.value)
        assertTrue(pc.engine.state.value.status != EngineStatus.PLAYING)
        assertTrue(pc.log.any { "ended: lost" in it })
        // The phone comes back too late: it is told, and nothing restarts here.
        expectEnded("lost") { a.status() }
        expectEnded("lost") { a.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A", followUp = true)) }
        expectEnded("lost") { a.control(ControlRequest(ControlAction.PLAY)) }
        assertNull(pc.player.phone.value)
        assertEquals(
            listOf(PhoneLink.None, PhoneLink.Connected::class, PhoneLink.Reconnecting::class, PhoneLink.None),
            pc.links.map { if (it is PhoneLink.None) it else it::class },
        )
    }

    @Test fun `5 a short break comes back to the same session, and what was pressed meanwhile isn't replayed`() {
        val pc = pc(suspect = 600, lost = 3_000)
        val a = phone(pc, "Pixel A")
        a.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A"))
        val id = (pc.host.link.value as PhoneLink.Connected).session
        waitFor("reconnecting") { pc.host.link.value is PhoneLink.Reconnecting }
        // Pressed on the computer while the phone is away: dropped, not saved up.
        repeat(5) { pc.player.next() }
        pc.player.toggleShuffle()
        // The phone answers again: connected, the same session, nothing stale waiting for it.
        val status = a.status()
        assertEquals(PhoneLink.Connected("Pixel A", id), pc.host.link.value)
        assertEquals(emptyList<RemoteRequest>(), status.requests)
        assertEquals(RemoteState.PLAYING, status.state)
        assertEquals("1", status.trackId)
        // And what's pressed now does go.
        pc.player.next()
        assertEquals(listOf(RemoteRequest.NEXT), a.status().requests)
    }

    @Test fun `6 the same phone in a new session, then another phone, and the old ones can't come back`() {
        val pc = pc()
        val a1 = phone(pc, "Pixel A", session = "a-1")
        a1.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A"))
        val first = (pc.host.link.value as PhoneLink.Connected).session
        // The phone connects again (its app started a new session): one session, the new one.
        val a2 = ConnectClient("127.0.0.1", pc.port, a1.token, session = "a-2")
        a2.play(PlayRequest(track("1"), 5_000, playing = true, phoneName = "Pixel A"))
        val second = (pc.host.link.value as PhoneLink.Connected).session
        assertTrue(second != first)
        expectEnded("replaced") { a1.status() }
        // A late disconnect from the first session doesn't end the second.
        a1.disconnect()
        assertEquals(PhoneLink.Connected("Pixel A", second), pc.host.link.value)
        assertEquals("Pixel A", pc.player.phone.value)

        // Another phone takes over.
        val b = phone(pc, "Pixel B")
        b.play(PlayRequest(track("2"), 0, playing = true, phoneName = "Pixel B"))
        assertEquals("Pixel B", (pc.host.link.value as PhoneLink.Connected).phone)
        expectEnded("replaced") { a2.status() }
        // A's late pause and late follow-up do nothing to B's music.
        expectEnded("replaced") { a2.control(ControlRequest(ControlAction.PAUSE)) }
        expectEnded("replaced") { a2.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A", followUp = true)) }
        a2.disconnect()
        assertEquals("Pixel B", pc.player.phone.value)
        assertEquals("2", b.status().trackId)
    }

    @Test fun `stop on the computer ends the session for good, and the phone is told`() {
        val pc = pc()
        val a = phone(pc, "Pixel A")
        a.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A"))
        pc.host.stopPhone()
        assertEquals(PhoneLink.None, pc.host.link.value)
        assertNull(pc.player.phone.value)
        expectEnded("stopped_on_computer") { a.status() }
        expectEnded("stopped_on_computer") { a.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A", followUp = true)) }
        assertNull(pc.player.phone.value)
        // Playing again from the phone (not a follow-up) is a new start, and works.
        a.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A"))
        assertTrue(pc.host.link.value is PhoneLink.Connected)
    }

    @Test fun `the computer's own music taking over keeps the session, and play on the phone brings it back`() {
        val pc = pc()
        val a = phone(pc, "Pixel A")
        a.play(PlayRequest(track("1"), 12_000, playing = true, phoneName = "Pixel A"))
        pc.player.playLocal(listOf(com.harmony.desktop.library.LocalTrack("/music/x.flac", "X", "Y", "Z", durationMs = 60_000)), 0)
        assertNull(pc.player.phone.value)
        val s = a.status()
        assertEquals(RemoteState.PAUSED, s.state)
        assertTrue(pc.host.link.value is PhoneLink.Connected)
        a.control(ControlRequest(ControlAction.PLAY))
        assertEquals("Pixel A", pc.player.phone.value)
    }

    @Test fun `7 connecting and disconnecting over and over leaves nothing behind`() {
        val pc = pc(suspect = 300, lost = 800)
        val a = phone(pc, "Pixel A", session = null)
        // Warm up the pools.
        a.play(PlayRequest(track("1"), 0, playing = true, phoneName = "Pixel A"))
        a.disconnect()
        Thread.sleep(300)
        val before = liveThreads()
        repeat(40) { i ->
            val c = ConnectClient("127.0.0.1", pc.port, a.token, session = "cycle-$i")
            c.play(PlayRequest(track("$i"), 0, playing = true, phoneName = "Pixel A"))
            c.status()
            if (i % 2 == 0) c.disconnect() else pc.host.stopPhone()
            assertEquals(PhoneLink.None, pc.host.link.value)
            assertNull(pc.player.phone.value)
        }
        // A few that are lost instead.
        repeat(3) { i ->
            val c = ConnectClient("127.0.0.1", pc.port, a.token, session = "lost-$i")
            c.play(PlayRequest(track("l$i"), 0, playing = true, phoneName = "Pixel A"))
            waitFor("lost $i") { pc.host.link.value == PhoneLink.None }
        }
        Thread.sleep(500)
        val after = liveThreads()
        // Only the long-lived ones: the watch, the HTTP pool's idle threads, the engine.
        val grew = after.filterKeys { it != "connect" }.filter { (name, n) -> n > (before[name] ?: 0) }
        assertTrue("threads left behind: $grew (before $before, after $after)", grew.isEmpty())
        assertEquals(1, after["harmony-connect-watch"] ?: 0)
    }

    private fun liveThreads(): Map<String, Int> = Thread.getAllStackTraces().keys
        .filter { it.isAlive }
        .groupingBy { it.name.replace(Regex("-\\d+$"), "") }
        .eachCount()
}
