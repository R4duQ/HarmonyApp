package com.harmony.domain.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val timeoutMs: Int,
)

/** [headers] keys are lower-case. */
data class HttpReply(val status: Int, val headers: Map<String, String>, val body: String)

/**
 * One HTTP exchange. Network errors are thrown as [IOException] (a
 * [java.net.SocketTimeoutException] for a deadline); cancelling the caller
 * aborts the exchange.
 */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpReply
}

/**
 * [HttpTransport] on [HttpURLConnection] (plain JVM and Android alike).
 * Cancellation disconnects the socket, so a cancelled request does not keep
 * a thread blocked until its timeout. Answers larger than [maxBodyBytes] are
 * refused (an InnerTube page is a few hundred KB).
 */
class UrlConnectionTransport(
    private val executor: Executor = Dispatchers.IO.asExecutor(),
    private val maxBodyBytes: Int = 4 * 1024 * 1024,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpReply = suspendCancellableCoroutine { cont ->
        val connection = URL(request.url).openConnection() as HttpURLConnection
        cont.invokeOnCancellation { runCatching { connection.disconnect() } }
        executor.execute {
            try {
                val reply = exchange(connection, request)
                cont.resume(reply)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun exchange(connection: HttpURLConnection, request: HttpRequest): HttpReply {
        connection.requestMethod = request.method
        connection.connectTimeout = request.timeoutMs
        connection.readTimeout = request.timeoutMs
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        if (request.body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use { it.write(request.body) }
        }
        val status = connection.responseCode
        val headers = connection.headerFields.orEmpty()
            .filterKeys { it != null }
            .mapKeys { it.key.lowercase() }
            .mapValues { it.value.lastOrNull().orEmpty() }
        val raw = (if (status >= 400) connection.errorStream else connection.inputStream) ?: return HttpReply(status, headers, "")
        val stream = if (headers["content-encoding"].equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
        return HttpReply(status, headers, stream.use { readCapped(it) })
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBodyBytes) throw IOException("answer larger than $maxBodyBytes bytes")
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
