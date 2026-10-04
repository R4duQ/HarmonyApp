package com.harmony.feature.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.ClarityBands
import com.harmony.core.model.ClarityPreset
import com.harmony.core.model.ClarityReadout
import com.harmony.core.model.ClaritySettings
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialTextAction
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** Clarity's colours: violet for the part, cyan for what it brings out, pink for what it holds back. */
internal object ClarityColors {
    val Clarity = Color(0xFFB45CFF)
    val ClarityLight = Color(0xFFE2BBFF)
    val Recover = Color(0xFF35E0FF)
    val Tame = Color(0xFFFF5C93)
    val Mask = Color(0xFFFFC857)
    val StageTop = Color(0xFF120C2B)
    val StageBottom = Color(0xFF1E1140)
}

internal object ClarityTags {
    const val SWITCH = "auto_clarity"
    const val VIEW = "clarity_view"
    const val RECOVER = "clarity_recover"
    const val TAME = "clarity_tame"
    const val BIAS = "clarity_bias"
    const val BRIGHTEN = "clarity_brighten"
    const val BOOST = "clarity_boost"
    const val RESET = "clarity_reset"
    fun preset(p: ClarityPreset) = "clarity_preset_${p.name.lowercase()}"
}

/**
 * Clarity's card body: what the hearing model hears right now, what it is
 * doing about it, starting points and the five controls.
 */
