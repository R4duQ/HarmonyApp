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

    @Test
    fun `quality reads hi-res, lossless and lossy, a low bitrate first`() {
        assertEquals(PlaylistDetailFormat.Quality.HI_RES, PlaylistDetailFormat.quality(song(1, bits = 24, rate = 96_000, kbps = 2800)))
        assertEquals(PlaylistDetailFormat.Quality.LOSSLESS, PlaylistDetailFormat.quality(song(2, bits = 16, rate = 44_100, kbps = 900)))
        // An MP3 that Android reports as 16-bit is still lossy.
        assertEquals(PlaylistDetailFormat.Quality.LOSSY, PlaylistDetailFormat.quality(song(3, bits = 16, rate = 44_100, kbps = 320)))
        assertEquals(PlaylistDetailFormat.Quality.UNKNOWN, PlaylistDetailFormat.quality(song(4)))
        assertEquals("320", PlaylistDetailFormat.badge(song(3, bits = 16, rate = 44_100, kbps = 320)))
        assertEquals("HI-RES", PlaylistDetailFormat.badge(song(1, bits = 24, rate = 96_000)))
        assertNull(PlaylistDetailFormat.badge(song(4)))
    }

    @Test
    fun `quality mix and summary name the majority and the best file`() {
        val songs = listOf(
            song(1, bits = 24, rate = 96_000), song(2, bits = 16, rate = 44_100),
            song(3, bits = 16, rate = 44_100), song(4, kbps = 256),
        )
        assertEquals(
            listOf(PlaylistDetailFormat.Quality.HI_RES to 1, PlaylistDetailFormat.Quality.LOSSLESS to 2, PlaylistDetailFormat.Quality.LOSSY to 1),
            PlaylistDetailFormat.qualityMix(songs),
        )
        assertEquals("Mostly lossless · up to 24-bit / 96 kHz", PlaylistDetailFormat.qualitySummary(songs))
        assertEquals("All lossy", PlaylistDetailFormat.qualitySummary(listOf(song(1, kbps = 320))))
        assertNull(PlaylistDetailFormat.qualitySummary(listOf(song(1))))
    }

    @Test
    fun `stats count distinct artists and albums`() {
        val songs = listOf(song(1, artist = "Air", albumId = 7), song(2, artist = "air ", albumId = 7), song(3, artist = "Justice", albumId = 8))
        assertEquals(PlaylistDetailFormat.Stats(3, 540_000, 2, 2), PlaylistDetailFormat.stats(songs))
        assertEquals("1h 29m", PlaylistDetailFormat.compactDuration(89 * 60_000L))
        assertEquals("42m", PlaylistDetailFormat.compactDuration(42 * 60_000L + 5_000))
    }

    @Test
    fun `genres are counted case-insensitively, most first`() {
        val songs = listOf(song(1, genre = "Pop"), song(2, genre = "Rock"), song(3, genre = "pop"), song(4, genre = " "), song(5))
        assertEquals(listOf("Pop" to 2, "Rock" to 1), PlaylistDetailFormat.genres(songs))
    }

    @Test
    fun `years are one bar a year, or five years a bar over a long span`() {
        val short = PlaylistDetailFormat.years(listOf(song(1, year = 2016), song(2, year = 2019), song(3, year = 2019)))!!
        assertEquals(2016, short.from)
        assertEquals(listOf(2016 to 1, 2017 to 0, 2018 to 0, 2019 to 2), short.bars)
        assertEquals("2016 – 2019 · most from 2019", PlaylistDetailFormat.yearsCaption(short))
        val long = PlaylistDetailFormat.years(listOf(song(1, year = 1971), song(2, year = 1999), song(3, year = 2021)))!!
        assertEquals(5, long.step)
        assertEquals(1970, long.bars.first().first)
        assertEquals(11, long.bars.size)
        assertNull(PlaylistDetailFormat.years(listOf(song(1))))
    }

    @Test
    fun `top artists come with a cover, most songs first`() {
        val songs = listOf(song(1, artist = "Air", art = null), song(2, artist = "Justice"), song(3, artist = "Air", art = "a3"))
        assertEquals(
            listOf(PlaylistDetailFormat.ArtistShare("Air", 2, "a3"), PlaylistDetailFormat.ArtistShare("Justice", 1, "art2")),
            PlaylistDetailFormat.topArtists(songs),
        )
    }

    @Test
    fun `created date and row positions read naturally`() {
        assertEquals("Created 12 March 2025", PlaylistDetailFormat.created(1_741_780_800_000, java.time.ZoneOffset.UTC))
        assertNull(PlaylistDetailFormat.created(0))
        assertEquals("01", PlaylistDetailFormat.position(0))
        assertEquals("100", PlaylistDetailFormat.position(99))
    }

    private fun song(
        id: Long,
        artist: String = "Artist",
        ms: Long = 180_000,
        albumId: Long = id,
        art: String? = "art$id",
        bits: Int? = null,
        rate: Int? = null,
        kbps: Int? = null,
        genre: String? = null,
        year: Int? = null,
    ) = Song(
        id = id, uri = "content://song/$id", title = "Song $id", artist = artist, album = "Album",
        albumId = albumId, albumArtist = null, composer = null, year = year, genre = genre,
        discNumber = null, trackNumber = null, durationMs = ms, bitrateKbps = kbps,
        sampleRateHz = rate, bitDepth = bits, channels = null, artworkUri = art,
        embeddedLyrics = null, replayGainTrackDb = null, replayGainAlbumDb = null,
    )
}
