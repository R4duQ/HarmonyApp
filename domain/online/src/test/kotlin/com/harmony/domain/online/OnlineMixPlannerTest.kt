package com.harmony.domain.online

import com.harmony.domain.library.discovery.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun song(id: String, title: String, artist: String, ms: Long? = 200_000, album: String? = "A", kind: CatalogKind = CatalogKind.SONG, playable: Boolean? = true) =
    CatalogTrack(id.padEnd(11, '0').take(11), title, listOf(artist), album, null, ms, kind, null, playable)

class OnlineMixPlannerTest {
    private val seed = SeedTrack("Pe Drum", "Subcarpați", null, 222_000)
    private val context = PlannerContext(seed, seedVideoId = "seed0000000")

    @Test
    fun onlySongsPassAndTheSeedIsNotRecommendedBack() {
        val radio = listOf(
            song("seed", "Pe Drum", "Subcarpați").copy(videoId = "seed0000000"),
            song("v1", "Drumul spre pace", "Subcarpați", kind = CatalogKind.OFFICIAL_VIDEO),
            song("v2", "Flori Ilegale", "Guess Who", kind = CatalogKind.USER_VIDEO),
            song("v3", "Lovesong", "Adele", kind = CatalogKind.PODCAST_EPISODE),
            song("v4", "da-i volum!", "raku", kind = CatalogKind.UNKNOWN),
            song("s1", "Scrisoare din trecut", "Subcarpați"),
            song("s2", "greyed", "X", playable = false),
        )
        val batch = OnlineMixPlanner.plan(radio, context, want = 10)
        assertEquals(listOf("s1000000000"), batch.accepted.map { it.track.videoId })
        assertEquals(6, batch.turnedAway.size)
        assertTrue(batch.turnedAway.any { it.reason == "the seed" })
        assertTrue(batch.turnedAway.any { it.reason == "not a song: USER_VIDEO" })
    }

    @Test
    fun nothingAlreadyQueuedPlayedOrPickedComesAgain() {
        val queued = TrackIdentity.of("Scrisoare din trecut", "Subcarpați", 200_000).key
        val radio = listOf(
            song("s1", "Scrisoare din trecut", "Subcarpati"),          // the queue has it (another spelling)
            song("s2", "Flori Ilegale", "Guess Who"),
            song("s3", "Flori ilegale", "GUESS WHO"),                  // the same recording twice in the radio
            song("s4", "Azi", "Deliric"),
        )
        val batch = OnlineMixPlanner.plan(radio, context.copy(avoidKeys = setOf(queued), avoidVideoIds = setOf("s4000000000")), want = 10)
        assertEquals(listOf("s2000000000"), batch.accepted.map { it.track.videoId })
        assertTrue(batch.turnedAway.any { it.videoId == "s1000000000" && it.reason.startsWith("played, queued") })
        assertTrue(batch.turnedAway.any { it.videoId == "s3000000000" && it.reason == "twice in the radio" })
        assertTrue(batch.turnedAway.any { it.videoId == "s4000000000" && it.reason == "already used" })
    }

    @Test
    fun variantsAndOddLengthsAreHandled() {
        val radio = listOf(
            song("a", "Song A (Sped Up)", "X"),
            song("b", "Song B (Karaoke Version)", "Y"),
            song("c", "Song C", "Z", ms = 45_000),
            song("d", "Song D", "W", ms = 3_600_000),
            song("e", "Song E (Live)", "V"),
            song("f", "Song F", "U", ms = null),
        )
        val batch = OnlineMixPlanner.plan(radio, context, want = 10)
        val ids = batch.accepted.map { it.track.videoId.take(1) }
        assertFalse("sped up", "a" in ids)
        assertFalse("karaoke", "b" in ids)
        assertFalse("interlude", "c" in ids)
        assertFalse("a full mix", "d" in ids)
        // A live take is not turned away outright, but scored lower than a studio one.
        val f = batch.accepted.firstOrNull { it.track.videoId.startsWith("f") }
        assertTrue("an unknown length is kept as unknown", f == null || "length unknown" in f.reasons)
        val e = batch.accepted.firstOrNull { it.track.videoId.startsWith("e") }
        assertTrue(e == null || "version: live" in e.reasons)
    }

    @Test
    fun aSmallerBatchRatherThanWeakPicks() {
        // Twenty songs, all by an artist skipped fast this session: none reaches the threshold.
        val radio = (1..20).map { song("k$it", "Track $it", "Skipped Artist") }
        val skipped = OnlineMixPlanner.plan(radio, context.copy(skippedArtists = setOf("skipped artist")), want = 12)
        assertTrue(skipped.accepted.size < 12)
        assertTrue(skipped.accepted.all { it.score >= OnlineMixPlanner.THRESHOLD })
    }

    @Test
    fun noArtistTwiceInARowAndAtMostTwoPerBatch() {
        val radio = listOf(
            song("a1", "A1", "Alpha"), song("a2", "A2", "Alpha"), song("a3", "A3", "Alpha"),
            song("b1", "B1", "Beta"), song("c1", "C1", "Gamma"),
        )
        val batch = OnlineMixPlanner.plan(radio, context, want = 10)
        val leads = batch.accepted.map { it.track.artists.single() }
        assertEquals(2, leads.count { it == "Alpha" })
        assertTrue(leads.zipWithNext().none { (x, y) -> x == y })
    }
}
