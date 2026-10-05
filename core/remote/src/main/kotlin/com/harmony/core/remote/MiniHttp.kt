package com.harmony.core.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small HTTP/1.1 server, enough for Harmony Connect on both ends: JSON
 * requests on the computer, and song files with byte ranges on the phone.
 * One thread per connection, every response closes its connection; no
 * libraries, so it runs the same on Android and on the desktop.
 */
class MiniHttpServer(
    private val port: Int,
    private val name: String,
    private val handler: (HttpRequest) -> HttpResponse,
) : Closeable {
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** The port actually bound: [port], or a free one when [port] is 0. */
    val boundPort: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start(): MiniHttpServer {
        if (socket != null) return this
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        socket = server
        val threads = Executors.newCachedThreadPool(daemonThreads(name))
        pool = threads
        threads.execute {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                threads.execute { serve(client) }
            }
        }
        return this
    }

    @Synchronized
    override fun close() {
        runCatching { socket?.close() }
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(s.getInputStream())
                val request = readRequest(input, s.inetAddress) ?: return
                val response = try {
                    handler(request)
                } catch (e: Exception) {
                    HttpResponse.text(500, e.message ?: "error")
                }
                writeResponse(s.getOutputStream(), request, response)
            } catch (_: SocketException) {
                // The other end went away mid-song (a seek, a skip): nothing to do.
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 1 shl 20

        internal fun readRequest(input: InputStream, from: InetAddress?): HttpRequest? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null
            val headers = mutableMapOf<String, String>()
            var total = 0
            while (true) {
                val line = readLine(input) ?: return null
                if (line.isEmpty()) break
                total += line.length
                if (total > MAX_HEADER_BYTES) return null
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val target = parts[1]
            val q = target.indexOf('?')
            val path = if (q >= 0) target.substring(0, q) else target
            val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
            return HttpRequest(parts[0].uppercase(), decode(path), query, headers, body, from)
        }

        private fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
                if (out.size() > MAX_HEADER_BYTES) return null
            }
            return out.toString(Charsets.ISO_8859_1.name())
        }

        private fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
            val eq = it.indexOf('=')
            if (eq < 0) decode(it) to "" else decode(it.substring(0, eq)) to decode(it.substring(eq + 1))
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        internal fun writeResponse(out: OutputStream, request: HttpRequest, response: HttpResponse) {
            val head = StringBuilder()
            head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            val headers = LinkedHashMap(response.headers)
            headers["Connection"] = "close"
            headers["Content-Length"] = response.length.toString()
            if (response.contentType != null) headers["Content-Type"] = response.contentType
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (request.method != "HEAD") response.writeBody(out)
            out.flush()
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            204 -> "No Content"
            206 -> "Partial Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            else -> "Error"
        }

        private fun daemonThreads(name: String): ThreadFactory {
            val n = AtomicInteger()
            return ThreadFactory { r -> Thread(r, "$name-${n.incrementAndGet()}").apply { isDaemon = true } }
        }
    }
}

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Lower-case names. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val from: InetAddress?,
) {
    val text: String get() = String(body, Charsets.UTF_8)
    fun header(name: String): String? = headers[name.lowercase()]
}

/** A response with a body of known [length], written by [writeBody]. */
class HttpResponse(
    val status: Int,
    val contentType: String?,
    val length: Long,
    val headers: Map<String, String> = emptyMap(),
    val writeBody: (OutputStream) -> Unit,
) {
    companion object {
        fun bytes(status: Int, bytes: ByteArray, contentType: String?) =
            HttpResponse(status, contentType, bytes.size.toLong()) { it.write(bytes) }

        fun json(json: String, status: Int = 200) = bytes(status, json.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
        fun text(status: Int, text: String) = bytes(status, text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
        fun empty(status: Int = 204) = HttpResponse(status, null, 0) {}

        /**
         * [source] served whole, or the part a `Range: bytes=a-b` header asks
         * for (206 with Content-Range), which is what lets the computer seek
         * without fetching the song from the start.
         */
        fun ranged(source: RangeSource, rangeHeader: String?, contentType: String): HttpResponse {
            val size = source.length
            val accept = mapOf("Accept-Ranges" to "bytes")
            val range = rangeHeader?.let { parseRange(it, size) }
            if (rangeHeader != null && range == null) {
                return HttpResponse(416, null, 0, accept + ("Content-Range" to "bytes */$size")) {}
            }
            val (from, to) = range ?: (0L to size - 1)
            val length = (to - from + 1).coerceAtLeast(0)
            val headers = if (range != null) accept + ("Content-Range" to "bytes $from-$to/$size") else accept
            return HttpResponse(if (range != null) 206 else 200, contentType, length, headers) { out ->
                source.open(from).use { input -> copy(input, out, length) }
            }
        }

        /** (first, last) inclusive, or null when the range can't be served. */
        internal fun parseRange(header: String, size: Long): Pair<Long, Long>? {
            if (!header.startsWith("bytes=") || size <= 0) return null
            val spec = header.removePrefix("bytes=").split(',').first().trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            return when {
                a.isEmpty() -> {
                    // Suffix: the last N bytes.
                    val n = b.toLongOrNull() ?: return null
                    if (n <= 0) null else (size - n).coerceAtLeast(0) to size - 1
                }
                else -> {
                    val first = a.toLongOrNull() ?: return null
                    val last = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(size - 1)
                    if (first >= size || last < first) null else first to last
                }
            }
        }

        private fun copy(input: InputStream, out: OutputStream, length: Long) {
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }
}

/** Bytes that can be read from any offset: a file, or a content:// document on Android. */
interface RangeSource {
    val length: Long
    fun open(offset: Long): InputStream
}

/** A [RangeSource] over a local file. */
class FileRangeSource(private val file: java.io.File) : RangeSource {
    override val length: Long get() = file.length()
    override fun open(offset: Long): InputStream {
        val raf = java.io.RandomAccessFile(file, "r")
        raf.seek(offset)
        return java.nio.channels.Channels.newInputStream(raf.channel)
    }
}
