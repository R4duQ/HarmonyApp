package com.harmony.desktop.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Where ffmpeg and ffprobe are: next to the app (the installer bundles them), or on the PATH. */
object FfmpegTools {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    val ffmpeg: String get() = find("ffmpeg")
    val ffprobe: String get() = find("ffprobe")

    private fun find(name: String): String {
        val exe = if (windows) "$name.exe" else name
        val dirs = listOfNotNull(
            System.getProperty("harmony.ffmpeg.dir"),
            System.getProperty("compose.application.resources.dir"),
        )
        for (dir in dirs) {
            val f = File(dir, exe)
            if (f.isFile) return f.absolutePath
        }
        return exe
    }
}

/** Decoded PCM coming out of a running decode. */
interface DecodeStream : AutoCloseable {
    val input: InputStream

    /** After the input ran out: null if all went well, else what went wrong. */
    fun finish(): String?
    override fun close()
}

interface Decoder {
    /** Starts decoding [source] (a path or a URL) from [startMs] to 16-bit little-endian PCM. */
    @Throws(IOException::class)
    fun open(source: String, startMs: Long, sampleRate: Int, channels: Int): DecodeStream
}

/** ffmpeg as a decoder: any format it knows, from a file or over HTTP with seeking. */
class FfmpegDecoder(private val ffmpeg: () -> String = { FfmpegTools.ffmpeg }) : Decoder {
    override fun open(source: String, startMs: Long, sampleRate: Int, channels: Int): DecodeStream {
        val remote = source.startsWith("http://") || source.startsWith("https://")
        val args = buildList {
            add(ffmpeg())
            addAll(listOf("-hide_banner", "-loglevel", "error", "-nostdin"))
            if (remote) addAll(listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "2", "-rw_timeout", "15000000"))
            if (startMs > 0) addAll(listOf("-ss", "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0)))
            addAll(listOf("-i", source, "-vn", "-sn", "-dn"))
            addAll(listOf("-f", "s16le", "-acodec", "pcm_s16le", "-ac", channels.toString(), "-ar", sampleRate.toString(), "pipe:1"))
        }
        val process = try {
            ProcessBuilder(args).redirectInput(ProcessBuilder.Redirect.PIPE).start()
        } catch (e: IOException) {
            throw IOException("ffmpeg wasn't found. Reinstall Harmony. (${e.message})", e)
        }
        // Keep stderr drained so ffmpeg never blocks on it, and keep the last of it for errors.
        val errors = ByteArrayOutputStream()
        val errThread = Thread({
            runCatching {
                process.errorStream.use { err ->
                    val b = ByteArray(4096)
                    while (true) {
                        val n = err.read(b)
                        if (n < 0) break
                        synchronized(errors) { if (errors.size() < 16_384) errors.write(b, 0, n) }
                    }
                }
            }
        }, "ffmpeg-stderr").apply { isDaemon = true; start() }
        return object : DecodeStream {
            override val input: InputStream = process.inputStream

            override fun finish(): String? {
                process.waitFor(5, TimeUnit.SECONDS)
                errThread.join(500)
                val code = if (process.isAlive) 0 else process.exitValue()
                val text = synchronized(errors) { errors.toString(Charsets.UTF_8.name()).trim() }
                return if (code == 0) null else text.lines().lastOrNull { it.isNotBlank() } ?: "ffmpeg stopped ($code)"
            }

            override fun close() {
                runCatching { process.inputStream.close() }
                process.destroy()
                if (!runCatching { process.waitFor(300, TimeUnit.MILLISECONDS) }.getOrDefault(false)) process.destroyForcibly()
            }
        }
    }
}

/**
 * Where the PCM goes. Opened once and kept for every song: the engine only
 * writes what fits ([available]), so a write never blocks and a seek or a
 * new song can [flush] whatever is waiting at once.
 */
interface PcmSink {
    fun open(sampleRate: Int, channels: Int)
    /** Bytes that can be written now without waiting. */
    fun available(): Int
    /** Writes [len] bytes, at most [available]. */
    fun write(buf: ByteArray, off: Int, len: Int)
    fun start()
    fun stop()
    /** Throws away what was written but not played yet. */
    fun flush()
    /** Frames written but not played yet. */
    fun queuedFrames(): Long
    /** Frames actually played since [open]. */
    fun framesPlayed(): Long
    fun close()
}

/** The sound card, through Java Sound (DirectSound / WASAPI on Windows). */
class JavaSoundSink(private val bufferMs: Int = 200) : PcmSink {
    private var line: SourceDataLine? = null
    private var frameBytes = 4

