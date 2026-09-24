package com.harmony.domain.library.repository

import com.harmony.core.model.Song
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DiscoveryRulesTest {
    private fun track(i: Int) = DiscoverySong("dz:$i", "Song $i", "Artist $i", durationMs = 180_000, genres = setOf("Pop"))
    private fun vote(s: SongDiscoveryState, i: Int, liked: Boolean) = DiscoveryRules.vote(s, track(i), liked, "batch", "Discover test")
    private fun song(i: Int) = Song(id = i.toLong(), uri = "content://songs/$i", title = "Song $i", artist = "Artist $i", album = "Album",
        albumId = 1, albumArtist = null, composer = null, year = null, genre = "Pop", discNumber = null, trackNumber = null,
        durationMs = 180_000, bitrateKbps = null, sampleRateHz = null, bitDepth = null, channels = null, artworkUri = null,
        embeddedLyrics = null, replayGainTrackDb = null, replayGainAlbumDb = null)

    @Test fun fiftyDislikesDoNotCreateAPlaylist() {
        val state = (1..50).fold(SongDiscoveryState()) { s, i -> vote(s, i, false) }
        assertEquals(0, state.likes.size); assertTrue(state.batches.isEmpty()); assertNull(state.revealedBatch)
    }
    @Test fun exactlyFiftyLikesCreateAnImmutableBatchInSwipeOrder() {
        var state = (1..49).fold(SongDiscoveryState()) { s, i -> vote(s, i, true) }
        assertEquals(49, state.likes.size); assertTrue(state.batches.isEmpty())
        state = vote(state, 50, true)
        assertEquals((1..50).map { "dz:$it" }, state.batches.single().songs.map { it.key })
        assertEquals("batch", state.revealedBatch); assertTrue(state.likes.isEmpty())
        assertEquals(state, vote(state, 51, true)); assertEquals(state, DiscoveryRules.undo(state))
    }
    @Test fun passDoesNotAdvanceLikeCounterAndUndoRemovesOnlyLastVote() {
        val state = vote(vote(SongDiscoveryState(), 1, true), 2, false)
        assertEquals(1, state.likes.size)
        val undone = DiscoveryRules.undo(state)
        assertEquals(listOf(track(1)), undone.likes); assertEquals(1, undone.votes.size)
        assertEquals(undone, DiscoveryRules.undo(undone))
    }
    @Test fun undoLikeAndReloadDoesNotLoseOlderLikes() {
        val state = vote(vote(SongDiscoveryState(), 1, true), 2, true)
        assertEquals(listOf(track(1)), DiscoveryRules.undo(state).likes)
    }
    @Test fun duplicateCrossProviderLikeIsIgnored() {
        val state = vote(SongDiscoveryState(), 1, true)
        assertEquals(state, DiscoveryRules.vote(state, track(1).copy(key = "apple:1"), true, "x", "x"))
        assertEquals(state, vote(state, 1, false))
    }
    @Test fun differentLiveVersionsAreNotDeduplicated() {
        val studio = track(1); val live = studio.copy(key = "dz:2", title = studio.title + " (Live)")
        assertFalse(DiscoveryRules.sameRecording(studio, live))
        assertEquals(2, DiscoveryRules.deduplicate(listOf(studio, studio.copy(key = "apple:1"), live)).size)
    }
    @Test fun editionsWithClearlyDifferentDurationsAreNotCollapsed() {
        assertFalse(DiscoveryRules.sameRecording(track(1), track(1).copy(key = "dz:2", durationMs = 230_000)))
    }
    @Test fun isrcExcludesCrossCatalogVariantsEvenWhenCreditsDiffer() {
        val original = track(1).copy(isrc = "USABC1234567")
        val alias = track(2).copy(isrc = original.isrc)
        assertEquals(listOf(original), DiscoveryRules.deduplicate(listOf(original, alias)))
        val state = DiscoveryRules.vote(SongDiscoveryState(), original, false, "b", "n")
        assertNull(DiscoveryRules.select(listOf(alias), state, Random(0)))
    }
    @Test fun localMatchingUsesArtistTitleAndDurationNotAlbumOnly() {
        assertEquals(song(1), DiscoveryRules.localMatch(track(1), listOf(song(1))))
        assertNull(DiscoveryRules.localMatch(track(1).copy(title = "Song 1 (Live)"), listOf(song(1))))
        assertNull(DiscoveryRules.localMatch(track(1).copy(artist = "Cover artist"), listOf(song(1))))
        assertNull(DiscoveryRules.localMatch(track(1).copy(durationMs = 210_000), listOf(song(1))))
    }
    @Test fun changingPreferencesKeepsExistingLikesAndExcludesWrongGenres() {
        val state = vote(SongDiscoveryState(), 1, true).copy(filter = DiscoveryFilter(genre = "Rock"))
        assertNull(DiscoveryRules.select(listOf(track(2)), state, Random(4)))
        assertEquals(listOf(track(1)), state.likes)
    }
    @Test fun marketIsNeverMistakenForArtistCountry() {
        val ro = SongDiscoveryState(filter = DiscoveryFilter(country = "RO", artistCountryOnly = true))
        assertNull(DiscoveryRules.select(listOf(track(1).copy(market = "RO")), ro, Random(1)))
        assertNull(DiscoveryRules.select(listOf(track(1).copy(artistCountry = "US")), ro, Random(1)))
        assertNotNull(DiscoveryRules.select(listOf(track(1).copy(artistCountry = "RO")), ro, Random(1)))
    }
    @Test fun randomArtistCountryModeStillExcludesUnknownOrigin() {
        val state = SongDiscoveryState(filter = DiscoveryFilter(artistCountryOnly = true))
        assertNull(DiscoveryRules.select(listOf(track(1)), state, Random(1)))
        assertNotNull(DiscoveryRules.select(listOf(track(1).copy(artistCountry = "GB")), state, Random(1)))
    }
    @Test fun unseenDiverseArtistsWinOverImmediateRepeats() {
        val state = vote(SongDiscoveryState(), 1, true)
        val repeated = track(2).copy(artist = track(1).artist)
        repeat(30) { assertEquals(track(3), DiscoveryRules.select(listOf(repeated, track(3)), state, Random(it))) }
    }
    @Test fun localAndRemoteAreBothUsedWhenBothExist() {
        var state = SongDiscoveryState(); val pool = (1..90).map { track(it).copy(localUri = if (it <= 25) "content://$it" else null) }
        val chosen = mutableListOf<DiscoverySong>()
        repeat(20) {
            val song = requireNotNull(DiscoveryRules.select(pool, state, Random(it)))
            chosen += song; state = DiscoveryRules.vote(state, song, true, "b", "n")
        }
        assertTrue(chosen.count { it.localUri != null } in 4..6)
        assertEquals(20, chosen.distinctBy { it.key }.size)
    }
    @Test fun exhaustedPoolReturnsNullInsteadOfRecyclingDislikes() {
        assertNull(DiscoveryRules.select(listOf(track(1)), vote(SongDiscoveryState(), 1, false), Random(0)))
    }
    @Test fun successfulCompletionRequiresAllFiftyReadableAndIndexedDistinctFiles() {
        val songs = (1..50).map(::song)
        val batch = DiscoveryBatch("b", "name", (1..50).map(::track), (1..50).associate { "dz:$it" to "content://songs/$it" })
        val uris = songs.map { it.uri }.toSet()
        assertEquals(songs.map { it.id }, DiscoveryRules.readyPlaylistIds(batch, songs.reversed(), uris))
        assertNull(DiscoveryRules.readyPlaylistIds(batch, songs.dropLast(1), uris))
        assertNull(DiscoveryRules.readyPlaylistIds(batch, songs, uris - songs.last().uri))
        assertNull(DiscoveryRules.readyPlaylistIds(batch.copy(uris = batch.uris - "dz:50"), songs, uris))
        assertNull(DiscoveryRules.readyPlaylistIds(batch.copy(uris = batch.uris + ("dz:50" to songs.first().uri)), songs, uris))
    }
    @Test fun vibeHeuristicIsExplicitAndDoesNotInventAudioMetrics() {
        assertEquals(1.2f, DiscoveryRules.vibeAffinity(track(1).copy(vibe = "Chill"), "Chill"))
        assertEquals(.6f, DiscoveryRules.vibeAffinity(track(1).copy(genres = setOf("Ambient")), "Focus"))
        assertEquals(0f, DiscoveryRules.vibeAffinity(track(1).copy(genres = emptySet()), "Focus"))
    }
    @Test fun genreAliasesRecognizeRapHipHop() {
        assertTrue(DiscoveryRules.genreMatches(track(1).copy(genres = setOf("Hip-Hop/Rap")), "Rap/Hip Hop"))
        assertFalse(DiscoveryRules.genreMatches(track(1), "Trap"))
    }
}
