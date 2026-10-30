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
import com.harmony.core.remote.RemoteTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared queue over real sockets: the computer coordinates
 * ([QueueService] behind [ConnectReceiver]), the phone holds a [QueueLink],
 * and between them a TCP proxy that can lose the link, refuse it, or slow it.
 */
class QueueLinkTest {
    /** Between the phone and the computer; counts the phone's requests. */
    private class Proxy(private val target: Int) : AutoCloseable {
        enum class Mode { PASS, BLACKHOLE, REFUSE }

        @Volatile var mode = Mode.PASS
        @Volatile var latencyMs = 0L
        val connections = AtomicInteger()
        private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 0)) }
        private val held = CopyOnWriteArrayList<Socket>()
        val port: Int get() = server.localPort

        init {
            Thread({
                while (!server.isClosed) {
                    val c = try { server.accept() } catch (_: Exception) { break }
                    connections.incrementAndGet()
                    Thread({ handle(c) }, "proxy-conn").apply { isDaemon = true }.start()
                }
            }, "proxy").apply { isDaemon = true }.start()
        }

        private fun handle(client: Socket) = client.use { c ->
            when (mode) {
                Mode.REFUSE -> return
                Mode.BLACKHOLE -> { hold(c); return }
                Mode.PASS -> Unit
            }
            if (latencyMs > 0) Thread.sleep(latencyMs)
            Socket().use { up ->
                up.connect(InetSocketAddress("127.0.0.1", target), 2_000)
                val a = Thread { pump(c.getInputStream(), up.getOutputStream()) }.apply { isDaemon = true; start() }
                pump(up.getInputStream(), c.getOutputStream())
                a.join(1_000)
            }
        }

        private fun hold(c: Socket) {
            held += c
            runCatching { c.soTimeout = 30_000; while (c.getInputStream().read() >= 0) Unit }
        }

        /** The outage ends: what was held open is dropped, as a router coming back would. */
        fun pass() {
            mode = Mode.PASS
            held.forEach { runCatching { it.close() } }
            held.clear()
        }

        private fun pump(i: InputStream, o: OutputStream) = runCatching {
            val buf = ByteArray(8192)
            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
            runCatching { o.close() }
        }

        override fun close() = server.close()
    }

    private class Renderer : RemoteRenderer {
        override fun play(request: PlayRequest) {}
        override fun control(request: ControlRequest) {}
        override fun setVolume(volume: Float) {}
        override fun status() = RemoteStatus(RemoteState.IDLE)
        override fun disconnect(phoneName: String?) {}
    }

    private val closing = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> T.closes(): T = also { closing += it }

    @After fun tearDown() = closing.asReversed().forEach { runCatching { it.close() } }

    private class Computer(val service: QueueService?, val receiver: ConnectReceiver, val port: Int, val pairing: Pairing)

    private fun computer(withQueue: Boolean = true): Computer {
        val service = if (withQueue) QueueService(QueueCoordinator(album(), shuffler = { it.reversed() })) else null
        val pairing = Pairing()
        val receiver = ConnectReceiver({ PcInfo("pc", "PC", 0) }, pairing, Renderer(), queue = service)
        val server = MiniHttpServer(0, "test-connect", receiver::handle).start().closes()
        return Computer(service, receiver, server.boundPort, pairing)
    }

    private fun phone(
        pc: Computer,
        port: Int,
        session: String = "s1",
        states: MutableList<QueueLink.State> = CopyOnWriteArrayList(),
        token: String = ConnectClient("127.0.0.1", pc.port).pair(pc.pairing.code, "Phone"),
    ): QueueLink {
        val client = ConnectClient("127.0.0.1", port, token, session = session)
        var n = 0
        return QueueLink(client, QueueReplica("phone") { "phone-${n++}" }, onState = { states += it }).closes().start()
    }

    /** The computer's own edit, as its screen would make it. */
    private var pcIds = 0
    private fun Computer.edit(op: QueueOp) = service!!.submit(QueueCommand("pc-${pcIds++}", "pc", op, service.state.epoch))

    private fun waitFor(timeoutMs: Long, what: String, test: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!test()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not within $timeoutMs ms: $what")
            Thread.sleep(10)
        }
    }

    private fun converged(pc: Computer, link: QueueLink) =
        link.replica.outbox.isEmpty() && link.replica.revision == pc.service!!.revision && link.replica.view == pc.service.state

    @Test fun anEditOnTheComputerShowsOnThePhoneAtOnce() {
        val pc = computer()
        val link = phone(pc, pc.port)
        waitFor(3_000, "first sync") { link.state == QueueLink.State.CONNECTED && converged(pc, link) }
        Thread.sleep(300) // the phone's request is now held open on the computer
        val t0 = System.nanoTime()
        pc.edit(QueueOp.PlayNext(listOf(entry("p1"))))
        waitFor(2_000, "the edit reaches the phone") { link.replica.view.upcomingIds().firstOrNull() == "p1" }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("held request answered at once: ${ms} ms", ms < 500)
    }

    @Test fun anEditOnThePhoneIsAppliedOnceOnTheComputer() {
        val pc = computer()
        val link = phone(pc, pc.port)
        waitFor(3_000, "first sync") { converged(pc, link) }
        link.issue(QueueOp.Move("l4", afterId = null))
        link.issue(QueueOp.Add(listOf(entry("m1"))))
        waitFor(3_000, "applied") { converged(pc, link) && pc.service!!.state.upcomingIds().first() == "l4" }
        val log = (pc.service!!.coordinator.since(0) as QueueDelta.Ops).ops
        assertEquals(2, log.size)
        assertEquals(listOf("l4", "l1", "l2", "l3", "m1"), pc.service.state.upcomingIds())
    }

    @Test fun editsOnBothSidesDuringAnOutageMeetAfterIt() {
        val pc = computer()
        val proxy = Proxy(pc.port).closes()
        val states = CopyOnWriteArrayList<QueueLink.State>()
        val link = phone(pc, proxy.port, states = states)
        waitFor(3_000, "first sync") { converged(pc, link) }

        proxy.mode = Proxy.Mode.BLACKHOLE
        link.issue(QueueOp.PlayNext(listOf(entry("ph1"))))
        link.issue(QueueOp.Move("l1", afterId = "l3"))
        pc.edit(QueueOp.Add(listOf(entry("pc1"))))
        pc.edit(QueueOp.Remove(listOf("l2")))
        waitFor(15_000, "the phone notices") { link.state == QueueLink.State.RECONNECTING }
        assertEquals("the phone shows its own edits meanwhile", "ph1", link.replica.view.upcomingIds().first())

        proxy.pass()
        val back = System.nanoTime()
        waitFor(15_000, "back in step") { converged(pc, link) && link.state == QueueLink.State.CONNECTED }
        println("queue back in step ${(System.nanoTime() - back) / 1_000_000} ms after the outage")
        assertEquals(listOf("ph1", "l3", "l1", "l4", "pc1"), pc.service!!.state.upcomingIds())
        val log = (pc.service.coordinator.since(0) as QueueDelta.Ops).ops
        assertEquals("each edit once", log.size, log.map { it.commandId }.toSet().size)
        assertEquals(4, log.size)
    }

    @Test fun aRefusedConnectionIsRetriedWithoutSpinning() {
        val pc = computer()
        val proxy = Proxy(pc.port).closes()
        val link = phone(pc, proxy.port)
        waitFor(3_000, "first sync") { converged(pc, link) }
        proxy.mode = Proxy.Mode.REFUSE
        link.issue(QueueOp.Add(listOf(entry("m1"))))
        val before = proxy.connections.get()
        Thread.sleep(5_000)
        val tries = proxy.connections.get() - before
        assertTrue("backing off, not spinning: $tries connections in 5 s", tries in 2..60)
        proxy.pass()
        waitFor(10_000, "delivered after") { converged(pc, link) && "m1" in pc.service!!.state.ids() }
    }

    @Test fun anIdleQueueCostsOneRequestEveryFewSeconds() {
        val pc = computer()
        val proxy = Proxy(pc.port).closes()
        val link = phone(pc, proxy.port)
        waitFor(3_000, "first sync") { converged(pc, link) && link.state == QueueLink.State.CONNECTED }
        Thread.sleep(500)
        val before = proxy.connections.get()
        Thread.sleep(12_000)
        val requests = proxy.connections.get() - before
        // Polling the status twice a second would be 24 here.
        assertTrue("$requests requests in 12 s while idle", requests in 2..5)
    }

    @Test fun aHeldRequestDoesntHoldUpTheRest() {
        val pc = computer()
        val link = phone(pc, pc.port)
        waitFor(3_000, "first sync") { converged(pc, link) && link.state == QueueLink.State.CONNECTED }
        Thread.sleep(300)
        val token = ConnectClient("127.0.0.1", pc.port).pair(pc.pairing.code, "Phone 2")
        val other = ConnectClient("127.0.0.1", pc.port, token, session = "s1")
        val t0 = System.nanoTime()
        other.status()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("status answered while the queue request is held: $ms ms", ms < 300)
    }

    @Test fun anOlderComputerWithoutTheQueueStopsTheLinkQuietly() {
        val pc = computer(withQueue = false)
        val proxy = Proxy(pc.port).closes()
        val link = phone(pc, proxy.port)
        waitFor(3_000, "noticed") { link.state == QueueLink.State.UNSUPPORTED }
        val after = proxy.connections.get()
        Thread.sleep(1_500)
        assertEquals("no more requests", after, proxy.connections.get())
    }

    @Test fun aSessionEndedOnTheComputerEndsTheLink() {
        val pc = computer()
        val token = ConnectClient("127.0.0.1", pc.port).pair(pc.pairing.code, "Phone")
        // The phone plays here (a session) and shares the queue in the same session.
        ConnectClient("127.0.0.1", pc.port, token, session = "s1")
            .play(PlayRequest(RemoteTrack("1", "Song", "A", "B", 1_000, "http://192.0.2.1/1"), 0, true, "Phone"))
        val link = phone(pc, pc.port, session = "s1", token = token)
        waitFor(3_000, "first sync") { converged(pc, link) && link.state == QueueLink.State.CONNECTED }
        // Stop pressed on the computer.
        pc.receiver.stopFromComputer()
        link.issue(QueueOp.Add(listOf(entry("late"))))
        waitFor(10_000, "the link ends") { link.state == QueueLink.State.ENDED }
        assertTrue("a late edit from the ended session changed nothing", "late" !in pc.service!!.state.ids())
    }
}
