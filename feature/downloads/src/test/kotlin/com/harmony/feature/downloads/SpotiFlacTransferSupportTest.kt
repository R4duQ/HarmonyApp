package com.harmony.feature.downloads

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stage 2, the rules: the watchdog's phases, what a failure is, and what staging keeps. */
class SpotiFlacTransferSupportTest {
    @get:Rule val folder = TemporaryFolder()

    private val deadlines = TransferDeadlines(firstByteMs = 95_000, stalledMs = 95_000, finalizeMs = 180_000, longestMs = 45 * 60_000L)

    // -- The watchdog ------------------------------------------------------------------------

    @Test fun aTransferThatKeepsReceivingIsNotStopped() {
        var now = 0L
        val activity = TransferActivity(deadlines) { now }
        activity.start()
        // A 300 MB hi-res file at 1 MB/s: five minutes, far past the old 95 s limit for the whole call.
        var bytes = 0L
        repeat(300) {
            now += 1_000
            bytes += 1_000_000
            activity.note(bytes, 300_000_000)
            assertNull("still receiving at ${now / 1000}s", activity.verdict())
        }
        assertEquals(300_000_000L, activity.receivedBytes)
        assertEquals(TransferActivity.Phase.FINALIZING, activity.phase)
    }

    @Test fun eachPhaseHasItsOwnDeadline() {
        var now = 0L
        val activity = TransferActivity(deadlines) { now }
        activity.start()
        assertEquals(TransferActivity.Phase.WAITING, activity.phase)
        now += 94_999
        assertNull("the provider still has time to deliver a first byte", activity.verdict())
        now += 1
        assertTrue(activity.verdict()!!.contains("did not start within 95s"))

        // Transferring: only a growing byte count is progress.
        activity.start()
        now += 10_000
        activity.note(5_000_000, 40_000_000)
        assertEquals(TransferActivity.Phase.TRANSFERRING, activity.phase)
        now += 94_000
        activity.note(5_000_000, 40_000_000)   // the same count
        activity.note(4_000_000, 40_000_000)   // a smaller one (a restart inside the runtime)
        activity.note(0, 40_000_000)           // a status change with no bytes
        assertNull(activity.verdict())
        now += 1_000
        assertTrue(activity.verdict()!!.contains("received nothing for 95s"))
        activity.note(5_000_001, 40_000_000)
        assertNull("one more byte and it goes on", activity.verdict())

        // Finalizing: every byte is in; the runtime gets its own, longer time to finish.
        activity.note(40_000_000, 40_000_000)
        assertEquals(TransferActivity.Phase.FINALIZING, activity.phase)
        now += 179_000
        assertNull("decrypting and tagging a large file is not a stall", activity.verdict())
        now += 1_000
        assertTrue(activity.verdict()!!.contains("did not finish within 180s"))
    }

    @Test fun evenASteadyTransferHasALongestTime() {
        var now = 0L
        val activity = TransferActivity(deadlines.copy(longestMs = 10 * 60_000L)) { now }
        activity.start()
        var bytes = 0L
        while (now < 10 * 60_000L - 1_000) {
            now += 1_000
            activity.note(++bytes)
            assertNull(activity.verdict())
        }
        now += 1_000
        activity.note(++bytes)
        assertTrue(activity.verdict()!!.contains("10 minutes"))
        // A new provider call starts its own clock.
        activity.start()
        assertNull(activity.verdict())
        assertEquals(0L, activity.receivedBytes)
    }

    // -- What a failure is -------------------------------------------------------------------

