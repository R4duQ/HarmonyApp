package com.harmony.desktop.connect

import com.harmony.core.remote.ConnectClient
import com.harmony.core.remote.ConnectRefused
import com.harmony.core.remote.ConnectSessionEnded
import com.harmony.core.remote.PhoneLink
import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteTrack
import com.harmony.desktop.engine.AudioEngine
import com.harmony.desktop.engine.CountingSink
import com.harmony.desktop.engine.DecodeStream
import com.harmony.desktop.engine.Decoder
import com.harmony.desktop.player.PlayerController
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A simulation, not a test of real devices: the computer's Connect host with
 * its real timings (6 s to "reconnecting", 35 s to "lost", a 1 s watch),
 * a phone that reads its status the way the app's ConnectSession does (every
 * 0.5 s, 4 s timeout, backing off to 2 s, giving up after 30 s), and between
 * them a TCP proxy that loses the link, refuses it, delays it and drops
 * packets. Prints what it measured. Takes about ten minutes, so it only runs
 * when asked: `-PconnectSoak=true`.
 */
class ConnectSoakTest {
    private class SilentDecoder : Decoder {
        override fun open(source: String, startMs: Long, sampleRate: Int, channels: Int) = object : DecodeStream {
            override val input = java.io.ByteArrayInputStream(ByteArray(sampleRate * channels * 2 * 600))
            override fun finish(): String? = null
            override fun close() {}
        }
    }

    /** Between the phone and the computer. Each phone request is one TCP connection. */
    private class FaultProxy(private val target: Int) : AutoCloseable {
        enum class Mode { PASS, BLACKHOLE, REFUSE }

