package com.harmony.playback.service.connect

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.harmony.core.remote.HttpResponse
import com.harmony.core.remote.MiniHttpServer
import com.harmony.core.remote.RangeSource
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Covers kept for the computer: the song playing and the next few, with room to spare. */
private const val MAX_COVERS = 48

/**
 * Serves the songs (and their covers) the computer is told to play, while a
 * Connect session lasts. Every URL carries this session's secret, so only
 * the computer it was given to can fetch anything; nothing else of the
 * phone's is reachable.
 */
internal class PhoneFileServer(private val context: Context) {
    private var server: MiniHttpServer? = null
    private val secret = UUID.randomUUID().toString().replace("-", "")
    private val tracks = ConcurrentHashMap<String, Uri>()
    /** Each song's validator, worked out once (it needs a query), so a computer can resume or check a copy it kept. */
    private val etags = ConcurrentHashMap<String, String>()
    /** The covers last shared (the song playing and what's next), the oldest let go. */
    private val covers = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > MAX_COVERS
    })

    val port: Int get() = server?.boundPort ?: -1

    fun start() {
        if (server != null) return
        server = MiniHttpServer(0, "connect-files", ::handle).start()
    }

    fun stop() {
        server?.close()
        server = null
        tracks.clear()
        etags.clear()
        covers.clear()
    }

    /** Makes [id] fetchable and returns its URL, as seen from the computer at [host] (this phone's address there). */
    fun share(id: String, uri: Uri, cover: ByteArray?, host: String): Pair<String, String?> {
        tracks[id] = uri
        if (cover != null && cover.isNotEmpty()) covers[id] = cover
        val base = "http://$host:$port"
        val key = Uri.encode(id)
        return "$base/track/$key?t=$secret" to (if (cover != null && cover.isNotEmpty()) "$base/art/$key?t=$secret" else null)
    }

    /** Makes only [id]'s cover fetchable (a song further down "up next"); null when it has none. */
    fun shareCover(id: String, cover: ByteArray?, host: String): String? {
        if (cover == null || cover.isEmpty()) return null
        covers[id] = cover
        return "http://$host:$port/art/${Uri.encode(id)}?t=$secret"
    }

    private fun handle(req: com.harmony.core.remote.HttpRequest): HttpResponse {
        if (req.query["t"] != secret) return HttpResponse.text(403, "no")
        val parts = req.path.trim('/').split('/')
        if (parts.size != 2) return HttpResponse.text(404, "no")
        val id = parts[1]
        return when (parts[0]) {
            "track" -> {
                val uri = tracks[id] ?: return HttpResponse.text(404, "no")
                val source = runCatching { sourceFor(id, uri) }.getOrNull() ?: return HttpResponse.text(404, "gone")
                HttpResponse.ranged(source, req.header("Range"), contentType(uri), req.header("If-Range"), req.header("If-None-Match"))
            }
            "art" -> covers[id]?.let { HttpResponse.bytes(200, it, "image/jpeg") } ?: HttpResponse.text(404, "no")
            else -> HttpResponse.text(404, "no")
        }
    }

    private fun sourceFor(id: String, uri: Uri): RangeSource = when (uri.scheme) {
        ContentResolver.SCHEME_FILE, null -> com.harmony.core.remote.FileRangeSource(File(uri.path ?: error("no path")))
        else -> ContentRangeSource(context.contentResolver, uri) { length -> etags.getOrPut(id) { etagFor(uri, length) } }
    }

    /**
     * A validator for a content:// song: its size, when it last changed (when
     * the provider says) and which document it is. A re-tagged or replaced file
     * gets another one.
     */
    private fun etagFor(uri: Uri, length: Long): String {
        val modified = listOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            .firstNotNullOfOrNull { column ->
                runCatching {
                    context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                    }
                }.getOrNull()
            } ?: 0L
        return "\"" + length.toString(16) + "-" + modified.toString(16) + "-" + uri.toString().hashCode().toUInt().toString(16) + "\""
    }

    private fun contentType(uri: Uri): String {
        context.contentResolver.getType(uri)?.let { return it }
        return when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "aac", "alac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}

/** A content:// document, readable from any offset. */
private class ContentRangeSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val validator: (Long) -> String,
) : RangeSource {
    override val length: Long = resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
        if (afd.length >= 0) afd.length else afd.parcelFileDescriptor.statSize
    } ?: error("can't open $uri")

    override val etag: String get() = validator(length)

    override fun open(offset: Long): InputStream {
        val afd = resolver.openAssetFileDescriptor(uri, "r") ?: error("can't open $uri")
        val stream = afd.createInputStream() as FileInputStream
        stream.channel.position(afd.startOffset.coerceAtLeast(0) + offset)
        return stream
    }
}