    @Test fun failuresAreToldApartAndOnlySomeKeepWhatWasReceived() {
        fun kind(type: String?, message: String? = null) = SpotiFlacFailures.kind(type, message)
        assertEquals(SpotiFlacFailureKind.TIMEOUT, kind("provider_timeout"))
        assertEquals(SpotiFlacFailureKind.NO_NETWORK, kind("network_unavailable"))
        assertEquals(SpotiFlacFailureKind.NO_NETWORK, kind("transient_network"))
        assertEquals(SpotiFlacFailureKind.RATE_LIMITED, kind("rate_limit"))
        assertEquals(SpotiFlacFailureKind.RATE_LIMITED, kind("too_many_requests"))
        assertEquals(SpotiFlacFailureKind.VERIFICATION, kind("verification_required"))
        assertEquals(SpotiFlacFailureKind.NOT_FOUND, kind("not_found"))
        assertEquals(SpotiFlacFailureKind.INTEGRITY, kind("integrity_failed"))
        assertEquals(SpotiFlacFailureKind.LOCAL, kind("lossless_finalization_failed"))
        assertEquals(SpotiFlacFailureKind.LOCAL, kind("storage_error"))
        // Untyped: by what it says.
        assertEquals(SpotiFlacFailureKind.RATE_LIMITED, kind(null, "HTTP 429 for https://api.example/x"))
        assertEquals(SpotiFlacFailureKind.TIMEOUT, kind("", "Read timed out"))
        assertEquals(SpotiFlacFailureKind.NO_NETWORK, kind("unknown", "Unable to resolve host \"api.example\""))
        assertEquals(SpotiFlacFailureKind.OTHER, kind(null, "something else"))
        // A typed failure is not re-read from its message.
        assertEquals(SpotiFlacFailureKind.NOT_FOUND, kind("not_found", "connection reset"))

        val keeps = SpotiFlacFailureKind.entries.filter { it.keepsPartial }.toSet()
        assertEquals(setOf(SpotiFlacFailureKind.TIMEOUT, SpotiFlacFailureKind.NO_NETWORK, SpotiFlacFailureKind.RATE_LIMITED), keeps)
        assertTrue("a transfer that no longer matches starts clean, and may be tried again",
            SpotiFlacFailureKind.INTEGRITY.retriable && !SpotiFlacFailureKind.INTEGRITY.keepsPartial)
        assertFalse(SpotiFlacFailureKind.NOT_FOUND.retriable)
        assertFalse(SpotiFlacFailureKind.VERIFICATION.retriable)
    }

    @Test fun retryAfterIsTheProvidersWhenItGaveOne() {
        fun wait(advertised: Long, retry: Int) =
            SpotiFlacFailures.retryAfterSeconds(advertised, retry, fallbackSeconds = 60, minSeconds = 5, maxSeconds = 600)
        assertEquals(30, wait(30, 0))
        assertEquals("never hammered sooner than five seconds", 5, wait(1, 0))
        assertEquals("never parked for more than ten minutes", 600, wait(86_400, 0))
        assertEquals(60, wait(0, 0))
        assertEquals(120, wait(0, 1))
        assertEquals(240, wait(0, 2))
        assertEquals(240, wait(0, 9))
        assertEquals("its own number even on a later retry", 30, wait(30, 3))
    }

    // -- Staging -----------------------------------------------------------------------------

    @Test fun theSameTrackAndFormatShareAFolderAndNothingElseDoes() {
        val one = SpotiFlacStaging.key(listOf("USRC1", "", "123", "Artist", "Title", "Album", "200000", "FLAC_LOSSLESS"))
        assertEquals(one, SpotiFlacStaging.key(listOf("usrc1 ", "", "123", " artist", "TITLE", "Album", "200000", "FLAC_LOSSLESS")))
        assertNotEquals("another quality is another download", one,
            SpotiFlacStaging.key(listOf("USRC1", "", "123", "Artist", "Title", "Album", "200000", "FLAC_HI_RES_96")))
        assertNotEquals(one, SpotiFlacStaging.key(listOf("USRC2", "", "123", "Artist", "Title", "Album", "200000", "FLAC_LOSSLESS")))
        // Fields do not run into each other.
        assertNotEquals(SpotiFlacStaging.key(listOf("ab", "c")), SpotiFlacStaging.key(listOf("a", "bc")))
        assertTrue(one.matches(Regex("[0-9a-f]{32}")))
    }

    @Test fun onlyWhatCanBeResumedSurvivesAnAttempt() {
        val job = folder.newFolder("job")
        val partial = File(job, "Artist - Title.flac.partial").apply { writeText("half") }
        val checkpoint = File(job, "Artist - Title.flac.partial.checkpoint.json").apply { writeText("{}") }
        val segments = File(job, "Artist - Title.m4a.partial.checkpoint.json.segments").apply { writeText("{}") }
        val segment = File(job, "Artist - Title.m4a.partial.segment.000003").apply { writeText("s") }
        File(job, "Artist - Title.flac").writeText("a finished attempt's file")
        File(job, "harmony-converted.flac").writeText("ours")
        File(job, "cover.jpg").writeText("cover")
        File(job, "sub").mkdirs()
        File(job, "sub/x.flac.partial").writeText("a folder is not a resume file")
        assertEquals("only the staged audio counts as bytes to continue from", 4L, SpotiFlacStaging.resumableBytes(job))

        SpotiFlacStaging.keepResumableIn(job)
        assertEquals(setOf(partial, checkpoint, segments, segment), job.listFiles()!!.toSet())

        // After a recoverable failure the folder stays while there is something to resume…
        SpotiFlacStaging.keepResumable(job)
        assertTrue(job.isDirectory)
        // …after a success nothing is, and an empty folder goes.
        SpotiFlacStaging.dropResumable(job)
        assertTrue(job.listFiles()!!.isEmpty())
        SpotiFlacStaging.keepResumable(job)
        assertFalse(job.exists())
    }

