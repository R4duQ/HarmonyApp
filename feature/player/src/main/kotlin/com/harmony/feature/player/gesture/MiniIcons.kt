package com.harmony.feature.player.gesture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The design's own vectors, path data copied from the Figma SVG exports
 * (stroke widths, caps and joins as they are there), drawn at their size.
 */

internal object MiniPaths {
    /** Figma "Replay arrow", 24 x 24, stroke 1.8. */
    const val REPLAY =
        "M4 10C4.66918 8.34682 5.87159 6.96465 7.41622 6.07307C8.96086 5.18148 10.7591 4.83161 12.5254 5.07904" +
            "C14.2916 5.32646 15.9245 6.15699 17.1647 7.43868C18.4049 8.72038 19.1812 10.3797 19.3704 12.1532" +
            "C19.5596 13.9266 19.1507 15.7124 18.2088 17.2268C17.2668 18.7413 15.8458 19.8975 14.1715 20.512" +
            "C12.4972 21.1264 10.6656 21.1637 8.96768 20.618C7.26974 20.0723 5.80284 18.9748 4.8 17.5M10 10H4V4"

    /** Figma "Skip forward", 24 x 24: the triangle (filled and stroked, 1.8) and the bar. */
    const val SKIP_TRIANGLE = "M7 5L16 12L7 19V5Z"
    const val SKIP_BAR = "M19 5V19"

    /** Figma "Pause", 16 x 16, stroke 3. */
    const val PAUSE = "M6 4V12M10 4V12"

    /** Figma "Preparing next song" (frame 06), in its 24 x 24 box: the orbit's arcs and tail, offsets as placed there. */
    const val LEADING_ORBIT =
        "M2.73559 0C4.29653 -6.82309e-08 5.8396 0.332214 7.26225 0.974564C8.68489 1.61691 9.95454 2.55469 10.9868 3.72557" +
            "C12.0191 4.89645 12.7903 6.27362 13.2493 7.76556C13.7083 9.25749 13.8445 10.83 13.6489 12.3787" +
            "C13.4532 13.9273 12.9302 15.4166 12.1146 16.7475C11.299 18.0784 10.2095 19.2205 8.91851 20.0979" +
            "C7.62748 20.9753 6.16451 21.5678 4.62681 21.8362C3.08911 22.1046 1.5119 22.0426 0 21.6544L0.382982 20.1628" +
            "C1.68322 20.4966 3.03962 20.5499 4.36204 20.3191C5.68446 20.0883 6.94262 19.5787 8.0529 18.8242" +
            "C9.16318 18.0696 10.1002 17.0874 10.8016 15.9428C11.503 14.7982 11.9527 13.5175 12.121 12.1857" +
            "C12.2892 10.8538 12.1721 9.50144 11.7774 8.21838C11.3827 6.93531 10.7194 5.75095 9.83164 4.74399" +
            "C8.94389 3.73703 7.85199 2.93055 6.62851 2.37812C5.40504 1.8257 4.078 1.54 2.73559 1.54L2.73559 0Z"
    const val TRAILING_ORBIT =
        "M3.46999 12.7022C1.77662 11.112 0.630131 9.02659 0.19485 6.74477C-0.240432 4.46294 0.0578386 2.10189 1.04691 0" +
            "L2.44035 0.6557C1.58974 2.46332 1.33323 4.49383 1.70757 6.4562C2.08191 8.41857 3.06789 10.2121 4.52419 11.5796" +
            "L3.46999 12.7022Z"
    const val ORBIT_TAIL = "M0 1.56243C0.933841 0.883958 1.96865 0.356695 3.06645 0L3.54233 1.46463C2.59823 1.77138 1.70829 2.22483 0.905189 2.80832L0 1.56243Z"

    /** Play, for the paused state, which the design doesn't draw: the pause glyph's weight and box. */
    const val PLAY = "M5.5 3.8L12 8L5.5 12.2Z"
}

private fun path(data: String): Path = PathParser().parsePathString(data).toPath()

@Composable
internal fun ReplayGlyph(stroke: Color, modifier: Modifier = Modifier) {
    val p = remember { path(MiniPaths.REPLAY) }
    Glyph(24.dp, modifier) {
        drawPath(p, stroke, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
internal fun SkipGlyph(fill: Color, stroke: Color, modifier: Modifier = Modifier) {
    val triangle = remember { path(MiniPaths.SKIP_TRIANGLE) }
    val bar = remember { path(MiniPaths.SKIP_BAR) }
    Glyph(24.dp, modifier) {
        drawPath(triangle, fill, style = Fill)
        val line = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(triangle, stroke, style = line)
        drawPath(bar, stroke, style = line)
    }
}

@Composable
internal fun PauseGlyph(playing: Boolean, modifier: Modifier = Modifier) {
    val pause = remember { path(MiniPaths.PAUSE) }
    val play = remember { path(MiniPaths.PLAY) }
    Glyph(16.dp, modifier) {
        if (playing) {
            drawPath(pause, MiniColors.Title, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawPath(play, MiniColors.Title, style = Fill)
            drawPath(play, MiniColors.Title, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Draws in the glyph's own units (its viewBox in dp). */
@Composable
private fun Glyph(size: Dp, modifier: Modifier, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Canvas(modifier.size(size)) {
        val unit = this.size.width / size.value
        scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) { draw() }
    }
}

/**
 * The skip button preparing the next song (frame 06), drawn over the 48 dp
 * button: the pressure ring (r 18.5, 1 dp at 20%), and the orbit (leading arc,
 * trailing arc at 32%, tail at 14%) turning around its 4 dp core.
 */
@Composable
internal fun PreparingGlyph(pose: PreparePose, modifier: Modifier = Modifier) {
    val leading = remember { path(MiniPaths.LEADING_ORBIT) }
    val trailing = remember { path(MiniPaths.TRAILING_ORBIT) }
    val tail = remember { path(MiniPaths.ORBIT_TAIL) }
    Glyph(48.dp, modifier) {
        val c = androidx.compose.ui.geometry.Offset(24f, 24f)
        if (pose.ringAlpha > 0f) {
            scale(pose.ringScale, pose.ringScale, pivot = c) {
                drawCircle(MiniColors.Title.copy(alpha = 0.2f * pose.ringAlpha), radius = 18.5f, center = c, style = Stroke(1f))
            }
        }
        if (pose.spinAlpha > 0f) {
            val ink = MiniColors.Title
            rotate(pose.spinDegrees, pivot = c) {
                scale(pose.spinScale, pose.spinScale, pivot = c) {
                    // The 24 dp group sits at 12, 12; its 22 dp orbit box at 1, 1 inside it.
                    translate(12f + 1f + 0.3757f * 22f, 12f + 1f) { drawPath(leading, ink.copy(alpha = pose.spinAlpha)) }
                    translate(12f + 1f, 12f + 1f + 0.2871f * 22f) { drawPath(trailing, ink.copy(alpha = 0.32f * pose.spinAlpha)) }
                    translate(12f + 1f + 0.2061f * 22f, 12f + 1f + 0.0245f * 22f) { drawPath(tail, ink.copy(alpha = 0.14f * pose.spinAlpha)) }
                    scale(pose.coreScale, pose.coreScale, pivot = c) {
                        drawCircle(ink.copy(alpha = pose.coreAlpha * pose.spinAlpha), radius = 2f, center = c)
                    }
                }
            }
        }
    }
}
