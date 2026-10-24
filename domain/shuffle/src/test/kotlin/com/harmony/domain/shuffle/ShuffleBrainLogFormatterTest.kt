package com.harmony.domain.shuffle

import com.harmony.core.model.AuditCheckKind
import com.harmony.core.model.AuditCheckResult
import com.harmony.core.model.AuditStatus
import com.harmony.core.model.BrainAuditReport
import com.harmony.core.model.QueuedDecision
import com.harmony.core.model.ReasonTag
import com.harmony.core.model.SessionEvent
import com.harmony.core.model.ShuffleBrainTelemetry
import com.harmony.core.model.TrackDecisionTrace
import com.harmony.core.model.VectorQuality
import com.harmony.domain.shuffle.brain.ShuffleBrainLogFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleBrainLogFormatterTest {
    private fun telemetry(healed: Boolean) = ShuffleBrainTelemetry(
        sessionVector = List(24) { if (it == 1) 0.61f else if (it == 0) 0.44f else if (it == 8) -0.5f else 0.01f },
        hasSession = true,
        lastEvent = SessionEvent.FAST_SKIP,
        audit = BrainAuditReport(
            generation = 12,
            checks = AuditCheckKind.entries.map {
                AuditCheckResult(it, if (healed && it == AuditCheckKind.SKIP_REPULSION) AuditStatus.HEALED else AuditStatus.PASS,
                    0.8f, 0.6f, if (healed) 1 else 0, "detail ${it.code}")
            },
            afterFastSkip = true, averagePairwiseSimilarity = 0.41f, artistEntropyBits = 2.5f, zoneEntropyBits = 1.2f,
            sessionNorm = 1f, nonFiniteValues = 0, realVectorPct = 82.4f, realTracks = 990, fallbackTracks = 200,
            placeholderTracks = 14, exploreBoost = 1f, elapsedMs = 0.41f,
        ),
        upcoming = (1..7).map {
            QueuedDecision(
                TrackDecisionTrace(it.toLong(), 1f, 62f, 10f, 18f, 10f, 1f, 0.91f, 0.88f, 1, VectorQuality.REAL,
                    ReasonTag.VIBE_MATCH, "94% Bass/Vibe Match"),
                title = "A very long song title number $it",
                artist = "Artist",
            )
        },
        librarySize = 1204,
    )

    @Test
    fun tableShowsVibeAuditAndTopFive() {
        val lines = ShuffleBrainLogFormatter.format(telemetry(healed = true), "re-rank")
        assertTrue(lines.first(), lines.first().startsWith("+-- SHUFFLE BRAIN | re-rank #12 | after FAST SKIP | 0.41 ms"))
        assertTrue(lines[1], lines[1].contains("S_t dominant: 62Hz +0.61, 31Hz +0.44") && lines[1].contains("norm"))
        assertTrue(lines.any { it == "| Self-audit: HEALED (1)" })
        assertTrue(lines.any { it.contains("SKIP HEALED  detail SKIP") })
        assertTrue(lines.any { it.contains("82.4% real 24-D vectors (214 cold-start)") })
        val rows = lines.filter { it.contains("94% Bass/Vibe Match") }
        assertEquals("top five only", 5, rows.size)
        assertTrue(rows.first(), rows.first().contains(" 62% |  10% |  18% |  10% | 1.00 | 0.91 |"))
        assertTrue("long titles are cut to the column", rows.all { it.indexOf("|", 7) - 7 <= 28 })
    }

    @Test
    fun beforeTheFirstSong() {
        val lines = ShuffleBrainLogFormatter.format(ShuffleBrainTelemetry())
        assertTrue(lines.any { it.contains("not seeded yet") })
    }

    @Test
    fun selfTestOneLinePerCheck() {
        val lines = ShuffleBrainLogFormatter.formatSelfTest("[PASS] a\n[FAIL] b\nRESULT: FAIL (1/2 checks)\n")
        assertEquals(listOf("| [PASS] a", "| [FAIL] b", "| RESULT: FAIL (1/2 checks)"), lines.drop(1).dropLast(1))
    }
}
