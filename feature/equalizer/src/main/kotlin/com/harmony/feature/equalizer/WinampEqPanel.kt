package com.harmony.feature.equalizer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.EqSettings
import com.harmony.core.model.EqStyle
import com.harmony.core.model.WinampEqDesign
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the Winamp panel can ask for. */
class WinampEqActions(
    val onToggle: () -> Unit = {},
    val onBand: (index: Int, gainDb: Float) -> Unit = { _, _ -> },
    val onPreamp: (gainDb: Float) -> Unit = {},
    val onPreset: (name: String) -> Unit = {},
    val onReset: () -> Unit = {},
)

internal object WinampTags {
    const val ON = "winamp_on"
    const val PRESETS = "winamp_presets"
    const val PREAMP = "winamp_preamp"
    fun band(index: Int) = "winamp_band_$index"
}

/**
 * The Winamp equalizer, laid out the way Winamp 2 drew it: a gunmetal
 * panel, an ON light, PRESETS, a black display with the response curve
 * in Winamp's red-yellow-green, the PREAMP slider and ten band sliders
 * whose grooves light up from green to red as they rise.
 *
 * The curve is the real response of the filters (WinampEqDesign), so when
 * neighbouring bands add up, the display shows it.
 *
 * Stateless: the screen owns the settings and passes them in.
 */
@Composable
fun WinampEqPanel(eq: EqSettings, actions: WinampEqActions, modifier: Modifier = Modifier) {
    val on = eq.enabled && eq.style == EqStyle.WINAMP
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .shadow(16.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Skin.PanelTop, Skin.PanelBottom)))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Skin.BevelLight, Skin.BevelDark))), shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        TitleBar()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnButton(on, actions.onToggle)
            PresetsButton(currentPreset(eq), actions.onPreset)
            Spacer(Modifier.weight(1f))
            SkinButton("RESET", onClick = actions.onReset)
        }
        Spacer(Modifier.height(10.dp))
        ResponseDisplay(
            gains = eq.winampGainsDb,
            preampDb = eq.winampPreampDb,
            lit = on,
            modifier = Modifier.fillMaxWidth().height(74.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(206.dp)) {
            DbScale(Modifier.width(30.dp).fillMaxHeight())
            WinampSlider(
                value = eq.winampPreampDb,
                label = "PREAMP",
                lit = on,
                onChange = actions.onPreamp,
                modifier = Modifier.weight(1.25f).testTag(WinampTags.PREAMP),
            )
            Spacer(Modifier.width(6.dp))
            WinampEqDesign.LABELS.forEachIndexed { i, label ->
                WinampSlider(
                    value = eq.winampGainsDb.getOrElse(i) { 0f },
                    label = label,
                    lit = on,
                    onChange = { actions.onBand(i, it) },
                    modifier = Modifier.weight(1f).testTag(WinampTags.band(i)),
                )
            }
        }
    }
}

/** The name of the Winamp preset these settings match, if any. */
internal fun currentPreset(eq: EqSettings): String? = WinampEqDesign.PRESETS.firstOrNull { (_, preset) ->
    val (preamp, gains) = preset
    abs(preamp - eq.winampPreampDb) < 0.05f &&
        gains.indices.all { abs(gains[it] - eq.winampGainsDb.getOrElse(it) { 0f }) < 0.05f }
}?.first

private object Skin {
    val PanelTop = Color(0xFF3B3B52)
    val PanelBottom = Color(0xFF17171F)
    val BevelLight = Color(0xFF6A6A8A)
    val BevelDark = Color(0xFF07070B)
    val Display = Color(0xFF000000)
    val Grid = Color(0xFF123A1C)
    val Red = Color(0xFFFF3B1F)
    val Yellow = Color(0xFFF2D21B)
    val Green = Color(0xFF2FD12F)
    val Gold = Color(0xFFC9A646)
    val Text = Color(0xFFD8D6CA)
    val Dim = Color(0xFF8E8EA6)
    val Led = Color(0xFF3CFF3C)
    val Groove = Color(0xFF0B0B11)
    val ThumbTop = Color(0xFFDCDCE6)
    val ThumbBottom = Color(0xFF8C8C9E)
}

@Composable
private fun TitleBar() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Stripes(Modifier.weight(1f).height(9.dp))
        Text(
            "WINAMP EQUALIZER",
            fontSize = 11.sp,
            lineHeight = 13.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Skin.Text,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Stripes(Modifier.weight(1f).height(9.dp))
    }
}

/** The ridged gold bars either side of a Winamp title. */
@Composable
private fun Stripes(modifier: Modifier) {
    Canvas(modifier) {
        val step = size.height / 3f
        for (k in 0 until 3) {
            val y = step * k + step / 2f
            drawLine(Skin.Gold.copy(alpha = 0.85f - k * 0.2f), Offset(0f, y), Offset(size.width, y), strokeWidth = step * 0.55f)
        }
    }
}

