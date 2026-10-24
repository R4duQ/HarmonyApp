package com.harmony.feature.equalizer

import android.view.Choreographer
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.harmony.core.model.EqSettings
import com.harmony.domain.playback.AudioLevels
import com.harmony.domain.playback.EqOutput
import com.harmony.domain.playback.EqStatus
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The live equalizer: one Canvas, redrawn at 60 fps from the native engine's
 * telemetry, with Winamp's buttons above it.
 *
 * Each display frame (Choreographer.FrameCallback) polls the engine once
 * (AudioLevels.pollTelemetry: the effective band gains, the 32-bar FFT and
 * the live preamp) into arrays allocated once, then bumps [LiveDspTelemetry.frameTick].
 * Only DrawScope reads that tick, so the 60 fps loop redraws without ever
 * recomposing. The Canvas draws, back to front:
 *
 *  1. the 32-bar FFT spectrum with falling peak-hold dots;
 *  2. a smooth dashed curve through the listener's sliders and a glowing
 *     neon curve through what the engine actually plays (sliders plus Auto's
 *     live offsets, tamers included);
 *  3. ten sliders, each with the listener's thumb and a glowing "ghost" thumb
 *     at the live gain, joined by a delta bar (amber where Auto lifts, cyan
 *     where it cuts) that moves on its own as the engine calibrates the song.
 *
 * Drag a column to set its slider, tap to jump, double-tap to centre.
 */
@Composable
fun WinampLiveEqualizerScreen(
    eq: EqSettings,
    status: EqStatus,
    onToggleEq: () -> Unit,
    onToggleAuto: () -> Unit,
    onBand: (index: Int, gainDb: Float) -> Unit,
    onFlat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val telemetry = rememberLiveDspTelemetry()
    val on = eq.enabled && !status.bitPerfect
    val auto = on && eq.auto
    Column(modifier.skinWindow()) {
        // Top bar: EQ ON, AUTO LIVE, the live preamp, FLAT.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LedButton("EQ ON", on, onToggleEq, if (on) "Equalizer on" else "Equalizer off")
            LedButton("AUTO LIVE", auto, onToggleAuto, if (auto) "Auto live on" else "Auto live off")
            PreampReadout(telemetry, Modifier.weight(1f).height(26.dp))
            SkinButton("FLAT", onClick = onFlat)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            hardwareBadge(status),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Skin.Lcd,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(Skin.Display)
                .border(1.dp, Skin.BevelDark, RoundedCornerShape(5.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        LiveCanvas(
            userGains = eq.bandGainsDb,
            on = on,
            auto = auto,
            telemetry = telemetry,
            onBand = onBand,
            modifier = Modifier.fillMaxWidth().height(300.dp),
        )
    }
}

/** "USB DAC • 96kHz / 32-bit Float". */
internal fun hardwareBadge(status: EqStatus): String {
    val device = when (status.output) {
        EqOutput.PHONE_SPEAKER -> "SPEAKER"
        EqOutput.WIRED_HEADPHONES -> "WIRED"
        EqOutput.BLUETOOTH -> "BLUETOOTH"
        EqOutput.USB_DAC -> "USB DAC"
    }
    val rate = status.sampleRate
    val khz = when {
        rate <= 0 -> "--"
        rate % 1000 == 0 -> "${rate / 1000}kHz"
        else -> "${(rate / 100) / 10f}kHz"
    }
    val path = if (status.bitPerfect) "Bit-perfect" else "32-bit Float"
    return "$device • $khz / $path"
}

/** What the engine reported this frame; written by the frame callback, read by DrawScope. */
@Stable
class LiveDspTelemetry {
    val effectiveGains = FloatArray(EqSettings.BAND_COUNT)
    val bars = FloatArray(AudioLevels.SPECTRUM_BARS)
    val peaks = FloatArray(AudioLevels.SPECTRUM_BARS)
    var preampDb = 0f
        internal set

    /** Whether an engine answered this frame. */
    var live = false
        internal set

    /** Bumped every frame. Read it only inside DrawScope: the frame then redraws without recomposing. */
    val frameTick: MutableLongState = mutableLongStateOf(0L)

    internal fun poll(frameNanos: Long) {
        val preamp = AudioLevels.pollTelemetry(effectiveGains, bars, peaks)
        live = !preamp.isNaN()
        if (live) {
            preampDb = preamp
        } else {
            bars.fill(0f)
            peaks.fill(0f)
        }
        frameTick.longValue = frameNanos
    }
}

/**
 * Polls the engine once per display frame while the screen is started:
 * a Choreographer.FrameCallback that re-posts itself, stopped on ON_STOP.
 */
@Composable
fun rememberLiveDspTelemetry(): LiveDspTelemetry {
    val telemetry = remember { LiveDspTelemetry() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, telemetry) {
        val choreographer = Choreographer.getInstance()
        var running = false
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                telemetry.poll(frameTimeNanos)
                choreographer.postFrameCallback(this)
            }
        }
        fun start() {
            if (running) return
            running = true
            AudioLevels.startWatching()
            choreographer.postFrameCallback(callback)
        }
        fun stop() {
            if (!running) return
            running = false
            choreographer.removeFrameCallback(callback)
            AudioLevels.stopWatching()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    return telemetry
}

/** The live preamp, drawn (not composed) so it follows the engine at 60 fps. */
@Composable
private fun PreampReadout(telemetry: LiveDspTelemetry, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = remember {
        TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Skin.Lcd)
    }
    Canvas(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Skin.Display)
            .border(1.dp, Skin.BevelDark, RoundedCornerShape(5.dp))
            .semantics { contentDescription = "Live preamp" },
    ) {
        telemetry.frameTick.longValue // redraw every frame
        val text = if (telemetry.live) "PREAMP ${fmtDb(telemetry.preampDb)}" else "PREAMP --"
        val layout = measurer.measure(text, style)
        drawText(layout, topLeft = Offset(8.dp.toPx(), (size.height - layout.size.height) / 2f))
    }
}

