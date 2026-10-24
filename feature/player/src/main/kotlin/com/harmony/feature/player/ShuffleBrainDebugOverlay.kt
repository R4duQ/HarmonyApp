package com.harmony.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.harmony.core.model.AcousticDims
import com.harmony.core.model.AuditCheckResult
import com.harmony.core.model.AuditStatus
import com.harmony.core.model.BrainAuditReport
import com.harmony.core.model.QueuedDecision
import com.harmony.core.model.ShuffleBrainTelemetry
import com.harmony.core.model.VectorQuality
import java.util.Locale

/**
 * The Diagnostic Brain's heads-up display, floated over the player:
 *
 *  - the 24-D session vibe vector S_t as bipolar bars (ten octave bands,
 *    their groove dynamics, punch / brightness / crest / pulse), with the
 *    bands it leans on named;
 *  - the five self-audit LEDs: green = pass, amber = auto-healed, red =
 *    could not be repaired; tap one for what it measured;
 *  - the upcoming queue, each song with its "Why this song?" badge and the
 *    split of its score (vibe, Markov habit, affinity, exploration), its
 *    cooldown multiplier and how smoothly it follows the song before;
 *  - the synthetic self-test, run on demand.
 *
 * A non-focusable popup, so the player underneath stays usable; it can be
 * folded down to its LED strip.
 */
@Composable
fun ShuffleBrainDebugOverlay(
    telemetry: ShuffleBrainTelemetry,
    selfTestRunning: Boolean,
    onRunSelfTest: () -> Unit,
    onClose: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        ShuffleBrainPanel(telemetry, selfTestRunning, onRunSelfTest, onClose)
    }
}

private object Hud {
    val panel = Color(0xEE0D1117)
    val edge = Color(0x33FFFFFF)
    val ink = Color(0xFFE6EDF3)
    val muted = Color(0xFF8B949E)
    val faint = Color(0x22FFFFFF)
    val green = Color(0xFF3FB950)
    val amber = Color(0xFFD29922)
    val red = Color(0xFFF85149)
    val positive = Color(0xFF58A6FF)
    val negative = Color(0xFFDB61A2)
    val vibe = Color(0xFF58A6FF)
    val markov = Color(0xFFBC8CFF)
    val affinity = Color(0xFF3FB950)
    val explore = Color(0xFFD29922)
    val mono = FontFamily.Monospace
}

@Composable
internal fun ShuffleBrainPanel(
    telemetry: ShuffleBrainTelemetry,
    selfTestRunning: Boolean,
    onRunSelfTest: () -> Unit,
    onClose: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var openCheck by remember { mutableStateOf<Int?>(null) }
    val audit = telemetry.audit

    Column(
        Modifier
            .padding(horizontal = 10.dp, vertical = 36.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Hud.panel)
            .border(1.dp, Hud.edge, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        // Header: title, verdict, fold and close.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SHUFFLE BRAIN", color = Hud.ink, fontFamily = Hud.mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            VerdictChip(audit, telemetry.engineAvailable)
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Fold the brain HUD" else "Unfold the brain HUD",
                tint = Hud.muted,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { expanded = !expanded }.padding(4.dp),
            )
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Close the brain HUD",
                tint = Hud.muted,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
            )
        }

        // The five LEDs.
        Spacer(Modifier.height(8.dp))
        AuditLeds(audit?.checks.orEmpty(), openCheck) { openCheck = if (openCheck == it) null else it }
        val open = openCheck?.let { audit?.checks?.getOrNull(it) }
        AnimatedVisibility(open != null) {
            open?.let {
                Text(
                    "${it.kind.title}: ${it.detail}",
                    color = Hud.ink,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = Hud.mono,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        AnimatedVisibility(expanded) {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(10.dp))
                SessionVectorBars(telemetry)
                Spacer(Modifier.height(8.dp))
                StatsLine(telemetry)
                Spacer(Modifier.height(10.dp))
                UpcomingDecisions(telemetry.upcoming)
                Spacer(Modifier.height(10.dp))
                SelfTest(telemetry.selfTestReport, selfTestRunning, telemetry.engineAvailable, onRunSelfTest)
            }
        }
    }
}

