package com.harmony.core.remote

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * The computer's side of discovery: answers every [Connect.DISCOVER_MESSAGE]
 * with its [PcInfo], straight back to whoever asked.
 */
class DiscoveryResponder(
    private val info: () -> PcInfo,
    private val listenPort: Int = Connect.DISCOVERY_PORT,
) : Closeable {
    @Volatile private var socket: DatagramSocket? = null

    fun start(): DiscoveryResponder {
        if (socket != null) return this
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(listenPort))
        }
        socket = s
        Thread({
            val buf = ByteArray(512)
            while (!s.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (_: Exception) {
                    break
                }
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                if (text != Connect.DISCOVER_MESSAGE) continue
                val reply = info().toJson().toByteArray(Charsets.UTF_8)
                runCatching { s.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
            }
        }, "harmony-discovery").apply { isDaemon = true }.start()
        return this
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}

/** The phone's side of discovery. */
object Discovery {
    /**
     * Asks every Harmony computer on the network to answer, and collects the
     * answers for [timeoutMs]. Each computer appears once, with the address
     * it answered from.
     */
    fun find(
        timeoutMs: Int = 1_500,
        targets: List<InetAddress> = broadcastAddresses(),
        port: Int = Connect.DISCOVERY_PORT,
    ): List<PcInfo> {
        val found = LinkedHashMap<String, PcInfo>()
        DatagramSocket().use { s ->
            s.broadcast = true
            val ask = Connect.DISCOVER_MESSAGE.toByteArray(Charsets.UTF_8)
            for (target in targets) runCatching { s.send(DatagramPacket(ask, ask.size, target, port)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1024)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                s.soTimeout = left.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val host = packet.address.hostAddress ?: continue
                PcInfo.fromJson(text, host)?.let { found.putIfAbsent(it.id, it) }
            }
        }
        return found.values.toList()
    }

    /** The network's broadcast address on every interface that's up, plus the all-ones address. */
    fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNullTo(out) { it.broadcast }
        }
        out += InetAddress.getByName("255.255.255.255")
        return out.toList()
    }
}
