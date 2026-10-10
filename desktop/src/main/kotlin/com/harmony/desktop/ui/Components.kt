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
import androidx.compose.runtime.State
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
import kotlin.math.exp
import kotlin.math.sin

/**
 * The song's position, read on the frame clock: about 30 times a second while
 * it plays, four times a second while paused (to catch seeks). Read it where
 * it's drawn ([PositionText], [WaveProgress]) so only those redraw.
 */
@Composable
fun rememberPosition(playing: Boolean, read: () -> Long): State<Long> {
    val position = remember { mutableLongStateOf(read()) }
    val reader by rememberUpdatedState(read)
    LaunchedEffect(playing) {
        position.longValue = reader()
        val every = if (playing) 33L else 250L
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                if (now - last >= every) {
                    last = now
                    position.longValue = reader()
                }
            }
        }
    }
    return position
}

/** "2:41", following [position]. */
@Composable
fun PositionText(position: State<Long>, fontSize: androidx.compose.ui.unit.TextUnit, color: Color, modifier: Modifier = Modifier) {
    Text(formatTime(position.value), fontSize = fontSize, color = color, modifier = modifier)
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
