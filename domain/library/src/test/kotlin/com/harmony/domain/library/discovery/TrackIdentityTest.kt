package com.harmony.domain.library.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityTest {
    private fun id(title: String, artist: String = "Daft Punk", ms: Long = 200_000, isrc: String? = null) =
        TrackIdentity.of(title, artist, ms, isrc)

    @Test fun featuringCreditsAndAccentsDoNotSplitOneRecording() {
        assertTrue(TrackIdentity.same(id("Get Lucky (feat. Pharrell Williams)"), id("Get Lucky")))
        assertTrue(TrackIdentity.same(id("Halo", "Beyoncé"), id("Halo", "Beyonce")))
        assertTrue(TrackIdentity.same(id("One Kiss", "Calvin Harris & Dua Lipa"), id("One Kiss", "Calvin Harris")))
    }

    @Test fun liveRemixRemasterAcousticAndCoverAreDifferentTracks() {
        val original = id("Around the World")
        listOf("Around the World (Live)", "Around the World - Live at Wembley", "Around the World (Remastered 2011)",
            "Around the World - 2011 Remaster", "Around the World (Kaskade Remix)", "Around the World (Acoustic)",
            "Around the World (Karaoke Version)", "Around the World (Sped Up)", "Around the World - Radio Edit",
        ).forEach { assertFalse(it, TrackIdentity.same(original, id(it))) }
        assertFalse(TrackIdentity.same(id("Song (Live)"), id("Song (Acoustic)")))
    }

    @Test fun labelsThatNameTheOriginalAreIgnored() {
        assertTrue(TrackIdentity.same(id("Strobe (Original Mix)", "deadmau5"), id("Strobe", "deadmau5")))
        assertTrue(TrackIdentity.same(id("Song - Single Version"), id("Song")))
    }

    @Test fun unknownBracketsStayPartOfTheTitle() {
        assertFalse(TrackIdentity.same(id("Echoes (Part 2)"), id("Echoes")))
    }

    @Test fun isrcWinsOverDifferingMetadata() {
        assertTrue(TrackIdentity.same(id("Song", "A", isrc = "USABC1234567"), id("Song (Deluxe)", "A B", ms = 0, isrc = "usabc1234567")))
    }

    @Test fun differentIsrcsNeedTheSameLength() {
        assertTrue(TrackIdentity.same(id("Song", isrc = "USABC1234567"), id("Song", ms = 201_000, isrc = "GBXYZ7654321")))
        assertFalse(TrackIdentity.same(id("Song", isrc = "USABC1234567"), id("Song", ms = 203_500, isrc = "GBXYZ7654321")))
        assertFalse(TrackIdentity.same(id("Song", isrc = "USABC1234567"), id("Song", ms = 0, isrc = "GBXYZ7654321")))
    }

    @Test fun durationSeparatesEditionsWithoutLabels() {
        assertTrue(TrackIdentity.same(id("Song"), id("Song", ms = 203_000)))
        assertFalse(TrackIdentity.same(id("Song"), id("Song", ms = 260_000)))
        assertTrue(TrackIdentity.same(id("Song"), id("Song", ms = 0)))
    }

    @Test fun bandNamesWithSlashesAndAndStayWhole() {
        assertEquals("ac dc", TrackIdentity.primaryArtist("AC/DC"))
        assertEquals("florence and the machine", TrackIdentity.primaryArtist("Florence and the Machine"))
        assertEquals("dua lipa", TrackIdentity.primaryArtist("Dua Lipa feat. DaBaby"))
        assertEquals("lil nas x", TrackIdentity.primaryArtist("Lil Nas X"))
    }

    @Test fun differentArtistsWithTheSameTitleAreDifferent() {
        assertFalse(TrackIdentity.same(id("Hallelujah", "Leonard Cohen"), id("Hallelujah", "Jeff Buckley")))
    }
}
