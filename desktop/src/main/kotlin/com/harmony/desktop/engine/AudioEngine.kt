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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/** The equalizer as the desktop app sets it: the Winamp bands, tone, Clarity and even volume. */
data class EqConfig(
    val enabled: Boolean = false,
    val winampGainsDb: List<Float> = List(10) { 0f },
    val winampPreampDb: Float = 0f,
    val clarity: Boolean = false,
    val claritySettings: ClaritySettings = ClaritySettings(),
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** Stereo width: 1 as recorded, 0 mono, up to 2. */
    val width: Float = 1f,
    /** Even volume: songs come out at a similar loudness. */
    val leveling: Boolean = false,
)

/** The song to go straight on to when this one ends; [tag] comes back in [AudioEngine.onAdvanced]. */
data class Upcoming(val source: String, val durationMs: Long, val tag: Any? = null)

/**
 * Plays one song after another: ffmpeg decodes each (any format, a file or a
 * URL the phone serves) to 48 kHz stereo PCM, which goes through the Winamp
 * equalizer, tone, Clarity and even volume, then to the sound card.
 *
 * The sound card stays open for good. A song or a seek starts a fresh decode
 * in its own thread and throws away what was waiting to be played, so it is
 * heard at once; nothing a button does waits on the decoder or the sound card.
 * Near the end of a song the next one ([upcoming]) is started in the
 * background, and it follows on without a gap.
 */