@Composable
internal fun ClarityDetail(
    state: AutoEqUiState,
    live: StateFlow<ClarityReadout>,
    onSettings: (ClaritySettings) -> Unit,
    palette: EditorialPalette,
) {
    val readout by live.collectAsStateWithLifecycle()
    val s = state.clarity
    val running = state.eqOn && !readout.silent
    Column(Modifier.padding(top = 14.dp)) {
        PerceptionView(readout, running, state.eqOn, Modifier.fillMaxWidth().testTag(ClarityTags.VIEW))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val lift = readout.gainDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f
            val cut = readout.gainDb.minOrNull()?.coerceAtMost(0f) ?: 0f
            StatTile(
                "RECOVER", if (running) fmtDb(lift) else "—",
                if (running) bandsText(readout.recovering) else "waiting",
                ClarityColors.Recover, palette, Modifier.weight(1f),
            )
            StatTile(
                "TAME", if (running) fmtDb(cut) else "—",
                if (running) bandsText(readout.taming) else "waiting",
                ClarityColors.Tame, palette, Modifier.weight(1f),
            )
            StatTile(
                "LEVEL", if (running) fmtDb(readout.outputDb) else fmtDb(s.boostDb),
                if (s.boostDb == 0f) "matched" else "boost ${fmtDb(s.boostDb)}",
                ClarityColors.Clarity, palette, Modifier.weight(1f),
            )
        }
        Text(
            when {
                !state.eqOn -> "Switch the equalizer on to let Clarity listen."
                !running -> "Play something and Clarity starts listening."
                else -> describeClarity(readout)
            },
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = palette.ink,
            modifier = Modifier.padding(top = 10.dp),
        )

        SectionLabel("STARTING POINTS", palette)
        PresetRow(s, onSettings, palette)

        SectionLabel("CONTROLS", palette)
        Control(
            title = "Recover",
            hint = "Brings out what other sounds cover up",
            value = "${(s.recover * 100).roundToInt()}%",
            color = ClarityColors.Recover,
            palette = palette,
        ) {
            ClaritySlider(
                s.recover, 0f..1f, 0.5f, step = 0.01f, bipolar = false, ClarityColors.Recover,
                onChange = { onSettings(s.copy(recover = it)) }, palette = palette, tag = ClarityTags.RECOVER,
            )
        }
        Control(
            title = "Tame",
            hint = "Holds back harshness, boom and ringing",
            value = "${(s.tame * 100).roundToInt()}%",
            color = ClarityColors.Tame,
            palette = palette,
        ) {
            ClaritySlider(
                s.tame, 0f..1f, 0.5f, step = 0.01f, bipolar = false, ClarityColors.Tame,
                onChange = { onSettings(s.copy(tame = it)) }, palette = palette, tag = ClarityTags.TAME,
            )
        }
        Control(
            title = "Bias",
            hint = "Leans towards taming or recovering",
            value = leaning(s.bias, "Even", "Tame", "Recover"),
            color = if (s.bias < 0f) ClarityColors.Tame else ClarityColors.Recover,
            palette = palette,
            ends = "Tame" to "Recover",
        ) {
            ClaritySlider(
                s.bias, -1f..1f, 0f, step = 0.01f, bipolar = true, ClarityColors.Tame, ClarityColors.Recover,
                onChange = { onSettings(s.copy(bias = it)) }, palette = palette, tag = ClarityTags.BIAS,
            )
        }
        Control(
            title = "Brighten",
            hint = "How bright the music feels, tilted around 1 kHz",
            value = leaning(s.brighten, "Neutral", "Darker", "Brighter"),
            color = if (s.brighten < 0f) Color(0xFFFF9F2E) else Color(0xFFFFD84D),
            palette = palette,
            ends = "Darker" to "Brighter",
        ) {
            ClaritySlider(
                s.brighten, -1f..1f, 0f, step = 0.01f, bipolar = true, Color(0xFFFF7A2E), Color(0xFFFFE066),
                onChange = { onSettings(s.copy(brighten = it)) }, palette = palette, tag = ClarityTags.BRIGHTEN,
            )
        }
        Control(
            title = "Boost",
            hint = "Output level, on top of Clarity's own loudness matching",
            value = fmtDb(s.boostDb),
            color = ClarityColors.Clarity,
            palette = palette,
            ends = "−6 dB" to "+6 dB",
        ) {
            ClaritySlider(
                s.boostDb, -ClaritySettings.MAX_BOOST_DB..ClaritySettings.MAX_BOOST_DB, 0f, step = 0.5f, bipolar = true,
                ClarityColors.Clarity, ClarityColors.ClarityLight,
                onChange = { onSettings(s.copy(boostDb = it)) }, palette = palette, tag = ClarityTags.BOOST,
            )
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Double-tap a slider to centre it.",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = palette.muted,
                modifier = Modifier.weight(1f),
            )
            if (s != ClaritySettings()) {
                EditorialTextAction("Reset", { onSettings(ClaritySettings()) }, palette, Modifier.testTag(ClarityTags.RESET))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Perception view
// ---------------------------------------------------------------------------

/**
 * What the model hears: a bar per band for its level, the masking threshold
 * as a dashed gold line (what sits under it is covered up), and below them
 * the curve Clarity plays, cyan where it brings out and pink where it holds
 * back.
 */
@Composable
private fun PerceptionView(r: ClarityReadout, running: Boolean, eqOn: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(ClarityColors.StageTop, ClarityColors.StageBottom)))
            .border(1.dp, ClarityColors.Clarity.copy(alpha = 0.35f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics {
                contentDescription = if (running) "Clarity: ${describeClarity(r)}" else "Clarity is waiting for music"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "WHAT YOU HEAR",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
            )
            Legend(ClarityColors.ClarityLight, "Heard")
            Spacer(Modifier.width(10.dp))
            Legend(ClarityColors.Mask, "Covered below", dashed = true)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(172.dp)) {
            Canvas(Modifier.fillMaxWidth().height(172.dp)) {
                val n = ClarityBands.COUNT
                val top = size.height * 0.6f
                val gainTop = top + 10.dp.toPx()
                val gainMid = gainTop + (size.height - gainTop) / 2f
                val gainSpan = (size.height - gainTop) / 2f * 0.9f
                val slot = size.width / n
                val barW = slot * 0.62f
                val alpha = if (running) 1f else 0.35f

                val loudest = r.levelDb.maxOrNull() ?: -120f
                val ceiling = if (running) loudest + 3f else 0f
                val floor = ceiling - RANGE_DB
                fun yOf(db: Float) = top * (1f - ((db - floor) / RANGE_DB).coerceIn(0f, 1f))

                // Grid.
                for (k in 1..3) {
                    val y = top * k / 4f
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                // Bars: what is heard, dim where it sits under the threshold.
                for (k in 0 until n) {
                    val cx = slot * (k + 0.5f)
                    val level = if (running) r.levelDb[k] else floor + RANGE_DB * idleShape(k)
                    val y = yOf(level)
                    val covered = running && r.levelDb[k] < r.maskDb[k]
                    val h = top - y
                    if (h > 0.5f) {
                        drawRoundRect(
                            Brush.verticalGradient(
                                if (covered) listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.06f))
                                else listOf(ClarityColors.ClarityLight.copy(alpha = alpha), ClarityColors.Clarity.copy(alpha = 0.35f * alpha)),
                                startY = y, endY = top,
                            ),
                            topLeft = Offset(cx - barW / 2, y), size = Size(barW, h),
                            cornerRadius = CornerRadius(barW / 3),
                        )
                    }
                    // A cap on bands being brought out or held back.
                    if (running) {
                        val g = r.gainDb[k]
                        if (abs(g) >= 0.3f) {
                            val capColor = if (g > 0) ClarityColors.Recover else ClarityColors.Tame
                            drawRoundRect(
                                capColor,
                                topLeft = Offset(cx - barW / 2, y - 4.dp.toPx()), size = Size(barW, 3.dp.toPx()),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                            )
                        }
                    }
                }
                // Masking threshold.
                if (running) {
                    val mask = Path()
                    for (k in 0 until n) {
                        val x = slot * (k + 0.5f)
                        val y = yOf(r.maskDb[k])
                        if (k == 0) mask.moveTo(x, y) else mask.lineTo(x, y)
                    }
                    drawPath(mask, ClarityColors.Mask.copy(alpha = 0.25f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(
                        mask, ClarityColors.Mask,
                        style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))),
                    )
                }
                // The curve Clarity plays.
                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, gainMid), Offset(size.width, gainMid), strokeWidth = 1.2f)
                for (db in listOf(-GAIN_RANGE_DB / 2, GAIN_RANGE_DB / 2)) {
                    val y = gainMid - db / GAIN_RANGE_DB * gainSpan
                    drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val steps = 96
                val curve = Path()
                val up = Path().apply { moveTo(0f, gainMid) }
                val down = Path().apply { moveTo(0f, gainMid) }
                for (i in 0..steps) {
                    val t = i.toFloat() / steps * (n - 1)
                    val v = if (running) interpolate(r.gainDb, t) else 0f
                    val x = slot * 0.5f + (size.width - slot) * i / steps
                    val y = gainMid - (v / GAIN_RANGE_DB).coerceIn(-1.1f, 1.1f) * gainSpan
                    if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
                    up.lineTo(x, minOf(y, gainMid))
                    down.lineTo(x, maxOf(y, gainMid))
                }
                up.lineTo(size.width - slot * 0.5f, gainMid); up.close()
                down.lineTo(size.width - slot * 0.5f, gainMid); down.close()
                drawPath(up, Brush.verticalGradient(listOf(ClarityColors.Recover.copy(alpha = 0.9f * alpha), ClarityColors.Recover.copy(alpha = 0.25f * alpha)), startY = gainMid - gainSpan * 0.6f, endY = gainMid))
                drawPath(down, Brush.verticalGradient(listOf(ClarityColors.Tame.copy(alpha = 0.25f * alpha), ClarityColors.Tame.copy(alpha = 0.9f * alpha)), startY = gainMid, endY = gainMid + gainSpan * 0.6f))
                drawPath(curve, Color.White.copy(alpha = 0.2f * alpha), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                drawPath(curve, Color.White.copy(alpha = alpha), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
            if (!running) {
                Text(
                    if (eqOn) "Play something and watch Clarity listen" else "Equalizer is off",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        // Frequencies under the bands they name.
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp).padding(top = 4.dp)) {
            listOf(60f to "60", 250f to "250", 1000f to "1K", 4000f to "4K", 14_000f to "14K").forEach { (hz, text) ->
                val pos = ((ClarityBands.erbNumber(hz) - ClarityBands.CENTER_ERB[0]) / ClarityBands.ERB_STEP + 0.5f) / ClarityBands.COUNT
                Text(
                    text,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.offset(x = maxWidth * pos - 8.dp),
                )
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(ClarityColors.Recover, "Brought out")
            Spacer(Modifier.width(12.dp))
            Legend(ClarityColors.Tame, "Held back")
            Spacer(Modifier.weight(1f))
            Text(
                "24 BANDS · ~90×/S",
                fontSize = 9.sp,
                lineHeight = 11.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.5f),
            )
        }
    }
}

/** A gentle hill for the bars to show while nothing plays, 0..1 of the range. */
private fun idleShape(k: Int): Float {
    val x = k / (ClarityBands.COUNT - 1f)
    return 0.28f + 0.22f * (1f - (x - 0.35f) * (x - 0.35f) * 2.2f).coerceAtLeast(0f)
}

@Composable
private fun Legend(color: Color, text: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dashed) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { Box(Modifier.size(width = 4.dp, height = 2.dp).background(color)) }
            }
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
        Text(
            text,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, color: Color, palette: EditorialPalette, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = color)
        Text(
            value,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(sub, fontSize = 10.sp, lineHeight = 12.sp, color = palette.muted, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String, palette: EditorialPalette) {
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 1.6.sp,
        fontWeight = FontWeight.Bold,
        color = palette.muted,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

// ---------------------------------------------------------------------------
// Presets and controls
// ---------------------------------------------------------------------------

@Composable
private fun PresetRow(s: ClaritySettings, onSettings: (ClaritySettings) -> Unit, palette: EditorialPalette) {
    val current = ClarityPreset.matching(s)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClarityPreset.entries.forEach { p ->
            val on = p == current
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier
                    .width(118.dp)
                    .clip(shape)
                    .background(
                        if (on) Brush.linearGradient(listOf(ClarityColors.Clarity, Color(0xFF6A5CFF)))
                        else Brush.linearGradient(listOf(palette.ink.copy(alpha = 0.05f), palette.ink.copy(alpha = 0.05f))),
                    )
                    .border(1.dp, if (on) Color.Transparent else palette.line, shape)
                    .semantics { selected = on }
                    .clickable(role = Role.Button) { onSettings(p.settings.copy(boostDb = s.boostDb)) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag(ClarityTags.preset(p)),
            ) {
                PresetGlyph(p.settings, if (on) Color.White else ClarityColors.Clarity)
                Text(
                    p.label,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color.White else palette.ink,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    p.blurb,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = if (on) Color.White.copy(alpha = 0.8f) else palette.muted,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Two little bars (recover, tame) and a tilt line: a preset at a glance. */
@Composable
private fun PresetGlyph(s: ClaritySettings, color: Color) {
    Canvas(Modifier.size(width = 40.dp, height = 18.dp)) {
        val w = 6.dp.toPx()
        val h = size.height
        drawRoundRect(color.copy(alpha = 0.25f), Offset(0f, 0f), Size(w, h), CornerRadius(w / 2))
        drawRoundRect(color, Offset(0f, h * (1 - s.recoverWeight / ClaritySettings.MAX_WEIGHT)), Size(w, h * s.recoverWeight / ClaritySettings.MAX_WEIGHT), CornerRadius(w / 2))
        drawRoundRect(color.copy(alpha = 0.25f), Offset(w * 1.6f, 0f), Size(w, h), CornerRadius(w / 2))
        drawRoundRect(color.copy(alpha = 0.6f), Offset(w * 1.6f, h * (1 - s.tameWeight / ClaritySettings.MAX_WEIGHT)), Size(w, h * s.tameWeight / ClaritySettings.MAX_WEIGHT), CornerRadius(w / 2))
        val x0 = w * 3.4f
        val mid = h / 2
        val lean = s.brighten * h * 0.4f
        drawLine(color, Offset(x0, mid + lean), Offset(size.width, mid - lean), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun Control(
    title: String,
    hint: String,
    value: String,
    color: Color,
    palette: EditorialPalette,
    ends: Pair<String, String>? = null,
    slider: @Composable () -> Unit,
) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = palette.ink)
                Text(hint, fontSize = 11.sp, lineHeight = 14.sp, color = palette.muted)
            }
            Text(
                value,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                color = lerp(color, palette.ink, 0.25f),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        slider()
        if (ends != null) {
            Row(Modifier.fillMaxWidth()) {
                Text(ends.first, fontSize = 10.sp, lineHeight = 12.sp, color = palette.muted, modifier = Modifier.weight(1f))
                Text(ends.second, fontSize = 10.sp, lineHeight = 12.sp, color = palette.muted)
            }
        }
    }
}

/**
 * A glowing slider: the track fills from its start, or from its centre when
 * [bipolar], in the colour of the side it leans to. Tap to jump, drag to
 * move, double-tap to go back to [default]; screen readers and tests set it
 * through its progress semantics.
 */
@Composable
private fun ClaritySlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    step: Float,
    bipolar: Boolean,
    color: Color,
    colorUp: Color = color,
    onChange: (Float) -> Unit,
    palette: EditorialPalette,
    tag: String,
) {
    val change by rememberUpdatedState(onChange)
    val perUnit = (1f / step).roundToInt()
    fun snap(v: Float) = ((v * perUnit).roundToInt() / perUnit.toFloat()).coerceIn(range.start, range.endInclusive)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .testTag(tag)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                stateDescription = "$value"
                setProgress { v -> change(snap(v)); true }
            }
            .pointerInput(range, step, default) {
                horizontalSliderGestures(range, default) { change(snap(it)) }
            },
    ) {
        val pad = THUMB_RADIUS.toPx()
        val usable = size.width - 2 * pad
        val cy = size.height / 2
        fun xOf(v: Float) = pad + usable * (v - range.start) / (range.endInclusive - range.start)
        val trackH = 6.dp.toPx()
        drawRoundRect(
            palette.line.copy(alpha = 0.8f),
            topLeft = Offset(pad, cy - trackH / 2), size = Size(usable, trackH),
            cornerRadius = CornerRadius(trackH / 2),
        )
        val origin = if (bipolar) xOf((range.start + range.endInclusive) / 2) else xOf(range.start)
        val x = xOf(value)
        val fill = if (x >= origin) colorUp else color
        if (abs(x - origin) > 0.5f) {
            drawRoundRect(
                Brush.horizontalGradient(
                    if (x >= origin) listOf(fill.copy(alpha = 0.55f), fill) else listOf(fill, fill.copy(alpha = 0.55f)),
                    startX = minOf(x, origin), endX = maxOf(x, origin),
                ),
                topLeft = Offset(minOf(x, origin), cy - trackH / 2), size = Size(abs(x - origin), trackH),
                cornerRadius = CornerRadius(trackH / 2),
            )
        }
        if (bipolar) {
            drawLine(palette.muted.copy(alpha = 0.6f), Offset(origin, cy - 7.dp.toPx()), Offset(origin, cy + 7.dp.toPx()), strokeWidth = 1.5f)
        }
        drawCircle(fill.copy(alpha = 0.25f), radius = pad + 4.dp.toPx(), center = Offset(x, cy))
        drawCircle(Color.White, radius = pad, center = Offset(x, cy))
        drawCircle(fill, radius = pad, center = Offset(x, cy), style = Stroke(3.dp.toPx()))
    }
}

private val THUMB_RADIUS = 10.dp

private suspend fun PointerInputScope.horizontalSliderGestures(
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    onChange: (Float) -> Unit,
) {
    var lastTapUp: Long? = null
    fun valueAt(x: Float): Float {
        val pad = THUMB_RADIUS.toPx()
        val t = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
        return range.start + t * (range.endInclusive - range.start)
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
        if (drag != null) {
            lastTapUp = null
            onChange(valueAt(drag.position.x))
            horizontalDrag(drag.id) { c ->
                c.consume()
                onChange(valueAt(c.position.x))
            }
            return@awaitEachGesture
        }
        val up = currentEvent.changes.firstOrNull { it.id == down.id }
        if (up == null || up.pressed || up.isConsumed) return@awaitEachGesture // taken by the page scroll
        up.consume()
        val again = lastTapUp?.let { up.uptimeMillis - it < viewConfiguration.doubleTapTimeoutMillis } == true
        if (again) {
            lastTapUp = null
            onChange(default)
        } else {
            lastTapUp = up.uptimeMillis
            onChange(valueAt(down.position.x))
        }
    }
}

// ---------------------------------------------------------------------------
// Words
// ---------------------------------------------------------------------------

/** "Bringing out 2.4 kHz · holding back 180 Hz": the band Clarity moves most each way. */
internal fun describeClarity(r: ClarityReadout): String {
    val g = r.gainDb
    if (g.isEmpty()) return "Listening."
    val up = g.indices.maxBy { g[it] }
    val down = g.indices.minBy { g[it] }
    val parts = buildList {
        if (g[up] >= 0.5f) add("Bringing out ${ClarityBands.label(up)}")
        if (g[down] <= -0.5f) add("${if (isEmpty()) "Holding" else "holding"} back ${ClarityBands.label(down)}")
    }
    return if (parts.isEmpty()) "Everything is coming through clearly: nothing to change right now." else parts.joinToString(" · ") + "."
}

private fun bandsText(n: Int) = when (n) {
    0 -> "no bands"
    1 -> "1 band"
    else -> "$n bands"
}

/** "Even", "Recover 40%", "Tame 25%". */
private fun leaning(v: Float, even: String, below: String, above: String): String {
    val pct = (abs(v) * 100).roundToInt()
    return when {
        pct == 0 -> even
        v < 0 -> "$below $pct%"
        else -> "$above $pct%"
    }
}

private fun fmtDb(v: Float): String {
    val r = (v * 10).roundToInt() / 10f
    return when {
        r > 0 -> "+$r dB"
        r < 0 -> "−${-r} dB"
        else -> "0 dB"
    }
}

private const val RANGE_DB = 54f
private const val GAIN_RANGE_DB = 6f
