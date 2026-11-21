package com.harmony.feature.player.neu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.harmony.feature.player.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The neumorphic Now Playing from Figma (file Bj9Z7C1JD7kaXGK8h5Tw8M, frames
 * 12:8 light and 12:18 dark): its colours, shadows, type and the prototype's
 * own transitions (Smart Animate, Figma's "Ease out" = cubic-bezier(0, 0, .58, 1)).
 */

/** Figma's "Ease out" preset. */
internal val FigmaEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** A Figma drop or inner shadow: offset, blur (CSS px, as Figma shows it) and colour, in dp. */
@Immutable
internal data class NeuShadow(val dx: Float, val dy: Float, val blur: Float, val color: Color)

/** A vertical gradient as Figma stores it: stops top to bottom. */
@Immutable
internal data class NeuGradient(val stops: List<Pair<Float, Color>>) {
    fun brush(height: Float): Brush = Brush.verticalGradient(*stops.toTypedArray(), startY = 0f, endY = height)
}

@Immutable
internal class NeuPalette(
    val dark: Boolean,
    val ground: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val waveRest: Color,
    /** 40 and 54 dp buttons, the lossless pill, the mix button. */
    val raised: List<NeuShadow>,
    /** The 42 dp header buttons and favourite. */
    val raisedLarge: List<NeuShadow>,
    /** The listening tools. */
    val raisedTool: List<NeuShadow>,
    val raisedSleeve: List<NeuShadow>,
    /** Pressed, On and Playing: the surface sinks. */
    val pressed: List<NeuShadow>,
    /** The waveform's channel. */
    val channel: List<NeuShadow>,
    val playFill: NeuGradient,
    val playStroke: NeuGradient,
    val playIcon: Color,
    val surroundStroke: NeuGradient,
    val surroundInset: List<NeuShadow>,
    val record: Color,
    val grooveOdd: Color,
    val grooveEven: Color,
    val recordLabel: Color,
    val recordShadow: NeuShadow,
    val card: List<NeuShadow>,
)

private fun hex(v: Long, a: Float = 1f) = Color(v).copy(alpha = a)

internal val NeuLight = NeuPalette(
    dark = false,
    ground = hex(0xFFE8ECEE),
    ink = hex(0xFF25383B),
    muted = hex(0xFF5C6D72),
    accent = hex(0xFF26775B),
    waveRest = hex(0xFFB6C5CA),
    raised = listOf(NeuShadow(5f, 5f, 10f, hex(0xFFB8C4CA, 0.7f)), NeuShadow(-4f, -4f, 10f, hex(0xFFFFFFFF, 0.96f))),
    raisedLarge = listOf(NeuShadow(6f, 6f, 12f, hex(0xFFB8C4CA, 0.7f)), NeuShadow(-5f, -5f, 12f, hex(0xFFFFFFFF, 0.96f))),
    raisedTool = listOf(NeuShadow(3f, 3f, 7f, hex(0xFFB8C4CA, 0.55f)), NeuShadow(-3f, -3f, 7f, hex(0xFFFFFFFF, 0.9f))),
    raisedSleeve = listOf(NeuShadow(5.4f, 5.4f, 10.8f, hex(0xFFB8C4CA, 0.7f)), NeuShadow(-4.5f, -4.5f, 10.8f, hex(0xFFFFFFFF, 0.96f))),
    pressed = listOf(NeuShadow(2f, 2f, 4f, hex(0xFFB8C4CA, 0.65f)), NeuShadow(-2f, -2f, 4f, hex(0xFFFFFFFF, 0.9f))),
    channel = listOf(NeuShadow(3f, 3f, 6f, hex(0xFFB8C4CA, 0.65f)), NeuShadow(-3f, -3f, 6f, hex(0xFFFFFFFF, 0.9f))),
    playFill = NeuGradient(listOf(0f to hex(0xFFB6E6D3), 0.48f to hex(0xFF9DDBC2), 1f to hex(0xFF85C9AF))),
    playStroke = NeuGradient(listOf(0f to hex(0xFFEFFFF6, 0.88f), 0.4f to hex(0xFFCCF1E0, 0.28f), 1f to hex(0xFF609E87, 0.34f))),
    playIcon = hex(0xFF194F3C),
    surroundStroke = NeuGradient(listOf(0f to hex(0xFFFFFFFF, 0.72f), 0.5f to hex(0xFFCAD4D8, 0.1f), 1f to hex(0xFFBDCBCE, 0.42f))),
    surroundInset = listOf(NeuShadow(3f, 3f, 6f, hex(0xFF9EAFB5, 0.46f)), NeuShadow(-3f, -3f, 6f, hex(0xFFFFFFFF, 0.94f))),
    record = hex(0xFF15191A),
    grooveOdd = hex(0xFF343C3E),
    grooveEven = hex(0xFF252B2D),
    recordLabel = hex(0xFF6E9B89),
    recordShadow = NeuShadow(5.4f, 6.3f, 12.6f, hex(0xFFB8C4CA, 0.45f)),
    card = listOf(NeuShadow(0f, 10f, 24f, hex(0xFFB8C4CA, 0.7f)), NeuShadow(-2f, -2f, 7f, hex(0xFFFFFFFF, 0.8f))),
)

/*
 * Dark is frame 12:18. Its play button and surround are the light frame's
 * newer ones (the gradient, the gradient rim and the sculpted surround), in
 * dark's colours: the same steps around dark's #91D8BA as light's around
 * #9BD8BF, and the rim and surround lit with dark's own highlight and shade.
 */
internal val NeuDark = NeuPalette(
    dark = true,
    ground = hex(0xFF25292B),
    ink = hex(0xFFEDF4F1),
    muted = hex(0xFFA6B4B5),
    accent = hex(0xFF91D8BA),
    waveRest = hex(0xFF414B4E),
    raised = listOf(NeuShadow(5f, 5f, 10f, hex(0xFF111416, 0.65f)), NeuShadow(-4f, -4f, 10f, hex(0xFF41484A, 0.45f))),
    raisedLarge = listOf(NeuShadow(6f, 6f, 12f, hex(0xFF111416, 0.65f)), NeuShadow(-5f, -5f, 12f, hex(0xFF41484A, 0.45f))),
    raisedTool = listOf(NeuShadow(3f, 3f, 7f, hex(0xFF111416, 0.5f)), NeuShadow(-3f, -3f, 7f, hex(0xFF41484A, 0.3f))),
    raisedSleeve = listOf(NeuShadow(5.4f, 5.4f, 10.8f, hex(0xFF111416, 0.65f)), NeuShadow(-4.5f, -4.5f, 10.8f, hex(0xFF41484A, 0.45f))),
    pressed = listOf(NeuShadow(2f, 2f, 4f, hex(0xFF111416, 0.8f)), NeuShadow(-2f, -2f, 4f, hex(0xFF41484A, 0.4f))),
    channel = listOf(NeuShadow(3f, 3f, 6f, hex(0xFF111416, 0.8f)), NeuShadow(-3f, -3f, 6f, hex(0xFF41484A, 0.4f))),
    playFill = NeuGradient(listOf(0f to hex(0xFFACE6CE), 0.48f to hex(0xFF93DBBD), 1f to hex(0xFF7BC9AA))),
    playStroke = NeuGradient(listOf(0f to hex(0xFFC8F3E1, 0.7f), 0.4f to hex(0xFFA6E3CA, 0.22f), 1f to hex(0xFF3E6E5D, 0.4f))),
    playIcon = hex(0xFF154733),
    surroundStroke = NeuGradient(listOf(0f to hex(0xFF41484A, 0.72f), 0.5f to hex(0xFF2E3335, 0.1f), 1f to hex(0xFF111416, 0.42f))),
    surroundInset = listOf(NeuShadow(3f, 3f, 6f, hex(0xFF111416, 0.8f)), NeuShadow(-3f, -3f, 6f, hex(0xFF41484A, 0.4f))),
    record = hex(0xFF15191A),
    grooveOdd = hex(0xFF343C3E),
    grooveEven = hex(0xFF252B2D),
    recordLabel = hex(0xFF4B7061),
    recordShadow = NeuShadow(5.4f, 6.3f, 12.6f, hex(0xFF111416, 0.45f)),
    card = listOf(NeuShadow(0f, 10f, 24f, hex(0xFF111416, 0.85f)), NeuShadow(-2f, -2f, 7f, hex(0xFF41484A, 0.22f))),
)

/** The palette for the app's theme. */
@Composable
internal fun neuPalette(): NeuPalette = if (com.harmony.core.ui.theme.Room.isDark) NeuDark else NeuLight

/** Inter, as the frames set it. */
internal val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** CSS blur (Figma's number) to Android's blur radius: sigma = blur / 2, Skia's sigma = 0.57735 r + 0.5. */
internal fun blurRadiusPx(blurDp: Float, density: Float): Float {
    val sigma = blurDp / 2f * density
    return ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
}

private fun shadowPaint(color: Color, alpha: Float, blurDp: Float, density: Float) = android.graphics.Paint().apply {
    isAntiAlias = true
    this.color = color.copy(alpha = color.alpha * alpha).toArgb()
    maskFilter = android.graphics.BlurMaskFilter(blurRadiusPx(blurDp, density), android.graphics.BlurMaskFilter.Blur.NORMAL)
}

/**
 * A Figma rounded frame: drop shadows outside, the fill, inner shadows
 * clipped inside, and an inside stroke. [dropAlpha] and [innerAlpha] carry
 * Smart Animate between a raised and a pressed state.
 */
internal fun Modifier.neuSurface(
    radius: Dp,
    fill: Brush,
    drop: List<NeuShadow> = emptyList(),
    dropAlpha: Float = 1f,
    inner: List<NeuShadow> = emptyList(),
    innerAlpha: Float = 1f,
    stroke: NeuGradient? = null,
    strokeWidth: Dp = 0.dp,
): Modifier = drawWithCache {
    val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
    val outline = android.graphics.Path().apply {
        addRoundRect(0f, 0f, size.width, size.height, r, r, android.graphics.Path.Direction.CW)
    }
    // The outside of the shape, for inner shadows: a frame around it, wide enough for any blur.
    val pad = 64.dp.toPx()
    val ring = android.graphics.Path().apply {
        fillType = android.graphics.Path.FillType.EVEN_ODD
        addRect(-pad, -pad, size.width + pad, size.height + pad, android.graphics.Path.Direction.CW)
        addRoundRect(0f, 0f, size.width, size.height, r, r, android.graphics.Path.Direction.CW)
    }
    val dropPaints = drop.map { shadowPaint(it.color, dropAlpha, it.blur, density) }
    val innerPaints = inner.map { shadowPaint(it.color, innerAlpha, it.blur, density) }
    val sw = strokeWidth.toPx()
    val strokeBrush = stroke?.brush(size.height)
    onDrawBehind {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            if (dropAlpha > 0.001f) drop.forEachIndexed { i, s ->
                nc.save()
                nc.translate(s.dx * density, s.dy * density)
                nc.drawPath(outline, dropPaints[i])
                nc.restore()
            }
        }
        drawRoundRect(fill, cornerRadius = CornerRadius(r))
        if (innerAlpha > 0.001f && inner.isNotEmpty()) drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.clipPath(outline)
            inner.forEachIndexed { i, s ->
                nc.save()
                nc.translate(s.dx * density, s.dy * density)
                nc.drawPath(ring, innerPaints[i])
                nc.restore()
            }
            nc.restore()
        }
        if (strokeBrush != null && sw > 0f) {
            drawRoundRect(
                strokeBrush,
                topLeft = Offset(sw / 2f, sw / 2f),
                size = Size(size.width - sw, size.height - sw),
                cornerRadius = CornerRadius((r - sw / 2f).coerceAtLeast(0f)),
                style = Stroke(sw),
            )
        }
    }
}

