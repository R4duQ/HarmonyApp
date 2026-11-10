package com.harmony.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 0: what the diagnostics may say, and where a download's time went. */
class SpotiFlacDiagnosticsTest {
    private fun redact(text: String) = SpotiFlacRedaction.redact(text)

    @Test fun signedUrlsKeepHostAndPathAndLoseTheSignature() {
        val akamai = "https://sp-pr-fa.audio.tidal.com/mediatracks/CAEaKRInYzQ5/0.flac?token=1731230000~bWFjPWFiYw&Policy=eyJTdGF0ZW1lbnQi&Signature=AbC-dEf&Key-Pair-Id=K14LZ"
        val safe = redact("GET $akamai failed")
        assertTrue(safe, safe.startsWith("GET https://sp-pr-fa.audio.tidal.com/mediatracks/CAEaKRInYzQ5/0.flac?<redacted>"))
        for (secret in listOf("bWFjPWFiYw", "eyJTdGF0ZW1lbnQi", "AbC-dEf", "K14LZ", "1731230000")) assertFalse(secret, secret in safe)

        // A signature carried in the path itself.
        val inPath = redact("https://streaming-qobuz-std.akamaized.net/hdnea=exp=1731~acl=/file/*~hmac=deadbeef/file/track.flac")
        assertFalse("deadbeef" in inPath)
        assertTrue(inPath.startsWith("https://streaming-qobuz-std.akamaized.net/"))
        assertTrue(inPath.endsWith("/file/track.flac"))

        // Credentials in the authority, a fragment, and a long opaque path segment.
        assertEquals("https://example.com/a?<redacted>", redact("https://user:pass@example.com/a#access_token=xyz"))
        val opaque = "A".repeat(64)
        assertEquals("https://cdn.example.com/v1/<redacted>/audio.flac", redact("https://cdn.example.com/v1/$opaque/audio.flac"))
        // An ordinary URL is left as it is.
        assertEquals("https://api.deezer.com/track/3135556", redact("https://api.deezer.com/track/3135556"))
    }

    @Test fun secretFieldsLoseTheirValuesAndEverythingElseStays() {
        val json = """{"success":false,"error_type":"rate_limit","provider":"tidal-web","access_token":"eyJhbGciOi.abc.def","decryption":{"strategy":"mov","key":"00112233445566778899aabbccddeeff"},"retry_after_seconds":30,"state":"n0nce","elapsed_ms":812}"""
        val safe = redact(json)
        for (kept in listOf("\"error_type\":\"rate_limit\"", "\"provider\":\"tidal-web\"", "\"strategy\":\"mov\"", "\"retry_after_seconds\":30", "\"elapsed_ms\":812")) {
            assertTrue(kept, kept in safe)
        }
        for (secret in listOf("eyJhbGciOi", "00112233445566778899aabbccddeeff", "n0nce")) assertFalse(secret, secret in safe)
        assertTrue("\"access_token\":\"<redacted>\"" in safe)
        assertTrue("\"key\":\"<redacted>\"" in safe)

        val headers = redact("Authorization: Bearer abcdef0123456789\nCookie: sid=s3cr3tvalue; theme=dark\nx-request-id: 42\nfailed with Bearer zyxwvu9876543210")
        assertEquals("Authorization: <redacted>\nCookie: <redacted>\nx-request-id: 42\nfailed with Bearer <redacted>", headers)
        assertEquals("grant=<redacted>&code=<redacted>&track=42", redact("grant=AAA.BBB&code=4/0Ab&track=42"))
        // A word that merely contains a secret's name is not one.
        assertEquals("monkey=1 passkeys=2 statement=3", redact("monkey=1 passkeys=2 statement=3"))
        // What the store reports about packages is not a secret.
        val hashes = "expected=69ce52eabdd9df6464a30b793b802d3d7be32b331082c02a9bf2b25d1d49114d; actual=054c1d2c40058b49f5ade17a64442014ddf05944663a12c790a17d63ee99985f"
        assertEquals(hashes, redact(hashes))
        assertEquals("", redact(""))
    }

    @Test fun whateverBuildsAnErrorItsDetailsAreRedacted() {
        val failure = SpotiFlacException(
            "Download failed",
            "provider_timeout",
            technicalDetails = """{"url":"https://x.audio.tidal.com/t/1.flac?token=SECRET","session":"SESSIONVALUE"}""",
        )
        assertFalse("SECRET" in failure.technicalDetails!!)
        assertFalse("SESSIONVALUE" in failure.technicalDetails!!)
        assertTrue("https://x.audio.tidal.com/t/1.flac?<redacted>" in failure.technicalDetails!!)
        assertEquals(null, SpotiFlacException("x").technicalDetails)
    }

