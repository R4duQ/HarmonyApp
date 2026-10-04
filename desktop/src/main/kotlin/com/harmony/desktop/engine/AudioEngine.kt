package com.harmony.desktop.engine

import com.harmony.core.model.ClaritySettings
import com.harmony.core.model.WinampEqDesign
import com.harmony.playback.service.player.ClarityLayer
import com.harmony.playback.service.player.WinampFilterBank
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import kotlin.math.pow
import kotlin.math.tanh

enum class EngineStatus { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

data class EngineState(
    val status: EngineStatus = EngineStatus.IDLE,
    /** What is loaded: a file path or a URL. */
    val source: String? = null,
    val durationMs: Long = 0,
    val error: String? = null,
)

/** The equalizer as the desktop app sets it: the Winamp bands, and Clarity. */
data class EqConfig(
    val enabled: Boolean = false,
    val winampGainsDb: List<Float> = List(10) { 0f },
    val winampPreampDb: Float = 0f,
    val clarity: Boolean = false,
    val claritySettings: ClaritySettings = ClaritySettings(),
)

/**
 * Plays one song at a time: ffmpeg decodes it (any format, a file or a URL
 * the phone serves) to 48 kHz stereo PCM, which goes through the Winamp
 * equalizer and Clarity exactly as on the phone, then to the sound card.
 *
 * Each load or seek starts a fresh decode in its own thread; a newer one
 * makes the older one stop. Pausing stops the sound card at once and lets
 * the decode wait. The position is what the sound card has actually played.
 */
class AudioEngine(
    private val sinkFactory: () -> PcmSink = { JavaSoundSink() },
    private val decoder: Decoder = FfmpegDecoder(),
) {
    val sampleRate = 48_000
    val channels = 2

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Called on the playback thread when a song plays to its end. */
    @Volatile var onEnded: () -> Unit = {}

    @Volatile var volume: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f) }

    @Volatile var eq: EqConfig = EqConfig()
        set(value) {
            field = value
            winamp.setGains(value.winampGainsDb)
        }

    private val lock = Object()
    private var generation = 0L
    @Volatile private var paused = false
    private var sink: PcmSink? = null
    private var process: DecodeStream? = null
    @Volatile private var basePositionMs = 0L

    // The equalizer lives across songs, like the phone's.
    private val winamp = WinampFilterBank(sampleRate, channels).also { it.setGains(eq.winampGainsDb); it.settle() }
    private var clarity: ClarityLayer? = null
    private var preamp = 1f

    /** Loads [source] and starts it at [startMs], playing or paused. */
    fun load(source: String, startMs: Long = 0, play: Boolean = true, durationMs: Long = 0) {
        val gen: Long
        synchronized(lock) {
            gen = ++generation
            stopCurrent()
            paused = !play
            basePositionMs = startMs.coerceAtLeast(0)
            _state.value = EngineState(EngineStatus.LOADING, source, durationMs)
            lock.notifyAll()
        }
        Thread({ run(gen, source, basePositionMs, durationMs) }, "harmony-playback-$gen").apply { isDaemon = true }.start()
    }

    fun play() {
        synchronized(lock) {
            val s = _state.value
            when (s.status) {
                EngineStatus.PAUSED, EngineStatus.LOADING -> {
                    paused = false
                    sink?.start()
                    if (s.status == EngineStatus.PAUSED) _state.value = s.copy(status = EngineStatus.PLAYING)
                    lock.notifyAll()
                }
                EngineStatus.ENDED -> s.source?.let { load(it, 0, true, s.durationMs) }
                else -> {}
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            paused = true
            sink?.stop()
            val s = _state.value
            if (s.status == EngineStatus.PLAYING) _state.value = s.copy(status = EngineStatus.PAUSED)
        }
    }

    fun seek(positionMs: Long) {
        val s = _state.value
        val source = s.source ?: return
        load(source, positionMs, play = !paused && s.status != EngineStatus.ENDED, durationMs = s.durationMs)
    }

    fun stop() {
        synchronized(lock) {
            generation++
            stopCurrent()
            _state.value = EngineState()
            lock.notifyAll()
        }
    }

    /** Where the song is, in ms. */
    fun positionMs(): Long {
        val s = sink ?: return basePositionMs
        val played = s.framesPlayed() * 1000L / sampleRate
        val pos = basePositionMs + played
        val duration = _state.value.durationMs
        return if (duration > 0) pos.coerceAtMost(duration) else pos
    }

    private fun stopCurrent() {
        process?.close()
        process = null
        sink?.close()
        sink = null
    }

    private fun run(gen: Long, source: String, startMs: Long, durationMs: Long) {
        val stream = try {
            decoder.open(source, startMs, sampleRate, channels)
        } catch (e: IOException) {
            fail(gen, source, durationMs, e.message ?: "Couldn't start the decoder")
            return
        }
        val out: PcmSink
        synchronized(lock) {
            if (gen != generation) {
                stream.close()
                return
            }
            process = stream
            out = try {
                sinkFactory().also { it.open(sampleRate, channels) }
            } catch (e: Exception) {
                stream.close()
                fail(gen, source, durationMs, "No sound output: ${e.message}")
                return
            }
            sink = out
        }
        val frameBytes = 2 * channels
        val buf = ByteArray(FRAMES_PER_CHUNK * frameBytes)
        var started = false
        var gotAny = false
        try {
            while (true) {
                val n = readFully(stream.input, buf)
                if (gen != generation) return
                if (n <= 0) break
                gotAny = true
                val frames = n / frameBytes
                process(buf, frames)
                synchronized(lock) {
                    if (gen != generation) return
                    if (!started) {
                        started = true
                        if (!paused) out.start()
                        _state.value = EngineState(if (paused) EngineStatus.PAUSED else EngineStatus.PLAYING, source, durationMs)
                    }
                }
                // Blocks while the sound card's buffer is full, and while paused.
                out.write(buf, 0, frames * frameBytes)
            }
            if (gen != generation) return
            val error = stream.finish()
            if (!gotAny && error != null) {
                fail(gen, source, durationMs, error)
                return
            }
            out.drain()
            synchronized(lock) {
                if (gen != generation) return
                _state.value = EngineState(EngineStatus.ENDED, source, durationMs)
            }
            onEnded()
        } catch (e: Exception) {
            if (gen == generation) fail(gen, source, durationMs, e.message ?: "Playback stopped")
        }
    }

    private fun fail(gen: Long, source: String, durationMs: Long, message: String) {
        synchronized(lock) {
            if (gen != generation) return
            _state.value = EngineState(EngineStatus.ERROR, source, durationMs, message)
        }
    }

    /** Equalizer, Clarity, volume and the soft limiter, in place on 16-bit PCM. */
    private fun process(buf: ByteArray, frames: Int) {
        val cfg = eq
        val targetPreamp = if (cfg.enabled) 10f.pow(cfg.winampPreampDb.coerceIn(-WinampEqDesign.MAX_DB, WinampEqDesign.MAX_DB) / 20f) else 1f
        val cl = if (cfg.enabled && cfg.clarity) {
            (clarity ?: ClarityLayer(sampleRate, channels).also { clarity = it }).also { it.settings = cfg.claritySettings }
        } else {
            clarity = null
            null
        }
        val gain = volume * volume // a gentler curve for the slider
        var i = 0
        for (f in 0 until frames) {
            if (cfg.enabled) winamp.glide(EASE)
            preamp += (targetPreamp - preamp) * EASE
            var mono = 0f
            for (ch in 0 until channels) {
                val lo = buf[i].toInt() and 0xFF
                val hi = buf[i + 1].toInt()
                var x = ((hi shl 8) or lo).toShort() / 32768f
                if (cfg.enabled) x = winamp.process(x, ch) * preamp
                if (cl != null) {
                    mono += x
                    x = cl.process(x, ch)
                }
                val y = (softLimit(x * gain) * 32767f).toInt().coerceIn(-32768, 32767)
                buf[i] = (y and 0xFF).toByte()
                buf[i + 1] = (y shr 8 and 0xFF).toByte()
                i += 2
            }
            cl?.analyze(mono / channels)
        }
    }

    private fun softLimit(x: Float): Float {
        val a = if (x >= 0f) x else -x
        if (a <= KNEE) return x
        val shaped = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x >= 0f) shaped else -shaped
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        // Whole frames only.
        return read - read % (2 * channels)
    }

    private companion object {
        const val FRAMES_PER_CHUNK = 2_048
        const val EASE = 0.002f
        const val KNEE = 0.92f
    }
}