// ---------------------------------------------------------------------------
// The live canvas
// ---------------------------------------------------------------------------

@Composable
private fun LiveCanvas(
    userGains: List<Float>,
    on: Boolean,
    auto: Boolean,
    telemetry: LiveDspTelemetry,
    onBand: (Int, Float) -> Unit,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Skin.Text)
    }
    val scaleStyle = remember { TextStyle(fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = Skin.GridText) }
    val change by rememberUpdatedState(onBand)
    val user = remember(userGains) { FloatArray(EqSettings.BAND_COUNT) { userGains.getOrElse(it) { 0f } } }
    val shape = RoundedCornerShape(10.dp)
    Canvas(
        modifier
            .clip(shape)
            .background(Skin.Display)
            .border(BorderStroke(1.dp, Skin.BevelDark), shape)
            .semantics { contentDescription = "Live equalizer: ten sliders over the spectrum" }
            .pointerInput(Unit) { sliderGestures { band, db -> change(band, db) } },
    ) {
        telemetry.frameTick.longValue // the only state read: a new frame redraws, nothing recomposes
        val geometry = Geometry(size.width, size.height, density)
        drawGrid(geometry, measurer, scaleStyle)
        drawSpectrum(geometry, telemetry)
        // What plays: the engine's live gains when it answers, else the sliders as set.
        val live = FloatArray(EqSettings.BAND_COUNT) { i ->
            when {
                telemetry.live -> telemetry.effectiveGains[i]
                on -> user[i]
                else -> 0f
            }
        }
        drawCurve(geometry, user, color = Skin.Text.copy(alpha = if (on) 0.75f else 0.3f), dashed = true, glow = false)
        drawCurve(geometry, live, color = if (auto) Neon.Cyan else Neon.Amber, dashed = false, glow = on)
        for (i in 0 until EqSettings.BAND_COUNT) drawSlider(geometry, i, user[i], live[i], on)
        for (i in 0 until EqSettings.BAND_COUNT) {
            val layout = measurer.measure(EqSettings.BAND_LABELS[i], labelStyle)
            drawText(
                layout,
                topLeft = Offset(geometry.columnX(i) - layout.size.width / 2f, size.height - geometry.labelHeight + 2f),
            )
        }
    }
}

