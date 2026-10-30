package com.harmony.desktop.connect

import com.harmony.core.remote.FileRangeSource
import com.harmony.core.remote.ServedMedia
import com.harmony.desktop.engine.FfmpegTools
import com.harmony.desktop.library.LocalTrack
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The computer's own songs, for a phone (Universal Queue, stage 6): the
 * phone plays them, or keeps them in its cache, without importing them into
 * its library.
 *
 * A song is known by an id made from its path and when it last changed
 * ([idFor]): never the path itself, and a re-tagged file is another id, so a
 * copy the phone kept from before is never mistaken for the new one.
 *
 * Formats the phone can play (MP3, FLAC, AAC, Ogg, Opus, WAV) are served as
 * they are. Others (APE, WavPack, WMA, DSD, ALAC, AIFF...) are converted once
 * to FLAC with ffmpeg (lossless, the same quality; DSD down to 24-bit/88.2 kHz)
 * and kept in [converted]; meanwhile the phone is told to ask again
 * ([ServedMedia.Preparing]). One conversion at a time; the folder holds at
 * most [convertedLimitBytes], the least recently served going first.
 */
class PcMedia(
    private val tracks: () -> List<LocalTrack>,
    private val converted: File,
    private val ffmpeg: () -> String = { FfmpegTools.ffmpeg },
    private val convertedLimitBytes: Long = 2L * 1024 * 1024 * 1024,
    private val log: (String) -> Unit = {},
) : (String) -> ServedMedia, AutoCloseable {
    private val lock = Any()
    private var indexed: List<LocalTrack>? = null
    private var byId: Map<String, LocalTrack> = emptyMap()
    private val converting = HashSet<String>()
    private val failed = HashSet<String>()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "harmony-convert").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    init {
        converted.mkdirs()
        // Leftovers of a conversion cut short.
        converted.listFiles { f -> f.name.endsWith(".tmp.flac") }?.forEach { it.delete() }
    }

    /** The track of [id], if it is in the library now. */
    fun track(id: String): LocalTrack? = synchronized(lock) { index()[id] }

    /** The local file of the shared queue's song [id] (for playing it here), if it is in the library. */
    fun file(id: String): File? = track(id)?.let { File(it.path) }?.takeIf { it.isFile }

    override fun invoke(id: String): ServedMedia {
        val track = track(id) ?: return ServedMedia.Missing
        val file = File(track.path)
        if (!file.isFile) return ServedMedia.Missing
        if (phoneCanPlay(track)) return ServedMedia.Ready(FileRangeSource(file), contentTypeOf(file.extension))
        val out = File(converted, "$id.flac")
        synchronized(lock) {
            if (out.isFile && out.length() > 0) {
                out.setLastModified(System.currentTimeMillis())
                return ServedMedia.Ready(FileRangeSource(out), "audio/flac")
            }
            if (id in failed) return ServedMedia.Missing
            if (converting.add(id)) worker.execute { convert(id, track, out) }
        }
        return ServedMedia.Preparing(RETRY_AFTER_S)
    }

    private fun convert(id: String, track: LocalTrack, out: File) {
        val tmp = File(converted, "$id.tmp.flac")
        val started = System.nanoTime()
        val ok = runCatching {
            val dsd = track.codec?.startsWith("dsd") == true
            val deep = dsd || (track.bitDepth ?: 16) > 16
            val args = mutableListOf(ffmpeg(), "-v", "error", "-nostdin", "-y", "-i", track.path, "-map", "0:a:0", "-map_metadata", "0", "-c:a", "flac")
            if (deep) args += listOf("-sample_fmt", "s32", "-bits_per_raw_sample", "24")
            if (dsd) args += listOf("-ar", "88200")
            args += tmp.absolutePath
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val said = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                error("took too long")
            }
            if (p.exitValue() != 0 || tmp.length() == 0L) error("ffmpeg ${p.exitValue()}: ${said.take(300)}")
            makeRoom(tmp.length())
            if (!tmp.renameTo(out)) error("could not keep it")
        }
        synchronized(lock) {
            converting -= id
            ok.onFailure {
                failed += id
                tmp.delete()
                log("convert: ${track.path} failed (${it.message})")
            }
            ok.onSuccess { log("convert: ${track.path} -> FLAC in ${(System.nanoTime() - started) / 1_000_000} ms") }
        }
    }

    private fun makeRoom(incoming: Long) {
        val files = converted.listFiles { f -> f.name.endsWith(".flac") && !f.name.endsWith(".tmp.flac") }?.sortedBy { it.lastModified() } ?: return
        var used = files.sumOf { it.length() } + incoming
        for (f in files) {
            if (used <= convertedLimitBytes) break
            used -= f.length()
            f.delete()
        }
    }

    /** The id index, rebuilt when the library list changes. */
    private fun index(): Map<String, LocalTrack> {
        val now = tracks()
        if (now !== indexed) {
            indexed = now
            byId = now.associateBy(::idFor)
        }
        return byId
    }

    override fun close() {
        worker.shutdownNow()
    }

    companion object {
        const val RETRY_AFTER_S = 2

        /** A song's id for phones: its path and when it last changed, hashed. */
        fun idFor(track: LocalTrack): String {
            val d = MessageDigest.getInstance("SHA-256").digest("${track.path}|${track.modified}".toByteArray())
            return d.take(12).joinToString("") { "%02x".format(it) }
        }

        /** What Android's player reads by itself (Media3: MP3, FLAC, AAC, Ogg Vorbis/Opus, WAV). */
        fun phoneCanPlay(track: LocalTrack): Boolean {
            val ext = File(track.path).extension.lowercase()
            val codec = track.codec?.lowercase()
            return when (ext) {
                "mp3" -> codec == null || codec == "mp3"
                "flac" -> codec == null || codec == "flac"
                "ogg", "oga", "opus" -> codec == null || codec == "vorbis" || codec == "opus" || codec == "flac"
                "aac" -> true
                "m4a", "mp4" -> codec == "aac" // ALAC in an .m4a needs a decoder the phone may not have
                "wav" -> codec == null || codec.startsWith("pcm_")
                else -> false
            }
        }

        fun contentTypeOf(ext: String): String = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
