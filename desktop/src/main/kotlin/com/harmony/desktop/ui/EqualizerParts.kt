package com.harmony.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.WinampEqDesign
import com.harmony.desktop.DesktopApp
import com.harmony.desktop.engine.EqConfig
import com.harmony.desktop.engine.SpectrumTap
import com.harmony.desktop.engine.ToneDesign
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private const val LOW_HZ = 25.0
private const val HIGH_HZ = 20_000.0
/** The analyzer's range: a full-scale tone is 0 dB. */
private const val SPECTRUM_TOP_DB = -6f
private const val SPECTRUM_BOTTOM_DB = -78f
/** The curve's range, up and down. */
private const val CURVE_DB = 20f
private const val CURVE_POINTS = 160

private fun xOf(hz: Double, width: Float): Float = (ln(hz / LOW_HZ) / ln(HIGH_HZ / LOW_HZ)).toFloat() * width

/** What the equalizer as set does to each frequency, in dB, at [CURVE_POINTS] points. */
fun eqCurve(eq: EqConfig): FloatArray {
    if (!eq.enabled) return FloatArray(CURVE_POINTS)
    val hz = FloatArray(CURVE_POINTS) { (LOW_HZ * (HIGH_HZ / LOW_HZ).pow(it / (CURVE_POINTS - 1.0))).toFloat() }
    val winamp = WinampEqDesign.responseDb(eq.winampGainsDb, eq.winampPreampDb, hz, 48_000)
    return FloatArray(CURVE_POINTS) { winamp[it] + ToneDesign.responseDb(eq.bassDb, eq.trebleDb, hz[it].toDouble()) }
}

/**
 * The music's spectrum as it is heard, live: bars after the equalizer, a
 * thin line for how it came in, and the equalizer's curve over both, so
 * every change can be seen as well as heard.
 */
