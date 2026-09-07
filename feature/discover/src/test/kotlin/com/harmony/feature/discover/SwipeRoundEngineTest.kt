package com.harmony.feature.discover

import com.harmony.feature.discover.model.*
import com.harmony.feature.discover.provider.*
import org.junit.Assert.*
import org.junit.Test

class SwipeRoundEngineTest {
    private val songs = SongTasteEngine.songs
    private val choices = songs.distinctBy { it.album.id }.take(SwipeRound.SIZE)
    private fun complete(liked: Boolean = true, initial: DiscoveryPreferences = DiscoveryPreferences()) =
        choices.fold(initial) { prefs, song -> SwipeRoundEngine.rate(prefs, song.id, liked) }

    @Test fun tenthVoteRevealsExactlyOneAlbumAndBlocksFurtherVotes() {
        var prefs = DiscoveryPreferences()
        choices.dropLast(1).forEach { song -> prefs = SwipeRoundEngine.rate(prefs, song.id, true) }
        assertEquals(9, prefs.round.songIds.size)
        assertFalse(prefs.round.completed)
        assertNull(RoundAlbumRecommender.explain(prefs))
        prefs = SwipeRoundEngine.rate(prefs, choices.last().id, false)
        assertTrue(prefs.round.completed)
        assertEquals(10, prefs.round.songIds.size)
        assertNotNull(RoundAlbumRecommender.explain(prefs))
        val eleventh = songs.first { it.id !in prefs.round.songIds }
        assertEquals(prefs, SwipeRoundEngine.rate(prefs, eleventh.id, true))
    }

    @Test fun duplicateVotesDoNotCountOrChangeDirection() {
        val first = SwipeRoundEngine.rate(DiscoveryPreferences(), choices.first().id, true)
        assertEquals(first, SwipeRoundEngine.rate(first, choices.first().id, false))
        assertEquals(1, first.round.songIds.size)
        assertTrue(first.dislikedSongs.isEmpty())
        assertEquals(first, SwipeRoundEngine.next(first))
        assertEquals(first, SwipeRoundEngine.finishRemaining(first))
    }

    @Test fun undoReopensTheNinthChoiceWithoutPollutingRecommendationHistory() {
        val completed = complete()
        val undone = SwipeRoundEngine.rate(completed, choices.last().id, null)
        assertFalse(undone.round.completed)
        assertNull(undone.round.albumId)
        assertNull(RoundAlbumRecommender.explain(undone))
        assertEquals(9, undone.round.songIds.size)
        assertFalse(choices.last().id in undone.likedSongs)
        assertTrue(undone.recommendedAlbums.isEmpty())
        assertEquals(completed, SwipeRoundEngine.rate(undone, choices.last().id, true))
    }

    @Test fun nextRoundRetainsTasteAndRecordsTheRevealOnlyOnce() {
        val completed = complete()
        val next = SwipeRoundEngine.next(completed)
        assertEquals(2, next.round.number)
        assertTrue(next.round.songIds.isEmpty())
        assertFalse(next.round.completed)
        assertEquals(completed.likedSongs, next.likedSongs)
        assertEquals(setOf(completed.round.albumId), next.recommendedAlbums)
        assertEquals(next, SwipeRoundEngine.next(next))
        assertEquals(next, SwipeRoundEngine.rate(next, choices.last().id, null))
    }

    @Test fun onlyTheFinalChoiceCanBeUndone() {
        val prefs = choices.take(3).fold(DiscoveryPreferences()) { p, song -> SwipeRoundEngine.rate(p, song.id, false) }
        assertEquals(prefs, SwipeRoundEngine.rate(prefs, choices.first().id, null))
        assertEquals(2, SwipeRoundEngine.rate(prefs, choices[2].id, null).round.songIds.size)
    }

    @Test fun recentLikesCanOvertakeAnOlderFavorite() {
        val metal = songs.filter { it.album.id == "Master-of-Puppets" }.map { it.id }.toSet()
        val jazz = songs.first { it.album.id == "Kind-of-Blue" }
        val prefs = DiscoveryPreferences(likedSongs = metal + jazz.id, round = SwipeRound(songIds = listOf(jazz.id)))
        assertEquals(jazz.album.id, RoundAlbumRecommender.choose(prefs)?.id)
    }

    @Test fun anExplicitRejectionIsAvoidedEvenIfOlderSongsFromItsAlbumWereLiked() {
        val related = songs.filter { it.album.id == "Random-Access-Memories" }
        val prefs = DiscoveryPreferences(likedSongs = setOf(related.first().id),
            dislikedSongs = setOf(related.last().id), round = SwipeRound(songIds = listOf(related.last().id)))
        assertNotEquals(related.first().album.id, RoundAlbumRecommender.choose(prefs)?.id)
    }

