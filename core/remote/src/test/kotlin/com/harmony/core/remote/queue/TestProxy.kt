package com.harmony.core.remote.queue

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Between the phone and the computer; counts the phone's requests. */
internal class TestProxy(private val target: Int) : AutoCloseable {
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