/** The prototype's timings for a button that springs back after a tap. */
@Immutable
internal data class PressTiming(val pressMs: Int, val holdMs: Int, val releaseMs: Int) {
    companion object {
        /** Back, skip, top back, more: Pressed in 90 ms, after 90 ms back in 180 ms. */
        val Standard = PressTiming(90, 90, 180)
        /** Lossless: Pressed in 90 ms, after 70 ms back in 160 ms (the details open as it rises). */
        val Lossless = PressTiming(90, 70, 160)
    }
}

/**
 * 0 at rest, 1 pressed. A press sinks the button in [PressTiming.pressMs];
 * once it is down and the finger is up, it holds [PressTiming.holdMs] and
 * rises in [PressTiming.releaseMs], so a quick tap plays the prototype's
 * whole Resting → Pressed → Resting sequence.
 */
@Composable
internal fun pressProgress(source: MutableInteractionSource, timing: PressTiming): Float {
    val p = remember { Animatable(0f) }
    val still = com.harmony.core.ui.theme.reducedMotion
    LaunchedEffect(source, timing, still) {
        var pressJob: Job? = null
        var releaseJob: Job? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    releaseJob?.cancel()
                    pressJob = launch {
                        p.animateTo(1f, if (still) snap() else tween(timing.pressMs, easing = FigmaEaseOut))
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val pressing = pressJob
                    releaseJob = launch {
                        pressing?.join()
                        if (!still) delay(timing.holdMs.toLong())
                        p.animateTo(0f, if (still) snap() else tween(timing.releaseMs, easing = FigmaEaseOut))
                    }
                }
            }
        }
    }
    return p.value
}

/** Off ↔ On and Paused ↔ Playing: 180 ms, Figma's ease out. */
@Composable
internal fun stateProgress(on: Boolean, label: String): Float {
    val still = com.harmony.core.ui.theme.reducedMotion
    val p by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = if (still) snap() else tween(180, easing = FigmaEaseOut),
        label = label,
    )
    return p
}

/** The same colours as the [com.harmony.feature.player.PlayerPalette] the shared pieces (mix line, sheets) draw with. */
internal fun NeuPalette.asPlayerPalette() = com.harmony.feature.player.PlayerPalette(
    background = ground,
    ink = ink,
    muted = muted,
    control = ground,
    controlEdge = muted.copy(alpha = 0.16f),
    primaryControl = playFill.stops[1].second,
    onPrimaryControl = playIcon,
    accent = accent,
    live = accent,
    track = waveRest,
    vinyl = record,
    vinylGroove = grooveOdd,
    artShadow = Color.Black,
)
