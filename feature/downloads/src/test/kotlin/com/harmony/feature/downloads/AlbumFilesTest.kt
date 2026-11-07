package com.harmony.feature.downloads

import com.harmony.domain.library.repository.AlbumJourneyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumFilesTest {
    private val track = AlbumJourneyTrack("1", "Get Lucky", "Daft Punk", 360_000, 1, 8)

    @Test fun anAlbumGoesInArtistThenAlbumFolders() {
        assertEquals(listOf("Daft Punk", "Random Access Memories"), AlbumFiles.albumFolders("Daft Punk", "Random Access Memories"))
        assertEquals("Music/Harmony/Downloads/Daft Punk/Random Access Memories/",
            AlbumFiles.relativePath("Music/Harmony/Downloads", AlbumFiles.albumFolders("Daft Punk", "Random Access Memories")))
        assertEquals("Music/Harmony/Downloads/", AlbumFiles.relativePath("Music/Harmony/Downloads/", emptyList()))
    }

    @Test fun tracksAreNumberedAndMultiDiscAlbumsSortByDisc() {
        assertEquals("08 - Get Lucky.flac", AlbumFiles.trackFileName(track, multiDisc = false, extension = "flac"))
        assertEquals("2-08 - Get Lucky.mp3", AlbumFiles.trackFileName(track.copy(disc = 2), multiDisc = true, extension = "MP3"))
        assertEquals("01 - ___.flac", AlbumFiles.trackFileName(track.copy(number = 1, title = "///"), false, "flac"))
        assertEquals("01 - Track.flac", AlbumFiles.trackFileName(track.copy(number = 1, title = " . "), false, "flac"))
    }

    @Test fun namesFromMetadataCannotEscapeOrBreakTheFolder() {
        assertEquals(listOf("AC_DC", "Back In Black"), AlbumFiles.albumFolders("AC/DC", "Back In Black"))
        // "..", "." and blank segments are dropped, not followed.
        assertEquals(listOf("x"), AlbumFiles.folders(listOf("..", ".", "  ", "x")))
        assertEquals(listOf("Unknown artist", "Unknown album"), AlbumFiles.albumFolders("", "   "))
        assertEquals(listOf("What_ Why_"), AlbumFiles.folders(listOf("What? Why*")))
        assertEquals(listOf("tab_and_newline"), AlbumFiles.folders(listOf("tab\tand\nnewline")))
        assertEquals(listOf("trailing dots"), AlbumFiles.folders(listOf("...trailing dots...")))
        val long = AlbumFiles.folders(listOf("x".repeat(500))).single()
        assertTrue(long.length <= 80)
        assertTrue(AlbumFiles.trackFileName(track.copy(title = "y".repeat(500)), false, "flac").length <= 160)
    }
}
