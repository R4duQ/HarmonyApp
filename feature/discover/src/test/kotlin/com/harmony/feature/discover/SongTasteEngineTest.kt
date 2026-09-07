package com.harmony.feature.discover

import com.harmony.feature.discover.model.*
import com.harmony.feature.discover.provider.*
import org.junit.Assert.*
import org.junit.Test

class SongTasteEngineTest {
    private val albums = ShflAlbumCatalog.albums
    private val metalSong = SongTasteEngine.songs.first { it.album.id == "Master-of-Puppets" }
    private fun ranked(prefs: DiscoveryPreferences = DiscoveryPreferences(), shelf: AlbumShelf = AlbumShelf.FOR_YOU) =
        AlbumRecommendationEngine.suggestions(albums, emptyMap(), prefs, shelf, null, 37)

    @Test fun likesPromoteTheAlbumAndRelatedGenres() {
        val scores = SongTasteEngine.scores(albums, DiscoveryPreferences(likedSongs = setOf(metalSong.id)))
        assertTrue(scores.getValue("Master-of-Puppets") > scores.getValue("Paranoid"))
        assertTrue(scores.getValue("Paranoid") > scores.getValue("Kind-of-Blue"))
        assertEquals("Master-of-Puppets", ranked(DiscoveryPreferences(likedSongs = setOf(metalSong.id))).first().album.id)
    }
    @Test fun dislikesLowerTheAlbumAndItsGenreWithoutHidingThemFromAll() {
        val prefs = DiscoveryPreferences(dislikedSongs = setOf(metalSong.id))
        val scores = SongTasteEngine.scores(albums, prefs)
        assertTrue(scores.getValue("Master-of-Puppets") < scores.getValue("Kind-of-Blue"))
        assertTrue(scores.getValue("Paranoid") < 0)
        assertEquals(ranked(shelf = AlbumShelf.ALL), ranked(prefs, AlbumShelf.ALL))
    }
    @Test fun choicesAreExcludedUntilUndoneAndGenreFilterApplies() {
        val initial = SongTasteEngine.unplayed(DiscoveryPreferences(), AlbumGenre.METAL, 9)
        assertTrue(initial.any { it.id == metalSong.id })
        val rated = SongTasteEngine.unplayed(DiscoveryPreferences(likedSongs = setOf(metalSong.id)), AlbumGenre.METAL, 9)
        assertFalse(rated.any { it.id == metalSong.id })
        assertTrue(rated.all { AlbumGenre.METAL in it.album.genres })
        assertEquals(initial, SongTasteEngine.unplayed(DiscoveryPreferences(), AlbumGenre.METAL, 9))
    }
    @Test fun eachGenreHasActualAlbumsAndSwipeSongs() {
        AlbumGenre.entries.forEach { genre ->
            assertTrue(genre.name, albums.any { genre in it.genres })
            assertTrue(genre.name, SongTasteEngine.songs.any { genre in it.album.genres })
        }
        assertEquals(SongTasteEngine.songs.size, SongTasteEngine.songIds.size)
    }
    @Test fun refreshVisitsFourDifferentBatchesBeforeCycling() {
        val order = ranked()
        var history = AlbumBatchHistory()
        val visited = mutableSetOf<String>()
        var last = emptyList<AlbumSuggestion>()
        repeat(4) {
            val page = AlbumBatches.page(order, history)
            assertEquals(24, page.size)
            assertTrue(page.none { it.album.id in visited })
            visited.addAll(page.map { it.album.id })
            history = AlbumBatches.advance(order, history, page)
            last = page
        }
        assertEquals(96, visited.size)
        assertTrue(AlbumBatches.page(order, history).none { next -> last.any { it.album.id == next.album.id } })
    }
    @Test fun smallAndIncompleteBatchesReportRealResultsWithoutDuplicates() {
        val small = ranked().take(7)
        assertEquals(7, AlbumBatches.page(small, AlbumBatchHistory()).size)
        val medium = ranked().take(29)
        val first = AlbumBatches.page(medium, AlbumBatchHistory())
        val next = AlbumBatches.page(medium, AlbumBatches.advance(medium, AlbumBatchHistory(), first))
        assertEquals(5, next.size)
        assertTrue(next.none { it in first })
        assertTrue(AlbumBatches.page(emptyList(), AlbumBatchHistory()).isEmpty())
    }

