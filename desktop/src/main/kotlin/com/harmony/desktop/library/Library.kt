package com.harmony.desktop.library

import com.harmony.desktop.engine.FfmpegTools
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A song in the computer's music folders. */
data class LocalTrack(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationMs: Long = 0,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
    val modified: Long = 0,
    val hasCover: Boolean = false,
) {
    val lossless: Boolean get() = codec in LOSSLESS

    /** "FLAC 24/96", "MP3 320": the sound quality at a glance. */
    val quality: String
        get() {
            val c = codec?.uppercase()?.let { if (it == "PCM_S16LE" || it == "PCM_S24LE") "WAV" else it } ?: "AUDIO"
            return if (lossless && bitDepth != null && sampleRate != null) {
                val khz = sampleRate / 1000.0
                "$c $bitDepth/${if (khz % 1.0 == 0.0) khz.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, khz)}"
            } else if (bitrateKbps != null) "$c $bitrateKbps" else c
        }

    fun toJson(): JSONObject = JSONObject()
        .put("path", path).put("title", title).put("artist", artist).put("album", album)
        .putOpt("albumArtist", albumArtist).putOpt("track", trackNumber).putOpt("disc", discNumber)
        .putOpt("year", year).putOpt("genre", genre).put("durationMs", durationMs).putOpt("codec", codec)
        .putOpt("sampleRate", sampleRate).putOpt("bitDepth", bitDepth).putOpt("kbps", bitrateKbps)
        .put("modified", modified).put("cover", hasCover)

    companion object {
        val LOSSLESS = setOf("flac", "alac", "wavpack", "ape", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "tta", "dsd_lsbf", "dsd_msbf")

        fun fromJson(o: JSONObject) = LocalTrack(
            path = o.getString("path"), title = o.optString("title"), artist = o.optString("artist"), album = o.optString("album"),
            albumArtist = o.optString("albumArtist").ifEmpty { null },
            trackNumber = o.optInt("track", -1).takeIf { it >= 0 }, discNumber = o.optInt("disc", -1).takeIf { it >= 0 },
            year = o.optInt("year", -1).takeIf { it > 0 }, genre = o.optString("genre").ifEmpty { null },
            durationMs = o.optLong("durationMs"), codec = o.optString("codec").ifEmpty { null },
            sampleRate = o.optInt("sampleRate", -1).takeIf { it > 0 }, bitDepth = o.optInt("bitDepth", -1).takeIf { it > 0 },
            bitrateKbps = o.optInt("kbps", -1).takeIf { it > 0 }, modified = o.optLong("modified"), hasCover = o.optBoolean("cover"),
        )
    }
}

/** Reads a song's tags and format with ffprobe. */
object Ffprobe {
    fun read(file: File, ffprobe: String = FfmpegTools.ffprobe): LocalTrack? = probe(file.absolutePath, ffprobe)

    /** [source] is a file path or a URL (a phone's song). */
    fun probe(source: String, ffprobe: String = FfmpegTools.ffprobe): LocalTrack? {
        val file = File(source)
        val process = runCatching {
            ProcessBuilder(
                ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", source,
            ).redirectErrorStream(false).start()
        }.getOrNull() ?: return null
        val text = process.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        process.errorStream.close()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return parse(text, file)
    }

