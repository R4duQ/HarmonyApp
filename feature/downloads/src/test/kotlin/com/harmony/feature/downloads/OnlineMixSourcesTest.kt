package com.harmony.feature.downloads

import com.harmony.domain.online.CatalogKind
import com.harmony.domain.online.CatalogTrack
import com.harmony.domain.online.PrepareOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OnlineMixSourcesTest {
    private val track = CatalogTrack("vid00000001", "Scrisoare din trecut", listOf("Subcarpați"), "Aripi de lumină", null, 241_000, CatalogKind.SONG, null, true)

    private fun listed(title: String, artist: String, ms: Long, isrc: String? = null, score: Int = 90, id: String = title) =
        SpotiFlacSearchCandidate(id, artist, title, "", ms, null, score, source = "Tidal", isrc = isrc)

    @Test
    fun onlyTheSameRecordingIsDownloaded() {
        val chosen = OnlineSourceMatch.pick(track, listOf(
            listed("Scrisoare din trecut (Live)", "Subcarpati", 241_000, id = "live"),
            listed("Scrisoare din trecut", "Subcarpati", 260_000, id = "long"),
            listed("Scrisoare din trecut", "Vama", 241_000, id = "other-artist"),
            listed("Scrisoare Din Trecut", "SUBCARPAŢI", 242_000, id = "right", score = 70),
            listed("Scrisoare din trecut", "Subcarpați", 241_500, isrc = "ROA231800001", id = "right-isrc", score = 60),
        ))
        assertEquals("the ISRC-bearing copy of the same recording first", "right-isrc", chosen?.metadataId)
        assertNull(OnlineSourceMatch.pick(track, listOf(listed("Scrisoare din trecut (Live)", "Subcarpati", 241_000))))
        assertNull(OnlineSourceMatch.pick(track, listOf(listed("Scrisoare din trecut", "Subcarpati", 250_000))))
    }

    @Test
    fun anUnknownLengthIsNotARejection() {
        assertTrue(OnlineSourceMatch.lengthAgrees(240_000, null))
        assertTrue(OnlineSourceMatch.lengthAgrees(null, 240_000))
        assertTrue(OnlineSourceMatch.lengthAgrees(242_900, 240_000))
        assertFalse(OnlineSourceMatch.lengthAgrees(243_100, 240_000))
    }

    @Test
    fun engineFailuresKeepTheirMeaning() {
        fun outcome(type: String?, message: String = "x") = OnlineSourceMatch.outcomeOf(SpotiFlacException(message, errorType = type, provider = "tidal"))
        assertTrue(outcome("verification_required") is PrepareOutcome.NotVerified)
        assertTrue(outcome("not_found") is PrepareOutcome.NotAvailable)
        assertTrue(outcome("unsupported_lossless_quality") is PrepareOutcome.NotAvailable)
        assertTrue(outcome("rate_limit") is PrepareOutcome.TemporaryError)
        assertTrue(outcome(null, "Unable to resolve host") is PrepareOutcome.TemporaryError)
        assertTrue(outcome("integrity_failed") is PrepareOutcome.RejectedAudio)
        assertTrue(outcome("storage_error") is PrepareOutcome.Held)
    }

    @Test
    fun theCacheMakesRoomWithoutTouchingWhatTheQueueHolds() {
        val dir = Files.createTempDirectory("online-mix").toFile()
        try {
            val cache = OnlineCache(dir)
            val now = System.currentTimeMillis()
            // Three files, oldest first; the oldest is in the queue.
            listOf("queued00001", "old00000002", "new00000003").forEachIndexed { i, id ->
                dir.resolve("$id.flac").apply { writeBytes(ByteArray(10)); setLastModified(now - (3 - i) * 60_000L) }
            }
            cache.makeRoom(OnlineCache.LIMIT_BYTES - 15, setOf("queued00001"))
            assertTrue("kept: the queue holds it", dir.resolve("queued00001.flac").exists())
            assertFalse("the least recently used other one went", dir.resolve("old00000002.flac").exists())

            val staged = Files.createTempFile("staged", ".flac").toFile().apply { writeText("fLaC") }
            val target = dir.resolve("abcdefghijk.flac")
            cache.place(staged, target)
            assertEquals("fLaC", target.readText())
            assertFalse(staged.exists())
            assertFalse(dir.resolve("abcdefghijk.flac.part").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