    @Test fun partialTransfersStayWithinAnAgeAndASize() {
        val root = folder.newFolder("staging")
        val now = 1_000_000_000L
        fun job(name: String, ageMs: Long, bytes: Int = 1) = File(root, name).apply {
            mkdirs()
            File(this, "x.flac.partial").apply { writeBytes(ByteArray(bytes)); setLastModified(now - ageMs) }
            setLastModified(now - ageMs)
        }
        val fresh = job("fresh", 60_000)
        val old = job("old", 25 * 3_600_000L)
        val oldButHeld = job("held", 25 * 3_600_000L)
        // A folder is as young as the newest thing in it.
        val touched = job("touched", 25 * 3_600_000L).also { File(it, "y.partial").apply { writeText("n"); setLastModified(now - 1_000) } }

        SpotiFlacStaging.sweep(root, olderThanMs = 24 * 3_600_000L, now = now, keep = setOf("held"))
        assertTrue(fresh.exists())
        assertFalse(old.exists())
        assertTrue(oldButHeld.exists())
        assertTrue(touched.exists())

        // Over the budget: the least recently touched go first, a folder in use never.
        val budget = folder.newFolder("budget")
        fun sized(name: String, ageMs: Long, bytes: Int) = File(budget, name).apply {
            mkdirs()
            File(this, "x.flac.partial").apply { writeBytes(ByteArray(bytes)); setLastModified(now - ageMs) }
            setLastModified(now - ageMs)
        }
        val oldest = sized("oldest", 3_000_000, 400)
        val middle = sized("middle", 2_000_000, 400)
        val newest = sized("newest", 1_000_000, 400)
        val heldBig = sized("in-use", 9_000_000, 5_000)
        SpotiFlacStaging.sweep(budget, olderThanMs = 24 * 3_600_000L, now = now, keep = setOf("in-use"), budgetBytes = 900)
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
        assertTrue(heldBig.exists())
    }

    @Test fun aFolderBelongsToOneJobUntilItsNativeCallHasReturned() {
        val leases = StagingLeases(folder.newFolder("staging"))
        val a = leases.open("track", "job-aaaaaaaa")
        assertEquals("track", a.folder.name)
        File(a.folder, "Artist - Title.flac.partial").writeText("half")
        a.nativeStarted()
        assertTrue(a.writing)

        // Closed while its native call may still write: nothing is tidied, nobody else gets the folder.
        a.close(StagingLeases.Disposition.DELETE)
        assertTrue(a.folder.exists())
        val b = leases.open("track", "job-bbbbbbbb")
        assertNotEquals(a.folder, b.folder)
        assertTrue(b.folder.listFiles()!!.isEmpty())
        assertEquals(setOf("track", "track-bbbbbbbb"), leases.heldNames)

        // The late call returns: now A's folder goes, and only now is its name free again.
        File(a.folder, "Artist - Title.flac").writeText("a late result")
        a.nativeFinished()
        assertFalse(a.folder.exists())
        assertEquals(setOf("track-bbbbbbbb"), leases.heldNames)

        // B succeeds: its output stays for the import, its partial files go, the folder is free.
        File(b.folder, "out.flac").writeText("fLaC")
        File(b.folder, "out.flac.partial").writeText("leftover")
        b.close(StagingLeases.Disposition.KEEP_OUTPUT)
        assertEquals(listOf("out.flac"), b.folder.list()!!.toList())
        assertTrue(leases.heldNames.isEmpty())

        // A recoverable failure: the next job for the track gets the same folder and what was left.
        val c = leases.open("track", "job-cccccccc")
        File(c.folder, "Artist - Title.flac.partial").writeText("half")
        File(c.folder, "junk.log").writeText("x")
        c.close(StagingLeases.Disposition.KEEP_RESUMABLE)
        val d = leases.open("track", "job-dddddddd")
        assertEquals(c.folder, d.folder)
        assertEquals(listOf("Artist - Title.flac.partial"), d.folder.list()!!.toList())
        // Closing twice, or finishing a call that was never counted, changes nothing.
        d.close(StagingLeases.Disposition.KEEP_RESUMABLE)
        d.close(StagingLeases.Disposition.DELETE)
        d.nativeFinished()
        assertTrue(File(d.folder, "Artist - Title.flac.partial").exists())
    }
}
