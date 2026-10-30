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
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random

/**
 * Stage 3b-2 on real sockets: the computer edits the shared queue (play next,
 * up, down, remove, play) while the phone's player keeps changing on its own,
 * through an outage. The phone takes the computer's edits into its player
 * ([QueueFollower]) and mirrors itself back. When all is still, both must
 * show the same queue, and the computer's last edits must be in the phone's player.
 */
class QueueFollowerLinkTest {
    private class Renderer : RemoteRenderer {
        override fun play(request: PlayRequest) {}
        override fun control(request: ControlRequest) {}
        override fun setVolume(volume: Float) {}
        override fun status() = RemoteStatus(RemoteState.IDLE)
        override fun disconnect(phoneName: String?) {}
    }

    private val closing = mutableListOf<AutoCloseable>()
    @After fun tearDown() = closing.asReversed().forEach { runCatching { it.close() } }

    /** The phone's player: checks the song expected is where it is told, as PlaybackConnection does. */
    private class Phone(val rnd: Random) : QueuePlayer {
        val songs = mutableListOf<String>()
        var current = 0
        private var next = 0
        var refused = 0
        /** A player that takes nothing (the queue changed under every edit). */
        var refuseAll = false
        init { repeat(12) { songs += fresh() } }
        fun fresh() = "s${next++}"
        private fun at(i: Int, key: String) = !refuseAll && i in songs.indices && "dev:phone:${songs[i]}" == key

        override suspend fun move(from: Int, to: Int, mediaKey: String): Boolean {
            if (!at(from, mediaKey) || from <= current || to <= current || to !in songs.indices) return false.also { refused++ }
            songs.add(to, songs.removeAt(from)); return true
        }
        override suspend fun remove(index: Int, mediaKey: String): Boolean {
            if (!at(index, mediaKey) || index == current) return false.also { refused++ }
            songs.removeAt(index); if (index < current) current--; return true
        }
        override suspend fun play(index: Int, mediaKey: String): Boolean {
            if (!at(index, mediaKey)) return false.also { refused++ }
            current = index; return true
        }

        fun change() {
            when (rnd.nextInt(5)) {
                0 -> if (songs.size - current > 2) songs.add(rnd.nextInt(current + 1, songs.size), songs.removeAt(rnd.nextInt(current + 1, songs.size)))
                1 -> songs.add((current + 1).coerceAtMost(songs.size), fresh())
                2 -> repeat(2) { songs += fresh() }
                3 -> if (current < songs.lastIndex) current++
                else -> Unit
            }
        }

        fun items() = songs.map { MirrorItem(media(it), Origin.LIST) }
    }

