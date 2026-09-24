package com.harmony.feature.library

import com.harmony.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDetailFormatTest {

    private fun song(
        id: Long,
        title: String = "Song $id",
        artist: String = "Mira Sol",
        albumArtist: String? = "Mira Sol",
        track: Int? = id.toInt(),
        disc: Int? = null,
        year: Int? = 2019,
        genre: String? = "Indie pop",
        durationMs: Long = 200_000,
        bitDepth: Int? = 24,
        sampleRate: Int? = 96_000,
        bitrate: Int? = 2_400,
        uri: String = "content://media/external/audio/media/$id",
        composer: String? = null,
        albumGain: Float? = null,
    ) = Song(
        id, uri, title, artist, "Harbour Lights", 7, albumArtist, composer, year, genre, disc, track, durationMs,
        bitrate, sampleRate, bitDepth, 2, "art", null, null, albumGain,
    )

    // ---- order and sections ---------------------------------------------------------------

    @Test fun ordersByDiscThenTrackThenTitle() {
        val songs = listOf(song(1, track = 2, disc = 2), song(2, track = 1, disc = 2), song(3, track = 3, disc = 1), song(4, track = null, title = "b"), song(5, track = null, title = "A"))
        assertEquals(listOf(3L, 5L, 4L, 2L, 1L), AlbumDetailFormat.ordered(songs).map { it.id })
    }

    @Test fun singleDiscHasNoHeaderAndNoGapsWhenComplete() {
        val sections = AlbumDetailFormat.sections((1L..5L).map { song(it) })
        assertEquals(1, sections.size)
        assertNull(sections[0].disc)
        assertTrue(sections[0].rows.all { it is AlbumRow.Track })
        assertEquals(listOf(1, 2, 3, 4, 5), sections[0].rows.map { (it as AlbumRow.Track).number })
    }

    @Test fun twoDiscsGetHeadersAndTheirOwnNumbering() {
        val songs = listOf(song(1, track = 1, disc = 1), song(2, track = 2, disc = 1), song(3, track = 1, disc = 2))
        val sections = AlbumDetailFormat.sections(songs)
        assertEquals(listOf(1, 2), sections.map { it.disc })
        assertEquals("Disc 2  ·  1 song  ·  3min", AlbumDetailFormat.discLabel(sections[1]))
    }

    @Test fun gapsInTheNumberingBecomeMissingRows() {
        val songs = listOf(song(1, track = 1), song(2, track = 2), song(4, track = 4), song(8, track = 8))
        val rows = AlbumDetailFormat.sections(songs).single().rows
        assertEquals(
            listOf("T1", "T2", "M3-3", "T4", "M5-7", "T8"),
            rows.map { if (it is AlbumRow.Track) "T${it.number}" else (it as AlbumRow.Missing).let { m -> "M${m.from}-${m.to}" } },
        )
        assertEquals(4, AlbumDetailFormat.missingCount(AlbumDetailFormat.sections(songs)))
        assertEquals("Track 3 isn't in your library", AlbumDetailFormat.missingLabel(AlbumRow.Missing(3, 3)))
        assertEquals("Tracks 5–7 aren't in your library", AlbumDetailFormat.missingLabel(AlbumRow.Missing(5, 7)))
    }

    @Test fun aLeadingGapIsShownButNothingIsGuessedAfterTheLastTrack() {
        val rows = AlbumDetailFormat.sections(listOf(song(3, track = 3), song(4, track = 4))).single().rows
        assertEquals(AlbumRow.Missing(1, 2), rows.first())
        assertTrue(rows.last() is AlbumRow.Track)
    }

    @Test fun untrustworthyNumbersProduceNoGaps() {
        // A missing tag, or two songs claiming the same number, means the
        // numbers don't describe one release.
        val oneUntagged = listOf(song(1, track = 1), song(2, track = null), song(5, track = 5))
        val duplicated = listOf(song(1, track = 1), song(2, track = 1), song(5, track = 5))
        assertTrue(AlbumDetailFormat.sections(oneUntagged).single().rows.all { it is AlbumRow.Track })
        assertTrue(AlbumDetailFormat.sections(duplicated).single().rows.all { it is AlbumRow.Track })
    }

    @Test fun songsWithoutNumbersArePrintedByPosition() {
        val rows = AlbumDetailFormat.sections(listOf(song(1, track = null, title = "a"), song(2, track = null, title = "b"))).single().rows
        assertEquals(listOf(1, 2), rows.map { (it as AlbumRow.Track).number })
    }

    // ---- headline ------------------------------------------------------------------------

    @Test fun headlineUsesTheAlbumArtistAndLinksToTheTrackArtist() {
        val h = AlbumDetailFormat.headline(listOf(song(1), song(2, artist = "Mira Sol feat. Oda Rivers")))
        assertEquals("Harbour Lights", h.title)
        assertEquals("Mira Sol", h.artist)
        assertEquals("Mira Sol", h.artistLink)
        assertEquals(2019, h.year)
        assertEquals("ALBUM · 2019", AlbumDetailFormat.eyebrow(h))
    }

    @Test fun compilationsReadVariousArtistsAndLinkNowhere() {
        val h = AlbumDetailFormat.headline(listOf(song(1, artist = "A", albumArtist = null), song(2, artist = "B", albumArtist = null)))
        assertEquals(AlbumDetailFormat.VARIOUS_ARTISTS, h.artist)
        assertNull(h.artistLink)
        assertEquals("A", AlbumDetailFormat.trackCredit(song(1, artist = "A", albumArtist = null), h))
    }

    @Test fun anAlbumArtistNoTrackCarriesIsShownButNotLinked() {
        val h = AlbumDetailFormat.headline(listOf(song(1, artist = "Vesna Kay", albumArtist = "The Label Sampler")))
        assertEquals("The Label Sampler", h.artist)
        assertNull(h.artistLink)
    }

    @Test fun trackCreditOnlyWhenItDiffersFromTheHeader() {
        val h = AlbumDetailFormat.headline(listOf(song(1)))
        assertNull(AlbumDetailFormat.trackCredit(song(1, artist = "mira sol"), h))
        assertEquals("Mira Sol & Oda Rivers", AlbumDetailFormat.trackCredit(song(2, artist = "Mira Sol & Oda Rivers"), h))
        assertNull(AlbumDetailFormat.trackCredit(song(3, artist = "<unknown>"), h))
    }

    @Test fun genresSkipNumericAndUnknownTagsAndSplitLists() {
        val h = AlbumDetailFormat.headline(listOf(song(1, genre = "(13)"), song(2, genre = "Dream pop; Indie pop"), song(3, genre = "Indie pop"), song(4, genre = "Unknown")))
        assertEquals(listOf("Indie pop", "Dream pop"), h.genres)
    }

    @Test fun summaryLine() {
        val songs = (1L..12L).map { song(it, durationMs = 240_000) }
        assertEquals("12 songs  ·  48min  ·  Indie pop", AlbumDetailFormat.summary(songs, AlbumDetailFormat.headline(songs)))
        assertEquals("1 song  ·  Under 1min  ·  Indie pop", AlbumDetailFormat.summary(listOf(song(1, durationMs = 30_000)), AlbumDetailFormat.headline(listOf(song(1)))))
    }

    // ---- quality -------------------------------------------------------------------------

    @Test fun allLosslessAtOneResolution() {
        val q = AlbumDetailFormat.quality((1L..3L).map { song(it) })
        assertEquals(Lossless.ALL, q.lossless)
        assertEquals("24-bit / 96 kHz", q.label)
        assertEquals("Lossless · 24-bit / 96 kHz", AlbumDetailFormat.qualityDetail(q))
    }

    @Test fun theFormatIsNamedOnlyWhenEveryPathShowsIt() {
        val saf = { id: Long -> "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FHarbour%2F0$id%20Night.flac" }
        assertEquals("FLAC · 24-bit / 96 kHz", AlbumDetailFormat.quality((1L..2L).map { song(it, uri = saf(it)) }).label)
        val mixed = listOf(song(1, uri = saf(1)), song(2))
        assertEquals("24-bit / 96 kHz", AlbumDetailFormat.quality(mixed).label)
    }

    @Test fun differingResolutionsShowARangeAndFlagTheOddTrack() {
        val songs = listOf(song(1, bitDepth = 16, sampleRate = 44_100), song(2, bitDepth = 16, sampleRate = 44_100), song(3, bitDepth = 24, sampleRate = 96_000))
        val q = AlbumDetailFormat.quality(songs)
        assertEquals("16–24-bit / 44.1–96 kHz", q.label)
        assertNull(AlbumDetailFormat.rowNote(songs[0], q))
        assertEquals("24-bit / 96 kHz", AlbumDetailFormat.rowNote(songs[2], q))
    }

    @Test fun mixedAlbumsFlagTheMinority() {
        val songs = listOf(song(1), song(2), song(3, bitDepth = null, sampleRate = 44_100, bitrate = 320))
        val q = AlbumDetailFormat.quality(songs)
        assertEquals(Lossless.SOME, q.lossless)
        assertEquals("2 lossless, 1 lossy", AlbumDetailFormat.qualityDetail(q))
        assertNull(AlbumDetailFormat.rowNote(songs[0], q))
        assertEquals("320 kbps", AlbumDetailFormat.rowNote(songs[2], q))
    }

    @Test fun lossyAlbumsShowTheirBitrate() {
        val q = AlbumDetailFormat.quality(listOf(song(1, bitDepth = null, bitrate = 256), song(2, bitDepth = null, bitrate = 320)))
        assertEquals(Lossless.NONE, q.lossless)
        assertEquals("256–320 kbps", q.label)
    }

    @Test fun nothingKnownMeansNoClaim() {
        val q = AlbumDetailFormat.quality(listOf(song(1, bitDepth = null, sampleRate = null, bitrate = null)))
        assertEquals(Lossless.UNKNOWN, q.lossless)
        assertNull(q.label)
        assertNull(AlbumDetailFormat.qualityDetail(q))
    }

    // ---- details -------------------------------------------------------------------------

    @Test fun detailsListOnlyWhatTheTagsProvide() {
        val songs = listOf(
            song(1, disc = 1, track = 1, composer = "A. Writer", albumGain = -7.44f),
            song(2, disc = 1, track = 3, composer = "A. Writer"),
            song(3, disc = 2, track = 1),
        )
        val sections = AlbumDetailFormat.sections(songs)
        val details = AlbumDetailFormat.details(songs, AlbumDetailFormat.headline(songs), sections, AlbumDetailFormat.quality(songs)).toMap()
        assertEquals("2019", details["Released"])
        assertEquals("Indie pop", details["Genre"])
        assertEquals("3 songs on 2 discs", details["Songs"])
        assertEquals("3 of 4 tracks", details["In your library"])
        assertEquals("10min", details["Length"])
        assertEquals("Lossless · 24-bit / 96 kHz", details["Quality"])
        assertEquals("A. Writer", details["Composer"])
        assertEquals("−7.4 dB", details["Album gain"])

        val bare = listOf(song(1, year = null, genre = null, bitDepth = null, sampleRate = null, bitrate = null))
        val bareDetails = AlbumDetailFormat.details(bare, AlbumDetailFormat.headline(bare), AlbumDetailFormat.sections(bare), AlbumDetailFormat.quality(bare)).toMap()
        assertEquals(setOf("Songs", "Length"), bareDetails.keys)
    }

    @Test fun otherAlbumsAreTheArtistsOwnNewestFirst() {
        fun on(id: Long, albumId: Long, album: String, year: Int?, albumArtist: String? = "Mira Sol") =
            song(id, year = year, albumArtist = albumArtist).copy(albumId = albumId, album = album)
        val artistSongs = listOf(
            on(1, 7, "Harbour Lights", 2019),                      // the album being shown
            on(2, 3, "Open Water", 2016), on(3, 3, "Open Water", 2016),
            on(4, 9, "Evergreen", 2022),
            on(5, 11, "Label Sampler", 2021, albumArtist = "Various"), // a guest spot
            on(6, 0, "Loose", 2020),                                 // no album id
        )
        val others = AlbumDetailFormat.otherAlbums(artistSongs, "Mira Sol", currentAlbumId = 7)
        assertEquals(listOf("Evergreen", "Open Water"), others.map { it.title })
        assertEquals(2, others[1].songCount)
        assertEquals(2016, others[1].year)
    }

    @Test fun gainAndSampleRateFormatting() {
        assertEquals("+1.2 dB", AlbumDetailFormat.gain(1.23f))
        assertEquals("0.0 dB", AlbumDetailFormat.gain(0.01f))
        assertEquals("44.1 kHz", AlbumDetailFormat.sampleRate(44_100))
        assertEquals("192 kHz", AlbumDetailFormat.sampleRate(192_000))
    }
}

class ArtistCountsTest {
    @Test fun singularAndPlural() {
        assertEquals("1 album  •  1 song", artistCounts(1, 1))
        assertEquals("3 albums  •  22 songs", artistCounts(3, 22))
    }
}
