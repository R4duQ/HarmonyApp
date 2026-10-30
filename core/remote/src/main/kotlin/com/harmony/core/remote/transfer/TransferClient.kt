package com.harmony.core.remote.transfer

import com.harmony.core.remote.Connect
import com.harmony.core.remote.cache.FetchResponse
import com.harmony.core.remote.cache.SocketFetcher
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

/** The computer answered [code] where something else was expected. */
class TransferHttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * The phone's side of "Send to phone" on the wire: the offers waiting for it,
 * one offer, an item's bytes, and its reports. The pairing token goes in a
 * header, never in an address (addresses end up in logs). Plain sockets, as
 * the rest of Harmony Connect: Android refuses unencrypted HTTP through its
 * usual clients, and this only talks to a paired computer on the home network.
 */
class TransferClient(
    val host: String,
    val port: Int,
    private val token: String,
    private val timeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
) {
    private val fetcher = SocketFetcher(timeoutMs, readTimeoutMs)

    /** The offers waiting for this phone. */
    fun offers(): List<TransferOffer> {
        val a = JSONObject(call("GET", TransferPaths.OFFERS, null)).getJSONArray("offers")
        return List(a.length()) { TransferOffer.fromJson(a.getJSONObject(it)) }
    }

    /** One offer as it is now; null when the computer doesn't have it (any more). */
    fun offer(id: String): TransferOffer? = try {
        TransferOffer.fromJson(JSONObject(call("GET", TransferPaths.OFFER + enc(id), null)))
    } catch (e: TransferHttpException) {
        if (e.code == 404) null else throw e
    }

    /** Sends [reports]; false when the computer has cancelled the offer (410). */
    fun report(offerId: String, reports: List<ItemReport>): Boolean = try {
        call("POST", TransferPaths.REPORT + enc(offerId), ItemReport.listToJson(reports))
        true
    } catch (e: TransferHttpException) {
        if (e.code == 410) false else throw e
    }

    /** The item's bytes from [offset] (resuming only if still [etag]). The caller closes it. */
    fun open(offerId: String, itemId: String, offset: Long, etag: String?): FetchResponse =
        fetcher.get("http://$host:$port${TransferPaths.FILE}${enc(offerId)}/${enc(itemId)}", offset, etag, mapOf(Connect.TOKEN_HEADER to token))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(method: String, path: String, body: String?): String {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val head = StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("Connection: close\r\n")
                .append(Connect.TOKEN_HEADER).append(": ").append(token).append("\r\n")
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
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer")
            var length = -1
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                    length = line.substring(colon + 1).trim().toIntOrNull() ?: -1
                }
            }
            if (length > MAX_ANSWER) throw IOException("answer too large")
            val text = String(if (length >= 0) readExactly(input, length) else input.readBytes(), Charsets.UTF_8)
            if (code !in 200..299) throw TransferHttpException(code, text.take(200))
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
            if (sb.length > 16 * 1024) throw IOException("header too long")
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw IOException("cut short")
            read += r
        }
        return buf
    }

    private companion object {
        const val MAX_ANSWER = 8 * 1024 * 1024
    }
}