    internal fun parse(json: String, file: File): LocalTrack? = runCatching {
        val root = JSONObject(json)
        val streams = root.optJSONArray("streams") ?: JSONArray()
        var audio: JSONObject? = null
        var cover = false
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            when (s.optString("codec_type")) {
                "audio" -> if (audio == null) audio = s
                "video" -> cover = true
            }
        }
        val a = audio ?: return null
        val format = root.optJSONObject("format") ?: JSONObject()
        val tags = HashMap<String, String>()
        fun collect(o: JSONObject?) {
            val t = o?.optJSONObject("tags") ?: return
            for (k in t.keys()) tags.putIfAbsent(k.lowercase(), t.optString(k))
        }
        collect(format)
        collect(a)
        val durationS = format.optString("duration").toDoubleOrNull() ?: a.optString("duration").toDoubleOrNull() ?: 0.0
        val codec = a.optString("codec_name").ifEmpty { null }
        val bits = a.optString("bits_per_raw_sample").toIntOrNull()?.takeIf { it > 0 }
            ?: a.optInt("bits_per_sample", 0).takeIf { it > 0 }
            ?: if (codec == "flac" || codec?.startsWith("pcm_s16") == true) 16 else null
        val bitrate = (a.optString("bit_rate").toLongOrNull() ?: format.optString("bit_rate").toLongOrNull())?.let { (it / 1000).toInt() }
        LocalTrack(
            path = file.absolutePath,
            title = tags["title"]?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            artist = tags["artist"]?.takeIf { it.isNotBlank() } ?: tags["album_artist"] ?: "Unknown artist",
            album = tags["album"]?.takeIf { it.isNotBlank() } ?: file.parentFile?.name ?: "Unknown album",
            albumArtist = tags["album_artist"] ?: tags["albumartist"],
            trackNumber = tags["track"]?.substringBefore('/')?.trim()?.toIntOrNull(),
            discNumber = tags["disc"]?.substringBefore('/')?.trim()?.toIntOrNull(),
            year = (tags["date"] ?: tags["year"])?.take(4)?.toIntOrNull(),
            genre = tags["genre"],
            durationMs = (durationS * 1000).toLong(),
            codec = codec,
            sampleRate = a.optString("sample_rate").toIntOrNull(),
            bitDepth = bits,
            bitrateKbps = bitrate,
            modified = file.lastModified(),
            hasCover = cover,
        )
    }.getOrNull()
}

/**
 * Finds every song under the chosen folders. Songs already known and not
 * changed since are taken from [known]; the rest are read with ffprobe, a
 * few at a time.
 */
class LibraryScanner(private val read: (File) -> LocalTrack? = { Ffprobe.read(it) }) {
    fun scan(folders: List<File>, known: Map<String, LocalTrack>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<LocalTrack> {
        val files = folders.flatMap { root ->
            root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }.toList()
        }.distinctBy { it.absolutePath }
        val done = AtomicInteger()
        onProgress(0, files.size)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val futures = files.map { f ->
                pool.submit<LocalTrack?> {
                    val cached = known[f.absolutePath]
                    val track = if (cached != null && cached.modified == f.lastModified()) cached else read(f)
                    onProgress(done.incrementAndGet(), files.size)
                    track
                }
            }
            return futures.mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        val EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "aiff", "aif", "wma", "ape", "wv", "dsf", "dff", "alac", "mka")
        private const val THREADS = 4
    }
}

/** The library as last scanned, kept on disk so the app opens with it at once. */
class LibraryCache(private val file: File) {
    fun load(): List<LocalTrack> = runCatching {
        val arr = JSONArray(file.readText())
        List(arr.length()) { LocalTrack.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(tracks: List<LocalTrack>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { tracks.forEach { put(it.toJson()) } }.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** Covers pulled out of the files with ffmpeg, once, into [dir]. */
class CoverCache(private val dir: File, private val ffmpeg: () -> String = { FfmpegTools.ffmpeg }) {
    fun coverFor(track: LocalTrack): File? {
        if (!track.hasCover) return null
        val out = File(dir, key(track) + ".jpg")
        if (out.isFile && out.length() > 0) return out
        dir.mkdirs()
        val ok = runCatching {
            val p = ProcessBuilder(
                ffmpeg(), "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", track.path,
                "-an", "-map", "0:v:0", "-frames:v", "1", "-vf", "scale=600:-2", out.absolutePath,
            ).redirectErrorStream(true).start()
            p.inputStream.use { it.readBytes() }
            p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        return out.takeIf { ok && it.isFile && it.length() > 0 }
    }

    private fun key(track: LocalTrack): String {
        val d = MessageDigest.getInstance("SHA-1").digest("${track.path}|${track.modified}".toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }
}
