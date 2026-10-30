package com.harmony.core.remote.queue

import com.harmony.core.remote.ConnectClient
import com.harmony.core.remote.ConnectReceiver
import com.harmony.core.remote.ControlRequest
import com.harmony.core.remote.MiniHttpServer
import com.harmony.core.remote.Pairing
import com.harmony.core.remote.PcInfo
import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteRenderer
import com.harmony.core.remote.RemoteState
import com.harmony.core.remote.RemoteStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Stage 3b on real sockets: a phone whose player keeps changing (songs moved,
 * added, removed, played on, Smart Shuffle appending) mirrors its queue to the
 * computer through a proxy that cuts the link for a while. When the phone's
 * player is still and the network back, the computer's queue must be exactly
 * the phone's, and the mirror must have nothing more to send (no echo).
 */
class QueueMirrorLinkTest {
    private class Renderer : RemoteRenderer {
        override fun play(request: PlayRequest) {}
        override fun control(request: ControlRequest) {}
        override fun setVolume(volume: Float) {}
        override fun status() = RemoteStatus(RemoteState.IDLE)
        override fun disconnect(phoneName: String?) {}
    }

    private val closing = mutableListOf<AutoCloseable>()
    @After fun tearDown() = closing.asReversed().forEach { runCatching { it.close() } }

    /** The phone's player: songs in order and the one playing. */
    private class Player(val rnd: Random) {
        val songs = mutableListOf<String>()
        var current = 0
        var nextSong = 0
        private fun fresh() = "s${nextSong++}"

        init { repeat(15) { songs += fresh() } }

        fun change() {
            when (rnd.nextInt(6)) {
                0 -> if (songs.size - current > 2) { // a drag in the queue sheet
                    val from = rnd.nextInt(current + 1, songs.size)
                    val to = rnd.nextInt(current + 1, songs.size)
                    songs.add(to, songs.removeAt(from))
                }
                1 -> songs.add((current + 1).coerceAtMost(songs.size), fresh()) // Play next
                2 -> repeat(3) { songs += fresh() } // Smart Shuffle appends
                3 -> if (songs.size - current > 2) songs.removeAt(rnd.nextInt(current + 1, songs.size)) // removed
                4 -> if (current < songs.lastIndex) current++ // the song ends
                else -> songs.add(rnd.nextInt(current + 1, songs.size + 1), songs[rnd.nextInt(songs.size)]) // a song queued twice
            }
        }

        fun items() = songs.map { MirrorItem(media(it), Origin.LIST) }
    }

    @Test fun thePhonesQueueReachesTheComputerExactlyThroughOutages() {
        val service = QueueService(QueueCoordinator())
        val pairing = Pairing()
        val receiver = ConnectReceiver({ PcInfo("pc", "PC", 0) }, pairing, Renderer(), queue = { service })
        val server = MiniHttpServer(0, "test-connect", receiver::handle).start().also { closing += it }
        val proxy = TestProxy(server.boundPort).also { closing += it }
        val token = ConnectClient("127.0.0.1", server.boundPort).pair(pairing.code, "Phone")
        val client = ConnectClient("127.0.0.1", proxy.port, token, session = "s1")
        var n = 0
        val link = QueueLink(client, QueueReplica("phone") { "c${n++}" }).start().also { closing += it }
        val mirror = QueueMirror("phone")
        val player = Player(Random(3))

        fun sync() = mirror.ops(link.replica.view, player.items(), player.current).forEach { link.issue(it) }

        val deadline = System.currentTimeMillis() + 5_000
        while (link.replica.revision < 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        var ops = 0
        repeat(120) { tick ->
            proxy.mode = if (tick in 40..70) TestProxy.Mode.BLACKHOLE else TestProxy.Mode.PASS
            if (tick == 71) proxy.pass()
            player.change()
            val before = link.replica.outbox.size
            sync()
            ops += (link.replica.outbox.size - before).coerceAtLeast(0)
            Thread.sleep(50)
        }
        // Still: the player stops changing; the link catches up.
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            sync()
            if (link.replica.outbox.isEmpty() && link.replica.revision == service.revision) break
            Thread.sleep(50)
        }
        val shared = service.state
        assertEquals("the computer has the phone's queue", player.songs, shared.entries.map { it.media.localId })
        assertEquals(player.songs[player.current], shared.current!!.media.localId)
        assertEquals("phone", shared.owner)
        assertTrue("no echo: nothing left to send", mirror.ops(link.replica.view, player.items(), player.current).isEmpty())
        val log = (service.coordinator.since(0) as QueueDelta.Ops).ops
        assertEquals("each mirror op applied once", log.size, log.map { it.commandId }.toSet().size)
        println("mirror: 120 player changes, $ops ops sent, ${log.size} applied, ${proxy.connections.get()} connections")
    }
}