@Composable
private fun OnButton(on: Boolean, onClick: () -> Unit) {
    SkinButton("ON", onClick = onClick, modifier = Modifier.testTag(WinampTags.ON).semantics {
        contentDescription = if (on) "Winamp equalizer on" else "Winamp equalizer off"
    }, leading = {
        Box(
            Modifier
                .padding(end = 6.dp)
                .size(9.dp)
                .shadow(if (on) 6.dp else 0.dp, CircleShape, ambientColor = Skin.Led, spotColor = Skin.Led)
                .clip(CircleShape)
                .background(if (on) Skin.Led else Color(0xFF1E3A1E)),
        )
    })
}

@Composable
private fun PresetsButton(current: String?, onPreset: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SkinButton(
            current?.uppercase() ?: "PRESETS",
            onClick = { open = true },
            modifier = Modifier.testTag(WinampTags.PRESETS),
            trailing = "▾",
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WinampEqDesign.PRESETS.forEach { (name, _) ->
                DropdownMenuItem(
                    text = { Text(if (name == current) "✓  $name" else name) },
                    onClick = { open = false; onPreset(name) },
                )
            }
        }
    }
}

/** A small bevelled Winamp button: light top edge, dark bottom edge. */
@Composable
private fun SkinButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF4A4A63), Color(0xFF26263A))))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Skin.BevelLight, Skin.BevelDark))), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, color = Skin.Text, maxLines = 1,
        )
        if (trailing != null) {
            Text(trailing, fontSize = 11.sp, lineHeight = 13.sp, color = Skin.Dim, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Winamp's colour for a level from -1 (bottom) to +1 (top): green, yellow, red. */
private fun levelColor(t: Float): Color {
    val u = ((t + 1f) / 2f).coerceIn(0f, 1f)
    return if (u < 0.5f) lerp(Skin.Green, Skin.Yellow, u * 2f) else lerp(Skin.Yellow, Skin.Red, (u - 0.5f) * 2f)
}

/** The black display: the filters' real response from 20 Hz to 20 kHz, plus the preamp line. */
@Composable
private fun ResponseDisplay(gains: List<Float>, preampDb: Float, lit: Boolean, modifier: Modifier) {
    val frequencies = remember { FloatArray(CURVE_POINTS) { i -> 20f * 1000f.pow(i / (CURVE_POINTS - 1f)) } }
    val curve = remember(gains, preampDb) { WinampEqDesign.responseDb(gains, preampDb, frequencies) }
    val shape = RoundedCornerShape(6.dp)
    Canvas(
        modifier
            .clip(shape)
            .background(Skin.Display)
            .border(BorderStroke(1.dp, Skin.BevelDark), shape),
    ) {
        val mid = size.height / 2f
        val span = size.height * 0.44f
        fun yFor(db: Float) = mid - (db / WinampEqDesign.MAX_DB).coerceIn(-1.1f, 1.1f) * span
        // Grid: the 0 dB line and the ten band positions.
        drawLine(Skin.Grid, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.5f)
        for (db in listOf(-10f, 10f)) drawLine(Skin.Grid.copy(alpha = 0.6f), Offset(0f, yFor(db)), Offset(size.width, yFor(db)), strokeWidth = 1f)
        WinampEqDesign.FREQUENCIES_HZ.forEach { f ->
            val x = size.width * (kotlin.math.log10(f / 20f) / 3f)
            drawLine(Skin.Grid.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        // Preamp level.
        drawLine(Skin.Yellow.copy(alpha = if (lit) 0.45f else 0.2f), Offset(0f, yFor(preampDb)), Offset(size.width, yFor(preampDb)), strokeWidth = 1.5f)
        // The curve, coloured by height like Winamp's.
        val path = Path()
        curve.forEachIndexed { i, db ->
            val x = size.width * i / (CURVE_POINTS - 1f)
            if (i == 0) path.moveTo(x, yFor(db)) else path.lineTo(x, yFor(db))
        }
        val brush = Brush.verticalGradient(
            listOf(Skin.Red, Skin.Yellow, Skin.Green),
            startY = mid - span, endY = mid + span,
        )
        drawPath(path, brush, alpha = if (lit) 1f else 0.35f, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun DbScale(modifier: Modifier) {
    // Lined up with the slider travel: value text + gap + half a thumb above, label + gap + half a thumb below.
    Column(modifier.padding(top = 15.5.dp, bottom = 16.5.dp), verticalArrangement = Arrangement.SpaceBetween) {
        listOf("+20", "0", "-20").forEach {
            Text(it, fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace, color = Skin.Dim)
        }
    }
}

/**
 * One Winamp slider, -20..+20 dB. Drag, or tap a spot to jump there;
 * double-tap puts it back to 0. The groove lights up green to red with
 * the setting, as Winamp's did.
 */
@Composable
private fun WinampSlider(
    value: Float,
    label: String,
    lit: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier,
) {
    val max = WinampEqDesign.MAX_DB
    // The gesture handlers outlive recompositions; read the latest callback through this.
    val change by rememberUpdatedState(onChange)
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            fmtWinamp(value), fontSize = 8.sp, lineHeight = 10.sp, fontFamily = FontFamily.Monospace,
            color = if (value == 0f) Skin.Dim else Skin.Text, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics {
                    contentDescription = "$label, ${fmtWinamp(value)} decibels"
                    progressBarRangeInfo = ProgressBarRangeInfo(value, -max..max)
                    setProgress { target -> change(target.coerceIn(-max, max)); true }
                }
                .pointerInput(Unit) { sliderGestures { change(it) } },
        ) {
            val pad = THUMB_HALF.toPx()
            val usable = size.height - 2 * pad
            val cx = size.width / 2f
            val grooveW = (size.width * 0.32f).coerceIn(5.dp.toPx(), 9.dp.toPx())
            val t = value / max
            // Groove, tinted by the setting.
            val tint = if (lit) levelColor(t) else Skin.Dim
            drawRoundRect(
                Skin.Groove,
                topLeft = Offset(cx - grooveW / 2, pad - 2), size = Size(grooveW, usable + 4),
                cornerRadius = CornerRadius(grooveW / 2),
            )
            drawRoundRect(
                Brush.verticalGradient(listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.55f))),
                topLeft = Offset(cx - grooveW / 2 + 1.5f, pad), size = Size(grooveW - 3f, usable),
                cornerRadius = CornerRadius(grooveW / 2),
                alpha = if (lit) 0.55f + 0.45f * abs(t) else 0.35f,
            )
            // Centre mark.
            drawLine(Skin.Dim.copy(alpha = 0.6f), Offset(cx - grooveW, pad + usable / 2), Offset(cx + grooveW, pad + usable / 2), strokeWidth = 1.5f)
            // Thumb: a bevelled metal block.
            val thumbW = (size.width * 0.72f).coerceAtMost(24.dp.toPx())
            val thumbH = 2 * pad * 0.8f
            val y = pad + usable * (1f - (t + 1f) / 2f)
            drawRoundRect(
                Brush.verticalGradient(listOf(Skin.ThumbTop, Skin.ThumbBottom), startY = y - thumbH / 2, endY = y + thumbH / 2),
                topLeft = Offset(cx - thumbW / 2, y - thumbH / 2), size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            drawLine(Color.Black.copy(alpha = 0.45f), Offset(cx - thumbW / 2 + 3, y), Offset(cx + thumbW / 2 - 3, y), strokeWidth = 1.5f)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label, fontSize = if (label.length > 4) 7.sp else 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, color = Skin.Text, maxLines = 1,
        )
    }
}

/**
 * Tap to jump, drag to slide, double-tap for 0, in one gesture loop. A tap
 * lands on release, so a finger that starts on a slider to scroll the page
 * doesn't move it; a drag takes over once it passes the touch slop.
 */
private suspend fun PointerInputScope.sliderGestures(onChange: (Float) -> Unit) {
    var lastTapUp = 0L
    var lastTapY = 0f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pad = THUMB_HALF.toPx()
        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
        if (drag != null) {
            lastTapUp = 0L
            onChange(valueAt(drag.position.y, size.height.toFloat(), pad))
            verticalDrag(drag.id) { c ->
                c.consume()
                onChange(valueAt(c.position.y, size.height.toFloat(), pad))
            }
            return@awaitEachGesture
        }
        val up = currentEvent.changes.firstOrNull { it.id == down.id }
        if (up == null || up.pressed || up.isConsumed) return@awaitEachGesture // taken by the page scroll
        up.consume()
        val again = up.uptimeMillis - lastTapUp < viewConfiguration.doubleTapTimeoutMillis
        if (again && abs(down.position.y - lastTapY) < DOUBLE_TAP_REACH.toPx()) {
            lastTapUp = 0L
            onChange(0f)
        } else {
            lastTapUp = up.uptimeMillis
            lastTapY = down.position.y
            onChange(valueAt(down.position.y, size.height.toFloat(), pad))
        }
    }
}

/** Slider value for a touch at [y], to the nearest 0.4 dB (Winamp's own step was about 0.6). */
private fun valueAt(y: Float, height: Float, pad: Float): Float {
    val usable = (height - 2 * pad).coerceAtLeast(1f)
    val frac = 1f - ((y - pad) / usable).coerceIn(0f, 1f)
    val db = frac * 2 * WinampEqDesign.MAX_DB - WinampEqDesign.MAX_DB
    return ((db / 0.4f).roundToInt() * 0.4f).let { if (abs(it) < 0.2f) 0f else it }
        .coerceIn(-WinampEqDesign.MAX_DB, WinampEqDesign.MAX_DB)
}

private fun fmtWinamp(v: Float): String = when {
    abs(v) < 0.05f -> "0"
    v > 0 -> "+" + ((v * 10).roundToInt() / 10f)
    else -> ((v * 10).roundToInt() / 10f).toString()
}

private val THUMB_HALF = 7.dp

/** How close a second tap has to land to count as a double-tap rather than a quick move. */
private val DOUBLE_TAP_REACH = 32.dp
private const val CURVE_POINTS = 120
