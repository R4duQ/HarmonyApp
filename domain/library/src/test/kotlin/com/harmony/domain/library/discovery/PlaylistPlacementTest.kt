package com.harmony.domain.library.discovery

import com.harmony.domain.library.repository.DiscoveryBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPlacementTest {
    // Library has songs matching remote 1 (by identity, with a feat. credit) and 3 (downloaded file).
    private val library = listOf(
        librarySong(10, "Artist 1", title = "Track 1 (feat. Guest)", ms = 180_001),
        librarySong(30, "Artist 3", title = "Track 3", ms = 180_003).copy(uri = "content://dl/3"),
    )
    private val batch = DiscoveryBatch("b", "Mix", (1..5).map { remote(it) },
        uris = mapOf("dz:3" to "content://dl/3", "dz:4" to "content://dl/missing"),
        errors = mapOf("dz:5" to "No peer"), activeKey = "dz:2")

    @Test fun onlyIndexedSongsCountAsAvailable() {
        val progress = PlaylistPlacement.progress(batch, LibraryIndex(library))
        assertEquals(mapOf("dz:1" to 10L, "dz:3" to 30L), progress.available)
        assertEquals(SongAvailability.DOWNLOADING, progress.status("dz:2"))
        assertEquals(SongAvailability.NEEDS_DOWNLOAD, progress.status("dz:4")) // a URI alone is not a playable file
        assertEquals(SongAvailability.FAILED, progress.status("dz:5"))
        assertEquals(listOf(10L, 30L), PlaylistPlacement.toCreate(batch, progress))
    }

    @Test fun laterArrivalsAreAppendedOnceAndUserRemovalsStick() {
        val created = batch.copy(playlistId = -5, placedKeys = setOf("dz:1", "dz:3"))
        val withDownload = LibraryIndex(library + librarySong(40, "Artist 4", title = "Track 4", ms = 180_004).copy(uri = "content://dl/missing"))
        assertEquals(listOf("dz:4" to 40L), PlaylistPlacement.toAppend(created, PlaylistPlacement.progress(created, withDownload)))
        // dz:1 was placed and later removed by the user: it is not put back.
        val afterRemoval = created.copy(placedKeys = created.placedKeys + "dz:4")
        assertTrue(PlaylistPlacement.toAppend(afterRemoval, PlaylistPlacement.progress(afterRemoval, withDownload)).isEmpty())
        assertTrue(PlaylistPlacement.toAppend(created.copy(playlistDeleted = true), PlaylistPlacement.progress(created, withDownload)).isEmpty())
    }

    @Test fun oneLibraryFileIsNeverUsedForTwoSongs() {
        val twin = batch.copy(songs = listOf(remote(1), remote(1).copy(key = "apple:1")))
        assertEquals(1, PlaylistPlacement.progress(twin, LibraryIndex(library)).availableCount)
    }

    @Test fun durationReportsHowManyLengthsAreKnown() {
        val songs = listOf(remote(1), remote(2).copy(durationMs = 0))
        assertEquals(180_001L to 1, PlaylistPlacement.duration(songs))
    }
}
