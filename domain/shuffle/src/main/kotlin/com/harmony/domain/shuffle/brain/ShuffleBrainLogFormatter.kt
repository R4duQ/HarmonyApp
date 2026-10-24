package com.harmony.domain.shuffle.brain

import com.harmony.core.model.AcousticDims
import com.harmony.core.model.ShuffleBrainTelemetry
import java.util.Locale

/**
 * The SHUFFLE_BRAIN Logcat table, written on every queue update:
 *
 *     +-- SHUFFLE BRAIN | queue #12 | after FAST SKIP | 0.41 ms ----------------
 *     | S_t dominant: 62Hz +0.61, 31Hz +0.44, 125Hz +0.20 | norm 1.0000 | FAST_SKIP
 *     | Self-audit: HEALED (1)
 *     |   ART  PASS    min same-artist gap 3 (>= 3 required), 0 repairs
 *     |   SKIP HEALED  S_t.v_skip 0.42 -> 0.05; 80% of the tail new and distinct
 *     |   ...
 *     | Library 1204 songs | 82.4% real 24-D vectors | explore x1.00
 *     +----+--------------------------+------+------+------+------+------+------+
 *     | #  | Song                     | Vibe | Mrkv | Affn | Expl | Cool | Smth | Why this song?
 *     | 1  | Title - Artist           |  62% |  10% |  18% |  10% | 1.00 | 0.91 | 94% Bass/Vibe Match
 *
 * Plain ASCII, one list entry per Logcat line (Logcat truncates long lines).
 */
object ShuffleBrainLogFormatter {
    const val TAG = "SHUFFLE_BRAIN"
    private const val TOP_TRACKS = 5
    private const val SONG_WIDTH = 26
    private const val RULE = "+----+----------------------------+------+------+------+------+------+------+"

    fun format(t: ShuffleBrainTelemetry, title: String = "queue"): List<String> {
        val audit = t.audit
        val lines = ArrayList<String>(24)
        val header = buildString {
            append("+-- SHUFFLE BRAIN | ").append(title)
            if (audit != null) append(" #").append(audit.generation)
            if (audit?.afterFastSkip == true) append(" | after FAST SKIP")
            if (audit != null) append(" | ").append(fmt("%.2f ms", audit.elapsedMs))
            append(' ')
        }
        lines += header.padEnd(RULE.length, '-')

        val vector = t.sessionVector
        lines += if (t.hasSession && vector.size >= AcousticDims.BANDS) {
            val dominant = AcousticDims.dominantBands(vector).joinToString(", ") {
                "${AcousticDims.BAND_LABELS[it]}Hz ${fmt("%+.2f", vector[it])}"
            }.ifEmpty { "flat" }
            val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() })
            "| S_t dominant: $dominant | norm ${fmt("%.4f", norm)} | ${t.lastEvent}"
        } else {
            "| S_t: not seeded yet (the next song played seeds it) | ${t.lastEvent}"
        }

        if (audit != null) {
            lines += "| Self-audit: ${audit.verdict}"
            audit.checks.forEach { c ->
                lines += "|   ${c.kind.code.padEnd(4)} ${c.status.name.padEnd(7)} ${c.detail}"
            }
            lines += "| Library ${t.librarySize} songs | ${fmt("%.1f", audit.realVectorPct)}% real 24-D vectors" +
                " (${audit.fallbackTracks + audit.placeholderTracks} cold-start) | explore x${fmt("%.2f", audit.exploreBoost)}"
        } else if (!t.engineAvailable) {
            lines += "| Native engine unavailable: random fallback"
        }

        if (t.upcoming.isNotEmpty()) {
            lines += RULE
            lines += "| #  | ${"Song".padEnd(SONG_WIDTH)} | Vibe | Mrkv | Affn | Expl | Cool | Smth | Why this song?"
            lines += RULE
            t.upcoming.take(TOP_TRACKS).forEachIndexed { i, d ->
                val tr = d.trace
                val song = "${d.title} - ${d.artist}".let { if (it.length > SONG_WIDTH) it.take(SONG_WIDTH - 1) + "~" else it }
                lines += "| ${(i + 1).toString().padEnd(2)} | ${song.padEnd(SONG_WIDTH)} |" +
                    " ${pct(tr.vibeSimilarityPct)} | ${pct(tr.markovHabitPct)} | ${pct(tr.userAffinityPct)} |" +
                    " ${pct(tr.explorationBonusPct)} | ${fmt("%.2f", tr.recencyPenaltyMultiplier)} |" +
                    " ${fmt("%.2f", tr.transitionSmoothnessScore)} | ${tr.primaryReasonTag}"
            }
            lines += RULE
        }
        return lines
    }

    /** The self-test report, one Logcat line per check. */
    fun formatSelfTest(report: String): List<String> =
        listOf("+-- SHUFFLE BRAIN | synthetic self-test ".padEnd(RULE.length, '-')) +
            report.lines().filter { it.isNotBlank() }.map { "| $it" } +
            listOf(RULE)

    private fun pct(value: Float) = fmt("%3.0f%%", value).padStart(4)

    private fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.US, pattern, *args)
}
