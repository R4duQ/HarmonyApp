package com.harmony.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A soft, raised surface: the base colour with a lit edge up-left and a
 * shaded edge down-right, as if pressed out of the page. [pressed] sinks it.
 */
fun Modifier.neumorphic(corner: Dp, elevation: Dp = 10.dp, pressed: Boolean = false, circle: Boolean = false): Modifier = composed {
    val c = LocalHarmonyColors.current
    drawBehind {
        val e = elevation.toPx()
        val r = if (circle) size.minDimension / 2 else corner.toPx()
        if (!pressed) {
            drawIntoCanvas { canvas ->
                val paint = Paint()
                val fp = paint.asFrameworkPaint()
                fp.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, e * 0.6f)
                fp.color = c.neuDark.copy(alpha = if (c.dark) 0.9f else 0.75f).toArgb()
                canvas.drawRoundRect(e * 0.45f, e * 0.45f, size.width + e * 0.45f, size.height + e * 0.45f, r, r, paint)
                fp.color = c.neuLight.copy(alpha = if (c.dark) 0.55f else 1f).toArgb()
                canvas.drawRoundRect(-e * 0.45f, -e * 0.45f, size.width - e * 0.45f, size.height - e * 0.45f, r, r, paint)
            }
            drawRoundRect(c.neu, cornerRadius = CornerRadius(r))
        } else {
            // Sunk in: shaded at the top-left inside, lit at the bottom-right.
            drawRoundRect(c.neu, cornerRadius = CornerRadius(r))
            drawRoundRect(
                Brush.linearGradient(listOf(c.neuDark.copy(alpha = 0.55f), Color.Transparent, c.neuLight.copy(alpha = 0.6f)), Offset.Zero, Offset(size.width, size.height)),
                cornerRadius = CornerRadius(r),
            )
        }
    }
}

/** A round soft button with an icon; [active] tints it with the accent. */
@Composable
fun NeuIconButton(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 52.dp, active: Boolean = false, enabled: Boolean = true) {
    val c = LocalHarmonyColors.current
    Box(
        Modifier
            .size(size)
            .neumorphic(size / 2, elevation = 8.dp, circle = true)
            .then(if (active) Modifier.drawBehind { drawCircle(Accent.Purple, radius = this.size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx())) } else Modifier)
            .clip(CircleShape)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Accent.Purple else c.ink.copy(alpha = if (enabled) 0.8f else 0.3f), modifier = Modifier.size(size * 0.42f))
    }
}

