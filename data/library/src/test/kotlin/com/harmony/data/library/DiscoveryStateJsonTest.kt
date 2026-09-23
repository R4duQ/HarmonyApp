package com.harmony.data.library

import com.harmony.domain.library.repository.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryStateJsonTest {
    private val tracks = (1..50).map { DiscoverySong("dz:$it", "Piesă $it (Live)", "Artist $it", durationMs = 200_000,
        genres = setOf("Pop", "Trap"), localUri = if (it == 1) "content://media/1" else null, artistCountry = "RO") }
    @Test fun batchFailuresPreferencesAndPartialDownloadsSurviveRestart() {
        val batch = DiscoveryBatch("uuid", "Discover 50", tracks, mapOf("dz:1" to "content://media/1"),
            mapOf("dz:2" to "Connection failed"), source = "SOULSEEK", format = "MP3_320", verificationProvider = "deezer")
        val state = SongDiscoveryState(DiscoveryFilter("RO", "Pop", 132, "Party", true), likes = tracks.take(2),
            votes = tracks.map { DiscoveryVote(it, true) }, batches = listOf(batch), revealedBatch = "uuid", undoKey = "dz:50")
        assertEquals(state, DiscoveryStateJson.decode(DiscoveryStateJson.encode(state)))
    }
    @Test fun savedNegativePlaylistIdsAndNullsRoundTrip() {
        val state = SongDiscoveryState(batches = listOf(DiscoveryBatch("a", "Test", tracks, playlistId = -876543210987654321)))
        assertEquals(state, DiscoveryStateJson.decode(DiscoveryStateJson.encode(state)))
    }
    @Test fun emptyStateDecodesWithoutOldAlbumPreferences() {
        assertEquals(SongDiscoveryState(), DiscoveryStateJson.decode("{}"))
    }
    @Test fun corruptOrTruncatedProgressIsNotSilentlyReplaced() {
        assertThrows(Exception::class.java) { DiscoveryStateJson.decode("{broken") }
        val empty = DiscoveryStateJson.encode(SongDiscoveryState(batches = listOf(DiscoveryBatch("b", "Bad", emptyList()))))
        assertThrows(IllegalArgumentException::class.java) { DiscoveryStateJson.decode(empty) }
        val repeated = DiscoveryStateJson.encode(SongDiscoveryState(batches = listOf(DiscoveryBatch("b", "Bad", tracks.take(3) + tracks.first()))))
        assertThrows(IllegalArgumentException::class.java) { DiscoveryStateJson.decode(repeated) }
    }
    @Test fun draftFeedbackAndPartialPlaylistsSurviveRestart() {
        val items = tracks.take(3).mapIndexed { i, t ->
            com.harmony.domain.library.discovery.DraftItem(t, if (i == 0) com.harmony.domain.library.discovery.CandidateKind.EXPLORE
                else com.harmony.domain.library.discovery.CandidateKind.CLOSE,
                com.harmony.domain.library.discovery.Reason(com.harmony.domain.library.discovery.ReasonKind.RELATED_ARTIST, "Deezer lists X as related to Y"),
                kept = i == 1)
        }
        val draft = com.harmony.domain.library.discovery.DiscoveryDraft("d1", com.harmony.domain.library.discovery.DraftStep.SONGS,
            com.harmony.domain.library.discovery.ExplorationLevel.SURPRISE_ME, 30, listOf("Radiohead"), listOf("Jazz"), items,
            setOf("a|b|"), "Friday", 123L, true, listOf("Deezer", "Your library"), "b1")
        val state = SongDiscoveryState(
            batches = listOf(DiscoveryBatch("b1", "Friday", tracks.take(12), playlistId = -5, placedKeys = setOf("dz:1"),
                reasons = mapOf("dz:1" to "why"), activeKey = "dz:2", playlistDeleted = false, locked = true)),
            draft = draft, recommended = listOf(RecommendationStamp("a|b|", 99L)), declined = setOf("dz:9", "USABC1234567"),
            feedback = listOf(com.harmony.domain.library.discovery.TasteFeedback(com.harmony.domain.library.discovery.FeedbackKind.NOT_INTERESTED,
                "dz:9", "Song", "Artist", setOf("Pop"), 77L)),
        )
        assertEquals(state, DiscoveryStateJson.decode(DiscoveryStateJson.encode(state)))
    }
}