@Composable
private fun VerdictChip(audit: BrainAuditReport?, engineAvailable: Boolean) {
    val (label, color) = when {
        !engineAvailable -> "ENGINE OFF" to Hud.red
        audit == null -> "WAITING" to Hud.muted
        !audit.healthy -> audit.verdict to Hud.red
        audit.healedCount > 0 -> audit.verdict to Hud.amber
        else -> "PASS" to Hud.green
    }
    Text(
        label,
        color = color,
        fontFamily = Hud.mono,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun ledColor(status: AuditStatus?) = when (status) {
    AuditStatus.PASS -> Hud.green
    AuditStatus.HEALED -> Hud.amber
    AuditStatus.FAILED -> Hud.red
    null -> Hud.faint
}

@Composable
private fun AuditLeds(checks: List<AuditCheckResult>, open: Int?, onTap: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        val kinds = com.harmony.core.model.AuditCheckKind.entries
        kinds.forEachIndexed { i, kind ->
            val check = checks.getOrNull(i)
            val color = ledColor(check?.status)
            val glow by animateFloatAsState(if (check?.status == AuditStatus.HEALED) 0.35f else 0.2f, label = "led-glow")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (open == i) Hud.faint else Color.Transparent)
                    .clickable { onTap(i) }
                    .semantics { contentDescription = "${kind.title}: ${check?.status?.name ?: "not run yet"}" }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(3.dp, color.copy(alpha = glow), CircleShape),
                )
                Spacer(Modifier.width(4.dp))
                Text(kind.code, color = Hud.ink, fontFamily = Hud.mono, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SessionVectorBars(telemetry: ShuffleBrainTelemetry) {
    val vector = telemetry.sessionVector
    Text(
        if (telemetry.hasSession) {
            val dominant = AcousticDims.dominantBands(vector)
                .joinToString(" · ") { "${AcousticDims.BAND_LABELS[it]} Hz" }
                .ifEmpty { "flat" }
            "Session vibe S_t: leans on $dominant (${telemetry.lastEvent.name})"
        } else {
            "Session vibe S_t: seeds from the next song played"
        },
        color = Hud.ink,
        fontSize = 11.sp,
        fontFamily = Hud.mono,
    )
    Spacer(Modifier.height(4.dp))
    val values = if (vector.size == AcousticDims.COUNT) vector else List(AcousticDims.COUNT) { 0f }
    val maxMagnitude = (values.maxOfOrNull { kotlin.math.abs(it) } ?: 0f).coerceAtLeast(0.25f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = "Session vibe vector, 24 dimensions" },
    ) {
        val slot = size.width / AcousticDims.COUNT
        val mid = size.height / 2f
        // Group backgrounds: bands, dynamics, macro features.
        listOf(0 to 10, 10 to 20, 20 to 24).forEachIndexed { g, (from, to) ->
            drawRoundRect(
                color = if (g % 2 == 0) Hud.faint else Color(0x11FFFFFF),
                topLeft = Offset(from * slot, 0f),
                size = Size((to - from) * slot - 2f, size.height),
                cornerRadius = CornerRadius(6f, 6f),
            )
        }
        drawLine(Hud.edge, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        values.forEachIndexed { i, v ->
            val h = (v / maxMagnitude).coerceIn(-1f, 1f) * (mid - 3f)
            val top = if (h >= 0f) mid - h else mid
            drawRoundRect(
                color = if (v >= 0f) Hud.positive else Hud.negative,
                topLeft = Offset(i * slot + slot * 0.18f, top),
                size = Size(slot * 0.64f, kotlin.math.abs(h).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("BANDS 31-16K", color = Hud.muted, fontSize = 9.sp, fontFamily = Hud.mono, modifier = Modifier.weight(10f))
        Text("GROOVE", color = Hud.muted, fontSize = 9.sp, fontFamily = Hud.mono, modifier = Modifier.weight(10f))
        Text("MACRO", color = Hud.muted, fontSize = 9.sp, fontFamily = Hud.mono, modifier = Modifier.weight(4f))
    }
}

@Composable
private fun StatsLine(telemetry: ShuffleBrainTelemetry) {
    val a = telemetry.audit
    val text = if (a == null) {
        "Library ${telemetry.librarySize} songs · no queue built yet"
    } else {
        String.format(
            Locale.US,
            "Library %d · %.0f%% real 24-D · %d cold-start\navg cos %.2f · artist H %.1f bits · zone H %.1f bits · explore x%.2f · %.2f ms",
            telemetry.librarySize, a.realVectorPct, a.fallbackTracks + a.placeholderTracks,
            a.averagePairwiseSimilarity, a.artistEntropyBits, a.zoneEntropyBits, a.exploreBoost, a.elapsedMs,
        )
    }
    Text(text, color = Hud.muted, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = Hud.mono)
}

@Composable
private fun UpcomingDecisions(upcoming: List<QueuedDecision>) {
    Text("UP NEXT · Why this song?", color = Hud.ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Hud.mono)
    if (upcoming.isEmpty()) {
        Text(
            "Nothing queued by Smart Shuffle yet. Turn it on and the brain explains every pick here.",
            color = Hud.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    Row(Modifier.padding(top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Legend("vibe", Hud.vibe)
        Legend("markov", Hud.markov)
        Legend("affinity", Hud.affinity)
        Legend("explore", Hud.explore)
    }
    upcoming.forEachIndexed { index, decision ->
        DecisionRow(index + 1, decision)
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(3.dp))
        Text(label, color = Hud.muted, fontSize = 9.sp, fontFamily = Hud.mono)
    }
}

@Composable
private fun DecisionRow(position: Int, decision: QueuedDecision) {
    val t = decision.trace
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$position", color = Hud.muted, fontFamily = Hud.mono, fontSize = 11.sp, modifier = Modifier.width(18.dp))
            Text(
                "${decision.title} · ${decision.artist}",
                color = Hud.ink,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (t.quality != VectorQuality.REAL) {
                Text(
                    if (t.quality == VectorQuality.FALLBACK) "COLD" else "NO VEC",
                    color = Hud.amber,
                    fontSize = 9.sp,
                    fontFamily = Hud.mono,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Row(Modifier.padding(start = 18.dp, top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            // The "Why this song?" badge.
            Text(
                t.primaryReasonTag,
                color = Hud.ink,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Hud.vibe.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                String.format(Locale.US, "cool %.2f · smooth %.2f", t.recencyPenaltyMultiplier, t.transitionSmoothnessScore),
                color = Hud.muted,
                fontSize = 9.sp,
                fontFamily = Hud.mono,
                maxLines = 1,
            )
        }
        ScoreSplit(
            listOf(t.vibeSimilarityPct to Hud.vibe, t.markovHabitPct to Hud.markov, t.userAffinityPct to Hud.affinity, t.explorationBonusPct to Hud.explore),
            Modifier.padding(start = 18.dp, top = 4.dp),
        )
        Text(
            String.format(
                Locale.US, "vibe %.0f%% · markov %.0f%% · affinity %.0f%% · explore %.0f%%",
                t.vibeSimilarityPct, t.markovHabitPct, t.userAffinityPct, t.explorationBonusPct,
            ),
            color = Hud.muted,
            fontSize = 9.sp,
            fontFamily = Hud.mono,
            modifier = Modifier.padding(start = 18.dp, top = 2.dp),
        )
    }
}

/** One stacked bar: the score's split into its four contributions. */
@Composable
private fun ScoreSplit(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        val total = parts.sumOf { it.first.coerceAtLeast(0f).toDouble() }.toFloat().coerceAtLeast(1e-3f)
        var x = 0f
        drawRect(Hud.faint)
        parts.forEach { (pct, color) ->
            val w = size.width * pct.coerceAtLeast(0f) / total
            drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            x += w
        }
    }
}

@Composable
private fun SelfTest(report: String?, running: Boolean, engineAvailable: Boolean, onRun: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("SELF-TEST", color = Hud.ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Hud.mono)
        Spacer(Modifier.weight(1f))
        val enabled = !running && engineAvailable
        Text(
            if (running) "running..." else "Run 100-track simulation",
            color = if (enabled) Hud.positive else Hud.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Hud.faint)
                .clickable(enabled = enabled, onClick = onRun)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
    if (report != null) {
        val passed = report.contains("RESULT: PASS")
        Text(
            report.trim(),
            color = if (passed) Hud.ink else Hud.red,
            fontSize = 9.5.sp,
            lineHeight = 13.sp,
            fontFamily = Hud.mono,
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x55000000))
                .border(1.dp, if (passed) Hud.green.copy(alpha = 0.4f) else Hud.red.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(8.dp),
        )
    }
}
