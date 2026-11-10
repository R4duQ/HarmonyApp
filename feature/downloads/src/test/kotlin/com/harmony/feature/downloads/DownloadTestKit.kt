package com.harmony.feature.downloads

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Test fixtures for the download engine: a controlled HTTP server and a stand-in for the runtime's transfer. */
internal object DownloadTestKit {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun sha256(file: File): String = sha256(file.readBytes())

    /** Bytes that start like a FLAC file and are different for each [seed]. */
    fun audio(size: Int, seed: Int): ByteArray {
        val random = java.util.Random(seed.toLong())
        return ByteArray(size).also { random.nextBytes(it); "fLaC".toByteArray().copyInto(it) }
    }
}

/**
 * One file on a local HTTP server, behaving as a provider's CDN can:
 * validators, ranges, `If-Range`, and the ways a transfer goes wrong.
 *
 * Plain sockets, one request per connection: Android unit tests compile
 * against the Android class library, which has no JDK HTTP server.
 */
internal class TestCdn(content: ByteArray, etag: String = "\"v1\"") : AutoCloseable {
    @Volatile var content: ByteArray = content
    @Volatile var etag: String = etag

    /** Cut the connection after this many body bytes of a response (once). */
    @Volatile var dropAfterBytes: Long = -1
    /** Answer every request with the whole file, as a server without range support does. */
    @Volatile var ignoreRange = false
    /** Answer a range with 206 even when `If-Range` no longer matches (a server that gets this wrong). */
    @Volatile var ignoreIfRange = false
    /** Wait this long before each response. */
    @Volatile var delayMs = 0L

    val requests = AtomicLong()
    val bodyBytesSent = AtomicLong()
    val rangeRequests = AtomicLong()

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "test-cdn").apply { isDaemon = true } }

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (closed: IOException) { break }
                workers.execute { socket.use { connection -> runCatching { answer(connection) } } }
            }
        }, "test-cdn-accept").apply { isDaemon = true }.start()
    }

    val url: String get() = "http://127.0.0.1:${server.localPort}/track.flac"

    /** The request line and headers (names in lower case), or null when the client went away first. */
    private fun readRequest(socket: Socket): Pair<String, Map<String, String>>? {
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val next = input.read()
            if (next < 0 || head.size() > 64 * 1024) return null
            head.write(next)
            matched = if (next == "\r\n\r\n"[matched].code) matched + 1 else if (next == '\r'.code) 1 else 0
        }
        val lines = head.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        return (lines.firstOrNull() ?: return null) to headers
    }

    private fun answer(socket: Socket) {
        val (requestLine, headers) = readRequest(socket) ?: return
        val output = socket.getOutputStream()
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        if (!path.startsWith("/track.flac")) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            return
        }
        requests.incrementAndGet()
        if (delayMs > 0) Thread.sleep(delayMs)
        val body = content
        val tag = etag
        val range = headers["range"]
        val ifRange = headers["if-range"]
        var from = 0
        var status = "200 OK"
        var contentRange = ""
        if (range != null) rangeRequests.incrementAndGet()
        if (range != null && !ignoreRange && (ifRange == null || ifRange == tag || ignoreIfRange)) {
            from = range.removePrefix("bytes=").substringBefore('-').toInt().coerceIn(0, body.size)
            status = "206 Partial Content"
            contentRange = "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n"
        }
        val length = body.size - from
        val drop = dropAfterBytes
        val send = if (drop in 0 until length) drop.toInt() else length
        if (drop in 0 until length) dropAfterBytes = -1
        output.write(
            ("HTTP/1.1 $status\r\n${contentRange}ETag: $tag\r\nAccept-Ranges: bytes\r\n" +
                "Content-Length: $length\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        bodyBytesSent.addAndGet(send.toLong())
        output.write(body, from, send)
        output.flush()
        // Fewer bytes than promised, then the connection goes: what a dropped link looks like.
    }

    override fun close() {
        runCatching { server.close() }
        workers.shutdownNow()
    }
}

