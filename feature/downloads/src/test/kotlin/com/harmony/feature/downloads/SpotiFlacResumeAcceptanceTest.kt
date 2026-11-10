package com.harmony.feature.downloads

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stage 2, the checks that block a merge, against a controlled HTTP server:
 *
 *  - a FLAC whose transfer is active for more than 95 seconds finishes, with
 *    the bytes of the original;
 *  - a transfer cut half way is continued by the next attempt, and nothing
 *    already received is sent again;
 *  - when the file changed in between (another validator), or the server
 *    ignores the range, the next attempt starts clean and still ends with
 *    the right bytes;
 *  - job A is cancelled, job B starts, and A's result arrives late: only B's
 *    file can be imported, once.
 *
 * What is under test is Harmony's side: the watchdog ([WatchedCall],
 * [TransferActivity]), which folder a job gets and what is kept in it
 * ([StagingLeases], [SpotiFlacStaging]), what a failure leads to
 * ([SpotiFlacFailures]) and the byte counts ([DownloadTimeline]). The
 * transfer itself is [FakeTransfer], which follows the runtime's rules but
 * is not the runtime.
 */
class SpotiFlacResumeAcceptanceTest {
    @get:Rule val folder = TemporaryFolder()

    private val native = Executors.newCachedThreadPool { runnable -> Thread(runnable, "fake-native").apply { isDaemon = true } }
    private val deadlines = TransferDeadlines(firstByteMs = 95_000, stalledMs = 95_000, finalizeMs = 180_000, longestMs = 45 * 60_000L)
    private val clock = AtomicLong()

    @After fun stop() { native.shutdownNow() }

    /** One job the way the engine runs it: a leased folder, a watched native call, a disposition by outcome. */
    private inner class Job(leases: StagingLeases, key: String, val id: String) {
        val lease = leases.open(key, id)
        val activity = TransferActivity(deadlines) { clock.get() }
        val timeline = DownloadTimeline { clock.get() }
        val target = File(lease.folder, "Artist - Title.flac")
        val cancelRequested = AtomicBoolean(false)
        val stopNativeCalls = AtomicLong()

        /** Runs [transfer] as the native call and applies the engine's rules to what comes back. */
        fun run(url: String, transfer: FakeTransfer, cancelled: () -> Boolean = { false }): Pair<WatchedCall.Result, FakeTransfer.Result?> {
            timeline.callStarted(SpotiFlacStaging.resumableBytes(lease.folder))
            lease.nativeStarted()
            var answered: FakeTransfer.Result? = null
            val future = native.submit<String> {
                try {
                    transfer.download(url, target) { received, total ->
                        activity.note(received, total)
                        timeline.progress(received)
                    }.also { answered = it }.toJson()
                } finally {
                    lease.nativeFinished()
                }
            }
            val result = WatchedCall(tickMs = 2, letGoMs = 50).await(future, activity, cancelled) {
                stopNativeCalls.incrementAndGet()
                cancelRequested.set(true)
            }
            timeline.callEnded()
            val native = (result as? WatchedCall.Result.Returned)?.let { answered }
            val disposition = when {
                result is WatchedCall.Result.Cancelled -> StagingLeases.Disposition.DELETE     // Cancel by hand
                native?.success == true -> StagingLeases.Disposition.KEEP_OUTPUT
                SpotiFlacFailures.kind(if (result is WatchedCall.Result.Stopped) "provider_timeout" else native?.errorType).keepsPartial ->
                    StagingLeases.Disposition.KEEP_RESUMABLE
                else -> StagingLeases.Disposition.DELETE
            }
            lease.close(disposition)
            return result to native
        }
    }

    @Test fun aFlacWhoseTransferIsActiveForMoreThan95SecondsFinishes() {
        val original = DownloadTestKit.audio(2 * 1024 * 1024, seed = 1)
        TestCdn(original).use { cdn ->
            val leases = StagingLeases(folder.newFolder("staging"))
            val job = Job(leases, "track", "job-1")
            // A slow link: every 64 KB takes eight seconds of the watchdog's clock.
            val slow = FakeTransfer(chunkBytes = 64 * 1024) { clock.addAndGet(8_000) }
            val startedAt = clock.get()
            val (result, native) = job.run(cdn.url, slow)

            assertTrue("the call was left to finish", result is WatchedCall.Result.Returned)
            assertTrue(native!!.success)
            val activeMs = clock.get() - startedAt
            assertTrue("the transfer was active for ${activeMs / 1000}s", activeMs > 95_000)
            assertEquals(DownloadTestKit.sha256(original), DownloadTestKit.sha256(job.target))
            assertEquals(0L, job.stopNativeCalls.get())
            assertEquals(original.size.toLong(), cdn.bodyBytesSent.get())
            assertEquals(0L, job.timeline.retransmittedBytes)
            assertEquals(listOf("Artist - Title.flac"), job.lease.folder.list()!!.toList())
        }
    }

