package com.harmony.feature.downloads

import com.harmony.domain.library.repository.AlbumJourney
import com.harmony.domain.library.repository.AlbumJourneyTrack
import org.junit.Assert.*
import org.junit.Test

class AlbumDownloadPolicyTest {
    private val tracks = (1..4).map { AlbumJourneyTrack("$it", "Track $it", "Artist", 180_000L, 1, it) }
    private fun album(rows: List<AlbumJourneyTrack> = tracks) = AlbumJourney("a", "Album", "Artist", "", "42", rows)
    private fun select(album: AlbumJourney, id: String?, selected: Boolean): AlbumJourney {
        val ids = AlbumDownloadPolicy.selectedIds(album, id, selected)
        return album.copy(tracks = album.tracks.map { it.copy(selectedForDownload = it.id in ids) })
    }

    @Test fun wholeAlbumStartsSelectedAndLabelIsExplicit() {
        assertEquals(tracks, AlbumDownloadPolicy.tracksFor(album(), null))
        assertEquals("Download full album (4)", AlbumDownloadPolicy.downloadLabel(album()))
    }
    @Test fun individualSelectionsOnlyDownloadCheckedSongs() {
        val partial = select(select(album(), "2", false), "4", false)
        assertEquals(listOf("1", "3"), partial.selectedMissing.map { it.id })
        assertEquals(listOf("1", "3"), AlbumDownloadPolicy.tracksFor(partial, null).map { it.id })
        assertEquals("Download selected (2)", AlbumDownloadPolicy.downloadLabel(partial))
    }
    @Test fun clearThenAllRestoresOnlyMissingTracks() {
        val initial = album(tracks.map { if (it.id == "2") it.copy(uri = "content://local/2") else it })
        val cleared = select(initial, null, false)
        assertTrue(cleared.selectedMissing.isEmpty())
        assertEquals("Select tracks to download", AlbumDownloadPolicy.downloadLabel(cleared))
        val restored = select(cleared, null, true)
        assertEquals(listOf("1", "3", "4"), restored.selectedMissing.map { it.id })
        assertEquals("content://local/2", restored.tracks[1].uri)
        assertEquals("Download all missing (3)", AlbumDownloadPolicy.downloadLabel(restored))
    }
    @Test fun workerUsesEnqueuedSnapshotNotLaterCheckboxValues() {
        val initial = select(album(), "2", false)
        val snapshot = initial.selectedMissing.map { it.id }.toSet()
        val changed = select(select(initial, null, false), "2", true)
        assertEquals(listOf("1", "3", "4"), AlbumDownloadPolicy.tracksFor(changed, snapshot).map { it.id })
    }
    @Test fun publishedSongsRemainCheckpointedDuringResume() {
        val resumed = album(tracks.map { if (it.id == "1") it.copy(uri = "content://saved", downloaded = true) else it })
        val workerTracks = AlbumDownloadPolicy.tracksFor(resumed, setOf("1", "3"))
        assertEquals("content://saved", workerTracks.first().uri)
        assertEquals(listOf("3"), workerTracks.filter { it.uri == null }.map { it.id })
    }
    @Test fun ordersMultipleDiscsAndTrackNumbers() {
        val rows = listOf(tracks[0].copy(disc = 2, number = 1), tracks[1].copy(disc = 1, number = 2), tracks[2].copy(disc = 1, number = 1))
        assertEquals(listOf("3", "2", "1"), AlbumDownloadPolicy.tracksFor(album(rows), null).map { it.id })
    }
    @Test fun emptyOrUnknownSnapshotsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AlbumDownloadPolicy.tracksFor(album(), emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { AlbumDownloadPolicy.tracksFor(album(), setOf("1", "missing")) }
        assertThrows(IllegalArgumentException::class.java) { AlbumDownloadPolicy.tracksFor(select(album(), null, false), null) }
    }
    @Test fun bothAlbumSourcesAreExplicitAndUnknownDoesNotFallThroughToSoulseek() {
        assertEquals(DownloadSource.SPOTIFLAC, AlbumDownloadPolicy.source("SPOTIFLAC"))
        assertEquals(DownloadSource.SOULSEEK, AlbumDownloadPolicy.source("SOULSEEK"))
        for (invalid in listOf("", "OTHER", "YTCONVERTER")) {
            assertThrows(IllegalArgumentException::class.java) { AlbumDownloadPolicy.source(invalid) }
        }
    }
    @Test fun spotiflacKeepsAllThreeFormats() {
        for (format in SpotiFlacOutputFormat.entries) assertEquals(format, AlbumDownloadPolicy.formatFor(DownloadSource.SPOTIFLAC, format))
    }
    @Test fun soulseekUsesNativeFlacOrMp3NotAnUnsupportedHiResTranscode() {
        assertEquals(SpotiFlacOutputFormat.FLAC_LOSSLESS, AlbumDownloadPolicy.formatFor(DownloadSource.SOULSEEK, SpotiFlacOutputFormat.FLAC_HI_RES_96))
        assertEquals(SpotiFlacOutputFormat.MP3_320, AlbumDownloadPolicy.formatFor(DownloadSource.SOULSEEK, SpotiFlacOutputFormat.MP3_320))
        assertThrows(IllegalArgumentException::class.java) { AlbumDownloadPolicy.formatFor(DownloadSource.YTCONVERTER, SpotiFlacOutputFormat.FLAC_LOSSLESS) }
    }
    @Test fun albumIdentityDoesNotDependOnAudioSource() {
        assertEquals("downloads-deezer-42", downloadsAlbumId("42"))
        assertNotEquals(downloadsAlbumId("42"), downloadsAlbumId("43"))
        for (bad in listOf("", "../42", "42?source=other", "-1")) {
            assertThrows(IllegalArgumentException::class.java) { downloadsAlbumId(bad) }
        }
    }
    @Test fun rapidSequentialChangesDoNotResetOtherCheckboxes() {
        var selected = album()
        for (id in listOf("1", "2", "3")) selected = select(selected, id, false)
        selected = select(selected, "2", true)
        assertEquals(listOf("2", "4"), selected.selectedMissing.map { it.id })
    }
}
