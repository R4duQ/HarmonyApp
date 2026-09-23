package com.harmony.domain.library.discovery

import com.harmony.domain.library.repository.DiscoveryVote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteProfileTest {
    private val library = listOf(
        librarySong(1, "Old Favourite", genre = "Rock"),
        librarySong(2, "New Crush", genre = "Indie"),
        librarySong(3, "Skipped Once", genre = "Jazz"),
        librarySong(4, "Mostly Loved", genre = "Soul"),
        librarySong(5, "Fav Artist", genre = "(13)"),
        librarySong(6, "Listed Artist", genre = "Unknown"),
    )

    private fun build(
        events: List<ListeningEvent> = emptyList(),
        favorites: Set<Long> = emptySet(),
        playlists: Set<Long> = emptySet(),
        feedback: List<TasteFeedback> = emptyList(),
        pickedArtists: List<String> = emptyList(),
        pickedGenres: List<String> = emptyList(),
        votes: List<DiscoveryVote> = emptyList(),
    ) = TasteProfileBuilder.build(TasteInputs(library, events, favorites, playlists, votes, feedback, pickedArtists, pickedGenres, NOW))

    @Test fun recentListeningLeadsWithoutErasingLongTermTaste() {
        val profile = build(plays(1, 25, daysAgo = 400) + plays(2, 6, daysAgo = 2, spacingHours = 20))
        assertEquals("New Crush", profile.artists.first().name)
        val old = requireNotNull(profile.artist("Old Favourite"))
        assertTrue("long-term favourite stays liked", old.score >= TasteProfileBuilder.LIKED_THRESHOLD)
    }

    @Test fun anIsolatedSkipNeverExcludesAnArtist() {
        val profile = build(plays(3, 1, daysAgo = 1, completed = false) + plays(4, 8, daysAgo = 5) + plays(4, 1, daysAgo = 1, completed = false))
        val skipped = requireNotNull(profile.artist("Skipped Once"))
        assertTrue(skipped.score > -1f)
        assertEquals(1, skipped.skipped)
        assertTrue(requireNotNull(profile.artist("Mostly Loved")).score >= TasteProfileBuilder.LIKED_THRESHOLD)
    }

    @Test fun replaysCountAsExtraInterest() {
        val replayed = build(plays(2, 4, daysAgo = 3, spacingHours = 2))
        val spread = build(plays(2, 4, daysAgo = 3, spacingHours = 24 * 7))
        assertEquals(3, replayed.artist("New Crush")!!.replays)
        assertTrue(replayed.artist("New Crush")!!.score > spread.artist("New Crush")!!.score)
    }

    @Test fun reasonsNameTheirSource() {
        val profile = build(plays(2, 4, daysAgo = 3), favorites = setOf(5), playlists = setOf(6))
        assertEquals(ReasonKind.FAVORITE, profile.reasonFor("Fav Artist")!!.kind)
        assertEquals("Listed Artist is in your playlists", profile.reasonFor("Listed Artist")!!.text)
        assertEquals(ReasonKind.FREQUENT_RECENT, profile.reasonFor("New Crush")!!.kind)
        assertEquals(null, profile.reasonFor("Nobody"))
    }

    @Test fun placeholderGenresAreIgnored() {
        val profile = build(plays(5, 5, daysAgo = 1) + plays(6, 5, daysAgo = 1) + plays(1, 5, daysAgo = 1))
        val genres = profile.genres.map { it.key }
        assertTrue("rock" in genres)
        assertFalse(genres.any { it == "13" || it == "unknown" })
    }

    @Test fun coldStartPicksBecomeLikedWithHonestReasons() {
        val profile = build(pickedArtists = listOf("Radiohead"), pickedGenres = listOf("Electronic"))
        assertFalse(profile.hasListeningData)
        assertEquals("You picked Radiohead", profile.reasonFor("Radiohead")!!.text)
        assertNotNull(profile.likedGenres.firstOrNull { it.key == "electronic" })
    }

    @Test fun explicitFeedbackOutweighsPassiveSignals() {
        val base = plays(2, 3, daysAgo = 2)
        val more = build(base, feedback = listOf(TasteFeedback(FeedbackKind.MORE_LIKE_THIS, "k", "t", "New Crush", at = NOW)))
        val less = build(base, feedback = listOf(TasteFeedback(FeedbackKind.NOT_INTERESTED, "k", "t", "New Crush", at = NOW)))
        assertTrue(more.artist("New Crush")!!.score > build(base).artist("New Crush")!!.score)
        assertTrue(less.artist("New Crush")!!.score < build(base).artist("New Crush")!!.score)
    }

    @Test fun earlierDiscoverSwipesStillCount() {
        val liked = remote(9, "Swiped Artist")
        val profile = build(votes = listOf(DiscoveryVote(liked, true)))
        assertEquals("You liked Swiped Artist in Discover", profile.reasonFor("Swiped Artist")!!.text)
    }
}