    @Test fun aTransferThatStopsMovingIsStoppedAndWhatItHadIsKept() {
        val original = DownloadTestKit.audio(1024 * 1024, seed = 2)
        TestCdn(original).use { cdn ->
            val leases = StagingLeases(folder.newFolder("staging"))
            val job = Job(leases, "track", "job-1")
            val stuck = CountDownLatch(1)
            // After a quarter of the file nothing more arrives, while the clock runs on.
            val stalling = FakeTransfer(chunkBytes = 64 * 1024) { received ->
                if (received >= original.size / 4 && !job.cancelRequested.get()) {
                    stuck.countDown()
                    while (!job.cancelRequested.get()) { clock.addAndGet(5_000); Thread.sleep(1) }
                    throw java.io.IOException("cancelled")
                }
            }
            val (result, _) = job.run(cdn.url, stalling)
            assertTrue(stuck.await(5, TimeUnit.SECONDS))
            assertTrue(result is WatchedCall.Result.Stopped)
            assertTrue((result as WatchedCall.Result.Stopped).reason.contains("received nothing for 95s"))
            assertEquals("the runtime was told to stop", 1L, job.stopNativeCalls.get())
            // A stall is recoverable: the partial transfer stays for the next attempt.
            assertTrue(File(job.lease.folder, "Artist - Title.flac.partial").length() >= original.size / 4)
            assertFalse(job.target.exists())
        }
    }

    @Test fun aTransferCutHalfWayIsContinuedWithoutSendingAnythingTwice() {
        val original = DownloadTestKit.audio(1024 * 1024, seed = 3)
        TestCdn(original).use { cdn ->
            val leases = StagingLeases(folder.newFolder("staging"))
            val half = original.size / 2L
            cdn.dropAfterBytes = half

            val first = Job(leases, "track", "job-1")
            val (_, cut) = first.run(cdn.url, FakeTransfer())
            assertFalse(cut!!.success)
            assertEquals(SpotiFlacFailureKind.NO_NETWORK, SpotiFlacFailures.kind(cut.errorType))
            assertEquals("what arrived is kept", half, File(first.lease.folder, "Artist - Title.flac.partial").length())
            assertTrue(File(first.lease.folder, "Artist - Title.flac.partial.checkpoint.json").isFile)

            // The next attempt (Resume, a retry, a restart of the app): same track, so the same folder.
            val second = Job(leases, "track", "job-2")
            assertEquals(first.lease.folder, second.lease.folder)
            val (result, done) = second.run(cdn.url, FakeTransfer())
            assertTrue(result is WatchedCall.Result.Returned)
            assertTrue(done!!.success)
            assertTrue("continued, not restarted", done.resumed)
            assertEquals(DownloadTestKit.sha256(original), DownloadTestKit.sha256(second.target))

            assertEquals("every byte crossed the network exactly once", original.size.toLong(), cdn.bodyBytesSent.get())
            assertEquals(1L, cdn.rangeRequests.get())
            assertEquals(half, second.timeline.resumedBytes)
            assertEquals(original.size - half, second.timeline.receivedBytes)
            assertEquals(0L, second.timeline.retransmittedBytes)
            assertEquals("nothing is left beside the file", listOf("Artist - Title.flac"), second.lease.folder.list()!!.toList())
        }
    }

    @Test fun aFileThatChangedInBetweenStartsCleanAndEndsRight() {
        val before = DownloadTestKit.audio(1024 * 1024, seed = 4)
        val after = DownloadTestKit.audio(1024 * 1024 + 4_096, seed = 5)
        TestCdn(before, etag = "\"v1\"").use { cdn ->
            val leases = StagingLeases(folder.newFolder("staging"))
            val half = before.size / 2L
            cdn.dropAfterBytes = half
            val first = Job(leases, "track", "job-1")
            assertFalse(first.run(cdn.url, FakeTransfer()).second!!.success)

            // The provider replaced the file: another validator, other bytes.
            cdn.content = after
            cdn.etag = "\"v2\""
            val second = Job(leases, "track", "job-2")
            val (_, done) = second.run(cdn.url, FakeTransfer())
            assertTrue(done!!.success)
            assertFalse("If-Range did not match: the server sent the whole new file", done.resumed)
            assertEquals(DownloadTestKit.sha256(after), DownloadTestKit.sha256(second.target))
            assertNotEquals(DownloadTestKit.sha256(before), DownloadTestKit.sha256(second.target))
            assertEquals("the half already on disk was sent again", half, second.timeline.retransmittedBytes)
            assertEquals(0L, second.timeline.resumedBytes)
            assertEquals(half + after.size, cdn.bodyBytesSent.get())
        }
    }

    @Test fun aServerThatIgnoresTheRangeAlsoStartsClean() {
        val original = DownloadTestKit.audio(512 * 1024, seed = 6)
        TestCdn(original).use { cdn ->
            val leases = StagingLeases(folder.newFolder("staging"))
            cdn.dropAfterBytes = original.size / 2L
            assertFalse(Job(leases, "track", "job-1").run(cdn.url, FakeTransfer()).second!!.success)
            cdn.ignoreRange = true
            val second = Job(leases, "track", "job-2")
            val (_, done) = second.run(cdn.url, FakeTransfer())
            assertTrue(done!!.success)
            assertFalse(done.resumed)
            assertEquals(DownloadTestKit.sha256(original), DownloadTestKit.sha256(second.target))
            assertEquals(original.size / 2L, second.timeline.retransmittedBytes)
        }
    }