    override fun open(sampleRate: Int, channels: Int) {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val l = AudioSystem.getSourceDataLine(format)
        frameBytes = 2 * channels
        l.open(format, sampleRate * frameBytes * bufferMs / 1000)
        line = l
    }

    override fun available(): Int = line?.available() ?: 0

    override fun write(buf: ByteArray, off: Int, len: Int) {
        line?.write(buf, off, len)
    }

    override fun start() { line?.start() }
    override fun stop() { line?.stop() }
    override fun flush() { line?.flush() }
    override fun queuedFrames(): Long = line?.let { ((it.bufferSize - it.available()) / frameBytes).toLong().coerceAtLeast(0) } ?: 0
    override fun framesPlayed(): Long = line?.longFramePosition ?: 0
    override fun close() {
        line?.let { runCatching { it.stop(); it.flush(); it.close() } }
        line = null
    }
}

/**
 * A sound card that only counts, for tests: with a buffer of [bufferMs] that
 * plays out in real time when [realTime], or at once when not.
 */
class CountingSink(private val realTime: Boolean = false, private val bufferMs: Int = 200) : PcmSink {
    private val lock = Object()
    private var rate = 48_000
    private var frameBytes = 4
    private var capacityFrames = 9_600L
    @Volatile private var running = false
    @Volatile private var closed = false
    /** Frames written and not yet played. */
    private var queued = 0L
    private var playedFrames = 0L
    private var lastTick = System.nanoTime()
    val written = ByteArrayOutputStream()

    /** Frames played so far. */
    val frames: Long get() = framesPlayed()

    override fun open(sampleRate: Int, channels: Int) {
        rate = sampleRate
        frameBytes = 2 * channels
        capacityFrames = rate.toLong() * bufferMs / 1000
    }

    /** Plays out what the clock says has been heard since the last look. */
    private fun tick() {
        val now = System.nanoTime()
        if (!running || closed || queued == 0L) {
            lastTick = now
            return
        }
        if (!realTime) {
            playedFrames += queued
            queued = 0
            return
        }
        val n = minOf((now - lastTick) * rate / 1_000_000_000L, queued)
        if (n > 0) {
            queued -= n
            playedFrames += n
            lastTick += n * 1_000_000_000L / rate
        }
        // Run dry: the clock starts again with the next write.
        if (queued == 0L) lastTick = now
    }

    override fun available(): Int = synchronized(lock) {
        tick()
        if (closed) 0 else ((capacityFrames - queued).coerceAtLeast(0) * frameBytes).toInt()
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        synchronized(written) { if (written.size() < 64 * 1024 * 1024) written.write(buf, off, len) }
        synchronized(lock) {
            tick()
            queued += len / frameBytes
            tick()
        }
    }

    override fun start() = synchronized(lock) { tick(); running = true; lastTick = System.nanoTime(); tick() }
    override fun stop() = synchronized(lock) { tick(); running = false }
    override fun flush() = synchronized(lock) { queued = 0 }
    override fun queuedFrames(): Long = synchronized(lock) { tick(); queued }
    override fun framesPlayed(): Long = synchronized(lock) { tick(); playedFrames }
    override fun close() = synchronized(lock) { closed = true; running = false }
}
