package com.harmony.domain.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedCleanerTest {
    private fun readings(title: String, artist: String) =
        SeedCleaner.readings(SeedTrack(title, artist, "<unknown>", 216_000)).map { it.artist to it.title }

    @Test
    fun aFileWithoutTagsIsReadFromItsName() {
        assertEquals(
            listOf("Linkin Park" to "In the End", "In the End" to "Linkin Park"),
            readings("01 - Linkin Park - In the End (Official Video) [320kbps]", "<unknown>"),
        )
        assertEquals(listOf("Linkin Park" to "In the End", "In the End" to "Linkin Park"), readings("Linkin_Park_-_In_the_End", ""))
        assertEquals("Linkin Park" to "In the End", readings("y2mate.com - Linkin Park - In the End HD", "Unknown Artist").first())
        assertEquals("Adele" to "Hello", readings("Adele - Hello | Lyrics", "Various Artists").first())
    }

    @Test
    fun aReadingCarriesNoAlbumThatSaysNothing() {
        val r = SeedCleaner.readings(SeedTrack("Adele - Hello", "<unknown>", "<unknown>", 295_000))
        assertNull(r.first().album)
        assertNull(r.first().uri)
        assertEquals(295_000L, r.first().durationMs)
    }

    @Test
    fun anArtistRepeatedInTheTitleIsTakenOut() {
        assertEquals(listOf("Adele" to "Hello"), readings("Adele - Hello (Official Music Video)", "Adele"))
        assertEquals(listOf("Adele" to "Hello"), readings("Hello - Adele", "Adele"))
        assertEquals(listOf("Adele" to "Hello"), readings("Hello [HD]", "Adele - Topic"))
        assertEquals(listOf("Adele" to "Hello"), readings("Hello", "AdeleVEVO"))
    }

    @Test
    fun versionsAndFeaturedArtistsStay() {
        assertEquals("Song (Live)", SeedCleaner.cleanTitle("Song (Live) [Official Video]"))
        assertEquals("Song (Acoustic Version)", SeedCleaner.cleanTitle("Song (Acoustic Version)"))
        assertEquals("Song (feat. Drake)", SeedCleaner.cleanTitle("Song (feat. Drake) (Official Audio)"))
        assertEquals("Song (with Ed Sheeran)", SeedCleaner.cleanTitle("Song (with Ed Sheeran)"))
        assertEquals("Song (2011 Remaster)", SeedCleaner.cleanTitle("Song (2011 Remaster)"))
        assertEquals("Song (Radio Version)", SeedCleaner.cleanTitle("Song (Radio Version)"))
        assertEquals("Song - Live at Wembley", SeedCleaner.cleanTitle("Song - Live at Wembley"))
    }

    @Test
    fun titlesThatLookLikeNoiseAreLeftAlone() {
        assertEquals("7 Years", SeedCleaner.cleanTitle("7 Years"))
        assertEquals("99 Problems", SeedCleaner.cleanTitle("99 Problems"))
        assertEquals("1979", SeedCleaner.cleanTitle("1979"))
        assertEquals("4:44", SeedCleaner.cleanTitle("4:44"))
        assertEquals("Video Killed the Radio Star", SeedCleaner.cleanTitle("Video Killed the Radio Star"))
        assertEquals("Radio Video", SeedCleaner.cleanTitle("Radio Video"))
        assertEquals("Audio", SeedCleaner.cleanTitle("Audio"))
        assertEquals("Part 2", SeedCleaner.cleanTitle("Part 2"))
        assertEquals("will.i.am", SeedCleaner.cleanArtist("will.i.am"))
    }

    @Test
    fun trackNumbersAndRipNotesGo() {
        assertEquals("Song", SeedCleaner.cleanTitle("01. Song"))
        assertEquals("Song", SeedCleaner.cleanTitle("07 Song"))
        assertEquals("Song", SeedCleaner.cleanTitle("1-05 Song"))
        assertEquals("Song", SeedCleaner.cleanTitle("A1. Song"))
        assertEquals("Song", SeedCleaner.cleanTitle("(03) Song"))
        assertEquals("Song", SeedCleaner.cleanTitle("Song.mp3"))
        assertEquals("Song", SeedCleaner.cleanTitle("Song Official Video"))
        assertEquals("Song", SeedCleaner.cleanTitle("Song - Lyrics"))
        assertEquals("Song", SeedCleaner.cleanTitle("Song (Videoclip Oficial)"))
        assertEquals("Song", SeedCleaner.cleanTitle("Song [M/V]"))
        assertEquals("Song", SeedCleaner.cleanTitle("Song (Versuri)"))
        assertEquals("Song", SeedCleaner.cleanTitle("[www.site.net] Song"))
    }

    @Test
    fun nothingToCleanMeansNoReading() {
        assertTrue(readings("Hello", "Adele").isEmpty())
        assertTrue("a title alone can't be matched", readings("Track 05", "<unknown>").isEmpty())
        assertTrue(SeedCleaner.isUnknownArtist("<unknown>"))
        assertTrue(SeedCleaner.isUnknownArtist("  "))
        assertFalse(SeedCleaner.isUnknownArtist("Unknown Mortal Orchestra"))
        assertEquals("Linkin Park", SeedCleaner.artistOf(SeedTrack("Linkin Park - Numb", "<unknown>")))
        assertNull(SeedCleaner.artistOf(SeedTrack("Track 05", "<unknown>")))
    }
}
