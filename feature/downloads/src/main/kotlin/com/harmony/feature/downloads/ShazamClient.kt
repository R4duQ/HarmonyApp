package com.harmony.feature.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.UUID

/**
 * One question to Shazam: this fingerprint, which song? Only the fingerprint
 * ([ShazamSignature]) is sent, never audio. Used by the Recognize screen
 * (the microphone) and by online mixes (a file whose tags don't find it).
 */
internal object ShazamClient {
    suspend fun ask(signature: ShazamSignature): RecognitionOutcome = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val body = ShazamApi.body(signature, now, TimeZone.getDefault().id).toByteArray()
        val url = ShazamApi.url(UUID.randomUUID().toString().uppercase(), UUID.randomUUID().toString())
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Language", "en_US")
            setRequestProperty("Accept", "*/*")
            // The phone's own Dalvik user agent, as the Shazam app sends.
            System.getProperty("http.agent")?.takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            ShazamApi.parse(code, text, now)
        } catch (e: java.io.IOException) {
            RecognitionOutcome.Failed("Harmony couldn't reach Shazam. Check your connection and try again.", retryable = true)
        } finally {
            connection.disconnect()
        }
    }

}
