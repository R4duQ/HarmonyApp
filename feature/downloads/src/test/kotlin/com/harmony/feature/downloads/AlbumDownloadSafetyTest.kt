package com.harmony.feature.downloads

import com.harmony.core.model.Song
import com.harmony.domain.library.repository.AlbumJourneyTrack
import com.harmony.domain.library.repository.AlbumJourney
import com.harmony.domain.library.repository.ListenedRange
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AlbumDownloadSafetyTest {
    private val track = AlbumJourneyTrack("1", "Get Lucky", "Daft Punk", 360_000, 1, 8)
    @Test fun selectionExcludesUncheckedAndExistingTracks() {
        val album = AlbumJourney("album", "Album", "Artist", "", "1", listOf(
            track, track.copy(id = "2", selectedForDownload = false), track.copy(id = "3", uri = "content://local/3")))
        assertEquals(listOf("1"), album.selectedMissing.map { it.id })
        assertTrue(album.copy(tracks = album.tracks.map { it.copy(selectedForDownload = false) }).selectedMissing.isEmpty())
    }
    @Test fun partialSelectionNeverPretendsTheFullAlbumWasHeard() {
        val album = AlbumJourney("album", "Album", "Artist", "", "1", listOf(
            track.copy(coverage = listOf(ListenedRange(0, 360_000))), track.copy(id = "2", selectedForDownload = false)))
        assertFalse(album.readyForReview)
        assertTrue(track.selectedForDownload) // Old albums default to the original complete-album selection.
    }
    private fun song(title: String = "Get Lucky", artist: String = "Daft Punk", album: String = "Random Access Memories", duration: Long = 360_000) =
        Song(1, "content://media/external/audio/media/1", title, artist, album, 1, artist,
            null, 2013, null, 1, 8, duration, 900, 44100, 16, 2, null, null, null, null)
    @Test fun reusesMatchingAlbumFile() { assertNotNull(AlbumTrackMatcher.match(track, "Random Access Memories", listOf(song()))) }
    @Test fun rejectsCoverArtist() { assertNull(AlbumTrackMatcher.match(track, "Random Access Memories", listOf(song(artist = "Tribute to Daft Punk")))) }
    @Test fun rejectsDifferentRecordingOrCompilation() {
        assertNull(AlbumTrackMatcher.match(track, "Random Access Memories", listOf(song(title = "Get Lucky (Live)"))))
        assertNull(AlbumTrackMatcher.match(track, "Random Access Memories", listOf(song(album = "Greatest Hits"))))
        assertNull(AlbumTrackMatcher.match(track, "Random Access Memories", listOf(song(duration = 180_000))))
    }
    @Test fun handlesPunctuationAndRecognizedEditionSuffix() {
        assertNotNull(AlbumTrackMatcher.match(track, "Random Access Memories", listOf(song(artist = "Daft-Punk", album = "Random Access Memories (Deluxe Edition)"))))
    }
    @Test fun nonLatinNamesRemainDistinct() {
        assertNotEquals(AlbumTrackMatcher.key("東京"), AlbumTrackMatcher.key("大阪"))
        val expected = track.copy(title = "東京", artist = "坂本")
        assertNull(AlbumTrackMatcher.match(expected, "日本", listOf(song(title = "大阪", artist = "山下", album = "日本"))))
    }
    @Test fun deluxeSuffixDoesNotEraseLiveEditionIdentity() {
        assertNotEquals(AlbumTrackMatcher.albumKey("Random Access Memories"),
            AlbumTrackMatcher.albumKey("Random Access Memories (Live) (Deluxe Edition)"))
    }
    @Test fun anotherArtistsAlbumWithTheSameTitleIsNotAnEdition() {
        val expected = AlbumEdition("1", "Discovery", "Daft Punk", "")
        val wrong = AlbumEdition("2", "Discovery", "Another artist", "")
        assertEquals(listOf(expected), AlbumEditionMatcher.matching(listOf(wrong, expected), "Discovery", "Daft Punk"))
        assertTrue(AlbumEditionMatcher.matching(listOf(wrong), "Discovery", "Daft Punk").isEmpty())
    }
    @Test fun knownArtistAliasesAndRemastersRemainAvailable() {
        val prince = AlbumEdition("1", "Purple Rain (2015 Remaster)", "Prince", "")
        assertEquals(listOf(prince), AlbumEditionMatcher.matching(listOf(prince), "Purple Rain", "Prince and the Revolution", setOf("Prince")))
        assertNotEquals(AlbumTrackMatcher.albumKey("Album (Live) (Deluxe Edition)"), AlbumTrackMatcher.albumKey("Album"))
        assertEquals(AlbumTrackMatcher.albumKey("Album"), AlbumTrackMatcher.albumKey("Album - 2015 Remaster"))
    }
    @Test fun nativeGateRejectsSecondTransferAndReleasesAfterCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val first = launch { DownloadEngineGate.run(albumTransfer = true) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        assertTrue(DownloadEngineGate.albumOwnsTransfer)
        try { DownloadEngineGate.run { fail("A second native transfer started") }; fail("Expected busy") }
        catch (_: DownloadEngineBusyException) { }
        first.cancelAndJoin()
        assertFalse(DownloadEngineGate.albumOwnsTransfer)
        assertEquals(7, DownloadEngineGate.run { 7 })
    }
}
