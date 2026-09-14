package com.harmony.feature.discover.swipe

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.harmony.core.common.coroutines.DispatcherProvider
import com.harmony.core.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** What the export produced, or why it could not. */
sealed interface ExportResult {
    data class Success(
        val uri: Uri,
        val displayName: String,
        val trackCount: Int,
        /** Tracks whose audio could not be read; the archive omits them. */
        val skipped: List<SkippedTrack>,
    ) : ExportResult

    data class Failure(val reason: String, val cause: Throwable? = null) : ExportResult
}

data class SkippedTrack(val title: String, val artist: String, val reason: String)

/** Progress for the UI, emitted per track. */
data class ExportProgress(val completed: Int, val total: Int, val currentTitle: String) {
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
}

/** A track that is definitely inside the archive, at a known path. */
data class ArchivedTrack(val song: Song, val archivePath: String)

/**
 * Packages a finished swipe session into an archive in the Downloads folder.
 *
 * Everything here operates on files the listener already owns and that
 * Harmony already plays. Nothing is fetched from anywhere.
 *
 * Audio is read through the ContentResolver, not `java.io.File`, because
 * [Song.uri] is whatever the scanner recorded — a `content://` MediaStore row
 * for most of the library, a `file://` path for side-loaded folders. The
 * resolver handles both; File handles only one, and would fail on exactly the
 * tracks that came from MediaStore.
 *
 * On miniz/libzip: not used. `java.util.zip.ZipOutputStream` streams straight
 * into the destination, so a 50-track FLAC playlist never holds more than one
 * 64 KB buffer in memory whether it totals 300 MB or 3 GB. A native zip
 * library would add an NDK dependency and a JNI bridge to do the same work at
 * the same speed — this is bound by disk I/O, not by zip bookkeeping.
 */
