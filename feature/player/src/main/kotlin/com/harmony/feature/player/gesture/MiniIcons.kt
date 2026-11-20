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
import androidx.compose.ui.graphics.drawscope.scale
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
