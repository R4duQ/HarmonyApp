package com.harmony.feature.discover

import com.harmony.domain.library.discovery.CandidateKind
import com.harmony.domain.library.discovery.ExplorationLevel
import com.harmony.domain.library.discovery.LibraryIndex
import com.harmony.domain.library.discovery.ListeningEvent
import com.harmony.domain.library.discovery.ReasonKind
import com.harmony.domain.library.discovery.TasteInputs
import com.harmony.domain.library.discovery.TasteProfileBuilder
import com.harmony.feature.discover.provider.RecommendationEngine
import com.harmony.feature.discover.provider.RecommendationRequest
import com.harmony.feature.discover.provider.SourceProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    // Muse played a lot recently; Foo Fighters liked long ago; a never-played rock song in the library.
    private val library = listOf(
        libSong(1, "Muse"), libSong(2, "Muse"), libSong(3, "Foo Fighters"),
        libSong(4, "Unplayed Band", genre = "Rock"),
        // A file of a song Deezer also lists, already heard: not a discovery.
        libSong(5, "Muse", title = "Muse track 1", ms = 181_001L),
    )
    private val events = (0 until 6).map { ListeningEvent(1, now - it * day, true) } +
        (0 until 3).map { ListeningEvent(5, now - 70 * day - it * day, true) } +
        (0 until 8).map { ListeningEvent(3, now - 300 * day - it * day, true) }
    private val profile = TasteProfileBuilder.build(TasteInputs(library, events, now = now))
    private val catalog = FakeCatalog()
    private val engine = RecommendationEngine(catalog)

    private fun request(level: ExplorationLevel = ExplorationLevel.BALANCED, online: Boolean = true, declined: Set<String> = emptySet()) =
        RecommendationRequest(profile, LibraryIndex(library), level, online, now, seed = 7, declined = declined)

    @Test fun closeSongsCarryTheRealReasonAndDiscoveriesNameTheirLink() = runBlocking {
        val out = engine.candidates(request())
        val museTrack = out.candidates.first { it.song.key == "dz:202" }
        assertEquals(CandidateKind.CLOSE, museTrack.kind)
        assertEquals("You've played Muse a lot lately", museTrack.reason.text)
        val radiohead = out.candidates.first { it.song.artist == "Radiohead" }
        assertEquals(CandidateKind.EXPLORE, radiohead.kind)
        assertEquals("Deezer lists Radiohead as related to Muse", radiohead.reason.text)
        assertTrue(out.sources.containsAll(listOf("Your library", "Deezer")))
        assertTrue(out.usedOnline)
    }

    @Test fun artistsTheListenerAlreadyLikesAreNeverCountedAsDiscoveries() = runBlocking {
        val out = engine.candidates(request(ExplorationLevel.SURPRISE_ME))
        assertTrue(out.candidates.filter { it.song.artist == "Foo Fighters" }.none { it.kind == CandidateKind.EXPLORE })
    }

    @Test fun surpriseReachesTwoStepsAlongTheGraphAndForMyTasteDoesNot() = runBlocking {
        val far = engine.candidates(request(ExplorationLevel.SURPRISE_ME)).candidates.filter { it.reason.kind == ReasonKind.RELATED_TWO_STEPS }
        assertTrue(far.isNotEmpty())
        assertTrue(far.all { it.reason.text.contains("who is related to") })
        val close = engine.candidates(request(ExplorationLevel.FOR_MY_TASTE)).candidates
        assertTrue(close.none { it.reason.kind == ReasonKind.RELATED_TWO_STEPS })
    }

    @Test fun offlineUsesOnlyTheLibraryAndTouchesNoCatalog() = runBlocking {
        val out = engine.candidates(request(online = false))
        assertTrue(out.candidates.isNotEmpty())
        assertTrue(out.candidates.all { it.song.localUri != null })
        assertEquals(listOf("Your library"), out.sources)
        assertFalse(out.usedOnline)
        assertTrue(catalog.calls.isEmpty())
        // The never-played song is offered as a library discovery, with its reason.
        assertEquals(ReasonKind.LIBRARY_UNPLAYED, out.candidates.first { it.song.artist == "Unplayed Band" }.reason.kind)
    }

    @Test fun whenDeezerIsDownAppleFillsInAndTheProblemIsReported() = runBlocking {
        catalog.deezer = SourceProblem.UNAVAILABLE
        val out = engine.candidates(request())
        assertTrue(out.candidates.any { it.song.provider == "Apple Music" && it.kind == CandidateKind.CLOSE })
        assertTrue(out.problems.any { it.contains("Deezer is unavailable") })
        // The breaker stops Deezer after the first failure instead of trying every step.
        assertEquals(1, catalog.calls.count { !it.startsWith("apple") && !it.startsWith("regional") })
    }

    @Test fun losingTheConnectionMidwayKeepsWhatTheLibraryGave() = runBlocking {
        catalog.offline = true
        val out = engine.candidates(request())
        assertTrue(out.offline)
        assertTrue(out.candidates.isNotEmpty())
        assertTrue(out.candidates.all { it.song.localUri != null })
    }

    @Test fun declinedAndAlreadyHeardSongsAreLeftOut() = runBlocking {
        val out = engine.candidates(request(declined = setOf("dz:203")))
        assertTrue(out.candidates.none { it.song.key == "dz:203" })
        // "Muse track 1" matches library song 5, heard 70 days ago: dz:201 is not offered as new.
        assertTrue(out.candidates.none { it.song.key == "dz:201" })
    }

    @Test fun noHistoryAndNoPicksFallsBackToChartsLabelledAsCharts() = runBlocking {
        val empty = TasteProfileBuilder.build(TasteInputs(now = now))
        val out = engine.candidates(RecommendationRequest(empty, LibraryIndex(emptyList()), ExplorationLevel.BALANCED, true, now, 1))
        assertTrue(out.candidates.isNotEmpty())
        assertTrue(out.candidates.all { it.reason.text == "Popular on Deezer right now" })
    }

    @Test fun moreLikeThisStaysWithTheArtistAndItsNeighbours() = runBlocking {
        val seed = engine.candidates(request()).candidates.first { it.song.artist == "Radiohead" }
        val out = engine.moreLike(com.harmony.domain.library.discovery.DraftItem(seed.song, seed.kind, seed.reason), request())
        val artists = out.candidates.map { it.song.artist }.toSet()
        assertTrue("Radiohead" in artists)
        assertTrue(artists.containsAll(listOf("Portishead", "Massive Attack")))
        assertTrue(out.candidates.first { it.song.artist == "Portishead" }.reason.text.contains("related to Radiohead"))
    }
}
