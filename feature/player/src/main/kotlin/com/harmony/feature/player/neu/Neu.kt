package com.harmony.feature.player.neu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.harmony.feature.player.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Now Playing from Figma (file Bj9Z7C1JD7kaXGK8h5Tw8M): frames 12:8 / 85:639
 * light and 12:18 / 85:949 dark. Flat surfaces on a warm ground, a hairline
 * edge, lavender for "pressed" and "on", and the prototype's own transitions
 * (Smart Animate with Figma's easing presets).
 */

/** Figma's "Ease out" preset. */
internal val FigmaEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** Figma's "Ease in and out" preset. */
internal val FigmaEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

@Immutable
internal class NeuPalette(
    val dark: Boolean,
    /** The page. */
    val ground: Color,
    /** Buttons, the time card, the mix button and the tools. */
    val surface: Color,
    /** Their 1 dp edge, the header rule, the waveform's baseline. */
    val line: Color,
    /** Pressed, and repeat or shuffle on. */
    val pressed: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val waveRest: Color,
    /** Repeat's "on" dot. */
    val repeatDot: Color,
    /** The lossless key and the details card (a shade apart from [surface] in dark). */
    val pill: Color,
    val pillLine: Color,
    /** Play / pause: Paused and Playing fills, and the glyph on them. */
    val playPaused: Color,
    val playPlaying: Color,
    val playIcon: Color,
    /** The sculpted ring around play / pause (light only). */
    val surround: Color?,
    val record: Color,
    val grooveOdd: Color,
    val grooveEven: Color,
    /** The record's drop shadow: (5.4, 6.3), blur 12.6. */
    val recordShadow: Color,
    /** Behind the audio details card. */
    val scrim: Color,
)

private fun hex(v: Long, a: Float = 1f) = Color(v).copy(alpha = a)

internal val NeuLight = NeuPalette(
    dark = false,
    ground = hex(0xFFF2F2EE),
    surface = hex(0xFFFAFAF7),
    line = hex(0xFFD4D4CD),
    pressed = hex(0xFFE8E5F7),
    ink = hex(0xFF191A1D),
    muted = hex(0xFF57575E),
    accent = hex(0xFF5956C2),
    waveRest = hex(0xFFCBCAD6),
    repeatDot = hex(0xFF26775B),
    pill = hex(0xFFFAFAF7),
    pillLine = hex(0xFFD4D4CD),
    playPaused = hex(0xFF191A1D),
    playPlaying = hex(0xFF37364D),
    playIcon = hex(0xFFF9F8FF),
    surround = hex(0xFFD4D4CD),
    record = hex(0xFF15191A),
    grooveOdd = hex(0xFF343C3E),
    grooveEven = hex(0xFF252B2D),
    recordShadow = hex(0xFFB8C4CA, 0.45f),
    scrim = hex(0xFF15141B, 0.16f),
)

internal val NeuDark = NeuPalette(
    dark = true,
    ground = hex(0xFF18181C),
    surface = hex(0xFF212126),
    line = hex(0xFF393942),
    pressed = hex(0xFF353249),
    ink = hex(0xFFF4F3F7),
    muted = hex(0xFFB4B1BE),
    accent = hex(0xFFA19DEA),
    waveRest = hex(0xFF464550),
    repeatDot = hex(0xFF91D8BA),
    pill = hex(0xFF23232A),
    pillLine = hex(0xFF41414B),
    playPaused = hex(0xFFA19DEA),
    playPlaying = hex(0xFFB2AEF4),
    playIcon = hex(0xFF1B1928),
    surround = null,
    record = hex(0xFF222229),
    grooveOdd = hex(0xFF42424C),
    grooveEven = hex(0xFF34343D),
    recordShadow = hex(0xFF111416, 0.45f),
    scrim = hex(0xFF090910, 0.44f),
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

/** IBM Plex Mono Medium: the small caps labels, the format key, the details card. */
internal val PlexMono = FontFamily(Font(R.font.ibm_plex_mono_medium, FontWeight.Medium))

/** Bitcount Grid Single SemiBold (CRSV 0, ELSH 0, ELXP 0): the elapsed time. */
internal val Bitcount = FontFamily(Font(R.font.bitcount_grid_single_semibold, FontWeight.SemiBold))

/** CSS blur (Figma's number) to Android's blur radius: sigma = blur / 2, Skia's sigma = 0.57735 r + 0.5. */
internal fun blurRadiusPx(blurDp: Float, density: Float): Float {
    val sigma = blurDp / 2f * density
    return ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
}

/**
 * The prototype's timings for a button that springs back after a tap:
 * Pressed in [pressMs] ([pressEasing]), held [holdMs], back in [releaseMs].
 */
@Immutable
internal data class PressTiming(
    val pressMs: Int,
    val holdMs: Int,
    val releaseMs: Int,
    val pressEasing: Easing = FigmaEaseOut,
    val releaseEasing: Easing = FigmaEaseOut,
) {
    companion object {
        /** Back, top back, more, the other keys: Pressed in 90 ms, after 90 ms back in 180 ms. */
        val Standard = PressTiming(90, 90, 180)
        /** Lossless: Pressed in 90 ms, after 55 ms back in 160 ms as the details dissolve in. */
        val Lossless = PressTiming(90, 55, 160)
        /**
         * Previous and next when they change the song: the "Apăsat" frames
         * come in over 140 ms (ease in and out), and 14 ms later the record
         * frame takes over in 85 ms (ease out), which lets the key up.
         */
        val Skip = PressTiming(140, 14, 85, pressEasing = FigmaEaseInOut)
    }
}

/**
 * 0 at rest, 1 pressed. A press sinks the key in [PressTiming.pressMs];
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
                        p.animateTo(1f, if (still) snap() else tween(timing.pressMs, easing = timing.pressEasing))
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val pressing = pressJob
                    releaseJob = launch {
                        pressing?.join()
                        if (!still) delay(timing.holdMs.toLong())
                        p.animateTo(0f, if (still) snap() else tween(timing.releaseMs, easing = timing.releaseEasing))
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
    control = surface,
    controlEdge = line,
    primaryControl = playPaused,
    onPrimaryControl = playIcon,
    accent = accent,
    live = accent,
    track = waveRest,
    vinyl = record,
    vinylGroove = grooveOdd,
    artShadow = Color.Black,
)
