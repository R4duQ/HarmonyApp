package com.harmony.core.remote.cache

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** The start of an answer to a GET: status, headers (lower-case names) and the body to read. */
class FetchResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() = onClose()
}

/** Fetches a URL from [offset] (a resume), sending [etag] as If-Range so another version comes whole. */
fun interface Fetcher {
    fun get(url: String, offset: Long, etag: String?): FetchResponse
}

/**
 * A plain-socket HTTP/1.1 GET for the home network (Harmony's own servers).
 * Not HttpURLConnection: on Android that refuses unencrypted HTTP unless the
 * whole app allows it.
 */
class SocketFetcher(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
) : Fetcher {
    override fun get(url: String, offset: Long, etag: String?): FetchResponse = get(url, offset, etag, emptyMap())

    /** As [get], with [headers] of the caller's own (a token: kept out of the address, so out of logs). */
    fun get(url: String, offset: Long, etag: String?, headers: Map<String, String>): FetchResponse {
        val uri = URI(url)
        if (uri.scheme != "http") throw IOException("only http: $url")
        val host = uri.host ?: throw IOException("no host: $url")
        val port = if (uri.port > 0) uri.port else 80
        val target = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val head = StringBuilder()
                .append("GET ").append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("Connection: close\r\n")
            if (offset > 0) {
                head.append("Range: bytes=").append(offset).append("-\r\n")
                if (etag != null) head.append("If-Range: ").append(etag).append("\r\n")
            }
            headers.forEach { (k, v) ->
                require('\r' !in k && '\n' !in k && '\r' !in v && '\n' !in v) { "bad header" }
                head.append(k).append(": ").append(v).append("\r\n")
            }
            head.append("\r\n")
            socket.getOutputStream().apply { write(head.toString().toByteArray(Charsets.ISO_8859_1)); flush() }
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val status = readLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer from $host")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            return FetchResponse(status, headers, input) { runCatching { socket.close() } }
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw if (e is IOException) e else IOException(e)
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
}
