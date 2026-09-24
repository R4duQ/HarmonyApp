package com.harmony.feature.home

import com.harmony.core.model.Playlist
import com.harmony.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionsTest {

    @Test
    fun `recent albums group songs by album, newest first, one entry each`() {
        val added = listOf(
            song(1, albumId = 10, album = "Discovery", art = null),
            song(2, albumId = 10, album = "Discovery", art = "d.jpg"),
            song(3, albumId = 20, album = "Moon Safari", art = "m.jpg", albumArtist = "Air"),
            song(4, albumId = 10, album = "Discovery"),
        )
        val albums = HomeSections.recentAlbums(added)
        assertEquals(listOf(10L, 20L), albums.map { it.id })
        assertEquals("d.jpg", albums[0].artworkUri) // first cover the album has, not the first song's null
        assertEquals("Air", albums[1].artist)
    }

    @Test
    fun `loose files without an album are not shown as albums`() {
        val added = listOf(song(1, albumId = 0, album = "Voice memo"), song(2, albumId = 5, album = ""))
        assertEquals(emptyList<HomeAlbum>(), HomeSections.recentAlbums(added))
    }

    @Test
    fun `compilations use the album artist`() {
        val added = listOf(
            song(1, albumId = 3, album = "Hits", artist = "One", albumArtist = "Various Artists"),
            song(2, albumId = 3, album = "Hits", artist = "Two", albumArtist = "Various Artists"),
        )
        assertEquals("Various Artists", HomeSections.recentAlbums(added).single().artist)
    }

    @Test
    fun `recently played drops the featured song and repeats`() {
        val history = listOf(song(1), song(2), song(1), song(3), song(2))
        assertEquals(listOf(2L, 3L), HomeSections.recentlyPlayed(history, featured = song(1)).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), HomeSections.recentlyPlayed(history, featured = null).map { it.id })
    }

    @Test
    fun `rows are capped`() {
        val history = (1L..30L).map { song(it) }
        assertEquals(HomeSections.ROW_LIMIT, HomeSections.recentlyPlayed(history, null).size)
    }

    @Test
    fun `playlists are newest first with their own artwork and length`() {
        val lists = listOf(Playlist(1, "Old", 3, createdAt = 100), Playlist(2, "New", 1, createdAt = 200))
        val out = HomeSections.playlists(lists, mapOf(2L to listOf("a")), mapOf(1L to 3_600_000L))
        assertEquals(listOf(2L, 1L), out.map { it.id })
        assertEquals(listOf("a"), out[0].artwork)
        assertEquals("1 song", HomeSections.playlistMeta(out[0]))
        assertEquals("3 songs  ·  1hr 0min", HomeSections.playlistMeta(out[1]))
    }

    @Test
    fun `library summary leaves out zeros and pluralises`() {
        assertEquals("1,306 songs  ·  1 album", HomeSections.librarySummary(LibraryStats(1306, 1, 0, 0)))
        assertEquals("", HomeSections.librarySummary(LibraryStats()))
    }

    @Test
    fun `greeting follows the clock`() {
        assertEquals("Good morning", greetingForHour(8))
        assertEquals("Good afternoon", greetingForHour(13))
        assertEquals("Good evening", greetingForHour(19))
        assertEquals("Good night", greetingForHour(2))
    }

    private fun song(
        id: Long,
        albumId: Long = id,
        album: String = "Album $id",
        artist: String = "Artist",
        albumArtist: String? = null,
        art: String? = "art$id",
    ) = Song(
        id = id, uri = "u$id", title = "Song $id", artist = artist, album = album, albumId = albumId,
        albumArtist = albumArtist, composer = null, year = null, genre = null, discNumber = null,
        trackNumber = null, durationMs = 1000, bitrateKbps = null, sampleRateHz = null, bitDepth = null,
        channels = null, artworkUri = art, embeddedLyrics = null, replayGainTrackDb = null,
        replayGainAlbumDb = null,
    )
}
