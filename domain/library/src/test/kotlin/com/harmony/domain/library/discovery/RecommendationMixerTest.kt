package com.harmony.domain.library.discovery

import com.harmony.domain.library.discovery.CandidateKind.CLOSE
import com.harmony.domain.library.discovery.CandidateKind.EXPLORE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationMixerTest {
    /** 200 close + 200 explore songs, 4 songs per artist, so the artist cap is exercised. */
    private val pool = (1..200).map { candidate(it, CLOSE, artist = "Close ${it / 4}", score = 2f - it / 200f) } +
        (201..400).map { candidate(it, EXPLORE, artist = "New ${it / 4}", score = 1f - it / 800f) }

    @Test fun eachLevelStartsFromItsShare() {
        for ((level, close) in listOf(ExplorationLevel.FOR_MY_TASTE to 40, ExplorationLevel.BALANCED to 30, ExplorationLevel.SURPRISE_ME to 20)) {
            val mix = RecommendationMixer.compose(50, level, pool, seed = 1)
            assertEquals(50, mix.items.size)
            assertEquals(level.name, close, mix.closeCount)
            assertEquals(0, mix.missing)
        }
    }

    @Test fun noArtistDominatesAndNeverTwiceInARow() {
        val mix = RecommendationMixer.compose(50, ExplorationLevel.BALANCED, pool, seed = 3).items
        val perArtist = mix.groupingBy { TrackIdentity.primaryArtist(it.song.artist) }.eachCount()
        assertTrue(perArtist.values.max() <= 3)
        mix.zipWithNext().forEach { (a, b) -> assertNotEquals(a.song.artist, b.song.artist) }
        val small = RecommendationMixer.compose(20, ExplorationLevel.BALANCED, pool, seed = 3).items
        assertTrue(small.groupingBy { it.song.artist }.eachCount().values.max() <= 2)
    }

    @Test fun oneRecordingAppearsOnceAcrossSources() {
        val dupes = listOf(
            candidate(1, CLOSE, artist = "A", isrc = "USABC1234567"),
            candidate(2, CLOSE, artist = "A feat. B", isrc = "USABC1234567"),
            candidate(3, CLOSE, artist = "A").let { it.copy(song = it.song.copy(key = "apple:1", title = "Track 3 (feat. C)")) },
            candidate(3, CLOSE, artist = "A"),
            candidate(4, CLOSE, artist = "A").let { it.copy(song = it.song.copy(key = "dz:44", title = "Track 3 (Live)")) },
        )
        for (seed in 0L..20L) {
            val mix = RecommendationMixer.compose(50, ExplorationLevel.FOR_MY_TASTE, dupes, seed = seed).items
            val keys = mix.map { it.key }.toSet()
            assertFalse(keys.containsAll(listOf("dz:1", "dz:2")))
            assertFalse(keys.containsAll(listOf("apple:1", "dz:3")))
            for (i in mix.indices) for (j in i + 1 until mix.size) assertFalse(TrackIdentity.same(mix[i].song.track, mix[j].song.track))
        }
        // The live take is a different recording: it may sit next to the studio one's slot.
        assertTrue(RecommendationMixer.compose(50, ExplorationLevel.FOR_MY_TASTE, dupes.drop(2), seed = 0).items.any { it.key == "dz:44" })
    }

    @Test fun anEmptyBucketIsFilledFromTheOtherInsteadOfLeavingHoles() {
        val mix = RecommendationMixer.compose(30, ExplorationLevel.SURPRISE_ME, pool.filter { it.kind == CLOSE }, seed = 2)
        assertEquals(30, mix.items.size); assertEquals(0, mix.exploreCount)
        // 8 candidates, but "Close 1" has 4 of them: the artist cap still holds when short,
        // and the shortfall is reported instead of padded.
        val tiny = RecommendationMixer.compose(30, ExplorationLevel.BALANCED, pool.take(8), seed = 2)
        assertEquals(7, tiny.items.size); assertEquals(23, tiny.missing)
    }

    @Test fun keptSongsStayInPlaceWhenTheRestIsRegenerated() {
        val first = RecommendationMixer.compose(50, ExplorationLevel.BALANCED, pool, seed = 5).items
        val kept = first.mapIndexed { i, item -> if (i % 10 == 0) item.copy(kept = true) else item }
        val again = RecommendationMixer.compose(50, ExplorationLevel.BALANCED, pool, kept,
            stale = first.map { it.identity }.toSet(), seed = 6).items
        for (i in 0 until 50 step 10) assertEquals(kept[i], again[i])
        val newOnes = again.filterNot { it.kept }
        assertTrue("regenerated songs are new", newOnes.none { n -> first.any { it.key == n.key } })
    }

    @Test fun earlierRecommendationsAreUsedOnlyWhenNothingFresherIsLeft() {
        val stale = pool.take(150).map { it.song.identity }.toSet()
        val mix = RecommendationMixer.compose(30, ExplorationLevel.FOR_MY_TASTE, pool, stale = stale, seed = 1).items
        assertTrue(mix.filter { it.kind == CLOSE }.none { it.song.identity in stale })
        val onlyStale = RecommendationMixer.compose(20, ExplorationLevel.FOR_MY_TASTE, pool.take(150), stale = stale, seed = 1)
        assertEquals(20, onlyStale.items.size)
    }

    @Test fun declinedSongsNeverComeBack() {
        val declined = pool.map { it.song.key }.filter { it.removePrefix("dz:").toInt() % 2 == 0 }.toSet()
        val mix = RecommendationMixer.compose(50, ExplorationLevel.BALANCED, pool, excluded = declined, seed = 9).items
        assertTrue(mix.none { it.key in declined })
    }

    @Test fun replacementKeepsTheKindAndAvoidsNeighbourArtists() {
        val items = RecommendationMixer.compose(50, ExplorationLevel.BALANCED, pool, seed = 4).items
        val index = items.indexOfFirst { it.kind == EXPLORE }
        val next = assertNotNullReturn(RecommendationMixer.replacement(items, index, pool, seed = 4))
        assertEquals(EXPLORE, next.kind)
        assertFalse(items.any { it.key == next.key })
        val neighbours = listOfNotNull(items.getOrNull(index - 1), items.getOrNull(index + 1), items[index]).map { it.song.artist }
        assertFalse(next.song.artist in neighbours)
    }

    @Test fun fillTopsUpAfterRemovalsWithoutMovingExistingSongs() {
        val items = RecommendationMixer.compose(50, ExplorationLevel.BALANCED, pool, seed = 4).items
        val trimmed = items.filterIndexed { i, _ -> i % 5 != 0 }
        val filled = RecommendationMixer.fill(trimmed, 50, pool, ExplorationLevel.BALANCED, seed = 4)
        assertEquals(50, filled.size)
        assertEquals(trimmed.map { it.key }.toSet(), filled.map { it.key }.filter { k -> trimmed.any { it.key == k } }.toSet())
        assertEquals(50, filled.map { it.key }.distinct().size)
    }

    @Test fun sameSeedSameMixDifferentSeedDifferentOrder() {
        val a = RecommendationMixer.compose(50, ExplorationLevel.BALANCED, pool, seed = 11).items.map { it.key }
        assertEquals(a, RecommendationMixer.compose(50, ExplorationLevel.BALANCED, pool, seed = 11).items.map { it.key })
        assertNotEquals(a, RecommendationMixer.compose(50, ExplorationLevel.BALANCED, pool, seed = 12).items.map { it.key })
    }

    @Test fun discoveriesAreSpreadThroughTheList() {
        val items = RecommendationMixer.compose(50, ExplorationLevel.FOR_MY_TASTE, pool, seed = 1).items
        val firstHalf = items.take(25).count { it.kind == EXPLORE }
        assertTrue("explore songs in both halves: $firstHalf", firstHalf in 3..7)
    }

    private fun <T> assertNotNullReturn(value: T?): T { assertNotNull(value); return value!! }
}
