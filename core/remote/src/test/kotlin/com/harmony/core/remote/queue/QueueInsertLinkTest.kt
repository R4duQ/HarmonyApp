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
 * Stage 6 on real sockets: the computer adds songs of its own library to the
 * shared queue (Play next, Add) while the phone's player keeps changing, with
 * an outage in between. Each lands in the phone's player once, as the user's,
 * keeping the entry the computer made for it; at rest both show the same queue.
 */
class QueueInsertLinkTest {
    private class Renderer : RemoteRenderer {
        override fun play(request: PlayRequest) {}
        override fun control(request: ControlRequest) {}
        override fun setVolume(volume: Float) {}
        override fun status() = RemoteStatus(RemoteState.IDLE)
        override fun disconnect(phoneName: String?) {}
    }

    private val closing = mutableListOf<AutoCloseable>()
    @After fun tearDown() = closing.asReversed().forEach { runCatching { it.close() } }

    /** A phone player with its own entry ids and provenance, as PlaybackConnection has them. */
    private class Phone(val rnd: Random) : QueuePlayer {
        data class Song(val media: MediaRef, val entry: Long, val origin: Origin, val run: Boolean = false)
        val songs = mutableListOf<Song>()
        var current = 0
        private var nextEntry = 1L
        private var nextSong = 0
        var inserted = 0
        init { repeat(8) { songs += own(Origin.LIST) } }
        fun own(origin: Origin) = Song(media("s${nextSong++}"), nextEntry++, origin)

        private fun at(i: Int, key: String) = i in songs.indices && songs[i].media.contentKey == key
        override suspend fun move(from: Int, to: Int, mediaKey: String): Boolean {
            if (!at(from, mediaKey) || from <= current || to <= current || to !in songs.indices) return false
            songs.add(to, songs.removeAt(from)); return true
        }
        override suspend fun remove(index: Int, mediaKey: String): Boolean {
            if (!at(index, mediaKey) || index == current) return false
            songs.removeAt(index); if (index < current) current--; return true
        }
        override suspend fun play(index: Int, mediaKey: String): Boolean {
            if (!at(index, mediaKey)) return false
            current = index; return true
        }
        override suspend fun insert(at: Int, media: MediaRef, next: Boolean): Boolean {
            val i = at.coerceIn(current + 1, songs.size)
            songs.add(i, Song(media, nextEntry++, Origin.MANUAL, run = next))
            inserted++
            return true
        }

        fun change() {
            when (rnd.nextInt(4)) {
                0 -> repeat(2) { songs += own(Origin.AUTO) } // Smart Shuffle tops up
                1 -> if (current < songs.lastIndex) current++
                2 -> if (songs.size - current > 2) songs.add(rnd.nextInt(current + 1, songs.size), songs.removeAt(rnd.nextInt(current + 1, songs.size)))
                else -> Unit
            }
        }

        fun items() = songs.map { MirrorItem(it.media, it.origin, priority = it.run, entry = it.entry) }
    }

    private fun <T> blocking(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    @Test fun songsAddedOnTheComputerLandInThePhonesPlayerOnceAndKeepTheirEntries() {
        val service = QueueService(QueueCoordinator())
        val pairing = Pairing()
        val receiver = ConnectReceiver({ PcInfo("pc", "PC", 0) }, pairing, Renderer(), queue = { service })
        val server = MiniHttpServer(0, "test-connect", receiver::handle).start().also { closing += it }
        val proxy = TestProxy(server.boundPort).also { closing += it }
        val token = ConnectClient("127.0.0.1", server.boundPort).pair(pairing.code, "Phone")
        val link = QueueLink(ConnectClient("127.0.0.1", proxy.port, token, session = "s1"), QueueReplica("phone")).start().also { closing += it }
        val follower = QueueFollower(link, QueueMirror("phone"))
        val rnd = Random(5)
        val phone = Phone(rnd)

        fun look() {
            if (blocking { follower.step(phone.items(), phone.current, phone) }) blocking { follower.step(phone.items(), phone.current, phone) }
        }

        val added = mutableListOf<String>()
        var pcSong = 0
        fun pcAdds() {
            val q = service.state
            if (q.currentId == null) return
            val e = QueueEntry("pc-${UUID.randomUUID()}", MediaRef("pc:pc:t${pcSong}", "pc", "t${pcSong++}", "PC song", "Artist"), Origin.MANUAL)
            val op = if (rnd.nextBoolean()) QueueOp.PlayNext(listOf(e.copy(priority = true))) else QueueOp.Add(listOf(e))
            val out = service.submit(QueueCommand(UUID.randomUUID().toString(), "pc", op, q.epoch))
            if (out is CommandOutcome.Applied) added += e.id
        }

        val start = System.currentTimeMillis()
        while (link.replica.revision < 0 && System.currentTimeMillis() - start < 5_000) Thread.sleep(10)
        repeat(120) { tick ->
            proxy.mode = if (tick in 40..70) TestProxy.Mode.BLACKHOLE else TestProxy.Mode.PASS
            if (tick == 71) proxy.pass()
            if (rnd.nextInt(3) == 0) phone.change()
            if (rnd.nextInt(4) == 0) pcAdds()
            look()
            Thread.sleep(40)
        }
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            look()
            if (link.replica.outbox.isEmpty() && link.replica.revision == service.revision &&
                service.state.entries.map { it.media.contentKey } == phone.songs.map { it.media.contentKey }) break
            Thread.sleep(50)
        }
        assertEquals("both show the same queue", phone.songs.map { it.media.contentKey }, service.state.entries.map { it.media.contentKey })
        assertTrue("the computer added some: $added", added.size >= 10)
        val onPhone = phone.songs.filter { it.media.home == "pc" }
        assertEquals("each added song in the phone's player once", phone.inserted, onPhone.size)
        assertTrue("as the user's", onPhone.all { it.origin == Origin.MANUAL })
        // Those added before the outage at least were taken into the player then: the same entries on both.
        val kept = service.state.entries.filter { it.media.home == "pc" }.count { it.id in added }
        assertTrue("entries kept, not removed and added again: $kept of ${onPhone.size}", kept >= onPhone.size * 3 / 4)
        assertTrue("every computer song in the shared queue is the user's", service.state.entries.filter { it.media.home == "pc" }.all { it.origin == Origin.MANUAL })
    }
}