    // -- The timeline ------------------------------------------------------------------------

    @Test fun aDownloadsTimeIsSplitByPhase() {
        var now = 0L
        val timeline = DownloadTimeline { now }
        timeline.coldStart = true
        timeline.measure(DownloadTimeline.Phase.INIT) { now += 1_800 }
        timeline.measure(DownloadTimeline.Phase.RESOLVE) { now += 700 }

        timeline.callStarted()
        now += 2_500                       // the provider looks the track up and negotiates the stream
        timeline.progress(64_000)          // first byte
        now += 500; timeline.progress(1_000_000)
        now += 500; timeline.progress(1_000_000) // the same count: nothing new arrived
        now += 9_000; timeline.progress(30_000_000)
        timeline.callEnded(nativeAttempts = 1)

        timeline.measure(DownloadTimeline.Phase.FINALIZE) { now += 400 }
        timeline.add(DownloadTimeline.Phase.IMPORT, 250)
        timeline.hostRequests(3)

        assertEquals(1_800, timeline.millis(DownloadTimeline.Phase.INIT))
        assertEquals(700, timeline.millis(DownloadTimeline.Phase.RESOLVE))
        assertEquals(2_500, timeline.millis(DownloadTimeline.Phase.FIRST_BYTE))
        assertEquals(10_000, timeline.millis(DownloadTimeline.Phase.TRANSFER))
        assertEquals(400, timeline.millis(DownloadTimeline.Phase.FINALIZE))
        assertEquals(250, timeline.millis(DownloadTimeline.Phase.IMPORT))
        assertEquals(1, timeline.providerCalls)
        assertEquals(0, timeline.retries)
        assertEquals(30_000_000L, timeline.receivedBytes)
        assertEquals(0L, timeline.retransmittedBytes)

        val line = timeline.summary("harmony-123e4567-e89b-12d3-a456-426614174000", "imported")
        assertEquals(
            "DOWNLOAD job=426614174000 outcome=imported cold=true init=1800ms resolve=700ms first_byte=2500ms " +
                "transfer=10000ms finalize=400ms import=250ms calls=1 retries=0 host_requests=3 received=30000000 resumed=0 resent=0",
            line,
        )
    }

    @Test fun retriesResumedAndRetransmittedBytesAreCounted() {
        var now = 0L
        val timeline = DownloadTimeline { now }
        // First provider: answers nothing and is given up on.
        timeline.callStarted()
        now += 95_000
        timeline.callEnded()
        // Second provider: restarts once inside the call (the count goes back), the runtime says 3 attempts.
        timeline.callStarted()
        now += 1_000; timeline.progress(1_000_000)
        now += 1_000; timeline.progress(10_000_000)
        now += 1_000; timeline.progress(2_000_000)     // 8 MB are being sent again
        now += 1_000; timeline.progress(20_000_000)
        timeline.callEnded(nativeAttempts = 3)
        assertEquals(2, timeline.providerCalls)
        assertEquals("one more provider call, two retries inside the second", 3, timeline.retries)
        assertEquals(95_000 + 1_000, timeline.millis(DownloadTimeline.Phase.FIRST_BYTE))
        assertEquals(3_000, timeline.millis(DownloadTimeline.Phase.TRANSFER))
        assertEquals(8_000_000L, timeline.retransmittedBytes)
        assertEquals(28_000_000L, timeline.receivedBytes)

        // An attempt that continues what an earlier one left: those bytes are not counted as received.
        val resumed = DownloadTimeline { now }
        resumed.callStarted(onDiskBytes = 12_000_000)
        now += 300; resumed.progress(12_500_000)
        now += 300; resumed.progress(20_000_000)
        resumed.callEnded()
        assertEquals(12_000_000L, resumed.resumedBytes)
        assertEquals(8_000_000L, resumed.receivedBytes)
        assertEquals(0L, resumed.retransmittedBytes)

        // An attempt that could not continue: what was on disk is sent again.
        val restarted = DownloadTimeline { now }
        restarted.callStarted(onDiskBytes = 12_000_000)
        now += 300; restarted.progress(500_000)
        now += 300; restarted.progress(20_000_000)
        restarted.callEnded()
        assertEquals(0L, restarted.resumedBytes)
        assertEquals(12_000_000L, restarted.retransmittedBytes)
        assertEquals(20_000_000L, restarted.receivedBytes)
        assertTrue("cold=false" in restarted.summary("job", "downloaded"))
    }
}
