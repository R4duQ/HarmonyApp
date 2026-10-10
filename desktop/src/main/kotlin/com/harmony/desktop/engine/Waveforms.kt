package com.harmony.desktop.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * A song's shape for the player: [BARS] loudness bars across its length,
 * 0..1, worked out once by decoding it quickly at a low rate, and kept.
 */
object Waveforms {
    const val BARS = 96
    private const val RATE = 4_000
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, FloatArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 64
    })

    fun cached(source: String): FloatArray? = cache[source]

    /** The bars for [source] (a file path or a URL), or null if it can't be read. */
    suspend fun of(source: String, ffmpeg: String = FfmpegTools.ffmpeg): FloatArray? {
        cache[source]?.let { return it }
        val bars = withContext(Dispatchers.IO) { runCatching { compute(source, ffmpeg) }.getOrNull() } ?: return null
        cache[source] = bars
        return bars
    }

    internal fun compute(source: String, ffmpeg: String): FloatArray? {
        val p = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-i", source,
            "-vn", "-ac", "1", "-ar", RATE.toString(), "-f", "s16le", "-acodec", "pcm_s16le", "pipe:1",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = try {
            p.inputStream.use { it.readBytes() }
        } finally {
            if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        val n = bytes.size / 2
        if (n < BARS) return null
        val per = n / BARS
        val bars = FloatArray(BARS) { b ->
            var sum = 0.0
            val from = b * per
            for (i in from until from + per) {
                val v = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768.0
                sum += v * v
            }
            sqrt(sum / per).toFloat()
        }
        val max = bars.maxOrNull()?.takeIf { it > 0f } ?: return FloatArray(BARS) { 0.15f }
        // Louder bars stand out, quiet ones stay visible.
        return FloatArray(BARS) { (0.12f + 0.88f * (bars[it] / max)).coerceIn(0.12f, 1f) }
    }
}
