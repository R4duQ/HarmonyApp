package com.harmony.core.remote

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Refused by the computer: not paired any more, or a wrong code. */
class ConnectRefused(message: String) : IOException(message)

/**
 * The phone's side of Connect: talks to one computer. Blocking; call it off
 * the main thread.
 */
class ConnectClient(
    val host: String,
    val port: Int,
    @Volatile var token: String? = null,
    private val timeoutMs: Int = 4_000,
) {
    fun info(): PcInfo = PcInfo.fromJson(call("GET", Connect.PATH_INFO, null, auth = false), host)
        ?: throw IOException("not a Harmony computer")

    /** Pairs with the code shown on the computer; returns the token (also kept in [token]). */
    fun pair(code: String, phoneName: String): String {
        val reply = JSONObject(call("POST", Connect.PATH_PAIR, PairRequest(code, phoneName).toJson(), auth = false))
        return reply.getString("token").also { token = it }
    }

    fun play(request: PlayRequest): RemoteStatus? = RemoteStatus.fromJson(call("POST", Connect.PATH_PLAY, request.toJson()))

    fun control(request: ControlRequest): RemoteStatus? = RemoteStatus.fromJson(call("POST", Connect.PATH_CONTROL, request.toJson()))

    fun volume(volume: Float) {
        call("POST", Connect.PATH_VOLUME, VolumeRequest(volume).toJson())
    }

    fun status(): RemoteStatus = RemoteStatus.fromJson(call("GET", Connect.PATH_STATUS, null))
        ?: throw IOException("unreadable status")

    fun disconnect() {
        runCatching { call("POST", Connect.PATH_DISCONNECT, "{}") }
    }

    /**
     * One request over a plain socket. Not HttpURLConnection: on Android that
     * refuses unencrypted HTTP unless the whole app allows it, and this only
     * ever talks to a computer on the home network.
     */
    private fun call(method: String, path: String, body: String?, auth: Boolean = true): String {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val head = StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("Connection: close\r\n")
            if (auth) token?.let { head.append(Connect.TOKEN_HEADER).append(": ").append(it).append("\r\n") }
            if (bytes != null) {
                head.append("Content-Type: application/json; charset=utf-8\r\n")
                head.append("Content-Length: ").append(bytes.size).append("\r\n")
            }
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (bytes != null) out.write(bytes)
            out.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val status = readLine(input) ?: throw IOException("no answer")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer: $status")
            var length = -1
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                    length = line.substring(colon + 1).trim().toIntOrNull() ?: -1
                }
            }
            val text = String(if (length >= 0) readExactly(input, length) else input.readBytes(), Charsets.UTF_8)
            if (code == 403) throw ConnectRefused(text.ifEmpty { "refused" })
            if (code !in 200..299) throw IOException("HTTP $code: $text")
            return text
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }
}