@Singleton
class SwipePlaylistExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    // DispatcherProvider, not a bare CoroutineDispatcher: that is the project
    // convention (see core:common) and the only shape Hilt has a binding for.
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Writes the archive.
     *
     * Cancellation-aware: a caller that navigates away mid-export cancels the
     * coroutine and the partial archive is removed, rather than left in
     * Downloads as a file that looks complete and is not.
     */
    suspend fun export(
        tracks: List<Song>,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult = withContext(dispatchers.io) {
        if (tracks.isEmpty()) {
            return@withContext ExportResult.Failure("There are no tracks to export.")
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val displayName = "Harmony_Swipe_${tracks.size}_Tracks_$stamp.zip"

        val destination = try {
            openDestination(displayName)
        } catch (t: Throwable) {
            return@withContext ExportResult.Failure(
                "Could not create the file in Downloads: ${t.message ?: "permission denied"}", t,
            )
        }

        val skipped = mutableListOf<SkippedTrack>()
        val archived = mutableListOf<ArchivedTrack>()

        try {
            destination.stream.use { raw ->
                ZipOutputStream(raw.buffered(BUFFER_BYTES)).use { zip ->
                    zip.setLevel(NO_COMPRESSION)

                    val usedNames = HashSet<String>()
                    tracks.forEachIndexed { index, song ->
                        coroutineContext.ensureActive()
                        onProgress(ExportProgress(archived.size, tracks.size, song.title))

                        val entryName = uniqueEntryName(index, song, usedNames)
                        val opened = runCatching {
                            context.contentResolver.openInputStream(Uri.parse(song.uri))
                        }.getOrNull()

                        if (opened == null) {
                            // The library row outlived the file — a moved SD
                            // card, a revoked permission, a deleted download.
                            // Skip and report: 49 of 50 is still a playlist.
                            skipped += SkippedTrack(song.title, song.artist, "file missing or unreadable")
                            return@forEachIndexed
                        }

                        try {
                            zip.putNextEntry(ZipEntry("audio/$entryName"))
                            opened.buffered(BUFFER_BYTES).use { it.copyTo(zip, BUFFER_BYTES) }
                            zip.closeEntry()
                            archived += ArchivedTrack(song, "audio/$entryName")
                        } catch (t: Throwable) {
                            // Cancellation has to escape; one bad file must not.
                            coroutineContext.ensureActive()
                            runCatching { zip.closeEntry() }
                            skipped += SkippedTrack(song.title, song.artist, t.message ?: "read failed")
                        } finally {
                            runCatching { opened.close() }
                        }
                    }

                    if (archived.isEmpty()) {
                        error("None of the ${tracks.size} audio files could be read")
                    }

                    // Written last, from what actually made it in, so the
                    // playlist can never point at an entry the archive lacks.
                    zip.writeText("playlist.m3u8", buildM3u(archived))
                    zip.writeText("manifest.json", buildManifest(archived, skipped))
                    onProgress(ExportProgress(archived.size, tracks.size, ""))
                }
            }
            destination.publish()
            ExportResult.Success(destination.uri, displayName, archived.size, skipped)
        } catch (t: Throwable) {
            destination.discard()
            if (t is CancellationException) throw t
            ExportResult.Failure(t.message ?: "The archive could not be written.", t)
        }
    }

    /**
     * Extended M3U pointing at the archive's own copies.
     *
     * Relative paths on purpose: the point of the archive is that it unzips
     * anywhere — another phone, a desktop, a car stick — and still plays.
     * Absolute paths into Harmony's storage would be dead on arrival, and
     * `content://` URIs are meaningless off-device.
     */
    fun buildM3u(tracks: List<ArchivedTrack>): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#PLAYLIST:Harmony Swipe Session")
        tracks.forEach { entry ->
            val seconds = (entry.song.durationMs / 1000).toInt()
            appendLine("#EXTINF:$seconds,${entry.song.artist} - ${entry.song.title}")
            appendLine(entry.archivePath)
        }
    }

    /**
     * Sidecar metadata, including what was left out and why.
     *
     * Hand-rolled rather than pulled through a serializer: it is two levels of
     * known-shape data, and adding kotlinx-serialization to this module for it
     * would be a dependency per string.
     */
    fun buildManifest(tracks: List<ArchivedTrack>, skipped: List<SkippedTrack>): String = buildString {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        appendLine("{")
        appendLine("""  "generator": "Harmony Swipe to Playlist",""")
        appendLine("""  "generatedAt": ${generatedAt.jsonQuoted()},""")
        appendLine("""  "trackCount": ${tracks.size},""")
        appendLine("""  "tracks": [""")
        tracks.forEachIndexed { index, entry ->
            val comma = if (index == tracks.lastIndex) "" else ","
            appendLine("    {")
            appendLine("""      "position": ${index + 1},""")
            appendLine("""      "title": ${entry.song.title.jsonQuoted()},""")
            appendLine("""      "artist": ${entry.song.artist.jsonQuoted()},""")
            appendLine("""      "album": ${entry.song.album.jsonQuoted()},""")
            appendLine("""      "durationMs": ${entry.song.durationMs},""")
            appendLine("""      "archivePath": ${entry.archivePath.jsonQuoted()}""")
            appendLine("    }$comma")
        }
        appendLine("  ],")
        appendLine("""  "skipped": [""")
        skipped.forEachIndexed { index, entry ->
            val comma = if (index == skipped.lastIndex) "" else ","
            appendLine(
                "    { \"title\": ${entry.title.jsonQuoted()}, " +
                    "\"artist\": ${entry.artist.jsonQuoted()}, " +
                    "\"reason\": ${entry.reason.jsonQuoted()} }$comma"
            )
        }
        appendLine("  ]")
        append("}")
    }

    /**
     * A filesystem-safe, collision-free name inside the archive.
     *
     * Prefixed with the running order because a zip has no inherent ordering
     * and most players sort alphabetically — without the prefix, an unzipped
     * playlist plays in a different order than the one that was curated.
     */
    private fun uniqueEntryName(index: Int, song: Song, used: MutableSet<String>): String {
        val base = "%02d - %s - %s"
            .format(Locale.US, index + 1, song.artist.sanitized(), song.title.sanitized())
            .take(MAX_NAME_CHARS)
        val ext = extensionOf(song)
        var candidate = "$base.$ext"
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "$base ($suffix).$ext"
            suffix++
        }
        return candidate
    }

    /**
     * File extension for an archived copy.
     *
     * Tries the URI's own last path segment first (right for `file://` and for
     * MediaStore rows that kept a display name), then the resolver's MIME
     * type, then falls back to something generic. Getting this wrong only
     * costs a player its format hint — every target reads the container header
     * anyway — so an approximate answer beats failing the export.
     */
    private fun extensionOf(song: Song): String {
        val uri = runCatching { Uri.parse(song.uri) }.getOrNull()
        val fromPath = uri?.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
        if (fromPath != null) return fromPath.lowercase(Locale.US)

        val mime = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
        return mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "audio"
    }

    /** Strips what Windows, macOS and FAT each disallow, so the archive is portable. */
    private fun String.sanitized(): String =
        replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_")
            .trim()
            .ifBlank { "Unknown" }

    private fun String.jsonQuoted(): String = buildString {
        append('"')
        this@jsonQuoted.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun ZipOutputStream.writeText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /**
     * Where the archive goes.
     *
     * On API 29+ this is MediaStore with IS_PENDING set, so a half-written
     * archive is invisible to other apps until [Destination.publish] and no
     * storage permission is needed. Below 29 it is a plain file in the public
     * Downloads directory, which is what that platform supports.
     */
    private fun openDestination(displayName: String): Destination =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused to create the download entry")
            Destination(
                uri = uri,
                stream = resolver.openOutputStream(uri) ?: error("Could not open the download for writing"),
                publish = {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null, null,
                    )
                },
                discard = { runCatching { resolver.delete(uri, null, null) } },
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, displayName)
            Destination(
                uri = Uri.fromFile(file),
                stream = file.outputStream(),
                publish = {},
                discard = { runCatching { file.delete() } },
            )
        }

    private class Destination(
        val uri: Uri,
        val stream: OutputStream,
        val publish: () -> Unit,
        val discard: () -> Unit,
    )

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /**
         * Deflate level 0. FLAC and MP3 are already compressed; running
         * DEFLATE over them typically saves well under 1% in exchange for a
         * full pass over every byte of a multi-gigabyte archive.
         */
        const val NO_COMPRESSION = 0

        /** Long enough to stay readable, short enough for path limits. */
        const val MAX_NAME_CHARS = 90
    }
}
