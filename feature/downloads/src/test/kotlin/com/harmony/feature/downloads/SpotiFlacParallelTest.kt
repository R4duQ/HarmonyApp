package com.harmony.feature.downloads

import com.harmony.domain.library.repository.AlbumJourney
import com.harmony.domain.library.repository.AlbumJourneyTrack
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stage 4, what can be checked without a phone: two jobs keep to themselves
 * (route, progress, cancel, files), the gate and the limits hold, and
 * playback comes first. Whether two at once is actually faster on a phone,
 * and harmless to playback and Cast, is NOT shown here; until it is, the
 * default is one job.
 */
class SpotiFlacParallelTest {
    @get:Rule val folder = TemporaryFolder()

    private val native = Executors.newCachedThreadPool { runnable -> Thread(runnable, "fake-native").apply { isDaemon = true } }
    private val deadlines = TransferDeadlines(firstByteMs = 95_000, stalledMs = 95_000, finalizeMs = 180_000, longestMs = 45 * 60_000L)

    @After fun stop() { native.shutdownNow() }

    @Test fun oneJobUnlessTheExperimentIsOnAndNothingPlays() {
        fun jobs(on: Boolean, runtime: Int, playing: Boolean) = SpotiFlacParallelPolicy.jobs(on, runtime, playing)
        assertEquals("the default", 1, jobs(on = false, runtime = 2, playing = false))
        assertEquals("the stable build's runtime routes for everyone at once", 1, jobs(on = true, runtime = 1, playing = false))
        assertEquals(2, jobs(on = true, runtime = 2, playing = false))
        assertEquals("playback comes first", 1, jobs(on = true, runtime = 2, playing = true))
        assertEquals("never more than two, whatever a runtime allows", 2, jobs(on = true, runtime = 3, playing = false))
        assertEquals("Tidal's package declares two", 2, SpotiFlacParallelPolicy.perProvider("tidal-web"))
        assertTrue(listOf("qobuz-web", "deezer", "amazon").all { SpotiFlacParallelPolicy.perProvider(it) == 2 })
    }

    @Test fun albumTracksShareTheGateOnlyAsFarAsTheEngineAllows() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val first = launch { DownloadEngineGate.run(albumTransfer = true, parallel = 2) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        val other = launch { DownloadEngineGate.run(albumTransfer = true, parallel = 2) { second.complete(Unit); awaitCancellation() } }
        second.await()
        assertTrue(DownloadEngineGate.albumOwnsTransfer)
        // A third album track, a single download and a one-at-a-time engine all find it busy.
        for (attempt in listOf<suspend () -> Unit>(
            { DownloadEngineGate.run(albumTransfer = true, parallel = 2) { fail("a third track started") } },
            { DownloadEngineGate.run { fail("a single download started beside the album") } },
            { DownloadEngineGate.run(albumTransfer = true, parallel = 1) { fail("a one-at-a-time engine started beside another") } },
        )) {
            try { attempt(); fail("expected busy") } catch (_: DownloadEngineBusyException) { }
        }
        other.cancelAndJoin()
        assertEquals(3, DownloadEngineGate.run(albumTransfer = true, parallel = 2) { 3 })
        first.cancelAndJoin()
        assertFalse(DownloadEngineGate.albumOwnsTransfer)

        // The default (one job): the gate is as it always was.
        val solo = CompletableDeferred<Unit>()
        val album = launch { DownloadEngineGate.run(albumTransfer = true) { solo.complete(Unit); awaitCancellation() } }
        solo.await()
        try { DownloadEngineGate.run(albumTransfer = true) { fail("a second track started with one job allowed") }; fail("expected busy") }
        catch (_: DownloadEngineBusyException) { }
        try { DownloadEngineGate.run(albumTransfer = true, parallel = 2) { fail("joined a one-at-a-time transfer") }; fail("expected busy") }
        catch (_: DownloadEngineBusyException) { }
        album.cancelAndJoin()
        // A single download holds the gate alone, and album tracks wait for it.
        val single = CompletableDeferred<Unit>()
        val screen = launch { DownloadEngineGate.run { single.complete(Unit); awaitCancellation() } }
        single.await()
        try { DownloadEngineGate.run(albumTransfer = true, parallel = 2) { fail("an album track started beside a single download") }; fail("expected busy") }
        catch (_: DownloadEngineBusyException) { }
        screen.cancelAndJoin()
        assertEquals(7, DownloadEngineGate.run { 7 })
    }

    /** One job as the engine runs it, on its own provider's server. */
    private inner class Job(leases: StagingLeases, key: String, val provider: String, val cdn: TestCdn) {
        val lease = leases.open(key, "job-$provider")
        val activity = TransferActivity(deadlines) { System.currentTimeMillis() }
        val timeline = DownloadTimeline { System.currentTimeMillis() }
        val target = File(lease.folder, "$provider - Title.flac")
        val cancel = AtomicBoolean(false)
        /** The engine told the runtime to stop this job. */
        val toldToStop = CountDownLatch(1)
        var served: String? = null

        fun run(transfer: FakeTransfer): WatchedCall.Result {
            timeline.callStarted()
            lease.nativeStarted()
            val future = native.submit<String> {
                try {
                    served = provider   // the request named its provider; nothing shared decides it
                    transfer.download(cdn.url, target) { received, total -> activity.note(received, total); timeline.progress(received) }.toJson()
                } finally { lease.nativeFinished() }
            }
            val result = WatchedCall(tickMs = 2, letGoMs = 500).await(future, activity, { cancel.get() }) { toldToStop.countDown() }
            timeline.callEnded()
            lease.close(if (result is WatchedCall.Result.Returned) StagingLeases.Disposition.KEEP_OUTPUT else StagingLeases.Disposition.DELETE)
            return result
        }
    }