private object Neon {
    val Cyan = Color(0xFF00E5FF)
    val Amber = Color(0xFFFFB300)
}

/** Where things go on the canvas. */
private class Geometry(val width: Float, val height: Float, density: Float) {
    val left = 26f * density
    val right = 6f * density
    val labelHeight = 16f * density
    val top = 10f * density
    val bottom = height - labelHeight - 8f * density
    val columnWidth = (width - left - right) / EqSettings.BAND_COUNT
    val thumbHalf = 6f * density

    fun columnX(i: Int) = left + (i + 0.5f) * columnWidth
    fun yFor(db: Float) = top + (1f - (db.coerceIn(-MAX_DB, MAX_DB) + MAX_DB) / (2 * MAX_DB)) * (bottom - top)
    fun dbAt(y: Float) = ((1f - ((y - top) / (bottom - top)).coerceIn(0f, 1f)) * 2 * MAX_DB - MAX_DB)
    fun columnAt(x: Float) = ((x - left) / columnWidth).toInt().coerceIn(0, EqSettings.BAND_COUNT - 1)
}

private fun DrawScope.drawGrid(g: Geometry, measurer: TextMeasurer, style: TextStyle) {
    for (db in listOf(12f, 6f, 0f, -6f, -12f)) {
        val y = g.yFor(db)
        drawLine(
            if (db == 0f) Skin.Grid else Skin.Grid.copy(alpha = 0.55f),
            Offset(g.left, y), Offset(g.width - g.right, y),
            strokeWidth = if (db == 0f) 1.5f else 1f,
        )
        val label = measurer.measure(if (db > 0) "+${db.toInt()}" else "${db.toInt()}", style)
        drawText(label, topLeft = Offset(2f, y - label.size.height / 2f))
    }
}

/** The 32-bar spectrum in the background, green to red by height, with peak-hold dots. */
private fun DrawScope.drawSpectrum(g: Geometry, t: LiveDspTelemetry) {
    val bars = t.bars.size
    val gap = 1.5f * density
    val w = (g.width - g.left - g.right - gap * (bars - 1)) / bars
    val span = g.bottom - g.top
    val brush = Brush.verticalGradient(
        listOf(Skin.Red.copy(alpha = 0.55f), Skin.Yellow.copy(alpha = 0.45f), Skin.Green.copy(alpha = 0.40f)),
        startY = g.top, endY = g.bottom,
    )
    for (i in 0 until bars) {
        val x = g.left + i * (w + gap)
        val h = t.bars[i].coerceIn(0f, 1f) * span
        if (h > 0.5f) drawRect(brush, Offset(x, g.bottom - h), Size(w, h))
        val peak = t.peaks[i].coerceIn(0f, 1f)
        if (peak > 0.01f) {
            drawRect(Color(0xFFD8D8E8).copy(alpha = 0.8f), Offset(x, g.bottom - peak * span - 2f * density), Size(w, 1.5f * density))
        }
    }
}

/**
 * A smooth curve through the ten band points: Catmull-Rom turned into cubic
 * Béziers, so it passes through every point without overshooting corners.
 */
private fun DrawScope.drawCurve(g: Geometry, gains: FloatArray, color: Color, dashed: Boolean, glow: Boolean) {
    val xs = FloatArray(gains.size) { g.columnX(it) }
    val ys = FloatArray(gains.size) { g.yFor(gains[it]) }
    val path = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 0 until gains.size - 1) {
            val i0 = (i - 1).coerceAtLeast(0)
            val i3 = (i + 2).coerceAtMost(gains.size - 1)
            cubicTo(
                xs[i] + (xs[i + 1] - xs[i0]) / 6f, ys[i] + (ys[i + 1] - ys[i0]) / 6f,
                xs[i + 1] - (xs[i3] - xs[i]) / 6f, ys[i + 1] - (ys[i3] - ys[i]) / 6f,
                xs[i + 1], ys[i + 1],
            )
        }
    }
    if (glow) {
        // Neon: wide soft strokes under a bright core, cyan into amber.
        drawPath(path, Neon.Amber.copy(alpha = 0.10f), style = Stroke(14f * density, cap = StrokeCap.Round))
        drawPath(path, color.copy(alpha = 0.22f), style = Stroke(8f * density, cap = StrokeCap.Round))
    }
    drawPath(
        path,
        color,
        style = Stroke(
            width = (if (dashed) 1.5f else 2.6f) * density,
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f * density, 5f * density)) else null,
        ),
    )
}

