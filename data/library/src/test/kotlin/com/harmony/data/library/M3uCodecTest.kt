package com.harmony.data.library

import com.harmony.core.model.Song
import com.harmony.data.library.m3u.M3uCodec
import org.junit.Assert.*
import org.junit.Test

class M3uCodecTest {
    private fun song(id: Long, uri: String) = Song(id, uri, "Title", "Artist", "Album", 1,
        null, null, null, null, 1, 1, 180_000, null, null, null, null, null, null, null, null)

    @Test fun literalPercentInFilenameDoesNotCrashImport() {
        val songs = listOf(song(1, "file:///Music/100% Love.flac"))
        assertEquals(listOf(1L), M3uCodec.matchImport("Music/100% Love.flac", songs))
    }
    @Test fun plusIsNotAFormEncodedSpace() {
        val songs = listOf(song(1, "file:///Music/A B.flac"), song(2, "file:///Music/A+B.flac"))
        assertEquals(listOf(2L), M3uCodec.matchImport("Music/A+B.flac", songs))
    }
    @Test fun encodedUnicodeAndSpacesMatchPaths() {
        val songs = listOf(song(1, "file:///Music/Și tu.flac"))
        assertEquals(listOf(1L), M3uCodec.matchImport("Music/%C8%98i%20tu.flac", songs))
    }
    @Test fun ambiguousBasenameDoesNotImportAnArbitraryAlbum() {
        val songs = listOf(song(1, "file:///Music/One/Intro.flac"), song(2, "file:///Music/Two/Intro.flac"))
        assertTrue(M3uCodec.matchImport("Intro.flac", songs).isEmpty())
        assertEquals(listOf(2L), M3uCodec.matchImport("Two/Intro.flac", songs))
    }
    @Test fun bomCommentsAndWindowsPathsAreAccepted() {
        val songs = listOf(song(1, "file:///Music/Album/Song.flac"))
        assertEquals(listOf(1L), M3uCodec.matchImport("\uFEFF#EXTM3U\r\n#EXTINF:180,Song\r\nD:\\Music\\Album\\Song.flac\r\n", songs))
    }
    @Test fun exportImportPreservesUrisAndOrder() {
        val songs = listOf(song(2, "content://media/external_primary/audio/media/2"), song(1, "content://media/external_primary/audio/media/1"))
        assertEquals(listOf(2L, 1L), M3uCodec.matchImport(M3uCodec.export(songs), songs.reversed()))
    }
}