/** A soft pill with an icon and a label (MUTE, EQ). */
@Composable
fun NeuPill(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, modifier: Modifier = Modifier) {
    val c = LocalHarmonyColors.current
    Row(
        modifier
            .neumorphic(26.dp, elevation = 8.dp, pressed = active)
            .clip(RoundedCornerShape(26.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Accent.Purple else c.ink.copy(alpha = 0.8f), modifier = Modifier.size(19.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = if (active) Accent.Purple else c.ink.copy(alpha = 0.85f), modifier = Modifier.padding(start = 10.dp))
    }
}

/** The knob's sweep: from bottom-left round the top to bottom-right. */
private const val KNOB_START_DEG = 135f
private const val KNOB_SWEEP_DEG = 270f

/**
 * A big rotary control, as on a hi-fi: ticks round the outside, a purple arc
 * up to the value, and a dimple on the knob where it points. Drag it round,
 * or scroll on it. [value] is 0..1.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Knob(
    value: Float,
    onChange: (Float) -> Unit,
    icon: ImageVector,
    label: String,
    readout: String,
    modifier: Modifier = Modifier,
    size: Dp = 250.dp,
) {
    val c = LocalHarmonyColors.current
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(value)
    fun valueAt(p: Offset, s: Size): Float {
        val dx = p.x - s.width / 2
        val dy = p.y - s.height / 2
        var deg = (atan2(dy, dx) * 180 / PI).toFloat()
        if (deg < 0) deg += 360f
        var rel = deg - KNOB_START_DEG
        if (rel < 0) rel += 360f
        // The gap at the bottom snaps to the nearer end.
        if (rel > KNOB_SWEEP_DEG) rel = if (rel - KNOB_SWEEP_DEG < (360f - rel)) KNOB_SWEEP_DEG else 0f
        return (rel / KNOB_SWEEP_DEG).coerceIn(0f, 1f)
    }
    Column(modifier.width(size), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { change(valueAt(it, Size(this.size.width.toFloat(), this.size.height.toFloat()))) }) { ch, _ ->
                        ch.consume()
                        change(valueAt(ch.position, Size(this.size.width.toFloat(), this.size.height.toFloat())))
                    }
                }
                .pointerInput(Unit) { detectTapGestures { change(valueAt(it, Size(this.size.width.toFloat(), this.size.height.toFloat()))) } }
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    change((current - dy * 0.02f).coerceIn(0f, 1f))
                }
                .semantics { contentDescription = "$label $readout" },
            contentAlignment = Alignment.Center,
        ) {
            // Ticks and the arc.
            Canvas(Modifier.fillMaxSize()) {
                val outer = this.size.minDimension / 2
                val ticks = 48
                for (k in 0..ticks) {
                    val a = Math.toRadians((KNOB_START_DEG + KNOB_SWEEP_DEG * k / ticks).toDouble())
                    val long = k % 6 == 0
                    val r0 = outer - (if (long) 10.dp.toPx() else 7.dp.toPx())
                    val r1 = outer - 2.dp.toPx()
                    drawLine(
                        c.muted.copy(alpha = if (long) 0.55f else 0.35f),
                        Offset(center.x + r0 * cos(a).toFloat(), center.y + r0 * sin(a).toFloat()),
                        Offset(center.x + r1 * cos(a).toFloat(), center.y + r1 * sin(a).toFloat()),
                        strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
                val arcR = outer - 22.dp.toPx()
                val stroke = 5.dp.toPx()
                drawArc(
                    c.neuDark.copy(alpha = 0.35f), KNOB_START_DEG, KNOB_SWEEP_DEG, false,
                    topLeft = Offset(center.x - arcR, center.y - arcR), size = Size(arcR * 2, arcR * 2), style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (value > 0.001f) {
                    drawArc(
                        Brush.sweepGradient(listOf(Accent.PurpleDeep, Accent.Purple, Accent.PurpleLight, Accent.PurpleDeep), center),
                        KNOB_START_DEG, KNOB_SWEEP_DEG * value, false,
                        topLeft = Offset(center.x - arcR, center.y - arcR), size = Size(arcR * 2, arcR * 2), style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            // The knob itself.
            val knob = size * 0.66f
            Box(
                Modifier.size(knob).neumorphic(knob / 2, elevation = 16.dp, circle = true),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val a = Math.toRadians((KNOB_START_DEG + KNOB_SWEEP_DEG * value).toDouble())
                    val r = this.size.minDimension / 2 - 26.dp.toPx()
                    val p = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
                    // A dimple: shaded on one side, lit on the other.
                    drawCircle(c.neuDark.copy(alpha = 0.45f), radius = 15.dp.toPx(), center = p + Offset(-1.5f, -1.5f))
                    drawCircle(c.neuLight.copy(alpha = 0.9f), radius = 15.dp.toPx(), center = p + Offset(1.5f, 1.5f))
                    drawCircle(c.neu, radius = 14.dp.toPx(), center = p)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = null, tint = c.ink.copy(alpha = 0.85f), modifier = Modifier.size(30.dp))
                    Text(label.uppercase(), fontSize = 11.sp, letterSpacing = 1.4.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                    Text(readout, fontSize = 22.sp, color = c.ink, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        // MIN and MAX under the two ends of the scale.
        Row(Modifier.fillMaxWidth().padding(horizontal = size * 0.12f).offset(y = -size * 0.08f)) {
            Text("MIN", fontSize = 11.sp, letterSpacing = 1.4.sp, color = c.muted)
            Box(Modifier.weight(1f))
            Text("MAX", fontSize = 11.sp, letterSpacing = 1.4.sp, color = c.muted)
        }
    }
}

/** The song's shape as bars: played in purple, the rest grey. Click to seek. */
@Composable
fun WaveformBars(bars: FloatArray?, position: State<Long>, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHarmonyColors.current
    val seek by rememberUpdatedState(onSeek)
    val placeholder = remember { FloatArray(96) { i -> 0.25f + 0.35f * (0.5f + 0.5f * sin(i * 0.55f)) * (0.6f + 0.4f * sin(i * 0.13f)) } }
    val shown = bars ?: placeholder
    Canvas(
        modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(durationMs) { detectTapGestures { o -> if (durationMs > 0) seek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) } }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures { ch, _ ->
                    ch.consume()
                    if (durationMs > 0) seek(((ch.position.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                }
            }
            .semantics { contentDescription = "Song waveform" },
    ) {
        val n = shown.size
        val step = size.width / n
        val w = (step * 0.42f).coerceAtLeast(1.5f)
        val played = if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        for (i in 0 until n) {
            val h = size.height * shown[i].coerceIn(0.08f, 1f)
            val x = i * step + (step - w) / 2
            val done = (i + 0.5f) / n <= played
            drawRoundRect(
                if (done) Accent.Purple else c.muted.copy(alpha = if (bars == null) 0.18f else 0.38f),
                topLeft = Offset(x, (size.height - h) / 2), size = Size(w, h), cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

/** A soft groove with a purple fill and a glowing thumb: the song's progress. */
@Composable
fun NeuSlider(position: State<Long>, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHarmonyColors.current
    val seek by rememberUpdatedState(onSeek)
    var dragging by remember { mutableStateOf<Float?>(null) }
    Canvas(
        modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(durationMs) { detectTapGestures { o -> if (durationMs > 0) seek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) } }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { ch, _ -> ch.consume(); dragging = (ch.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let { if (durationMs > 0) seek((it * durationMs).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null },
                )
            }
            .semantics { contentDescription = "Song position" },
    ) {
        val t = dragging ?: if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val thumb = 11.dp.toPx()
        val track = 7.dp.toPx()
        val left = thumb
        val right = size.width - thumb
        val mid = size.height / 2
        drawRoundRect(c.neuDark.copy(alpha = 0.45f), Offset(left, mid - track / 2), Size(right - left, track), CornerRadius(track / 2))
        drawRoundRect(c.neuLight.copy(alpha = 0.5f), Offset(left, mid), Size(right - left, track / 2), CornerRadius(track / 2))
        val x = left + (right - left) * t
        drawRoundRect(Brush.horizontalGradient(listOf(Accent.PurpleDeep, Accent.Purple), startX = left, endX = x.coerceAtLeast(left + 1f)), Offset(left, mid - track / 2), Size((x - left).coerceAtLeast(0f), track), CornerRadius(track / 2))
        drawCircle(Accent.Purple.copy(alpha = 0.25f), radius = thumb * 1.6f, center = Offset(x, mid))
        drawCircle(c.neuLight, radius = thumb, center = Offset(x, mid))
        drawCircle(Brush.linearGradient(listOf(Accent.PurpleLight, Accent.Purple), Offset(x - thumb, mid - thumb), Offset(x + thumb, mid + thumb)), radius = thumb * 0.78f, center = Offset(x, mid))
    }
}

/** 0..1 as "62%". */
fun percent(v: Float): String = "${(v * 100).roundToInt()}%"