    @Test fun aNewRelatedAlbumIsPreferredToRepeatingThePreviousReveal() {
        val first = complete()
        val again = SwipeRoundEngine.next(first)
        assertNotEquals(first.round.albumId, RoundAlbumRecommender.choose(again)?.id)
        assertNotNull(RoundAlbumRecommender.choose(again))
    }

    @Test fun listenedAlbumsAreExcludedAndExhaustionHasAnExplicitEmptyResult() {
        val first = complete()
        val withoutFirst = first.copy(listened = setOfNotNull(first.round.albumId))
        assertNotEquals(first.round.albumId, RoundAlbumRecommender.choose(withoutFirst)?.id)
        val allListened = complete(initial = DiscoveryPreferences(listened = ShflAlbumCatalog.ids))
        assertTrue(allListened.round.completed)
        assertNull(allListened.round.albumId)
        assertNull(RoundAlbumRecommender.explain(allListened))
    }

    @Test fun allDislikesAreDescribedAsExplorationWithoutInventedLikes() {
        val prefs = complete(liked = false)
        val explained = requireNotNull(RoundAlbumRecommender.explain(prefs))
        assertTrue(explained.exploratory)
        assertTrue(explained.supportingSongs.isEmpty())
        assertFalse(explained.reason.contains("You liked"))
        assertTrue(explained.reason.contains("exploratory"))
        assertFalse(explained.album.id in choices.map { it.album.id })
    }

    @Test fun explanationNamesRealLikedSongsAndItsAlbumKeepsTheOriginalNote() {
        val prefs = complete()
        val explained = requireNotNull(RoundAlbumRecommender.explain(prefs))
        assertFalse(explained.exploratory)
        assertTrue(explained.supportingSongs.isNotEmpty())
        explained.supportingSongs.forEach {
            assertTrue(it.id in prefs.likedSongs)
            assertTrue(explained.reason.contains(it.title))
            assertTrue(explained.reason.contains(it.album.artist))
        }
        assertTrue(explained.album.listeningNote.isNotBlank())
        assertEquals("https://theshfl.com/album/${explained.album.id}", explained.album.shflUrl)
        // Bookmark/familiarity changes cannot silently reroll the reveal or its explanation.
        assertEquals(explained, RoundAlbumRecommender.explain(prefs.copy(saved = ShflAlbumCatalog.ids, familiar = ShflAlbumCatalog.ids)))
    }

    @Test fun earlierLikesAreIdentifiedAsEarlierWhenCurrentRoundHasNone() {
        val metal = songs.first { it.album.id == "Master-of-Puppets" }
        val explained = requireNotNull(RoundAlbumRecommender.explain(complete(false,
            DiscoveryPreferences(likedSongs = setOf(metal.id)))))
        assertTrue(explained.reason.contains("earlier choices"))
        assertEquals(listOf(metal), explained.supportingSongs)
    }

    @Test fun differentAlbumsComeBeforeOtherSinglesFromTheSameRecord() {
        val heard = choices.first()
        val prefs = SwipeRoundEngine.rate(DiscoveryPreferences(), heard.id, true)
        val deck = SongTasteEngine.unplayed(prefs, null, 37)
        val same = deck.indexOfFirst { it.album.id == heard.album.id }
        assertTrue(same > 0)
        assertTrue(deck.take(same).none { it.album.id == heard.album.id })
        assertTrue(deck.drop(same).all { it.album.id == heard.album.id })
    }

    @Test fun finalPartialRoundCanRevealAndCannotOfferNonexistentNewSongs() {
        val last = songs.last()
        val old = DiscoveryPreferences(likedSongs = SongTasteEngine.songIds - last.id,
            round = SwipeRound(number = 24))
        assertFalse(SwipeRoundEngine.canFinishRemaining(old))
        val partial = SwipeRoundEngine.rate(old, last.id, false)
        assertEquals(1, partial.round.songIds.size)
        assertTrue(SwipeRoundEngine.canFinishRemaining(partial))
        val revealed = SwipeRoundEngine.finishRemaining(partial)
        assertTrue(revealed.round.completed)
        assertNotNull(revealed.round.albumId)
        assertFalse(SwipeRoundEngine.hasUnratedSongs(revealed))
        assertEquals(revealed, SwipeRoundEngine.next(revealed))
    }

    @Test fun anExhaustedLegacyHistoryCanRevealWithoutInventingARound() {
        val legacy = DiscoveryPreferences(dislikedSongs = SongTasteEngine.songIds)
        val prefs = SwipeRoundEngine.finishRemaining(legacy)
        assertTrue(prefs.round.completed)
        assertTrue(prefs.round.songIds.isEmpty())
        val explained = requireNotNull(RoundAlbumRecommender.explain(prefs))
        assertTrue(explained.reason.contains("saved choices"))
        assertFalse(explained.reason.contains("this round's songs"))
    }
}