class AudioEngine(
    private val sinkFactory: () -> PcmSink = { JavaSoundSink() },
    private val decoder: Decoder = FfmpegDecoder(),
) {
    val sampleRate = 48_000
    val channels = 2
    private val frameBytes = 2 * channels

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Called on the playback thread when the last song plays to its end. */
    @Volatile var onEnded: () -> Unit = {}

    /** Asked near the end of each song: what follows it, if anything. */
    @Volatile var upcoming: () -> Upcoming? = { null }

    /** Called on the playback thread when playback has gone on to [Upcoming] by itself. */
    @Volatile var onAdvanced: (Upcoming) -> Unit = {}

    /** Called when playback of [source] broke off at [positionMs] (the file vanished, the network dropped). */
    @Volatile var onError: (source: String, positionMs: Long, message: String) -> Unit = { _, _, _ -> }

    @Volatile var volume: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f) }

    @Volatile var eq: EqConfig = EqConfig()
        set(value) {
            field = value
            winamp.setGains(value.winampGainsDb)
            tone.bassDb = value.bassDb
            tone.trebleDb = value.trebleDb
            tone.width = value.width
        }

    /** While true the music is heard without any of the sound shaping (Compare). */
    @Volatile var bypass: Boolean = false

    private val lock = Object()
    /** Held while writing to the sound card, so a flush can't be followed by stale sound. */
    private val writeLock = Object()
    @Volatile private var generation = 0L
    @Volatile private var paused = false
    @Volatile private var sink: PcmSink? = null
    private var current: DecodeStream? = null
    private var preload: Preload? = null

    @Volatile private var basePositionMs = 0L
    /** The sound card's frame count where this song's first frame is played. */
    @Volatile private var trackStartFrame = 0L
    @Volatile private var trackStarted = false
    /** Frames written to the sound card, ever: tags the analyzer's spectra. */
    @Volatile private var writtenTotal = 0L

    // The sound shaping lives across songs, like the phone's.
    private val winamp = WinampFilterBank(sampleRate, channels).also { it.setGains(eq.winampGainsDb); it.settle() }
    private val tone = ToneStage(sampleRate)
    private var clarity: ClarityLayer? = null
    private val leveler = Leveler(sampleRate)
    private val spectrumTap = SpectrumTap(sampleRate)
    private var preamp = 1f
    private var shaping = 0f
    private var levelGain = 1f
    private val frameLr = FloatArray(2)

    /** A decode started ahead of time for the song that comes next. */
    private class Preload(val upcoming: Upcoming) {
        @Volatile var stream: DecodeStream? = null
        @Volatile var failed = false
        val ready = CountDownLatch(1)
    }

    /** Loads [source] and starts it at [startMs], playing or paused. */
    fun load(source: String, startMs: Long = 0, play: Boolean = true, durationMs: Long = 0) {
        val gen: Long
        val old: DecodeStream?
        val pre: Preload?
        synchronized(lock) {
            gen = ++generation
            old = current
            current = null
            synchronized(writeLock) {
                sink?.stop()
                sink?.flush()
            }
            paused = !play
            basePositionMs = startMs.coerceAtLeast(0)
            trackStarted = false
            spectrumTap.clear()
            _state.value = EngineState(EngineStatus.LOADING, source, durationMs)
            pre = preload?.takeIf { it.upcoming.source == source && startMs <= 0 }
            if (pre != null) preload = null
        }
        old?.let(::closeLater)
        Thread({ run(gen, source, basePositionMs, durationMs, pre) }, "harmony-playback-$gen").apply { isDaemon = true }.start()
    }

    fun play() {
        synchronized(lock) {
            val s = _state.value
            when (s.status) {
                EngineStatus.PAUSED, EngineStatus.LOADING -> {
                    paused = false
                    if (trackStarted || s.status == EngineStatus.PAUSED) sink?.start()
                    if (s.status == EngineStatus.PAUSED) _state.value = s.copy(status = EngineStatus.PLAYING)
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

    /** Stops and forgets the song; the sound card stays ready. */
    fun stop() {
        val old: DecodeStream?
        val pre: Preload?
        synchronized(lock) {
            generation++
            old = current
            current = null
            pre = preload
            preload = null
            synchronized(writeLock) {
                sink?.stop()
                sink?.flush()
            }
            trackStarted = false
            _state.value = EngineState()
        }
        old?.let(::closeLater)
        pre?.let(::dropLater)
    }

    /** Stops everything and lets go of the sound card (the app is closing). */
    fun shutdown() {
        stop()
        synchronized(lock) {
            sink?.close()
            sink = null
        }
    }

    /** Where the song is, in ms. */
    fun positionMs(): Long {
        val s = sink
        if (s == null || !trackStarted) return basePositionMs
        val played = (s.framesPlayed() - trackStartFrame).coerceAtLeast(0)
        val pos = basePositionMs + played * 1000L / sampleRate
        val duration = _state.value.durationMs
        return if (duration > 0) pos.coerceAtMost(duration) else pos
    }

    /** The spectrum of what is being heard now, before and after the sound shaping. */
    fun spectrum(): SpectrumFrame? {
        val s = sink ?: return null
        return spectrumTap.at(writtenTotal - s.queuedFrames())
    }

    /** How much even volume is turning the music up or down now, in dB. */
    val levelingDb: Float get() = leveler.currentGainDb

    // ---- The playback thread -----------------------------------------------------

    private fun run(gen: Long, source: String, startMs: Long, durationMs: Long, pre: Preload?) {
        var stream = pre?.let { awaitPreload(it) } ?: try {
            decoder.open(source, startMs, sampleRate, channels)
        } catch (e: IOException) {
            fail(gen, source, durationMs, e.message ?: "Couldn't start the decoder")
            return
        }
        val out: PcmSink
        synchronized(lock) {
            if (gen != generation) {
                closeLater(stream)
                return
            }
            current = stream
            out = sink ?: try {
                sinkFactory().also { it.open(sampleRate, channels); sink = it }
            } catch (e: Exception) {
                closeLater(stream)
                fail(gen, source, durationMs, "No sound output: ${e.message}")
                return
            }
        }
        val buf = ByteArray(FRAMES_PER_CHUNK * frameBytes)
        var src = source
        var duration = durationMs
        var startOffsetMs = startMs
        var decodedFrames = 0L
        var gotAny = false
        var asked = false
        var boundaryPending = true
        try {
            while (true) {
                val n = readFully(stream.input, buf)
                if (gen != generation) return
                if (n <= 0) {
                    val error = stream.finish()
                    if (!gotAny && error != null) {
                        fail(gen, src, duration, error)
                        return
                    }
                    // Ended well before its end: the stream broke (the phone's Wi-Fi dropped,
                    // say), it didn't finish. Over the network even a quiet stop counts.
                    val reachedMs = startOffsetMs + decodedFrames * 1000L / sampleRate
                    val remote = src.startsWith("http://") || src.startsWith("https://")
                    if (duration > 0 && reachedMs < duration - CUT_SHORT_MS && (error != null || remote)) {
                        fail(gen, src, duration, error ?: "The song stopped coming")
                        return
                    }
                    // Straight on to the next song, if there is one.
                    val next = nextStream(gen)
                    if (next != null) {
                        val (up, nextStream) = next
                        synchronized(lock) {
                            if (gen != generation) {
                                closeLater(nextStream)
                                return
                            }
                            current = nextStream
                            src = up.source
                            duration = up.durationMs
                            // Its first frame is heard once the old song's tail has played out.
                            basePositionMs = 0
                            trackStartFrame = out.framesPlayed() + out.queuedFrames()
                            _state.value = EngineState(if (paused) EngineStatus.PAUSED else EngineStatus.PLAYING, src, duration)
                        }
                        closeLater(stream)
                        stream = nextStream
                        startOffsetMs = 0
                        decodedFrames = 0
                        gotAny = false
                        asked = false
                        onAdvanced(up)
                        continue
                    }
                    // The very end: let the sound card play out what it has (giving up if it stops counting).
                    var lastPlayed = out.framesPlayed()
                    var stuckSince = System.currentTimeMillis()
                    while (gen == generation && out.queuedFrames() > 0) {
                        Thread.sleep(10)
                        val now = out.framesPlayed()
                        if (now != lastPlayed || paused) {
                            lastPlayed = now
                            stuckSince = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - stuckSince > 1_000) {
                            break
                        }
                    }
                    synchronized(lock) {
                        if (gen != generation) return
                        _state.value = EngineState(EngineStatus.ENDED, src, duration)
                    }
                    onEnded()
                    return
                }
                gotAny = true
                val frames = n / frameBytes
                process(buf, frames)
                if (boundaryPending) {
                    synchronized(lock) {
                        if (gen != generation) return
                        // A seek or a new song: the sound card was emptied, so this is heard next.
                        trackStartFrame = out.framesPlayed() + out.queuedFrames()
                        trackStarted = true
                        if (!paused) out.start()
                        _state.value = EngineState(if (paused) EngineStatus.PAUSED else EngineStatus.PLAYING, src, duration)
                    }
                    boundaryPending = false
                }
                if (!writeAll(gen, out, buf, frames * frameBytes)) return
                decodedFrames += frames
                val decodedMs = startOffsetMs + decodedFrames * 1000L / sampleRate
                if (!asked && duration > 0 && decodedMs >= duration - PRELOAD_AHEAD_MS) {
                    asked = true
                    startPreload(upcoming())
                }
            }
        } catch (e: Exception) {
            if (gen == generation) fail(gen, src, duration, e.message ?: "Playback stopped")
        }
    }

    /**
     * Writes only what fits, a piece at a time, checking each time that this
     * decode is still the one wanted; while paused the sound card is full and
     * this just waits.
     */
    private fun writeAll(gen: Long, out: PcmSink, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            if (gen != generation) return false
            val room = out.available().let { it - it % frameBytes }
            if (room <= 0) {
                Thread.sleep(WAIT_MS)
                continue
            }
            val n = minOf(room, len - off)
            synchronized(writeLock) {
                if (gen != generation) return false
                out.write(buf, off, n)
                writtenTotal += n / frameBytes
            }
            off += n
        }
        return true
    }

    // ---- What comes next ------------------------------------------------------------

    /** Starts decoding [up] in the background, unless it already is. */
    private fun startPreload(up: Upcoming?) {
        if (up == null) return
        val p: Preload
        synchronized(lock) {
            if (preload?.upcoming?.source == up.source) return
            preload?.let(::dropLater)
            p = Preload(up)
            preload = p
        }
        Thread({
            try {
                p.stream = decoder.open(up.source, 0, sampleRate, channels)
            } catch (_: IOException) {
                p.failed = true
            } finally {
                p.ready.countDown()
            }
        }, "harmony-preload").apply { isDaemon = true }.start()
    }

    /** At the end of a song: the next one's decode, if there is a next one. */
    private fun nextStream(gen: Long): Pair<Upcoming, DecodeStream>? {
        // Asked again: shuffle, repeat or the queue may have changed since.
        val want = upcoming() ?: run {
            synchronized(lock) { preload?.let(::dropLater); preload = null }
            return null
        }
        val p = synchronized(lock) {
            if (gen != generation) return null
            val have = preload?.takeIf { it.upcoming.source == want.source }
            if (have == null) preload?.let(::dropLater)
            preload = null
            have
        }
        val stream = p?.let(::awaitPreload) ?: runCatching { decoder.open(want.source, 0, sampleRate, channels) }.getOrNull()
        return stream?.let { want to it }
    }

    private fun awaitPreload(p: Preload): DecodeStream? {
        p.ready.await(PRELOAD_WAIT_S, TimeUnit.SECONDS)
        return if (p.failed) null else p.stream
    }

    private fun dropLater(p: Preload) {
        Thread({
            p.ready.await(PRELOAD_WAIT_S, TimeUnit.SECONDS)
            p.stream?.close()
        }, "harmony-preload-drop").apply { isDaemon = true }.start()
    }

    private fun closeLater(stream: DecodeStream) {
        Thread({ runCatching { stream.close() } }, "harmony-decode-close").apply { isDaemon = true }.start()
    }

    private fun fail(gen: Long, source: String, durationMs: Long, message: String) {
        val at: Long
        synchronized(lock) {
            if (gen != generation) return
            at = positionMs()
            _state.value = EngineState(EngineStatus.ERROR, source, durationMs, message)
        }
        onError(source, at, message)
    }

    // ---- The sound shaping ----------------------------------------------------------

    /** Equalizer, tone, Clarity, even volume, volume and the soft limiter, in place on 16-bit PCM. */
    private fun process(buf: ByteArray, frames: Int) {
        val cfg = eq
        val on = cfg.enabled && !bypass
        val targetPreamp = if (on) 10f.pow(cfg.winampPreampDb.coerceIn(-WinampEqDesign.MAX_DB, WinampEqDesign.MAX_DB) / 20f) else 1f
        val cl = if (on && cfg.clarity) {
            (clarity ?: ClarityLayer(sampleRate, channels).also { clarity = it }).also { it.settings = cfg.claritySettings }
        } else {
            if (!cfg.clarity) clarity = null
            null
        }
        // The equalizer fades in and out over ~20 ms rather than switching.
        val targetShaping = if (on) 1f else 0f
        val toneIdle = tone.idle(on)
        val level = on && cfg.leveling
        val gain = volume * volume // a gentler curve for the slider
        var i = 0
        for (f in 0 until frames) {
            winamp.glide(EASE)
            preamp += (targetPreamp - preamp) * EASE
            shaping += (targetShaping - shaping) * EASE
            var monoIn = 0f
            var monoOut = 0f
            for (ch in 0 until channels) {
                val lo = buf[i + ch * 2].toInt() and 0xFF
                val hi = buf[i + ch * 2 + 1].toInt()
                frameLr[ch] = ((hi shl 8) or lo).toShort() / 32768f
                monoIn += frameLr[ch]
            }
            for (ch in 0 until channels) {
                val x = frameLr[ch]
                val shaped = winamp.process(x, ch) * preamp
                frameLr[ch] = if (shaping >= 0.9999f) shaped else x + (shaped - x) * shaping
            }
            if (!toneIdle) tone.process(frameLr, on)
            if (cl != null) {
                var m = 0f
                for (ch in 0 until channels) {
                    m += frameLr[ch]
                    frameLr[ch] = cl.process(frameLr[ch], ch)
                }
                cl.analyze(m / channels)
            }
            val targetLevel = if (level) leveler.next((frameLr[0] + frameLr[1]) * 0.5f) else 1f
            levelGain += (targetLevel - levelGain) * EASE
            for (ch in 0 until channels) {
                val x = frameLr[ch] * levelGain
                monoOut += x
                val y = (softLimit(x * gain) * 32767f).toInt().coerceIn(-32768, 32767)
                buf[i] = (y and 0xFF).toByte()
                buf[i + 1] = (y shr 8 and 0xFF).toByte()
                i += 2
            }
            spectrumTap.push(monoIn / channels, monoOut / channels, writtenTotal + f)
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
        return read - read % frameBytes
    }

    private companion object {
        const val FRAMES_PER_CHUNK = 1_024
        const val EASE = 0.002f
        const val KNEE = 0.92f
        const val WAIT_MS = 4L
        /** The next song starts decoding this long before the current one ends. */
        const val PRELOAD_AHEAD_MS = 20_000L
        const val PRELOAD_WAIT_S = 6L
        /** A song stopping this much before its end has broken off. */
        const val CUT_SHORT_MS = 3_000L
    }
}
