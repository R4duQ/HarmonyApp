package com.harmony.core.remote

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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

    private fun call(method: String, path: String, body: String?, auth: Boolean = true): String {
        val connection = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            if (auth) token?.let { connection.setRequestProperty(Connect.TOKEN_HEADER, it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (code == 403) throw ConnectRefused(text.ifEmpty { "refused" })
            if (code !in 200..299) throw IOException("HTTP $code: $text")
            return text
        } finally {
            connection.disconnect()
        }
    }
}
