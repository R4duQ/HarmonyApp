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
    private val covers = ConcurrentHashMap<String, ByteArray>()

    val port: Int get() = server?.boundPort ?: -1

    fun start() {
        if (server != null) return
        server = MiniHttpServer(0, "connect-files", ::handle).start()
    }

    fun stop() {
        server?.close()
        server = null
        tracks.clear()
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

    private fun handle(req: com.harmony.core.remote.HttpRequest): HttpResponse {
        if (req.query["t"] != secret) return HttpResponse.text(403, "no")
        val parts = req.path.trim('/').split('/')
        if (parts.size != 2) return HttpResponse.text(404, "no")
        val id = parts[1]
        return when (parts[0]) {
            "track" -> {
                val uri = tracks[id] ?: return HttpResponse.text(404, "no")
                val source = runCatching { sourceFor(uri) }.getOrNull() ?: return HttpResponse.text(404, "gone")
                HttpResponse.ranged(source, req.header("Range"), contentType(uri))
            }
            "art" -> covers[id]?.let { HttpResponse.bytes(200, it, "image/jpeg") } ?: HttpResponse.text(404, "no")
            else -> HttpResponse.text(404, "no")
        }
    }

    private fun sourceFor(uri: Uri): RangeSource = when (uri.scheme) {
        ContentResolver.SCHEME_FILE, null -> com.harmony.core.remote.FileRangeSource(File(uri.path ?: error("no path")))
        else -> ContentRangeSource(context.contentResolver, uri)
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
private class ContentRangeSource(private val resolver: ContentResolver, private val uri: Uri) : RangeSource {
    override val length: Long = resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
        if (afd.length >= 0) afd.length else afd.parcelFileDescriptor.statSize
    } ?: error("can't open $uri")

    override fun open(offset: Long): InputStream {
        val afd = resolver.openAssetFileDescriptor(uri, "r") ?: error("can't open $uri")
        val stream = afd.createInputStream() as FileInputStream
        stream.channel.position(afd.startOffset.coerceAtLeast(0) + offset)
        return stream
    }
}