    @Test fun aRangeAnsweredWithAnotherFileIsRefusedAndNothingOfItIsKept() {
        val before = DownloadTestKit.audio(512 * 1024, seed = 7)
        val after = DownloadTestKit.audio(512 * 1024, seed = 8)
        TestCdn(before, etag = "\"v1\"").use { cdn ->
            val leases = StagingLeases(folder.newFolder("staging"))
            cdn.dropAfterBytes = before.size / 2L
            assertFalse(Job(leases, "track", "job-1").run(cdn.url, FakeTransfer()).second!!.success)

            // A server that answers the range although the file is no longer the same one.
            cdn.content = after
            cdn.etag = "\"v2\""
            cdn.ignoreIfRange = true
            val second = Job(leases, "track", "job-2")
            val (_, refused) = second.run(cdn.url, FakeTransfer())
            assertFalse(refused!!.success)
            assertEquals(SpotiFlacFailureKind.INTEGRITY, SpotiFlacFailures.kind(refused.errorType))
            assertFalse("two halves of different files are never joined", second.target.exists())
            assertFalse("and the stale half goes", second.lease.folder.exists())

            // The attempt after that has nothing to continue from, and gets the new file whole.
            val third = Job(leases, "track", "job-3")
            val (_, done) = third.run(cdn.url, FakeTransfer())
            assertTrue(done!!.success)
            assertEquals(DownloadTestKit.sha256(after), DownloadTestKit.sha256(third.target))
        }
    }

    @Test fun onlyTheLiveJobsFileCanBeImportedWhenACancelledJobAnswersLate() {
        val original = DownloadTestKit.audio(512 * 1024, seed = 9)
        TestCdn(original).use { cdn ->
            val staging = folder.newFolder("staging")
            val leases = StagingLeases(staging)

            // Job A: cancelled mid-transfer; its native call does not stop at once.
            val a = Job(leases, "track", "job-aaaaaaaa")
            val aIsMidTransfer = CountDownLatch(1)
            val letALateResultArrive = CountDownLatch(1)
            val aNativeReturned = CountDownLatch(1)
            val slowToStop = FakeTransfer(chunkBytes = 64 * 1024) { received ->
                if (received >= original.size / 2) {
                    aIsMidTransfer.countDown()
                    // Ignores the cancel for now, and the interrupt that follows it.
                    DownloadTestKit.awaitLikeNative(letALateResultArrive, 10_000)
                }
            }
            val aThread = Thread {
                val cancelled = { aIsMidTransfer.count == 0L }
                val (result, _) = a.run(cdn.url, slowToStop, cancelled)
                assertTrue(result is WatchedCall.Result.Cancelled)
                aNativeReturned.countDown()
            }.apply { start() }
            assertTrue(aIsMidTransfer.await(5, TimeUnit.SECONDS))
            assertTrue("A's job is over for the engine", aNativeReturned.await(5, TimeUnit.SECONDS))
            aThread.join()
            assertTrue("but its native call may still be writing: the folder is neither deleted nor given away", a.lease.writing && a.lease.folder.exists())

            // Job B: the same track, right away.
            val b = Job(leases, "track", "job-bbbbbbbb")
            assertNotEquals("B does not share A's folder", a.lease.folder, b.lease.folder)
            val (bResult, bDone) = b.run(cdn.url, FakeTransfer())
            assertTrue(bResult is WatchedCall.Result.Returned)
            assertTrue(bDone!!.success)
            assertEquals("B's progress is B's alone", original.size.toLong(), b.activity.receivedBytes)

            // Now A's native call finishes after all and publishes its file.
            val aReportedPath = a.target.path
            letALateResultArrive.countDown()
            val deadline = System.currentTimeMillis() + 5_000
            // The folder is tidied right after the call is counted as returned.
            while ((a.lease.writing || a.lease.folder.exists()) && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertFalse(a.lease.writing)

            // A's late file is gone with its folder, and was never B's to hand on.
            assertFalse("A's folder went the moment its call returned", a.lease.folder.exists())
            assertNull(SpotiFlacJobFiles.owned(File(aReportedPath), b.lease.folder))
            // Exactly one file in all of staging can be imported: B's, with the right bytes.
            val importable = staging.listFiles()!!.flatMap { job -> SpotiFlacJobFiles.all(job) }
            assertEquals(listOf(b.target.canonicalFile), importable)
            assertEquals(DownloadTestKit.sha256(original), DownloadTestKit.sha256(b.target))
            assertEquals(1L, a.stopNativeCalls.get())
            assertEquals(0L, b.stopNativeCalls.get())
        }
    }
}