        @Volatile var mode = Mode.PASS
        @Volatile var latencyMs = 0L
        @Volatile var jitterMs = 0L
        /** Chance that a packet is lost; a lost one costs TCP a retransmit (1 s, then 2 s, ...). */
        @Volatile var loss = 0.0
        private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 0)) }
        val port: Int get() = server.localPort

        init {
            Thread({
                while (!server.isClosed) {
                    val c = try { server.accept() } catch (_: Exception) { break }
                    Thread({ handle(c) }, "proxy-conn").apply { isDaemon = true }.start()
                }
            }, "proxy").apply { isDaemon = true }.start()
        }

        private fun handle(client: Socket) {
            client.use { c ->
                when (mode) {
                    Mode.REFUSE -> return // closed at once: like a refused or unroutable connection
                    Mode.BLACKHOLE -> { holdUntilClosed(c); return } // nothing gets through: the phone times out
                    Mode.PASS -> Unit
                }
                // Latency and loss: the connect and the request each cross once, the answer once.
                var delay = latencyMs + if (jitterMs > 0) Random.nextLong(jitterMs + 1) else 0
                repeat(3) { delay += retransmitDelay() }
                Thread.sleep(delay)
                if (mode != Mode.PASS) { holdUntilClosed(c); return }
                Socket().use { up ->
                    up.connect(InetSocketAddress("127.0.0.1", target), 2_000)
                    val a = Thread { pump(c.getInputStream(), up.getOutputStream()) }.apply { isDaemon = true; start() }
                    pump(up.getInputStream(), c.getOutputStream())
                    a.join(1_000)
                }
            }
        }

        private fun retransmitDelay(): Long {
            var rto = 1_000L
            var total = 0L
            while (loss > 0 && Random.nextDouble() < loss && total < 8_000) { total += rto; rto *= 2 }
            return total
        }

        private fun holdUntilClosed(c: Socket) {
            runCatching { c.soTimeout = 30_000; while (c.getInputStream().read() >= 0) Unit }
        }

        private fun pump(i: InputStream, o: OutputStream) = runCatching {
            val buf = ByteArray(8192)
            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
            runCatching { o.close() }
        }

        override fun close() = server.close()
    }

    /** The app's ConnectSession read loop, without Android. */
    private class PhoneSim(val client: ConnectClient, private val t0: Long) : AutoCloseable {
        @Volatile var active = true
        @Volatile var endedAt = -1L
        @Volatile var endedWhy = ""
        @Volatile var failures = 0
        private var failingSince = 0L
        val reads = AtomicInteger()
        val failedReads = AtomicInteger()
        private val thread = Thread({ loop() }, "phone-sim").apply { isDaemon = true; start() }

        private fun nextDelay(): Long {
            if (failures == 0) return 500
            val base = (500L shl failures.coerceAtMost(2)).coerceAtMost(2_000)
            return base + Random.nextLong(base / 4 + 1)
        }

        private fun loop() {
            while (active) {
                try { Thread.sleep(nextDelay()) } catch (_: InterruptedException) { return }
                reads.incrementAndGet()
                try {
                    client.status()
                    failures = 0
                    failingSince = 0
                } catch (e: ConnectSessionEnded) {
                    end("computer ended it: ${e.reason}"); return
                } catch (e: ConnectRefused) {
                    end("refused"); return
                } catch (e: Exception) {
                    failedReads.incrementAndGet()
                    failures++
                    val now = System.nanoTime()
                    if (failingSince == 0L) failingSince = now
                    if (failures >= 6 && (now - failingSince) / 1_000_000 >= 30_000) { end("gave up"); return }
                }
            }
        }

        private fun end(why: String) {
            endedWhy = why
            endedAt = (System.nanoTime() - t0) / 1_000_000
            active = false
        }

        override fun close() { active = false; thread.interrupt() }
    }

    private val t0 = System.nanoTime()
    private fun now() = (System.nanoTime() - t0) / 1_000_000
    private val events = Collections.synchronizedList(mutableListOf<Pair<Long, PhoneLink>>())

    private fun watch(host: ConnectHost) = Thread {
        var last: PhoneLink? = null
        while (true) {
            val l = host.link.value
            if (l != last) { events += now() to l; last = l }
            try { Thread.sleep(5) } catch (_: InterruptedException) { break }
        }
    }.apply { isDaemon = true; start() }

    private fun firstAfter(t: Long, test: (PhoneLink) -> Boolean): Long? =
        synchronized(events) { events.firstOrNull { it.first >= t && test(it.second) }?.first }

    private fun reconnectsBetween(a: Long, b: Long) = synchronized(events) { events.count { it.first in a..b && it.second is PhoneLink.Reconnecting } }

    @Test fun soak() {
        assumeTrue("set -PconnectSoak=true to run the ten-minute simulation", System.getProperty("connect.soak") == "true")
        val engine = AudioEngine({ CountingSink(realTime = true) }, SilentDecoder())
        val player = PlayerController(engine)
        val udp = java.net.DatagramSocket().use { it.localPort }
        val log = Collections.synchronizedList(mutableListOf<String>())
        val host = ConnectHost("pc-1", { "Soak PC" }, player, emptyMap(), {}, httpPort = 0, discoveryPort = udp, log = { log += "${now()} $it" })
        host.start()
        val watcher = watch(host)
        val proxy = FaultProxy(host.state.value.port)
        val report = StringBuilder()
        fun line(s: String) { report.appendLine(s); println(s) }
        val token = ConnectClient("127.0.0.1", host.state.value.port).pair(host.state.value.code, "Sim phone")
        var session = 0
        fun connect(): PhoneSim {
            val c = ConnectClient("127.0.0.1", proxy.port, token, session = "soak-${++session}")
            c.play(PlayRequest(RemoteTrack("1", "Song", "A", "B", 600_000, "http://192.0.2.1/1", quality = "FLAC 16/44.1"), 0, playing = true, phoneName = "Sim phone"))
            return PhoneSim(c, t0)
        }
        fun pass() { proxy.mode = FaultProxy.Mode.PASS; proxy.latencyMs = 0; proxy.jitterMs = 0; proxy.loss = 0.0 }

        try {
            // ---- Steady conditions: any "reconnecting" here is a false alarm.
            var phone = connect()
            val conditions = listOf(
                Triple("clean network", 60_000L) { pass() },
                Triple("latency 200 +0..400 ms", 60_000L) { pass(); proxy.latencyMs = 200; proxy.jitterMs = 400 },
                Triple("latency 800 +0..1500 ms", 90_000L) { pass(); proxy.latencyMs = 800; proxy.jitterMs = 1_500 },
                Triple("10% packet loss", 90_000L) { pass(); proxy.loss = 0.10 },
                Triple("25% packet loss", 90_000L) { pass(); proxy.loss = 0.25 },
            )
            line("== steady conditions (false alarms) ==")
            for ((name, ms, setUp) in conditions) {
                setUp()
                val from = now()
                val r0 = phone.reads.get(); val f0 = phone.failedReads.get()
                Thread.sleep(ms)
                val alarms = reconnectsBetween(from, now())
                line("$name, ${ms / 1000}s: reads=${phone.reads.get() - r0} failed=${phone.failedReads.get() - f0} falseReconnecting=$alarms phoneActive=${phone.active} link=${host.link.value::class.simpleName}")
            }
            pass()
            Thread.sleep(3_000)

            // ---- Outages, then the link back.
            line("== outages ==")
            val outages = listOf(
                FaultProxy.Mode.BLACKHOLE to listOf(3_000L, 3_000L, 3_000L, 8_000L, 8_000L, 8_000L, 15_000L, 15_000L, 15_000L, 25_000L, 25_000L),
                FaultProxy.Mode.REFUSE to listOf(8_000L, 15_000L, 25_000L),
            )
            var recovered = 0
            var tried = 0
            for ((mode, lengths) in outages) for (len in lengths) {
                if (!phone.active) phone = connect()
                Thread.sleep(2_000)
                val cut = now()
                proxy.mode = mode
                Thread.sleep(len)
                val restored = now()
                pass()
                Thread.sleep(10_000)
                tried++
                val detected = firstAfter(cut) { it is PhoneLink.Reconnecting }?.takeIf { it < restored + 2_000 }
                val back = firstAfter(restored) { it is PhoneLink.Connected }
                val ok = phone.active && host.link.value is PhoneLink.Connected
                if (ok) recovered++
                line(
                    "${mode.name.lowercase()} ${len / 1000}s: reconnectingAfter=${detected?.let { "${it - cut}ms" } ?: "-"} " +
                        "connectedAgain=${if (detected == null) "(never left)" else back?.let { "${it - restored}ms after restore" } ?: "no"} " +
                        "sessionKept=$ok phoneFailedReads=${phone.failedReads.get()}",
                )
            }
            line("short outages recovered: $recovered/$tried")

            // ---- A long outage: both sides let go.
            line("== long outages ==")
            for (mode in listOf(FaultProxy.Mode.BLACKHOLE, FaultProxy.Mode.REFUSE)) {
                if (!phone.active) phone = connect()
                Thread.sleep(2_000)
                val cut = now()
                proxy.mode = mode
                Thread.sleep(50_000)
                val reconnecting = firstAfter(cut) { it is PhoneLink.Reconnecting }
                val lost = firstAfter(cut) { it == PhoneLink.None }
                line(
                    "${mode.name.lowercase()} 50s: reconnectingAfter=${reconnecting?.let { it - cut }}ms pcLostAfter=${lost?.let { it - cut }}ms " +
                        "phoneEndedAfter=${if (phone.endedAt >= 0) "${phone.endedAt - cut}ms (${phone.endedWhy})" else "still trying"} " +
                        "playerPhone=${player.phone.value}",
                )
                pass()
                Thread.sleep(3_000)
                line("  after restore: phoneActive=${phone.active} link=${host.link.value::class.simpleName}")
            }
            phone.close()
        } finally {
            proxy.close()
            watcher.interrupt()
            host.close()
            engine.stop()
            println("---- host log ----")
            synchronized(log) { log.forEach(::println) }
            println("---- report ----")
            print(report)
        }
    }
}
