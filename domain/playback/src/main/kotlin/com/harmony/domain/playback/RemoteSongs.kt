package com.harmony.domain.playback

import com.harmony.core.model.Song
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Songs of a computer's library in the phone's queue (Universal Queue, stage
 * 6): put there from the computer, played from the phone's cache or straight
 * from the computer, never imported into the phone's library (no library row,
 * no 24-D analysis, no play counts).
 *
 * Such a song is named by its shared queue content key ("pc:<computer>:<id>"),
 * which is also its player media id. Its [Song.id] is below zero, made from the
 * key, so it can never be a library id, and its [Song.uri] uses [SCHEME], which
 * the player's data source knows to read from the cache or the computer.
 */
object RemoteSongs {
    const val SCHEME = "harmony-remote"
    private const val PREFIX = "$SCHEME://media/"

    /** A shared queue content key of a computer's song. */
    fun isRemoteKey(mediaId: String): Boolean = mediaId.startsWith("pc:")

    /** A song id of such a song (below zero; library ids never are). */
    fun isRemote(songId: Long): Boolean = songId < 0

    fun isRemote(song: Song): Boolean = song.uri.startsWith(PREFIX)

    /** The song id of the computer's song [key]: below zero, the same for the same key. */
    fun idFor(key: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return -((v ushr 1) or 1L)
    }

    fun uriFor(key: String): String = PREFIX + URLEncoder.encode(key, "UTF-8")

    /** The content key in a [uriFor] address, or null for any other. */
    fun keyOf(uri: String): String? =
        uri.takeIf { it.startsWith(PREFIX) }?.let { runCatching { URLDecoder.decode(it.removePrefix(PREFIX), "UTF-8") }.getOrNull() }

    /** The computer a key names ("pc:<computer>:<id>"), and its song id there. */
    fun homeOf(key: String): String? = key.split(':').takeIf { it.size >= 3 && it[0] == "pc" }?.get(1)
    fun localIdOf(key: String): String? = key.split(':', limit = 3).takeIf { it.size == 3 && it[0] == "pc" }?.get(2)

    /** The phone's [Song] for the computer's song [key]. */
    fun song(key: String, title: String, artist: String, album: String, durationMs: Long): Song = Song(
        id = idFor(key),
        uri = uriFor(key),
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
        sampleRateHz = null,
        bitDepth = null,
        channels = null,
        artworkUri = null,
        embeddedLyrics = null,
        replayGainTrackDb = null,
        replayGainAlbumDb = null,
    )
}