    private fun <T> blocking(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    @Test fun editsOnTheComputerReachThePhonesPlayerAndBothStayInStep() {
        val service = QueueService(QueueCoordinator())
        val pairing = Pairing()
        val receiver = ConnectReceiver({ PcInfo("pc", "PC", 0) }, pairing, Renderer(), queue = { service })
        val server = MiniHttpServer(0, "test-connect", receiver::handle).start().also { closing += it }
        val proxy = TestProxy(server.boundPort).also { closing += it }
        val token = ConnectClient("127.0.0.1", server.boundPort).pair(pairing.code, "Phone")
        val link = QueueLink(ConnectClient("127.0.0.1", proxy.port, token, session = "s1"), QueueReplica("phone")).start().also { closing += it }
        val follower = QueueFollower(link, QueueMirror("phone"))
        val rnd = Random(11)
        val phone = Phone(rnd)

        fun look() {
            // As the phone's bridge does: a second look once the player took edits.
            if (blocking { follower.step(phone.items(), phone.current, phone) }) blocking { follower.step(phone.items(), phone.current, phone) }
        }

        fun pcEdit() {
            val q = service.state
            val up = q.upcoming
            if (up.isEmpty()) return
            val e = up.random(rnd)
            val i = up.indexOf(e)
            val op = when (rnd.nextInt(5)) {
                0 -> QueueOp.Move(e.id, null) // play next
                1 -> if (i > 0) QueueOp.Move(e.id, up.getOrNull(i - 2)?.id) else return // up
                2 -> if (i < up.lastIndex) QueueOp.Move(e.id, up[i + 1].id) else return // down
                3 -> QueueOp.Remove(listOf(e.id))
                else -> QueueOp.SetCurrent(e.id) // play it
            }
            service.submit(QueueCommand(UUID.randomUUID().toString(), "pc", op, q.epoch))
        }

        val start = System.currentTimeMillis()
        while (link.replica.revision < 0 && System.currentTimeMillis() - start < 5_000) Thread.sleep(10)
        repeat(150) { tick ->
            proxy.mode = if (tick in 50..80) TestProxy.Mode.BLACKHOLE else TestProxy.Mode.PASS
            if (tick == 81) proxy.pass()
            if (rnd.nextInt(3) == 0) phone.change()
            if (rnd.nextInt(3) == 0) pcEdit()
            look()
            Thread.sleep(40)
        }

        fun settle() {
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end) {
                look()
                if (link.replica.outbox.isEmpty() && link.replica.revision == service.revision &&
                    service.state.entries.map { it.media.localId } == phone.songs) return
                Thread.sleep(50)
            }
        }
        settle()
        assertEquals("both show the same queue", phone.songs, service.state.entries.map { it.media.localId })
        assertEquals(phone.songs[phone.current], service.state.current!!.media.localId)

        // Quiet now: one edit of each kind on the computer lands in the phone's player.
        repeat(6) { phone.songs += phone.fresh() } // Smart Shuffle tops the queue up
        settle()
        val up = service.state.upcoming
        assertTrue("enough left to edit: ${up.size}", up.size >= 4)
        val last = up.last()
        service.submit(QueueCommand("move", "pc", QueueOp.Move(last.id, null), service.state.epoch))
        settle()
        assertEquals("played next on the phone", last.media.localId, phone.songs[phone.current + 1])
        val gone = service.state.upcoming[1]
        service.submit(QueueCommand("remove", "pc", QueueOp.Remove(listOf(gone.id)), service.state.epoch))
        settle()
        assertTrue("removed on the phone", gone.media.localId !in phone.songs.drop(phone.current + 1).take(2))
        val play = service.state.upcoming[1]
        service.submit(QueueCommand("play", "pc", QueueOp.SetCurrent(play.id), service.state.epoch))
        settle()
        assertEquals("playing on the phone", play.media.localId, phone.songs[phone.current])
        assertEquals(phone.songs, service.state.entries.map { it.media.localId })
        val log = (service.coordinator.since(0) as QueueDelta.Ops).ops
        assertEquals("each op applied once", log.size, log.map { it.commandId }.toSet().size)
        println("follower: ${log.size} ops, ${phone.refused} computer edits refused by the phone (stale), ${proxy.connections.get()} connections")
    }

    @Test fun anEditThePhoneRefusesIsUndoneInTheSharedQueue() {
        val service = QueueService(QueueCoordinator())
        val pairing = Pairing()
        val receiver = ConnectReceiver({ PcInfo("pc", "PC", 0) }, pairing, Renderer(), queue = { service })
        val server = MiniHttpServer(0, "test-connect", receiver::handle).start().also { closing += it }
        val token = ConnectClient("127.0.0.1", server.boundPort).pair(pairing.code, "Phone")
        val link = QueueLink(ConnectClient("127.0.0.1", server.boundPort, token, session = "s1"), QueueReplica("phone")).start().also { closing += it }
        val follower = QueueFollower(link, QueueMirror("phone"))
        val phone = Phone(Random(1))
        fun settle() {
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end) {
                if (blocking { follower.step(phone.items(), phone.current, phone) }) continue
                if (link.replica.outbox.isEmpty() && link.replica.revision == service.revision &&
                    service.state.entries.map { it.media.localId } == phone.songs) return
                Thread.sleep(30)
            }
        }
        while (link.replica.revision < 0) Thread.sleep(10)
        settle()
        val before = phone.songs.toList()
        phone.refuseAll = true
        val last = service.state.upcoming.last()
        service.submit(QueueCommand("move", "pc", QueueOp.Move(last.id, null), service.state.epoch))
        val shown = System.currentTimeMillis() + 3_000
        while (service.state.upcoming.first().id != last.id && System.currentTimeMillis() < shown) Thread.sleep(10)
        settle()
        assertEquals("the phone's player untouched", before, phone.songs)
        assertEquals("the shared queue back to the phone's", before, service.state.entries.map { it.media.localId })
        assertTrue(phone.refused > 0)
    }
}
