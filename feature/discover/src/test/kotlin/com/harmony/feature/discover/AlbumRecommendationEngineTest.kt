package com.harmony.feature.discover

import com.harmony.core.model.Song
import com.harmony.feature.discover.model.*
import com.harmony.feature.discover.provider.AlbumRecommendationEngine
import com.harmony.feature.discover.provider.ShflAlbumCatalog
import org.junit.Assert.*
import org.junit.Test

class AlbumRecommendationEngineTest {
    private val albums = ShflAlbumCatalog.albums
    private val rumours = albums.first { it.id == "Rumours" }

    @Test fun `catalogue has unique source pages and real cover URLs`() {
        assertEquals(96, albums.size)
        assertEquals(albums.size, albums.map { it.id }.toSet().size)
        albums.forEach { album ->
            assertEquals(album.shflUrl, ShflUrlPolicy.sanitizeDestination(album.shflUrl))
            assertTrue(album.coverUrl, album.coverUrl.matches(Regex("https://images\\.theshfl\\.com/[A-Za-z0-9_-]+_600\\.jpg")))
            assertTrue(album.entryTracks.isNotEmpty())
            assertTrue(album.listeningNote.isNotBlank())
            assertTrue(album.genres.isNotEmpty())
        }
    }

    @Test fun `a familiar single on a compilation promotes the original album without inventing owned tracks`() {
        val song = song(1, "Dreams", "Fleetwood Mac", "Greatest Hits")
        val matches = AlbumRecommendationEngine.matchLibrary(albums, listOf(song))
        assertEquals(song, matches.getValue(rumours.id).entrySong)
        assertTrue(matches.getValue(rumours.id).albumSongs.isEmpty())
        val deck = deck(matches = matches)
        assertEquals(rumours.id, deck.first().album.id)
        assertEquals("Dreams", deck.first().startingTitle)
    }

    @Test fun `case diacritics and remaster metadata normalize without fuzzy artist matching`() {
        val track = song(1, "DREAMS - 2004 Remaster", "Fléetwood Mac", "Rumours (Deluxe Edition)")
        val match = AlbumRecommendationEngine.matchLibrary(albums, listOf(track)).getValue(rumours.id)
        assertEquals(track, match.entrySong)
        assertEquals(listOf(track), match.albumSongs)
    }

    @Test fun `covers and artist substrings do not become familiar tracks`() {
        val songs = listOf(song(1, "Dreams", "Another Artist", "Rumours"),
            song(2, "The World Is Yours", "Lil Nas X", "Illmatic"))
        val matches = AlbumRecommendationEngine.matchLibrary(albums, songs)
        assertNull(matches.getValue(rumours.id).entrySong)
        assertTrue(matches.getValue(rumours.id).albumSongs.isEmpty())
        assertNull(matches.getValue("Illmatic").entrySong)
    }

    @Test fun `live remixes and instrumentals are not treated as the studio entry song`() {
        val songs = listOf("Dreams (Live)", "Dreams - Remix", "Dreams (Instrumental)").mapIndexed { index, title ->
            song(index.toLong(), title, "Fleetwood Mac", "Rumours")
        }
        val match = AlbumRecommendationEngine.matchLibrary(albums, songs).getValue(rumours.id)
        assertNull(match.entrySong)
        assertTrue(match.albumSongs.isEmpty())
    }

    @Test fun `album playback follows disc and track order and removes duplicate recordings`() {
        val songs = listOf(
            song(3, "The Chain", track = 7, disc = 1),
            song(4, "Bonus track", track = 1, disc = 2),
            song(1, "Dreams", track = 2, disc = 1),
            song(2, "Dreams - 2004 Remaster", track = 2, disc = 1).copy(bitrateKbps = 1000),
        )
        val match = AlbumRecommendationEngine.matchLibrary(albums, songs).getValue(rumours.id)
        assertEquals(listOf(2L, 3L, 4L), match.albumSongs.map { it.id })
    }

