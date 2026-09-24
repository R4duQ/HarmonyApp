package com.harmony.feature.playlists

import com.harmony.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistDetailFormatTest {

    @Test
    fun `song count is singular for one`() {
        assertEquals("1 song", PlaylistDetailFormat.songCount(1))
        assertEquals("0 songs", PlaylistDetailFormat.songCount(0))
        assertEquals("24 songs", PlaylistDetailFormat.songCount(24))
    }

    @Test
    fun `summary joins count and running time`() {
        val songs = listOf(song(1, ms = 30 * 60_000L), song(2, ms = 62 * 60_000L))
        assertEquals("2 songs  ·  1hr 32min", PlaylistDetailFormat.summary(songs))
    }

    @Test
    fun `short lists read under a minute instead of 0min`() {
        assertEquals("Under 1min", PlaylistDetailFormat.totalDuration(listOf(song(1, ms = 20_000))))
        assertNull(PlaylistDetailFormat.totalDuration(emptyList()))
        assertEquals("0 songs", PlaylistDetailFormat.summary(emptyList()))
    }

    @Test
    fun `artists are ranked by frequency, ties keep playlist order`() {
        val songs = listOf(
            song(1, artist = "Air"),
            song(2, artist = "Daft Punk"),
            song(3, artist = "Daft Punk"),
            song(4, artist = "Justice"),
        )
        assertEquals("Daft Punk, Air and Justice", PlaylistDetailFormat.artists(songs))
    }

    @Test
    fun `artists beyond three are counted`() {
        val songs = listOf("A", "B", "C", "D", "E").mapIndexed { i, a -> song(i.toLong(), artist = a) }
        assertEquals("A, B, C and 2 more", PlaylistDetailFormat.artists(songs))
    }

    @Test
    fun `one and two artists read naturally`() {
        assertEquals("Air", PlaylistDetailFormat.artists(listOf(song(1, artist = "Air"), song(2, artist = "Air"))))
        assertEquals("Air and Moby", PlaylistDetailFormat.artists(listOf(song(1, artist = "Air"), song(2, artist = "Moby"))))
    }

    @Test
    fun `unknown and blank artists are skipped`() {
        assertNull(PlaylistDetailFormat.artists(listOf(song(1, artist = "<unknown>"), song(2, artist = "  "))))
        assertEquals("Moby", PlaylistDetailFormat.artists(listOf(song(1, artist = "<unknown>"), song(2, artist = "Moby"))))
    }

    @Test
    fun `cover artwork takes one cover per album, in order, up to four`() {
        val songs = listOf(
            song(1, albumId = 10, art = "a"),
            song(2, albumId = 10, art = "a"),   // same album: skipped
            song(3, albumId = 11, art = null),  // no art: skipped
            song(4, albumId = 12, art = "c"),
            song(5, albumId = 13, art = "d"),
            song(6, albumId = 14, art = "e"),
            song(7, albumId = 15, art = "f"),
        )
        assertEquals(listOf("a", "c", "d", "e"), PlaylistDetailFormat.coverArtwork(songs))
    }

    @Test
    fun `songs without an album id still count by their artwork`() {
        val songs = listOf(song(1, albumId = 0, art = "x"), song(2, albumId = 0, art = "y"), song(3, albumId = 0, art = "x"))
        assertEquals(listOf("x", "y"), PlaylistDetailFormat.coverArtwork(songs))
    }

    @Test
    fun `no artwork gives an empty mosaic`() {
        assertEquals(emptyList<String>(), PlaylistDetailFormat.coverArtwork(listOf(song(1, art = null))))
    }

    private fun song(
        id: Long,
        artist: String = "Artist",
        ms: Long = 180_000,
        albumId: Long = id,
        art: String? = "art$id",
    ) = Song(
        id = id, uri = "content://song/$id", title = "Song $id", artist = artist, album = "Album",
        albumId = albumId, albumArtist = null, composer = null, year = null, genre = null,
        discNumber = null, trackNumber = null, durationMs = ms, bitrateKbps = null,
        sampleRateHz = null, bitDepth = null, channels = null, artworkUri = art,
        embeddedLyrics = null, replayGainTrackDb = null, replayGainAlbumDb = null,
    )
}
