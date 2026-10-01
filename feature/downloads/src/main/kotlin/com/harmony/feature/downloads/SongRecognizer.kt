package com.harmony.feature.downloads

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens through the microphone and asks AudD what is playing.
 *
 * The recording never touches storage: ten seconds of 16-bit mono, halved
 * to 22.05 kHz (about 440 KB as WAV), goes straight into the request. The
 * AudD token and the recent recognitions live in this feature's own
 * preferences, so nothing here needs an account beyond AudD's.
 */
@Singleton
class SongRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _history = MutableStateFlow<List<RecognizedSong>>(emptyList())
    val history: StateFlow<List<RecognizedSong>> = _history.asStateFlow()

    private val _token = MutableStateFlow("")
    /** The user's AudD token, or blank for AudD's free daily requests. */
    val token: StateFlow<String> = _token.asStateFlow()

    private var loaded = false

    /** Reads the stored token and history once; cheap to call again. */
    fun load() {
        if (loaded) return
        loaded = true
        _token.value = prefs.getString(KEY_TOKEN, null).orEmpty()
        _history.value = RecognitionHistoryCodec.decode(prefs.getString(KEY_HISTORY, null))
    }

    fun setToken(value: String) {
        _token.value = value.trim()
        prefs.edit().putString(KEY_TOKEN, _token.value).apply()
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
     * Records [LISTEN_MS] of audio, reporting loudness to [onLevel] and the
     * fraction recorded to [onProgress], then sends it to AudD. Requires the
     * RECORD_AUDIO permission; cancelling the coroutine stops the microphone.
     */
    suspend fun recognize(onLevel: (Float) -> Unit, onProgress: (Float) -> Unit, onUploading: () -> Unit): RecognitionOutcome {
        load()
        val samples = try {
            record(onLevel, onProgress)
        } catch (e: SecurityException) {
            return RecognitionOutcome.Failed("Harmony needs the microphone to hear the song. Allow it and try again.")
        } catch (e: IllegalStateException) {
            return RecognitionOutcome.Failed(e.message ?: "The microphone is busy. Close other apps that record and try again.")
        }
        if (Pcm.level(samples) < SILENCE_LEVEL) {
            return RecognitionOutcome.Failed("Harmony couldn't hear anything. Move closer to the music and try again.")
        }
        onUploading()
        val wav = Pcm.wav(Pcm.decimateBy2(samples), SAMPLE_RATE / 2)
        val outcome = upload(wav)
        if (outcome is RecognitionOutcome.Match) remember(outcome.song)
        return outcome
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(onLevel: (Float) -> Unit, onProgress: (Float) -> Unit): ShortArray = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "This phone's microphone can't record at 44.1 kHz." }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE / 5 * 2),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone is busy. Close other apps that record and try again." }
        val total = SAMPLE_RATE * LISTEN_MS / 1000
        val samples = ShortArray(total)
        val block = ShortArray(SAMPLE_RATE / 20) // 50 ms
        try {
            recorder.startRecording()
            var filled = 0
            while (filled < total) {
                ensureActive()
                val read = recorder.read(block, 0, minOf(block.size, total - filled))
                if (read <= 0) { check(read == 0) { "The microphone stopped unexpectedly." }; continue }
                System.arraycopy(block, 0, samples, filled, read)
                filled += read
                onLevel(Pcm.level(block, read))
                onProgress(filled.toFloat() / total)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        samples
    }

    private suspend fun upload(wav: ByteArray): RecognitionOutcome = withContext(Dispatchers.IO) {
        val boundary = "harmony-${UUID.randomUUID()}"
        val body = AudDApi.multipartBody(boundary, _token.value, wav)
        val connection = (URL(AudDApi.ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "Harmony/1.0 Android SongRecognition")
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && text.isBlank()) {
                RecognitionOutcome.Failed("The recognition service didn't answer (HTTP $code). Try again in a moment.")
            } else {
                AudDApi.parse(text, System.currentTimeMillis())
            }
        } catch (e: java.io.IOException) {
            RecognitionOutcome.Failed("Harmony couldn't reach the recognition service. Check your connection and try again.")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PREFS = "harmony_recognize"
        const val KEY_TOKEN = "audd_token"
        const val KEY_HISTORY = "history_v1"
        const val SAMPLE_RATE = 44_100
        const val LISTEN_MS = 10_000
        /** Below this RMS the recording is silence; AudD would only say "no match". */
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
