package com.harmony.domain.playback

import com.harmony.core.model.Song
import java.io.File
import java.security.MessageDigest

/**
 * Online recommendations in the queue ("Mix din piesa asta" with online
 * songs): files prepared through the download engine into Harmony's own
 * online cache, played from there, never imported into the library (no
 * library row, no 24-D analysis, no play counts).
 *
 * Such a song is named by its key `ytm:<videoId>`, which is also its player
 * media id, so the queue can be rebuilt after the app was closed. Its
 * [Song.id] is below zero, made from the key (never a library id, and never
 * a computer's song id: those are made from `pc:` keys). Its [Song.uri] is
 * the prepared file, `file://<cache>/online-mix/<videoId>.flac`, which the
 * player and Connect read as any local file.
 */
object OnlineSongs {
    const val KEY_PREFIX = "ytm:"
    const val CACHE_DIR = "online-mix"
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val FILE = Regex("/$CACHE_DIR/([A-Za-z0-9_-]{11})\\.flac$")

    fun keyFor(videoId: String): String {
        require(VIDEO_ID.matches(videoId)) { "not a video id" }
        return KEY_PREFIX + videoId
    }

    fun isOnlineKey(mediaId: String): Boolean = mediaId.startsWith(KEY_PREFIX)

    fun videoIdOf(key: String): String? = key.removePrefix(KEY_PREFIX).takeIf { isOnlineKey(key) && VIDEO_ID.matches(it) }

    /** The song id of the online song [key]: below zero, the same for the same key. */
    fun idFor(key: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return -((v ushr 1) or 1L)
    }

    /** The prepared file of [videoId] in [cacheRoot] (the app's cache directory). */
    fun fileFor(cacheRoot: File, videoId: String): File = File(File(cacheRoot, CACHE_DIR), "${videoIdOf(keyFor(videoId))}.flac")

    /** The key of a prepared file's address, or null for any other address. */
    fun keyOf(uri: String): String? = FILE.find(uri.substringBefore('?'))?.groupValues?.get(1)?.let(::keyFor)

    fun isOnline(song: Song): Boolean = keyOf(song.uri) != null

    /** The phone's [Song] for the prepared file at [uri]. */
    fun song(videoId: String, uri: String, title: String, artist: String, album: String, durationMs: Long,
             sampleRateHz: Int? = null, bitDepth: Int? = null): Song = Song(
        id = idFor(keyFor(videoId)),
        uri = uri,
        title = title.ifBlank { "Unknown" },
        artist = artist,
        album = album,
        albumId = 0,
        albumArtist = null,
        composer = null,
        year = null,
        genre = null,
        discNumber = null,
        trackNumber = null,
        durationMs = durationMs.coerceAtLeast(0),
        bitrateKbps = null,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        channels = null,
        artworkUri = null,
        embeddedLyrics = null,
        replayGainTrackDb = null,
        replayGainAlbumDb = null,
    )
}