/** One slider: groove, the delta bar, the listener's thumb and the glowing ghost thumb at the live gain. */
private fun DrawScope.drawSlider(g: Geometry, i: Int, user: Float, live: Float, on: Boolean) {
    val cx = g.columnX(i)
    val groove = (g.columnWidth * 0.16f).coerceIn(3f * density, 6f * density)
    drawRoundRect(
        Skin.Groove.copy(alpha = 0.85f),
        topLeft = Offset(cx - groove / 2, g.top - 2f),
        size = Size(groove, g.bottom - g.top + 4f),
        cornerRadius = CornerRadius(groove / 2),
    )
    val userY = g.yFor(user)
    val liveY = g.yFor(live)
    val delta = live - user
    // Delta bar: amber where the engine lifts above the slider, cyan where it cuts below.
    if (on && abs(delta) > 0.05f) {
        val color = if (delta > 0) Neon.Amber else Neon.Cyan
        drawLine(color.copy(alpha = 0.35f), Offset(cx, userY), Offset(cx, liveY), strokeWidth = groove * 2.2f, cap = StrokeCap.Round)
        drawLine(color, Offset(cx, userY), Offset(cx, liveY), strokeWidth = groove * 0.8f, cap = StrokeCap.Round)
    }
    val thumbW = (g.columnWidth * 0.62f).coerceAtMost(24f * density)
    // Ghost thumb: where the engine actually is, glowing.
    if (on) {
        val ghost = if (delta >= 0f) Neon.Amber else Neon.Cyan
        val c = if (abs(delta) > 0.05f) ghost else Neon.Cyan
        drawRoundRect(
            c.copy(alpha = 0.25f),
            topLeft = Offset(cx - thumbW / 2 - 4f, liveY - g.thumbHalf - 4f),
            size = Size(thumbW + 8f, g.thumbHalf * 2 + 8f),
            cornerRadius = CornerRadius(6f * density),
        )
        drawRoundRect(
            c.copy(alpha = 0.9f),
            topLeft = Offset(cx - thumbW / 2, liveY - 1.5f * density),
            size = Size(thumbW, 3f * density),
            cornerRadius = CornerRadius(1.5f * density),
        )
    }
    // The listener's thumb: bevelled metal.
    drawRoundRect(
        Brush.verticalGradient(listOf(Skin.ThumbTop, Skin.ThumbBottom), startY = userY - g.thumbHalf, endY = userY + g.thumbHalf),
        topLeft = Offset(cx - thumbW / 2, userY - g.thumbHalf),
        size = Size(thumbW, g.thumbHalf * 2),
        cornerRadius = CornerRadius(3f * density),
        alpha = if (on) 1f else 0.45f,
    )
    drawLine(Color.Black.copy(alpha = 0.45f), Offset(cx - thumbW / 2 + 3f, userY), Offset(cx + thumbW / 2 - 3f, userY), strokeWidth = 1.5f)
}

/**
 * Tap a column to jump its slider, drag to slide, double-tap for 0. A tap
 * lands on release, so a finger that starts here to scroll the page doesn't
 * move anything; a drag takes over once it passes the touch slop.
 */