    @Test fun `live in the song name is not a live recording qualifier`() {
        val album = rumours.copy(id = "oasis-test", title = "Definitely Maybe", artist = "Oasis")
        val track = song(1, "Live Forever", "Oasis", album.title)
        assertEquals(listOf(track), AlbumRecommendationEngine.matchLibrary(listOf(album), listOf(track))
            .getValue(album.id).albumSongs)
    }

    @Test fun `a live album retains its own live tracks`() {
        val album = albums.first { it.id == "Live-at-the-Regal" }
        val track = song(1, "${album.entryTracks.first()} (Live)", album.artist, album.title)
        val match = AlbumRecommendationEngine.matchLibrary(listOf(album), listOf(track)).getValue(album.id)
        assertEquals(listOf(track), match.albumSongs)
        assertEquals(track, match.entrySong)
    }

    @Test fun `unfamiliar suggestions never claim there is a playable local song`() {
        deck().forEach { assertNull(it.startingSong); assertFalse(it.familiar) }
    }

    @Test fun `explicit familiarity is respected even without a local file`() {
        val item = deck(preferences = DiscoveryPreferences(familiar = setOf(rumours.id))).first()
        assertEquals(rumours.id, item.album.id)
        assertTrue(item.familiar)
        assertNull(item.startingSong)
    }

    @Test fun `saving does not change the browsing order`() {
        val before = deck()
        val after = deck(preferences = DiscoveryPreferences(saved = setOf(before[3].album.id)))
        assertEquals(before.map { it.album.id }, after.map { it.album.id })
        assertTrue(after[3].saved)
    }

    @Test fun `saved shelf and genre filters intersect correctly`() {
        val prefs = DiscoveryPreferences(saved = setOf("Illmatic", "Rumours"))
        val deck = deck(preferences = prefs, shelf = AlbumShelf.SAVED, genre = AlbumGenre.HIP_HOP)
        assertEquals(listOf("Illmatic"), deck.map { it.album.id })
        assertTrue(deck(preferences = prefs, shelf = AlbumShelf.SAVED, genre = AlbumGenre.ELECTRONIC).isEmpty())
    }

    @Test fun `listened albums leave recommendations but stay in all and saved and can be restored`() {
        val prefs = DiscoveryPreferences(saved = setOf(rumours.id), listened = setOf(rumours.id))
        assertFalse(deck(preferences = prefs).any { it.album.id == rumours.id })
        assertTrue(deck(preferences = prefs, shelf = AlbumShelf.ALL).any { it.album.id == rumours.id && it.listened })
        assertEquals(rumours.id, deck(preferences = prefs, shelf = AlbumShelf.SAVED).single().album.id)
        assertTrue(deck(preferences = prefs.copy(listened = emptySet())).any { it.album.id == rumours.id })
    }

    @Test fun `shuffling is reproducible and visits each eligible album exactly once`() {
        assertEquals(deck().map { it.album.id }, deck().map { it.album.id })
        assertNotEquals(deck().map { it.album.id }, deck(seed = 2).map { it.album.id })
        assertEquals(albums.map { it.id }.toSet(), deck().map { it.album.id }.toSet())
        assertTrue(deck(preferences = DiscoveryPreferences(listened = albums.map { it.id }.toSet())).isEmpty())
    }

    private fun deck(
        matches: Map<String, AlbumLibraryMatch> = emptyMap(), preferences: DiscoveryPreferences = DiscoveryPreferences(),
        shelf: AlbumShelf = AlbumShelf.FOR_YOU, genre: AlbumGenre? = null, seed: Long = 1L,
    ) = AlbumRecommendationEngine.suggestions(albums, matches, preferences, shelf, genre, seed)

    private fun song(id: Long, title: String, artist: String = "Fleetwood Mac", album: String = "Rumours",
        track: Int? = null, disc: Int? = null) = Song(
        id, "content://test/$id", title, artist, album, 1L, null, null, null, null, disc, track,
        180_000L, 320, null, null, null, null, null, null, null,
    )
}
