package com.harmony.domain.library

import com.harmony.domain.library.repository.*
import org.junit.Assert.*
import org.junit.Test

class AlbumListeningTest {
    @Test fun screenOffFiveSecondTicksStillCountListening() {
        val clock = ListeningClock()
        clock.sample("a", 0, 0, true, 1f)
        assertEquals(ListenedRange(0, 5_000), clock.sample("a", 5_000, 5_000, true, 1f))
        assertEquals(ListenedRange(5_000, 10_000), clock.sample("a", 10_000, 10_000, true, 1f))
    }
    @Test fun replayedChorusDoesNotCompleteTrack() {
        var ranges = emptyList<ListenedRange>()
        repeat(20) { ranges = ListeningCoverage.merge(ranges, ListenedRange(20_000, 40_000), 200_000) }
        assertEquals(listOf(ListenedRange(20_000, 40_000)), ranges)
        assertFalse(ListeningCoverage.isComplete(ranges, 200_000))
    }
    @Test fun coverageMergesAcrossSessionsAndInAnyOrder() {
        var ranges = listOf(ListenedRange(60_000, 100_000))
        ranges = ListeningCoverage.merge(ranges, ListenedRange(0, 30_000), 100_000)
        ranges = ListeningCoverage.merge(ranges, ListenedRange(30_000, 60_000), 100_000)
        assertEquals(listOf(ListenedRange(0, 100_000)), ranges)
        assertTrue(ListeningCoverage.isComplete(ranges, 100_000))
    }
    @Test fun gapsRemainUnheard() {
        val ranges = ListeningCoverage.merge(listOf(ListenedRange(0, 30_000)), ListenedRange(80_000, 100_000), 100_000)
        assertEquals(2, ranges.size)
        assertFalse(ListeningCoverage.isComplete(ranges, 100_000))
    }
    @Test fun unknownDurationCannotBeComplete() { assertFalse(ListeningCoverage.isComplete(listOf(ListenedRange(0, 100_000)), 0)) }
    @Test fun rangeBoundsCannotInflateListening() {
        val ranges = ListeningCoverage.merge(emptyList(), ListenedRange(-20, 500_000), 100_000)
        assertEquals(listOf(ListenedRange(0, 100_000)), ranges)
    }
    @Test fun clockAcceptsRealProgressAndRejectsSeek() {
        val clock = ListeningClock()
        assertNull(clock.sample("a", 0, 0, true, 1f))
        assertEquals(ListenedRange(0, 1_000), clock.sample("a", 1_000, 1_000, true, 1f))
        assertNull(clock.sample("a", 99_000, 2_000, true, 1f))
        clock.reset() // Every Media3 discontinuity, including a small seek.
        assertNull(clock.sample("a", 99_500, 3_000, true, 1f))
    }
    @Test fun pauseAndLongSuspensionDoNotCount() {
        val clock = ListeningClock()
        clock.sample("a", 0, 0, false, 1f)
        assertNull(clock.sample("a", 10_000, 10_000, true, 1f))
        assertNull(clock.sample("a", 40_000, 40_000, true, 1f))
    }
    @Test fun speedChangesAndTrackTransitionsAreHandled() {
        val clock = ListeningClock()
        clock.sample("a", 0, 0, true, 2f)
        assertEquals(ListenedRange(0, 2_000), clock.sample("a", 2_000, 1_000, true, 2f))
        assertNull(clock.sample("b", 3_000, 2_000, true, 1f))
    }
    @Test fun allEditionTracksAreRequiredForReview() {
        val heard = AlbumJourneyTrack("1", "Song", "Artist", 100_000, 1, 1, coverage = listOf(ListenedRange(0, 90_000)))
        val missing = heard.copy(id = "2", coverage = emptyList())
        val album = AlbumJourney("album", "Album", "Artist", "", "123", listOf(heard, missing))
        assertFalse(album.readyForReview)
        val complete = album.copy(tracks = listOf(heard, heard.copy(id = "2")))
        assertTrue(complete.readyForReview)
        assertFalse(complete.copy(reviewed = true).readyForReview)
    }
}
