package com.harmony.feature.downloads

import com.harmony.domain.library.repository.AlbumJourney
import com.harmony.domain.library.repository.AlbumJourneyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumBatchTest {
    private val tracks = (1..4).map { AlbumJourneyTrack("$it", "Track $it", "Artist", 200_000L, 1, it) }
    private fun album(rows: List<AlbumJourneyTrack> = tracks, queuedAt: Long = 0L, id: String = "a") =
        AlbumJourney(id, "Album", "Artist", "https://cover", "42", rows, releaseDate = "2013-05-17", queuedAt = queuedAt)
    private fun saved(t: AlbumJourneyTrack, status: String = AlbumTrackStatus.DOWNLOADED) = t.copy(uri = "content://${t.id}", status = status)

    @Test fun anAlbumNotStartedIsReadyWithEveryTrackWaiting() {
        val batch = AlbumBatches.of(album(), AlbumWork.NONE, processing = false, live = null)
        assertEquals(AlbumBatchState.READY, batch.state)
        assertEquals(4, batch.total)
        assertEquals(0, batch.completed)
        assertEquals("2013", batch.year)
        assertEquals("Ready · 4 tracks", batch.statusLabel)
        assertTrue(batch.tracks.all { it.phase == AlbumTrackPhase.WAITING })
        assertTrue(batch.canDownload)
        assertFalse(batch.canPause)
    }

    @Test fun theAlbumBeingDownloadedShowsXOfYPercentSpeedAndTheTracksRing() {
        val rows = listOf(saved(tracks[0]), tracks[1].copy(status = AlbumTrackStatus.DOWNLOADING), tracks[2], tracks[3])
        val live = ActiveDownload("Track 2", "Mornye", "Downloading", 0.5f, albumId = "a", trackId = "2", bytesPerSecond = 3_400_000L)
        val batch = AlbumBatches.of(album(rows, queuedAt = 10L), AlbumWork.RUNNING, processing = true, live = live)
        assertEquals(AlbumBatchState.DOWNLOADING, batch.state)
        assertEquals("Downloading (2/4)", batch.statusLabel)
        assertEquals(1, batch.completed)
        assertEquals(37, batch.percent) // (1 + 0.5) / 4
        assertEquals(3_400_000L, batch.bytesPerSecond)
        assertEquals("2", batch.currentTrackId)
        val ring = batch.tracks[1]
        assertEquals(AlbumTrackPhase.DOWNLOADING, ring.phase)
        assertEquals(0.5f, ring.fraction)
        assertEquals("Downloading", ring.detail)
        assertEquals(AlbumTrackPhase.SAVED, batch.tracks[0].phase)
        assertEquals(AlbumTrackPhase.WAITING, batch.tracks[2].phase)
        assertTrue(batch.canPause && batch.canCancel && !batch.canResume)
        assertEquals("3.4 MB/s", transferSpeedLabel(batch.bytesPerSecond))
    }

    @Test fun anotherAlbumsProgressNeverLeaksIntoThisOne() {
        val live = ActiveDownload("Other", "Mornye", "Downloading", 0.9f, albumId = "b", trackId = "2", bytesPerSecond = 1_000L)
        val rows = listOf(tracks[0], tracks[1].copy(status = AlbumTrackStatus.DOWNLOADING), tracks[2], tracks[3])
        val batch = AlbumBatches.of(album(rows, queuedAt = 10L), AlbumWork.RUNNING, processing = true, live = live)
        assertNull(batch.tracks[1].fraction)
        assertEquals(0L, batch.bytesPerSecond)
        assertEquals(0, batch.percent)
    }

    @Test fun albumsBehindTheCurrentOneAreQueuedAndTheOldestRunsFirst() {
        val first = album(queuedAt = 100L, id = "a")
        val second = album(queuedAt = 200L, id = "b")
        assertEquals("a", AlbumBatches.processingId(listOf(second, first)))
        val batch = AlbumBatches.of(second, AlbumWork.RUNNING, processing = false, live = null)
        assertEquals(AlbumBatchState.QUEUED, batch.state)
        assertEquals("Queued", batch.statusLabel)
        assertTrue(batch.canPause && batch.canCancel)
        assertNull(AlbumBatches.processingId(listOf(album())))
    }

    @Test fun queuedWithNoJobLeftIsPausedNotSilentlyQueued() {
        val batch = AlbumBatches.of(album(queuedAt = 5L), AlbumWork.NONE, processing = true, live = null)
        assertEquals(AlbumBatchState.PAUSED, batch.state)
        assertTrue(batch.canResume)
    }

    @Test fun aStatusLeftOverFromAStoppedJobIsNotADownloadInProgress() {
        val rows = listOf(tracks[0].copy(status = AlbumTrackStatus.DOWNLOADING), tracks[1], tracks[2], tracks[3])
        val batch = AlbumBatches.of(album(rows), AlbumWork.NONE, processing = false, live = null)
        assertEquals(AlbumTrackPhase.WAITING, batch.tracks[0].phase)
        assertNull(batch.currentTrackId)
    }

    @Test fun pausedByHandStaysPausedAfterTheQueueMovesOn() {
        val rows = listOf(saved(tracks[0]), tracks[1].copy(status = AlbumTrackStatus.PAUSED), tracks[2].copy(status = AlbumTrackStatus.PAUSED), tracks[3].copy(status = AlbumTrackStatus.PAUSED))
        val batch = AlbumBatches.of(album(rows), AlbumWork.RUNNING, processing = false, live = null)
        assertEquals(AlbumBatchState.PAUSED, batch.state)
        assertEquals("Paused · 1/4", batch.statusLabel)
    }

    @Test fun completedPartialAndFailed() {
        val all = AlbumBatches.of(album(tracks.map { saved(it, AlbumTrackStatus.INDEXED) }), AlbumWork.NONE, false, null)
        assertEquals(AlbumBatchState.COMPLETED, all.state)
        assertEquals(100, all.percent)
        assertTrue(all.tracks.all { it.phase == AlbumTrackPhase.INDEXED })

        val failedRow = tracks[3].copy(status = AlbumTrackStatus.NEEDS_RETRY, error = "No provider had it")
        val partial = AlbumBatches.of(album(tracks.take(3).map(::saved) + failedRow), AlbumWork.NONE, false, null)
        assertEquals(AlbumBatchState.PARTIAL, partial.state)
        assertEquals("Partial · 1 failed", partial.statusLabel)
        assertTrue(partial.canRetryFailed)
        assertTrue(partial.tracks[3].canRetry)
        assertEquals("No provider had it", partial.tracks[3].detail)

        val none = AlbumBatches.of(album(tracks.map { it.copy(status = AlbumTrackStatus.NEEDS_RETRY) }), AlbumWork.NONE, false, null)
        assertEquals(AlbumBatchState.FAILED, none.state)
    }

    @Test fun indexingFollowsTheDownloads() {
        val rows = tracks.take(3).map { saved(it, AlbumTrackStatus.INDEXED) } + saved(tracks[3], AlbumTrackStatus.INDEXING)
        val batch = AlbumBatches.of(album(rows, queuedAt = 1L), AlbumWork.RUNNING, processing = true, live = null)
        assertEquals(AlbumBatchState.INDEXING, batch.state)
        assertEquals("Indexing 24D", batch.statusLabel)
        assertEquals(AlbumTrackPhase.INDEXING, batch.tracks[3].phase)
        // Interrupted mid-measurement: the file is saved, the library's own pass measures it.
        val stopped = AlbumBatches.of(album(rows), AlbumWork.NONE, processing = false, live = null)
        assertEquals(AlbumTrackPhase.SAVED, stopped.tracks[3].phase)
        assertEquals(AlbumBatchState.COMPLETED, stopped.state)
    }

    @Test fun skippedAndUnselectedTracksAreOutOfTheBatch() {
        val rows = listOf(saved(tracks[0]), saved(tracks[1]),
            tracks[2].copy(selectedForDownload = false, status = AlbumTrackStatus.SKIPPED),
            tracks[3].copy(selectedForDownload = false))
        val batch = AlbumBatches.of(album(rows), AlbumWork.NONE, false, null)
        assertEquals(2, batch.total)
        assertEquals(AlbumBatchState.COMPLETED, batch.state)
        assertEquals(AlbumTrackPhase.SKIPPED, batch.tracks[2].phase)
        assertTrue(batch.tracks[2].canRetry)
        assertEquals(AlbumTrackPhase.NOT_SELECTED, batch.tracks[3].phase)
        assertFalse(batch.tracks[3].canRetry)
        assertTrue(batch.tracks.first().phase.done)
    }

    @Test fun onlyAWaitingTrackCanBeSkipped() {
        val rows = listOf(tracks[0].copy(status = AlbumTrackStatus.DOWNLOADING), tracks[1], saved(tracks[2]), tracks[3])
        val batch = AlbumBatches.of(album(rows, queuedAt = 1L), AlbumWork.RUNNING, processing = true, live = null)
        assertEquals(listOf(false, true, false, true), batch.tracks.map { it.canSkip })
    }

    @Test fun multiDiscAlbumsNumberTracksByDisc() {
        val rows = listOf(tracks[0], tracks[1].copy(disc = 2, number = 1))
        val batch = AlbumBatches.of(album(rows), AlbumWork.NONE, false, null)
        assertEquals(listOf("1.01", "2.01"), batch.tracks.map { it.number })
        assertEquals(listOf("01", "02"), AlbumBatches.of(album(), AlbumWork.NONE, false, null).tracks.take(2).map { it.number })
    }

    @Test fun speedIsMeasuredAndSmoothed() {
        var now = 0L
        val meter = TransferSpeedMeter { now }
        assertEquals(0L, meter.sample(0L))
        now = 1_000L
        assertEquals(2_000_000L, meter.sample(2_000_000L))
        now = 1_100L
        assertEquals(2_000_000L, meter.sample(2_500_000L)) // Too soon: the last value.
        now = 2_000L
        assertEquals(1_800_000L, meter.sample(3_500_000L)) // 0.6 × 2.0 MB/s + 0.4 × 1.5 MB/s
        meter.reset()
        assertEquals(0L, meter.sample(0L))
        assertEquals("", transferSpeedLabel(0L))
        assertEquals("820 KB/s", transferSpeedLabel(820_000L))
    }
}
