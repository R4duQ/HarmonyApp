package com.harmony.feature.downloads

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens through the microphone and asks Shazam what is playing.
 *
 * The recording never leaves the phone or touches storage: Harmony turns it
 * into a Shazam fingerprint ([ShazamSignature]) and sends only that. It asks
 * after 4, 8 and 12 seconds of sound and stops at the first match, so a clear
 * song is usually named before the 12 seconds are up. Recent recognitions
 * live in this feature's own preferences.
 */
@Singleton
class SongRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _history = MutableStateFlow<List<RecognizedSong>>(emptyList())
    val history: StateFlow<List<RecognizedSong>> = _history.asStateFlow()

    private var loaded = false

    /** Reads the stored history once; cheap to call again. */
    fun load() {
        if (loaded) return
        loaded = true
        _history.value = RecognitionHistoryCodec.decode(prefs.getString(KEY_HISTORY, null))
        // Left over from the AudD version, which kept a token here.
        if (prefs.contains(KEY_OLD_TOKEN)) prefs.edit().remove(KEY_OLD_TOKEN).apply()
    }

    fun forget(song: RecognizedSong) {
        _history.value = _history.value.filterNot { it == song }
        prefs.edit().putString(KEY_HISTORY, RecognitionHistoryCodec.encode(_history.value)).apply()
    }

    private fun remember(song: RecognizedSong) {
        _history.value = RecognitionHistoryCodec.add(_history.value, song)
        prefs.edit().putString(KEY_HISTORY, RecognitionHistoryCodec.encode(_history.value)).apply()
    }

    /**
     * Records up to [LISTEN_SECONDS] of audio, reporting loudness to [onLevel]
     * and the fraction recorded to [onProgress], and asks Shazam at each of
     * [CHECKPOINT_SECONDS]. [onUploading] runs when the recording is complete
     * and only the last answer is pending. Requires the RECORD_AUDIO
     * permission; cancelling the coroutine stops the microphone.
     */
    suspend fun recognize(
        onLevel: (Float) -> Unit,
        onProgress: (Float) -> Unit,
        onUploading: () -> Unit,
    ): RecognitionOutcome = coroutineScope {
        load()
        val capture = Capture()
        val microphone = launch(Dispatchers.IO) { capture.record(onLevel, onProgress) }
        try {
            var heard = false
            for (seconds in CHECKPOINT_SECONDS) {
                val wanted = minOf(seconds * capture.rate, capture.samples.size)
                val state = capture.state.first { it.failure != null || it.ended || it.filled >= wanted }
                state.failure?.let { return@coroutineScope it }
                val final = seconds == CHECKPOINT_SECONDS.last() || state.ended
                if (final) onUploading()
                if (Pcm.level(capture.samples, state.filled) < SILENCE_LEVEL) {
                    if (final) break else continue
                }
                heard = true
                val signature = withContext(Dispatchers.Default) {
                    ShazamSignature.of(Pcm.resample(capture.samples, state.filled, capture.rate, ShazamSignature.SAMPLE_RATE))
                }
                if (signature.peakCount > 0) {
                    when (val outcome = ask(signature)) {
                        is RecognitionOutcome.Match -> {
                            remember(outcome.song)
                            return@coroutineScope outcome
                        }
                        is RecognitionOutcome.Failed -> return@coroutineScope outcome
                        RecognitionOutcome.NoMatch -> Unit
                    }
                }
                if (final) break
            }
            if (heard) RecognitionOutcome.NoMatch
            else RecognitionOutcome.Failed("Harmony couldn't hear anything. Move closer to the music and try again.")
        } finally {
            microphone.cancel()
        }
    }

    private class CaptureState(val filled: Int = 0, val ended: Boolean = false, val failure: RecognitionOutcome.Failed? = null)

    /** One recording, shared with the checkpoints as it fills. */
    private class Capture {
        /** 16 kHz when the phone offers it (nearly all do), else 44.1 kHz, resampled later. */
        val rate: Int = RATES.firstOrNull {
            AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        } ?: RATES.last()
        val samples = ShortArray(rate * LISTEN_SECONDS)
        val state = MutableStateFlow(CaptureState())

        @SuppressLint("MissingPermission")
        suspend fun record(onLevel: (Float) -> Unit, onProgress: (Float) -> Unit) {
            try {
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "This phone's microphone can't record here. Close other apps that record and try again." }
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate / 5 * 2),
                )
                try {
                    check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone is busy. Close other apps that record and try again." }
                    val block = ShortArray(rate / 20) // 50 ms
                    recorder.startRecording()
                    var filled = 0
                    while (filled < samples.size) {
                        currentCoroutineContext().ensureActive()
                        val read = recorder.read(block, 0, minOf(block.size, samples.size - filled))
                        if (read <= 0) { check(read == 0) { "The microphone stopped unexpectedly." }; continue }
                        System.arraycopy(block, 0, samples, filled, read)
                        filled += read
                        state.value = CaptureState(filled)
                        onLevel(Pcm.level(block, read))
                        onProgress(filled.toFloat() / samples.size)
                    }
                } finally {
                    runCatching { recorder.stop() }
                    recorder.release()
                }
                state.value = CaptureState(state.value.filled, ended = true)
            } catch (e: SecurityException) {
                state.value = CaptureState(failure = RecognitionOutcome.Failed("Harmony needs the microphone to hear the song. Allow it and try again."))
            } catch (e: IllegalStateException) {
                state.value = CaptureState(failure = RecognitionOutcome.Failed(e.message ?: "The microphone is busy. Close other apps that record and try again."))
            } catch (e: IllegalArgumentException) {
                state.value = CaptureState(failure = RecognitionOutcome.Failed("This phone's microphone can't record here. Close other apps that record and try again."))
            }
        }
    }

    private suspend fun ask(signature: ShazamSignature): RecognitionOutcome = withContext(Dispatchers.IO) {
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
            RecognitionOutcome.Failed("Harmony couldn't reach Shazam. Check your connection and try again.")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PREFS = "harmony_recognize"
        const val KEY_OLD_TOKEN = "audd_token"
        const val KEY_HISTORY = "history_v1"
        val RATES = intArrayOf(ShazamSignature.SAMPLE_RATE, 44_100)
        const val LISTEN_SECONDS = 12
        val CHECKPOINT_SECONDS = intArrayOf(4, 8, LISTEN_SECONDS)
        /** Below this RMS the recording is silence; Shazam would only say "no match". */
        const val SILENCE_LEVEL = 0.002f
    }
}

/**
 * Carries a recognised song from the Recognize screen into the Downloads
 * screen's SpotiFLAC search, which owns its own ViewModel.
 */
@Singleton
class RecognitionHandoff @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun searchSpotiFlac(query: String) { _pending.value = query }

    /** Returns the pending query once, then clears it. */
    fun take(): String? = _pending.value.also { _pending.value = null }
}