private suspend fun PointerInputScope.sliderGestures(onChange: (band: Int, db: Float) -> Unit) {
    var lastTapUp: Long? = null
    var lastTap = Offset.Zero
    awaitEachGesture {
        val g = Geometry(size.width.toFloat(), size.height.toFloat(), density)
        val down = awaitFirstDown(requireUnconsumed = false)
        val band = g.columnAt(down.position.x)
        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
        if (drag != null) {
            lastTapUp = null
            onChange(band, snap(g.dbAt(drag.position.y)))
            verticalDrag(drag.id) { c ->
                c.consume()
                onChange(band, snap(g.dbAt(c.position.y)))
            }
            return@awaitEachGesture
        }
        val up = currentEvent.changes.firstOrNull { it.id == down.id }
        if (up == null || up.pressed || up.isConsumed) return@awaitEachGesture // taken by the page scroll
        up.consume()
        val again = lastTapUp?.let { up.uptimeMillis - it < viewConfiguration.doubleTapTimeoutMillis } == true
        if (again && (down.position - lastTap).getDistance() < 32.dp.toPx()) {
            lastTapUp = null
            onChange(band, 0f)
        } else {
            lastTapUp = up.uptimeMillis
            lastTap = down.position
            onChange(band, snap(g.dbAt(down.position.y)))
        }
    }
}

/** To the nearest 0.5 dB. */
private fun snap(db: Float): Float =
    ((db / 0.5f).roundToInt() * 0.5f).let { if (abs(it) < 0.25f) 0f else it }.coerceIn(-MAX_DB, MAX_DB)

private fun fmtDb(v: Float): String = when {
    abs(v) < 0.05f -> "0.0 dB"
    v > 0 -> "+${"%.1f".format(v)} dB"
    else -> "−${"%.1f".format(-v)} dB"
}

private const val MAX_DB = EqSettings.MAX_GAIN_DB

// ---------------------------------------------------------------------------
// Winamp skin
// ---------------------------------------------------------------------------

internal object Skin {
    val PanelTop = Color(0xFF3E3E58)
    val PanelMid = Color(0xFF2A2A3D)
    val PanelBottom = Color(0xFF16161F)
    val BevelLight = Color(0xFF7474A0)
    val BevelDark = Color(0xFF07070B)
    val Display = Color(0xFF000000)
    val Grid = Color(0xFF123A1C)
    val GridText = Color(0xFF2E8C46)
    val Lcd = Color(0xFF00E05A)
    val Red = Color(0xFFFF3B1F)
    val Yellow = Color(0xFFF2D21B)
    val Green = Color(0xFF2FD12F)
    val Gold = Color(0xFFE2B451)
    val Text = Color(0xFFE6E4D8)
    val Dim = Color(0xFF8E8EA6)
    val Led = Color(0xFF3CFF3C)
    val LedOff = Color(0xFF1C2E1C)
    val Groove = Color(0xFF0B0B11)
    val ThumbTop = Color(0xFFEDEDF5)
    val ThumbBottom = Color(0xFF8C8C9E)
}

/** A Winamp window: brushed gunmetal with a bevelled rim. */
private fun Modifier.skinWindow(): Modifier {
    val shape = RoundedCornerShape(18.dp)
    return this
        .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Skin.PanelTop, Skin.PanelMid, Skin.PanelBottom)))
        .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Skin.BevelLight, Skin.BevelDark))), shape)
        .padding(12.dp)
}

/** A bevelled Winamp button with a little light in it. */
@Composable
private fun LedButton(text: String, lit: Boolean, onClick: () -> Unit, description: String) {
    SkinButton(text, onClick = onClick, modifier = Modifier.semantics { contentDescription = description }, leading = {
        Box(
            Modifier
                .padding(end = 6.dp)
                .size(9.dp)
                .shadow(if (lit) 6.dp else 0.dp, CircleShape, ambientColor = Skin.Led, spotColor = Skin.Led)
                .clip(CircleShape)
                .background(if (lit) Skin.Led else Skin.LedOff),
        )
    })
}

/** A small bevelled Winamp button: light top edge, dark bottom edge. */
@Composable
private fun SkinButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF55557A), Color(0xFF26263A))))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Skin.BevelLight, Skin.BevelDark))), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(
            text, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, color = Skin.Text, maxLines = 1,
        )
    }
}
