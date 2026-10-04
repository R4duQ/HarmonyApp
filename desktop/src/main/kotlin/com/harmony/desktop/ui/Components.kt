package com.harmony.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.desktop.player.Art
import kotlin.math.PI
import kotlin.math.sin

/**
 * The song's position, read on the frame clock: about 30 times a second while
 * it plays, four times a second while paused (to catch seeks).
 */
@Composable
fun rememberPosition(playing: Boolean, read: () -> Long): Long {
    var position by remember { mutableLongStateOf(read()) }
    val reader by rememberUpdatedState(read)
    LaunchedEffect(playing) {
        position = reader()
        val every = if (playing) 33L else 250L
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                if (now - last >= every) {
                    last = now
                    position = reader()
                }
            }
        }
    }
    return position
}

/** The cover as a record that turns while the music plays, coasting to a stop on pause. */
@Composable
fun Disc(art: Art?, loader: ImageLoader, playing: Boolean, size: Dp, modifier: Modifier = Modifier, key: Any? = null) {
    val turn = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                turn.snapTo(turn.value % 360f)
                turn.animateTo(turn.value + 360f, tween(9_000, easing = LinearEasing))
            }
        } else {
            turn.animateTo(turn.value + 24f, tween(700, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(key) {
        turn.animateTo(turn.value + 160f, tween(650, easing = FastOutSlowInEasing))
    }
    val scale by animateFloatAsState(if (playing) 1f else 0.94f, spring(dampingRatio = 0.55f, stiffness = 300f))
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = turn.value }
            .shadow(18.dp, CircleShape, ambientColor = Accent.Purple, spotColor = Accent.Purple)
            .clip(CircleShape)
            .background(Color(0xFF0D0D10))
            .border(2.dp, Brush.sweepGradient(Accent.Rim), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ArtImage(art, loader, CircleShape, Modifier.fillMaxSize().padding(3.dp))
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            drawCircle(
                Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent, Color.White.copy(alpha = 0.06f), Color.Transparent)),
                radius = r * 0.98f,
            )
            drawCircle(Color.Black.copy(alpha = 0.55f), radius = r * 0.08f)
            drawCircle(Color.White.copy(alpha = 0.35f), radius = r * 0.08f, style = Stroke(1.dp.toPx()))
        }
    }
}

/**
 * Progress as a rainbow wave up to where the song is, flat after it. The
 * wave runs along while playing and lies down on pause. Click or drag to seek.
 */
@Composable
fun WaveProgress(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    track: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val phase = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                phase.snapTo(phase.value % (2 * PI.toFloat()))
                phase.animateTo(phase.value + 2 * PI.toFloat(), tween(1_400, easing = LinearEasing))
            }
        }
    }
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(500))
    val shown = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Canvas(
        modifier
            .semantics { contentDescription = "Position ${formatTime(positionMs)} of ${formatTime(durationMs)}" }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(durationMs) {
                detectTapGestures { o -> if (durationMs > 0) seek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ -> change.consume(); dragging = (change.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let { if (durationMs > 0) seek((it * durationMs).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null },
                )
            },
    ) {
        val fraction = dragging ?: shown
        val thumbR = 8.dp.toPx()
        val left = thumbR
        val right = size.width - thumbR
        val mid = size.height / 2
        val x = left + (right - left) * fraction
        val stroke = 4.dp.toPx()
        drawLine(track, Offset(x, mid), Offset(right, mid), strokeWidth = stroke, cap = StrokeCap.Round)
        if (x > left) {
            val wavelength = 18.dp.toPx()
            val amp = 3.dp.toPx() * amplitude
            val wave = Path()
            var px = left
            wave.moveTo(px, mid + amp * sin(-phase.value))
            while (px < x) {
                px = (px + 2f).coerceAtMost(x)
                val ease = ((x - px) / (wavelength * 0.75f)).coerceIn(0f, 1f)
                wave.lineTo(px, mid + amp * ease * sin((px - left) / wavelength * 2 * PI.toFloat() - phase.value))
            }
            drawPath(wave, Brush.horizontalGradient(Accent.Wave, startX = left, endX = maxOf(x, left + wavelength)), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        drawCircle(Accent.Orange.copy(alpha = 0.25f), radius = thumbR * 1.5f, center = Offset(x, mid))
        drawCircle(
            Brush.linearGradient(listOf(Accent.Purple, Accent.Orange), start = Offset(x - thumbR, mid - thumbR), end = Offset(x + thumbR, mid + thumbR)),
            radius = thumbR, center = Offset(x, mid),
        )
    }
}

@Composable
fun RoundButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    active: Boolean = false,
) {
    val c = LocalHarmonyColors.current
    val tint = if (active) Accent.PurpleLight else c.ink.copy(alpha = 0.85f)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(c.chip)
            .then(if (active) Modifier.border(1.5.dp, Accent.Purple.copy(alpha = 0.6f), CircleShape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** The big purple play/pause, breathing a halo while the music plays. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, size: Dp = 52.dp) {
    val halo = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                halo.snapTo(0f)
                halo.animateTo(1f, tween(1_600, easing = LinearEasing))
            }
        } else {
            halo.animateTo(0f, tween(300))
        }
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 8.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = halo.value
            if (t > 0f) drawCircle(Accent.Purple.copy(alpha = 0.35f * (1f - t)), radius = this.size.minDimension / 2 * (0.86f + 0.14f * t))
        }
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent.Purple, Accent.PurpleDeep)))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClickLabel = if (playing) "Pause" else "Play", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = {
                    (scaleIn(initialScale = 0.5f, animationSpec = tween(180)) + fadeIn(tween(180)))
                        .togetherWith(scaleOut(targetScale = 0.5f, animationSpec = tween(120)) + fadeOut(tween(120)))
                },
            ) { p ->
                Icon(
                    if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (p) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        }
    }
}

/** A small rounded label: "FLAC 24/96", "FROM PIXEL 8". */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp).padding(end = 0.dp))
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(start = if (icon != null) 5.dp else 0.dp),
        )
    }
}

/** A horizontal slider in Harmony's colours: [value] in [range]. */
@Composable
fun HSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    colors: List<Color> = listOf(Accent.Purple, Accent.PurpleLight),
    bipolar: Boolean = false,
) {
    val c = LocalHarmonyColors.current
    val change by rememberUpdatedState(onChange)
    var width by remember { mutableFloatStateOf(1f) }
    fun valueAt(x: Float) = range.start + ((x - 8f) / (width - 16f)).coerceIn(0f, 1f) * (range.endInclusive - range.start)
    Canvas(
        modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(range) { detectTapGestures { change(valueAt(it.x)) } }
            .pointerInput(range) {
                detectHorizontalDragGestures { ch, _ -> ch.consume(); change(valueAt(ch.position.x)) }
            },
    ) {
        width = size.width
        val pad = 8f
        val mid = size.height / 2
        val t = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val x = pad + (size.width - 2 * pad) * t
        val origin = if (bipolar) pad + (size.width - 2 * pad) / 2 else pad
        drawLine(c.line, Offset(pad, mid), Offset(size.width - pad, mid), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        if (kotlin.math.abs(x - origin) > 0.5f) {
            drawLine(Brush.horizontalGradient(colors, startX = minOf(x, origin), endX = maxOf(x, origin)), Offset(origin, mid), Offset(x, mid), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x, mid))
        drawCircle(colors.last(), radius = 7.dp.toPx(), center = Offset(x, mid), style = Stroke(2.5.dp.toPx()))
    }
}
