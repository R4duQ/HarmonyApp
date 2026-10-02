package com.harmony.feature.downloads

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
 * into a Shazam fingerprint ([ShazamSignature]) and sends only that.
 *
 * Built for songs heard live, across a room or through a crowd:
 * - It records through the voice-recognition input, which Android keeps
 *   free of noise suppression and automatic gain. The ordinary microphone
 *   input on many phones filters music out as background noise.
 * - It listens for up to 20 seconds and asks after 4, 8, 12, 16 and 20,
 *   stopping at the first match. Each attempt fingerprints the latest 12
 *   seconds, so talking or applause at the start falls out of later ones.
 * - A dropped connection on one attempt doesn't end the listen.
 *
 * Recent recognitions live in this feature's own preferences.
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
     * Records up to [LISTEN_SECONDS] of audio, reporting a loudness meter
     * (0..1) to [onLevel] and the fraction recorded to [onProgress], and asks
     * Shazam at each of [CHECKPOINT_SECONDS]. [onUploading] runs when the
     * recording is complete and only the last answer is pending. Requires
     * the RECORD_AUDIO permission; cancelling the coroutine stops the
     * microphone.
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
            var answered = false
            var problem: RecognitionOutcome.Failed? = null
            for (seconds in CHECKPOINT_SECONDS) {
                val wanted = minOf(seconds * capture.rate, capture.samples.size)
                val state = capture.state.first { it.failure != null || it.ended || it.filled >= wanted }
                state.failure?.let { return@coroutineScope it }
                val final = seconds == CHECKPOINT_SECONDS.last() || state.ended
                if (final) onUploading()
                val window = capture.samples.copyOfRange(
                    RecognitionWindow.start(state.filled, capture.rate, WINDOW_SECONDS), state.filled,
                )
                if (Pcm.level(window) < SILENCE_LEVEL) {
                    if (final) break else continue
                }
                heard = true
                val signature = withContext(Dispatchers.Default) {
                    ShazamSignature.of(Pcm.normalize(Pcm.resample(window, window.size, capture.rate, ShazamSignature.SAMPLE_RATE)))
                }
                if (signature.peakCount > 0) {
                    when (val outcome = ask(signature)) {
                        is RecognitionOutcome.Match -> {
                            remember(outcome.song)
                            return@coroutineScope outcome
                        }
                        is RecognitionOutcome.Failed -> if (outcome.retryable) problem = outcome else return@coroutineScope outcome
                        RecognitionOutcome.NoMatch -> answered = true
                    }
                }
                if (final) break
            }
            when {
                problem != null && !answered -> problem
                heard -> RecognitionOutcome.NoMatch
                else -> RecognitionOutcome.Failed("Harmony couldn't hear anything. Move closer to the music and try again.")
            }
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

        /** The first input that opens: voice recognition (no noise suppression or gain), else the plain microphone. */
        @SuppressLint("MissingPermission")
        private fun open(bufferSize: Int): AudioRecord? {
            for (source in SOURCES) {
                val recorder = runCatching {
                    AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                }.getOrNull() ?: continue
                if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
                recorder.release()
            }
            return null
        }

        /** Switches off any voice processing the phone attached to this recording anyway. */
        private fun withoutVoiceProcessing(session: Int): List<AudioEffect> = buildList {
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(session) }.getOrNull()?.let(::add)
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(session) }.getOrNull()?.let(::add)
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(session) }.getOrNull()?.let(::add)
            forEach { effect -> runCatching { effect.enabled = false } }
        }

        suspend fun record(onLevel: (Float) -> Unit, onProgress: (Float) -> Unit) {
            try {
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "This phone's microphone can't record here. Close other apps that record and try again." }
                val recorder = checkNotNull(open(maxOf(minBuffer, rate / 5 * 2))) {
                    "The microphone is busy. Close other apps that record and try again."
                }
                val effects = withoutVoiceProcessing(recorder.audioSessionId)
                try {
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
                        onLevel(Pcm.meter(Pcm.level(block, read)))
                        onProgress(filled.toFloat() / samples.size)
                    }
                } finally {
                    runCatching { recorder.stop() }
                    recorder.release()
                    effects.forEach { runCatching { it.release() } }
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
            RecognitionOutcome.Failed("Harmony couldn't reach Shazam. Check your connection and try again.", retryable = true)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PREFS = "harmony_recognize"
        const val KEY_OLD_TOKEN = "audd_token"
        const val KEY_HISTORY = "history_v1"
        val RATES = intArrayOf(ShazamSignature.SAMPLE_RATE, 44_100)
        val SOURCES = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        const val LISTEN_SECONDS = 20
        val CHECKPOINT_SECONDS = intArrayOf(4, 8, 12, 16, LISTEN_SECONDS)
        /** The most sound one request fingerprints, as the Shazam app and SongRec do. */
        const val WINDOW_SECONDS = 12
        /**
         * Below this RMS (about -68 dBFS) the recording is the microphone's own
         * hiss; quiet music from across a room is well above it.
         */
        const val SILENCE_LEVEL = 0.0004f
    }
}

/** Which part of a recording one request fingerprints: the latest [windowSeconds], or all of it while shorter. */
internal object RecognitionWindow {
    fun start(filled: Int, rate: Int, windowSeconds: Int): Int = maxOf(0, filled - windowSeconds * rate)
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
