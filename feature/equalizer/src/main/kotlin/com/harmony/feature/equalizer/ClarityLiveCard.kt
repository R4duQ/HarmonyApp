package com.harmony.feature.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.EqClarity
import com.harmony.core.model.EqSettings
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialSwitch
import com.harmony.domain.playback.ClarityLive
import com.harmony.domain.playback.EqStatus
import kotlin.math.roundToInt

private val Recover = Color(0xFF4FD1C5)
private val Tame = Color(0xFFF2994A)

/**
 * Clarity Live: 24 bands that each set themselves while the song plays. The
 * live view shows, band by band, what the music measures right now (bars),
 * how loud a sound has to be there to be heard over the rest (the line), and
 * what Clarity does to the band (the curve above: up brings it out, down
 * holds it back).
 */
@Composable
internal fun ClarityLiveCard(
    eq: EqSettings,
    status: EqStatus,
    onToggle: () -> Unit,
    onIntensity: (Float) -> Unit,
    onBalance: (Float) -> Unit,
    palette: EditorialPalette,
) {
    val on = eq.enabled && eq.clarity && !status.bitPerfect
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Recover.copy(alpha = if (on) 0.10f else 0.04f))
            .border(1.dp, if (on) Recover.copy(alpha = 0.55f) else palette.line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clarity Live", fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = palette.ink)
                Text(
                    when {
                        status.bitPerfect -> "Off while bit-perfect: the DAC gets the file untouched"
                        on -> "Every band sets itself to what the music needs, live"
                        else -> "Off: 24 bands that adjust themselves while the song plays"
                    },
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                )
            }
            EditorialSwitch(on, { onToggle() }, palette)
        }
        if (on) {
            ClarityLiveView(palette, Modifier.padding(top = 14.dp).fillMaxWidth().height(170.dp))
            LabeledSlider(
                label = "Intensity",
                valueText = "${(eq.clarityIntensity * 100).roundToInt()} %",
                value = eq.clarityIntensity,
                range = 0f..1f,
                onChange = onIntensity,
                palette = palette,
            )
            LabeledSlider(
                label = "Balance",
                valueText = when {
                    eq.clarityBalance < -0.05f -> "Smoother"
                    eq.clarityBalance > 0.05f -> "More detail"
                    else -> "Even"
                },
                value = eq.clarityBalance,
                range = -1f..1f,
                onChange = onBalance,
                palette = palette,
            )
        }
        Text(
            "Clarity Live listens to the music about 190 times a second through a model of hearing, in 24 bands " +
                "spaced the way the ear resolves pitch (40 Hz to 16 kHz). Each band moves on its own: what is " +
                "covered up by louder sounds nearby comes forward (up to 4 dB at the default), what pushes forward " +
                "(a ringing resonance, harshness, boom) is held back, and the loudness stays where it was. Your " +
                "sliders still apply on top; Auto rests while it runs. Nothing is added to the latency.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.muted,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    palette: EditorialPalette,
) {
    // Follows the finger at once; the setting is written when the finger lifts.
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value else dragging
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.ink, modifier = Modifier.weight(1f))
        Text(valueText, fontSize = 12.sp, color = palette.muted)
    }
    Slider(
        value = shown,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            if (!dragging.isNaN()) onChange(dragging)
            dragging = Float.NaN
        },
        valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = Recover,
            activeTrackColor = Recover,
            inactiveTrackColor = palette.line,
        ),
    )
}

/** The live view, drawn every frame from the engine's telemetry into arrays allocated once. */
@Composable
private fun ClarityLiveView(palette: EditorialPalette, modifier: Modifier) {
    val n = EqClarity.BAND_COUNT
    val response = remember { FloatArray(n) }
    val levels = remember { FloatArray(n) }
    val masks = remember { FloatArray(n) }
    var frame by remember { mutableLongStateOf(0L) }
    var outputDb by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                outputDb = ClarityLive.poll(response, levels, masks)
                frame = now
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(palette) { TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = palette.muted) }
    val labels = remember { List(n) { EqClarity.label(it) } }
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(palette.ink.copy(alpha = 0.05f))) {
        frame // redraw on every polled frame
        val labelSpace = 14.dp.toPx()
        val curveHeight = size.height * 0.34f
        val barsTop = curveHeight + 6.dp.toPx()
        val barsBottom = size.height - labelSpace
        val column = size.width / n
        val playing = !outputDb.isNaN()

        // The curve Clarity applies: ±8 dB around the centre line.
        val mid = curveHeight / 2f
        drawLine(palette.line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        for (k in 0 until n) {
            val db = if (playing) response[k].coerceIn(-8f, 8f) else 0f
            val h = db / 8f * (mid - 4.dp.toPx())
            val x = k * column + column * 0.2f
            val w = column * 0.6f
            if (h > 0f) {
                drawRoundRect(Recover, Offset(x, mid - h), Size(w, h), CornerRadius(2f))
            } else if (h < 0f) {
                drawRoundRect(Tame, Offset(x, mid), Size(w, -h), CornerRadius(2f))
            }
        }

        // What the music measures in each band (−72..0 dB), and the masking threshold over it.
        fun yOf(db: Float): Float {
            val t = ((db + 72f) / 72f).coerceIn(0f, 1f)
            return barsBottom - t * (barsBottom - barsTop)
        }
        val mask = Path()
        for (k in 0 until n) {
            val level = if (playing) levels[k] else -200f
            val covered = playing && level < masks[k]
            val top = yOf(level)
            val x = k * column + column * 0.15f
            drawRect(
                (if (covered) palette.muted else palette.accent).copy(alpha = if (covered) 0.35f else 0.8f),
                Offset(x, top),
                Size(column * 0.7f, (barsBottom - top).coerceAtLeast(0f)),
            )
            if (playing) {
                val y = yOf(masks[k])
                val cx = k * column + column / 2f
                if (k == 0) mask.moveTo(cx, y) else mask.lineTo(cx, y)
            }
        }
        if (playing) drawPath(mask, palette.ink.copy(alpha = 0.7f), style = Stroke(width = 1.5.dp.toPx()))

        for (k in 0 until n step 4) {
            drawText(measurer, labels[k], Offset(k * column + 1.dp.toPx(), barsBottom + 2.dp.toPx()), labelStyle)
        }
        val caption = if (playing) "level ${"%+.1f".format(outputDb)} dB" else "waiting for music"
        drawText(measurer, caption, Offset(4.dp.toPx(), 2.dp.toPx()), labelStyle)
    }
}