/**
 * A stand-in for the runtime's transfer, for tests of what Harmony does
 * around it. It follows the rules of Mornye 5.3.1's own transfer
 * (rust_backend/crates/extensions/src/download.rs and download/store.rs at
 * e7a8f754): the file is staged as `<target>.partial` with
 * `<target>.partial.checkpoint.json` (resource fingerprint, validator, bytes);
 * a later call continues it only for the same resource with a validator,
 * asking `Range` with `If-Range`; a 206 must start at the asked offset and
 * carry the same validator, a 200 starts the file again, and a finished file
 * is published under its own name with nothing left beside it.
 *
 * It is NOT the runtime: it proves Harmony keeps, reuses and discards the
 * right files and counts the right bytes. The runtime's own behaviour is
 * covered by its tests upstream and has to be confirmed on a phone.
 */
internal class FakeTransfer(
    private val chunkBytes: Int = 64 * 1024,
    /** Called before each chunk is written (a test's clock, a cancel check, a pause). */
    private val beforeChunk: (receivedBytes: Long) -> Unit = {},
) {
    class Result(val success: Boolean, val errorType: String, val file: File?, val resumed: Boolean, val message: String = "") {
        fun toJson(): String = JSONObject().put("success", success).put("error_type", errorType)
            .put("file_path", file?.path.orEmpty()).put("resumed", resumed).put("error", message).toString()
    }

    fun download(url: String, target: File, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }): Result {
        val staged = File(target.path + ".partial")
        val checkpoint = File(staged.path + ".checkpoint.json")
        val fingerprint = URL(url).let { "${it.protocol}://${it.authority}${it.path}" }
        val saved = runCatching { JSONObject(checkpoint.readText()) }.getOrNull()
            ?.takeIf { it.optString("fingerprint") == fingerprint && it.optString("validator").isNotEmpty() && it.optLong("bytes") > 0 }
        if (saved == null) { staged.delete(); checkpoint.delete() }
        var validator = saved?.optString("validator").orEmpty()
        var bytes = minOf(saved?.optLong("bytes") ?: 0L, if (staged.isFile) staged.length() else 0L)
        val resumeFrom = bytes

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            connection.setRequestProperty("If-Range", validator)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) return Result(false, if (status == 429) "rate_limit" else "transient_network", null, false, "HTTP $status")
            val served = connection.getHeaderField("ETag").orEmpty()
            if (resumeFrom > 0 && status == 206) {
                val range = connection.getHeaderField("Content-Range").orEmpty()
                if (!range.startsWith("bytes $resumeFrom-")) return Result(false, "integrity_failed", null, false, "unexpected Content-Range")
                if (served.isNotEmpty() && served != validator) return Result(false, "integrity_failed", null, false, "response validator changed")
            }
            if (resumeFrom > 0 && status == 200) bytes = 0   // The server sent the whole file: start it again.
            if (served.isNotEmpty()) validator = served
            val length = connection.contentLengthLong
            val total = if (status == 206) resumeFrom + length else length
            java.io.RandomAccessFile(staged, "rw").use { file ->
                file.setLength(bytes)
                file.seek(bytes)
                onProgress(bytes, total)
                val buffer = ByteArray(chunkBytes)
                connection.inputStream.use { input ->
                    while (true) {
                        beforeChunk(bytes)
                        val read = try { input.read(buffer) } catch (dropped: IOException) {
                            return Result(false, "transient_network", null, false, dropped.message ?: "connection dropped")
                        }
                        if (read < 0) break
                        file.write(buffer, 0, read)
                        bytes += read
                        checkpoint.writeText(JSONObject().put("fingerprint", fingerprint).put("validator", validator).put("bytes", bytes).toString())
                        onProgress(bytes, total)
                    }
                }
            }
            if (total > 0 && bytes < total) return Result(false, "transient_network", null, false, "connection dropped at $bytes of $total")
            target.delete()
            check(staged.renameTo(target))
            checkpoint.delete()
            return Result(true, "", target, resumed = resumeFrom > 0 && status == 206)
        } catch (failure: IOException) {
            return Result(false, "transient_network", null, false, failure.message ?: "network")
        } finally {
            connection.disconnect()
        }
    }
}