    @Test fun cancellingOneOfTwoJobsLeavesTheOtherItsProviderItsProgressAndItsFile() {
        val tidalAudio = DownloadTestKit.audio(768 * 1024, seed = 21)
        val qobuzAudio = DownloadTestKit.audio(512 * 1024, seed = 22)
        TestCdn(tidalAudio).use { tidalCdn -> TestCdn(qobuzAudio).use { qobuzCdn ->
            val staging = folder.newFolder("staging")
            val leases = StagingLeases(staging)
            val tidal = Job(leases, "track-one", "tidal-web", tidalCdn)
            val qobuz = Job(leases, "track-two", "qobuz-web", qobuzCdn)
            assertNotEquals(tidal.lease.folder, qobuz.lease.folder)

            val bothMidTransfer = CountDownLatch(2)
            val go = CountDownLatch(1)
            fun paced(job: Job, half: Int) = FakeTransfer(chunkBytes = 32 * 1024) { received ->
                if (received >= half) { bothMidTransfer.countDown(); go.await(10, TimeUnit.SECONDS) }
                if (job.cancel.get()) throw java.io.IOException("cancelled")
            }
            var tidalResult: WatchedCall.Result? = null
            var qobuzResult: WatchedCall.Result? = null
            val t1 = Thread { tidalResult = tidal.run(paced(tidal, tidalAudio.size / 2)) }.apply { start() }
            val t2 = Thread { qobuzResult = qobuz.run(paced(qobuz, qobuzAudio.size / 2)) }.apply { start() }
            assertTrue("both transfers are in flight at once", bothMidTransfer.await(10, TimeUnit.SECONDS))

            // Cancel Qobuz mid-transfer; Tidal goes on. The runtime only lets go
            // once it has been told to stop, as the real one does.
            qobuz.cancel.set(true)
            assertTrue("the cancel reached the runtime", qobuz.toldToStop.await(10, TimeUnit.SECONDS))
            go.countDown()
            t1.join(10_000); t2.join(10_000)

            assertTrue(qobuzResult is WatchedCall.Result.Cancelled)
            assertTrue(tidalResult is WatchedCall.Result.Returned)
            assertEquals("each job ran on the provider it named", "tidal-web" to "qobuz-web", tidal.served to qobuz.served)
            assertEquals("Tidal's progress is Tidal's alone", tidalAudio.size.toLong(), tidal.activity.receivedBytes)
            assertTrue("Qobuz stopped where it was cancelled", qobuz.activity.receivedBytes < qobuzAudio.size)
            assertEquals(DownloadTestKit.sha256(tidalAudio), DownloadTestKit.sha256(tidal.target))
            assertEquals(tidalAudio.size.toLong(), tidalCdn.bodyBytesSent.get())
            // The cancelled job's folder is gone; the only importable file in staging is Tidal's.
            val deadline = System.currentTimeMillis() + 5_000
            while (qobuz.lease.folder.exists() && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertFalse(qobuz.lease.folder.exists())
            assertEquals(listOf(tidal.target.canonicalFile), staging.listFiles()!!.flatMap { SpotiFlacJobFiles.all(it) })
            assertNull(SpotiFlacJobFiles.owned(qobuz.target, tidal.lease.folder))
        } }
    }

    @Test fun twoTracksInFlightEachShowTheirOwnProgress() {
        val tracks = (1..4).map { AlbumJourneyTrack("$it", "Track $it", "Artist", 200_000L, 1, it) }
        val rows = listOf(
            tracks[0].copy(uri = "content://1", status = AlbumTrackStatus.DOWNLOADED),
            tracks[1].copy(status = AlbumTrackStatus.DOWNLOADING),
            tracks[2].copy(status = AlbumTrackStatus.DOWNLOADING),
            tracks[3],
        )
        val album = AlbumJourney("a", "Album", "Artist", "https://cover", "42", rows, releaseDate = "2013-05-17", queuedAt = 10L)
        val third = ActiveDownload("Track 3", "Mornye", "Resolving", 0.25f, albumId = "a", trackId = "3", bytesPerSecond = 1_000_000L)
        val live = ActiveDownload("Track 2", "Mornye", "Downloading", 0.75f, albumId = "a", trackId = "2",
            bytesPerSecond = 2_000_000L, parallel = listOf(third))
        val batch = AlbumBatches.of(album, AlbumWork.RUNNING, processing = true, live = live)
        assertEquals(AlbumBatchState.DOWNLOADING, batch.state)
        assertEquals(0.75f, batch.tracks[1].fraction)
        assertEquals("Downloading", batch.tracks[1].detail)
        assertEquals(0.25f, batch.tracks[2].fraction)
        assertEquals("Resolving", batch.tracks[2].detail)
        assertNull(batch.tracks[3].fraction)
        assertEquals("both speeds add up", 3_000_000L, batch.bytesPerSecond)
        assertEquals("(1 + 0.75 + 0.25) / 4", 50, batch.percent)
        // A transfer for a track that is no longer downloading (just saved) does not count twice.
        val saved = album.copy(tracks = rows.toMutableList().also { it[1] = tracks[1].copy(uri = "content://2", status = AlbumTrackStatus.DOWNLOADED) })
        val after = AlbumBatches.of(saved, AlbumWork.RUNNING, processing = true, live = live)
        assertNull(after.tracks[1].fraction)
        assertEquals("(2 + 0.25) / 4", 56, after.percent)
        // One job (the default) reads as it always did.
        val one = AlbumBatches.of(album, AlbumWork.RUNNING, processing = true, live = live.copy(parallel = emptyList()))
        assertEquals(0.75f, one.tracks[1].fraction)
        assertNull(one.tracks[2].fraction)
        assertEquals(2_000_000L, one.bytesPerSecond)
    }
}