    @Test fun eachNewCycleIncludesEveryAlbumAndDefersThePreviousPageOnlyOnce() {
        var history = AlbumBatchHistory()
        var previous = emptySet<String>()
        var seed = 37L
        repeat(3) {
            val visited = mutableSetOf<String>()
            repeat(4) {
                val order = AlbumRecommendationEngine.suggestions(albums, emptyMap(), DiscoveryPreferences(), AlbumShelf.ALL, null, seed++)
                val page = AlbumBatches.page(order, history)
                val ids = page.map { it.album.id }.toSet()
                assertEquals(24, ids.size)
                assertTrue(ids.intersect(previous).isEmpty())
                assertTrue(ids.intersect(visited).isEmpty())
                visited.addAll(ids)
                history = AlbumBatches.advance(order, history, page)
                previous = ids
            }
            assertEquals(albums.map { it.id }.toSet(), visited)
        }
    }

    @Test fun incompleteCyclesDoNotSkipAlbumsOrRepeatThePreviousBatch() {
        listOf(25, 29, 48, 49, 73, 96).forEach { size ->
            val order = ranked().take(size)
            var history = AlbumBatchHistory()
            var previous = emptySet<String>()
            repeat(3) {
                val visited = mutableSetOf<String>()
                while (visited.size < size) {
                    val page = AlbumBatches.page(order, history)
                    val ids = page.map { it.album.id }.toSet()
                    assertTrue("Empty batch for $size albums", ids.isNotEmpty())
                    assertTrue(ids.size <= 24)
                    assertTrue("Repeated album in cycle for $size albums", ids.intersect(visited).isEmpty())
                    assertTrue("Repeated previous page for $size albums", ids.intersect(previous).isEmpty())
                    visited.addAll(ids)
                    history = AlbumBatches.advance(order, history, page)
                    previous = ids
                }
                assertEquals(size, visited.size)
            }
        }
    }

    @Test fun refreshingPersonalRecommendationsDoesNotKeepTheSameTopRankedAlbums() {
        val preferences = DiscoveryPreferences(likedSongs = setOf(metalSong.id))
        val order = ranked(preferences)
        val first = AlbumBatches.page(order, AlbumBatchHistory())
        assertEquals(metalSong.album.id, first.first().album.id)
        val history = AlbumBatches.advance(order, AlbumBatchHistory(), first)
        val reordered = AlbumRecommendationEngine.suggestions(albums, emptyMap(), preferences, AlbumShelf.FOR_YOU, null, 38)
        val next = AlbumBatches.page(reordered, history)
        assertEquals(24, next.size)
        assertTrue(next.none { item -> first.any { it.album.id == item.album.id } })
    }

    @Test fun everyGenreShowsOnlyItsOwnAlbumsAndSmallSelectionsFitOneBatch() {
        AlbumGenre.entries.forEach { genre ->
            val order = AlbumRecommendationEngine.suggestions(albums, emptyMap(), DiscoveryPreferences(), AlbumShelf.ALL, genre, 37)
            val page = AlbumBatches.page(order, AlbumBatchHistory())
            assertTrue(page.all { genre in it.album.genres })
            if (order.size <= 24) assertEquals(order, page)
            else {
                val next = AlbumBatches.page(order, AlbumBatches.advance(order, AlbumBatchHistory(), page))
                assertTrue(next.none { item -> page.any { it.album.id == item.album.id } })
            }
        }
    }

    @Test fun removingTheLastAlbumOfTheLastSavedBatchDoesNotHideOtherSavedAlbums() {
        val saved = ranked().take(25)
        val first = AlbumBatches.page(saved, AlbumBatchHistory())
        val history = AlbumBatches.advance(saved, AlbumBatchHistory(), first)
        val last = AlbumBatches.page(saved, history).single()
        val remaining = saved.filterNot { it.album.id == last.album.id }
        assertEquals(24, AlbumBatches.page(remaining, history).size)
    }
    @Test fun alternateTitlesDoNotCreateDuplicateVotesOrRenumberOtherSongs() {
        val kate = SongTasteEngine.songs.filter { it.album.id == "Hounds-of-Love" }
        assertEquals(setOf("Hounds-of-Love:0", "Hounds-of-Love:2"), kate.map { it.id }.toSet())
        assertEquals("Hounds of Love", kate.single { it.id == "Hounds-of-Love:2" }.title)
        assertEquals("Hounds-of-Love:0", SongTasteEngine.canonicalSongId("Hounds-of-Love:1"))
        assertNull(SongTasteEngine.canonicalSongId("unknown:0"))
    }
}