@Composable
fun EqVisualizer(app: DesktopApp, eq: EqConfig, playing: Boolean) {
    val c = LocalHarmonyColors.current
    val bands = SpectrumTap.BANDS
    val after = remember { FloatArray(bands) { SPECTRUM_BOTTOM_DB } }
    val before = remember { FloatArray(bands) { SPECTRUM_BOTTOM_DB } }
    val peaks = remember { FloatArray(bands) { SPECTRUM_BOTTOM_DB } }
    val frame = remember { mutableIntStateOf(0) }
    LaunchedEffect(playing) {
        var last = -1L
        var settled = false
        while (playing || !settled) {
            withFrameMillis { now ->
                val dt = if (last < 0) 0.033f else ((now - last).coerceIn(0, 200)) / 1000f
                if (last < 0 || now - last >= 33) {
                    last = now
                    val f = if (playing) app.engine.spectrum() else null
                    settled = true
                    for (b in 0 until bands) {
                        val a = f?.after?.getOrNull(b) ?: SPECTRUM_BOTTOM_DB
                        val i = f?.before?.getOrNull(b) ?: SPECTRUM_BOTTOM_DB
                        // Up at once, down gently, like a meter.
                        after[b] = if (a > after[b]) a else maxOf(a, after[b] - 48f * dt)
                        before[b] = if (i > before[b]) i else maxOf(i, before[b] - 48f * dt)
                        peaks[b] = if (after[b] > peaks[b]) after[b] else maxOf(after[b], peaks[b] - 14f * dt)
                        if (after[b] > SPECTRUM_BOTTOM_DB + 0.5f || peaks[b] > SPECTRUM_BOTTOM_DB + 0.5f) settled = false
                    }
                    frame.intValue++
                }
            }
        }
    }
    val curve = remember(eq) { eqCurve(eq) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = c.muted)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Accent.PurpleLight, modifier = Modifier.size(20.dp))
            Text("Live", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            Legend(Brush.verticalGradient(listOf(Accent.Purple, Color(0xFF39B9D3))), "The music now")
            Spacer(Modifier.width(16.dp))
            Legend(Brush.linearGradient(listOf(c.ink.copy(alpha = 0.5f), c.ink.copy(alpha = 0.5f))), "Without the equalizer")
            Spacer(Modifier.width(16.dp))
            Legend(Brush.horizontalGradient(listOf(Accent.Orange, Accent.Pink)), "Your curve")
        }
        Canvas(Modifier.fillMaxWidth().height(230.dp).padding(top = 12.dp).testTag("eq_visualizer")) {
            frame.intValue // redraws with each analyzer frame
            val w = size.width
            val labelH = 16.dp.toPx()
            val h = size.height - labelH
            fun specY(db: Float) = h * (1f - ((db - SPECTRUM_BOTTOM_DB) / (SPECTRUM_TOP_DB - SPECTRUM_BOTTOM_DB)).coerceIn(0f, 1f))
            fun curveY(db: Float) = h / 2 - (db / CURVE_DB).coerceIn(-1f, 1f) * (h / 2 - 6.dp.toPx())

            // Grid: octaves across, and the curve's 0 dB line.
            for (hz in listOf(50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0)) {
                val x = xOf(hz, w)
                drawLine(c.line, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                val label = if (hz >= 1_000) "${(hz / 1_000).roundToInt()}k" else "${hz.roundToInt()}"
                val layout = measurer.measure(label, labelStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, h + 3.dp.toPx()))
            }
            drawLine(c.muted.copy(alpha = 0.5f), Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))

            // The music after the equalizer: bars, with peaks.
            val gap = 2f
            for (b in 0 until bands) {
                val x0 = xOf(SpectrumTap.LOW_HZ * (SpectrumTap.HIGH_HZ / SpectrumTap.LOW_HZ).pow(b.toDouble() / bands), w)
                val x1 = xOf(SpectrumTap.LOW_HZ * (SpectrumTap.HIGH_HZ / SpectrumTap.LOW_HZ).pow((b + 1.0) / bands), w)
                val top = specY(after[b])
                if (top < h - 0.5f) {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Accent.Purple, Color(0xFF39B9D3).copy(alpha = 0.75f)), startY = 0f, endY = h),
                        topLeft = Offset(x0 + gap / 2, top), size = Size((x1 - x0 - gap).coerceAtLeast(1f), h - top), cornerRadius = CornerRadius(2f),
                    )
                }
                val py = specY(peaks[b])
                if (py < h - 1f) drawLine(Color.White.copy(alpha = 0.7f), Offset(x0 + gap / 2, py), Offset(x1 - gap / 2, py), strokeWidth = 2f)
            }

            // How it came in.
            val line = Path()
            for (b in 0 until bands) {
                val x = xOf(SpectrumTap.centreHz(b), w)
                val y = specY(before[b])
                if (b == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            drawPath(line, c.ink.copy(alpha = 0.5f), style = Stroke(1.5.dp.toPx()))

            // The curve.
            val path = Path()
            val fill = Path()
            for (i in 0 until CURVE_POINTS) {
                val x = w * i / (CURVE_POINTS - 1f)
                val y = curveY(curve[i])
                if (i == 0) { path.moveTo(x, y); fill.moveTo(x, h / 2); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(w, h / 2)
            fill.close()
            drawPath(fill, Brush.horizontalGradient(listOf(Accent.Orange.copy(alpha = 0.16f), Accent.Pink.copy(alpha = 0.16f))))
            drawPath(path, Brush.horizontalGradient(listOf(Accent.Orange, Accent.Pink)), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            // The curve's scale, on a chip so it reads over the bars.
            for ((text, top) in listOf("+${CURVE_DB.roundToInt()} dB" to true, "-${CURVE_DB.roundToInt()} dB" to false)) {
                val layout = measurer.measure(text, labelStyle.copy(color = Accent.Orange))
                val pad = 4.dp.toPx()
                val x = w - layout.size.width - 3 * pad
                val y = if (top) pad else h - layout.size.height - 3 * pad
                drawRoundRect(c.surface.copy(alpha = 0.85f), Offset(x - pad, y - pad / 2), Size(layout.size.width + 2 * pad, layout.size.height + pad), CornerRadius(pad))
                drawText(layout, topLeft = Offset(x, y))
            }
        }
        if (!playing) {
            Text(
                "Play a song to see the music here, before and after the equalizer.",
                fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Legend(brush: Brush, text: String) {
    val c = LocalHarmonyColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(brush))
        Text(text, fontSize = 11.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Hold to hear the music without any of the sound shaping; let go to hear it with. */
@Composable
fun CompareButton(app: DesktopApp, enabled: Boolean) {
    val c = LocalHarmonyColors.current
    var held by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (held) Accent.Pink.copy(alpha = 0.22f) else c.chip)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (!enabled) return@detectTapGestures
                    held = true
                    app.engine.bypass = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        held = false
                        app.engine.bypass = false
                    }
                })
            }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("eq_compare"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Compare, contentDescription = null, tint = if (held) Accent.Pink else c.ink.copy(alpha = if (enabled) 0.85f else 0.4f), modifier = Modifier.size(16.dp))
        Text(
            if (held) "Without the equalizer" else "Hold to compare",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (held) Accent.Pink else c.ink.copy(alpha = if (enabled) 0.85f else 0.4f),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** Bass, treble, stereo width and even volume. */
@Composable
fun ToneCard(app: DesktopApp, eq: EqConfig) {
    val c = LocalHarmonyColors.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(Accent.Orange, Accent.Pink))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Tone", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.ink)
                Text("Deep bass and clear highs at a turn, how wide the stereo sounds, and songs at an even volume.", fontSize = 12.sp, color = c.muted)
            }
            Text(
                "Reset", fontSize = 13.sp, color = Accent.PurpleLight,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(eq) { detectTapGestures { app.setEq(eq.copy(bassDb = 0f, trebleDb = 0f, width = 1f)) } }
                    .padding(6.dp),
            )
        }
        ToneSlider("Bass", "%+.1f dB".format(eq.bassDb), eq.bassDb, -ToneDesign.MAX_DB..ToneDesign.MAX_DB, listOf(Accent.Purple, Accent.Orange), "tone_bass") {
            app.setEq(eq.copy(enabled = true, bassDb = (it * 2).roundToInt() / 2f))
        }
        ToneSlider("Treble", "%+.1f dB".format(eq.trebleDb), eq.trebleDb, -ToneDesign.MAX_DB..ToneDesign.MAX_DB, listOf(Color(0xFF39B9D3), Color(0xFFFFE066)), "tone_treble") {
            app.setEq(eq.copy(enabled = true, trebleDb = (it * 2).roundToInt() / 2f))
        }
        ToneSlider(
            "Stereo width",
            when {
                eq.width <= 0.005f -> "Mono"
                else -> "${(eq.width * 100).roundToInt()}%"
            },
            eq.width, 0f..2f, listOf(Accent.Pink, Accent.Cyan), "tone_width",
        ) { app.setEq(eq.copy(enabled = true, width = (it * 20).roundToInt() / 20f)) }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Even volume", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                Text("Brings quiet songs up and loud ones down, slowly, so you don't reach for the volume.", fontSize = 12.sp, color = c.muted)
            }
            if (eq.leveling && eq.enabled) LevelingReadout(app)
            Switch(
                eq.leveling, { app.setEq(eq.copy(enabled = eq.enabled || it, leveling = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent.Purple, checkedThumbColor = Color.White),
                modifier = Modifier.padding(start = 12.dp).testTag("tone_leveling"),
            )
        }
    }
}

@Composable
private fun LevelingReadout(app: DesktopApp) {
    val c = LocalHarmonyColors.current
    var db by remember { mutableFloatStateOf(app.engine.levelingDb) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameMillis { now -> if (now - last >= 400) { last = now; db = app.engine.levelingDb } }
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Accent.Cyan))
        Text("now %+.1f dB".format(db), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Accent.Cyan)
    }
}

@Composable
private fun ToneSlider(
    title: String,
    value: String,
    v: Float,
    range: ClosedFloatingPointRange<Float>,
    colors: List<Color>,
    tag: String,
    onChange: (Float) -> Unit,
) {
    val c = LocalHarmonyColors.current
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.width(110.dp))
        HSlider(v, onChange, Modifier.weight(1f).height(28.dp).testTag(tag), range, colors, bipolar = true)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.last(), modifier = Modifier.width(110.dp).padding(start = 14.dp))
    }
}
