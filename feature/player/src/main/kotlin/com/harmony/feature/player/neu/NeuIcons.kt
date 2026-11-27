package com.harmony.feature.player.neu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The frames' own vectors (Figma SVG exports), in their viewBox units. */
internal enum class NeuGlyph(
    val box: Float,
    val stroke: String?,
    val strokeWidth: Float,
    val fill: String? = null,
    val roundJoin: Boolean = true,
) {
    Back(20f, "M15.8333 10H4.16663M9.16663 15L4.16663 10L9.16663 5", 1.8f),
    More(20f, "M10 4.16666H10.0083M10 9.99999H10.0083M10 15.8333H10.0083", 1.8f),
    Heart(
        20f,
        "M10 16.6667C10 16.6667 1.66667 11.6667 1.66667 5.83333C1.55616 4.72827 1.88917 3.62456 2.59243 2.76502" +
            "C3.29569 1.90548 4.3116 1.36051 5.41667 1.25C6.52174 1.13949 7.62544 1.4725 8.48499 2.17576" +
            "C9.34453 2.87902 9.8895 3.89493 10 5C10.1105 3.89493 10.6555 2.87902 11.515 2.17576" +
            "C11.9406 1.82754 12.4306 1.56656 12.9571 1.40772C13.4836 1.24888 14.0362 1.19528 14.5833 1.25" +
            "C15.1305 1.30472 15.6615 1.46667 16.1461 1.72662C16.6307 1.98657 17.0594 2.33941 17.4076 2.76502" +
            "C17.7558 3.19062 18.0168 3.68064 18.1756 4.2071C18.3345 4.73357 18.3881 5.28616 18.3333 5.83333" +
            "C18.3333 11.6667 10 16.6667 10 16.6667Z",
        1.8f,
    ),
    Lossless(14f, "M2 5V9M5 2V12M8 4V10M11 6V8", 1.5f, roundJoin = false),
    Chevron(10f, "M2 4L5 7L8 4", 1.3f),
    Repeat(18f, "M3.75 5.25H14.25M12 7.5L14.25 5.25L12 3M14.25 12.75H3.75M6 10.5L3.75 12.75L6 15", 1.65f),
    Shuffle(
        18f,
        "M13.5 10.5L16.5 13.5L13.5 16.5M13.5 1.5L16.5 4.5L13.5 7.5" +
            "M1.5 13.5H3.375C3.79336 13.4998 4.20563 13.3997 4.57744 13.2079C4.94926 13.0162 5.26985 12.7383 5.5125 12.3975L6.0225 11.67" +
            "M1.5 4.5H3.375C3.79336 4.50014 4.20563 4.60027 4.57744 4.79205C4.94926 4.98383 5.26985 5.2617 5.5125 5.6025L10.9875 12.3975" +
            "C11.2301 12.7383 11.5507 13.0162 11.9226 13.208C12.2944 13.3997 12.7066 13.4999 13.125 13.5H16.5" +
            "M16.5 4.5H13.125C12.7067 4.50014 12.2944 4.60027 11.9226 4.79205C11.5508 4.98383 11.2302 5.2617 10.9875 5.6025L10.4423 6.345",
        1.65f,
    ),
    /** Previous while a song is more than five seconds in: back to its start. */
    Replay(
        23f,
        "M4.3125 10.1584C4.59918 8.52864 5.42876 7.04385 6.66635 5.94542C7.90393 4.84699 9.47671 4.19955 11.1289 4.10837" +
            "C12.7812 4.01719 14.4156 4.48765 15.7666 5.44323C17.1175 6.39881 18.1054 7.78331 18.5697 9.37158" +
            "C19.0339 10.9598 18.9472 12.6585 18.3237 14.1912C17.7001 15.724 16.5763 17.0007 15.1351 17.8137" +
            "C13.6939 18.6268 12.02 18.9284 10.3857 18.6694C8.75132 18.4105 7.25264 17.6063 6.13333 16.3875" +
            "M8.81667 10.1584H4.3125V5.65419",
        1.77292f,
    ),
    Previous(
        23f,
        "M4.79163 4.79167V18.2083M18.2083 4.79167L7.66663 11.5L18.2083 18.2083V4.79167Z",
        1.65f,
        fill = "M18.2083 4.79167L7.66663 11.5L18.2083 18.2083V4.79167Z",
    ),
    Next(
        23f,
        "M18.2083 4.79167V18.2083M4.79163 4.79167L15.3333 11.5L4.79163 18.2083V4.79167Z",
        1.65f,
        fill = "M4.79163 4.79167L15.3333 11.5L4.79163 18.2083V4.79167Z",
    ),
    Play(28f, "M9.33337 4.66667L23.3334 14L9.33337 23.3333V4.66667Z", 1.65f, fill = "M9.33337 4.66667L23.3334 14L9.33337 23.3333V4.66667Z"),
    Pause(
        28f, null, 0f,
        fill = "M10.6 5H8.4C7.6268 5 7 5.6268 7 6.4V21.6C7 22.3732 7.6268 23 8.4 23H10.6C11.3732 23 12 22.3732 12 21.6V6.4" +
            "C12 5.6268 11.3732 5 10.6 5Z" +
            "M19.6 5H17.4C16.6268 5 16 5.6268 16 6.4V21.6C16 22.3732 16.6268 23 17.4 23H19.6C20.3732 23 21 22.3732 21 21.6V6.4" +
            "C21 5.6268 20.3732 5 19.6 5Z",
    ),
    Spark(
        20f,
        "M16.6667 0.833344V5.83334M14.1667 3.33334H19.1667M7.5 1.66668L9.58333 6.66668L15 9.16668L9.58333 11.6667" +
            "L7.5 16.6667L5.41667 11.6667L0 9.16668L5.41667 6.66668L7.5 1.66668Z",
        1.65f,
    ),
    Queue(
        20f,
        "M2.5 4.16666H12.5M2.5 8.33332H12.5M2.5 12.5H8.33333M14.1667 14.1667H12.5C12.1704 14.1667 11.8481 14.2644 11.574 14.4475" +
            "C11.3 14.6307 11.0863 14.891 10.9602 15.1955C10.8341 15.5001 10.801 15.8352 10.8654 16.1585" +
            "C10.9297 16.4818 11.0884 16.7787 11.3215 17.0118C11.5546 17.2449 11.8515 17.4037 12.1748 17.468" +
            "C12.4982 17.5323 12.8333 17.4993 13.1378 17.3731C13.4423 17.247 13.7026 17.0334 13.8858 16.7593" +
            "C14.0689 16.4852 14.1667 16.163 14.1667 15.8333V8.33332L18.3333 7.49999",
        1.65f,
    ),
    Phone(20f, "M8.33333 15H11.6667M5 1.66666H15V18.3333H5V1.66666Z", 1.65f),
    Sound(20f, "M3.33337 10.8333V15.8333M7.50004 6.66667V15.8333M11.6667 2.5V15.8333M15.8334 8.33333V15.8333", 1.65f),
    Close(16f, "M4 4L12 12M12 4L4 12", 1.6f, roundJoin = false),
}

private fun parse(data: String): Path = PathParser().parsePathString(data).toPath()

/** Draws [glyph] at [size] (its viewBox in dp), clipped to its box as Figma clips it. */
@Composable
internal fun NeuIcon(glyph: NeuGlyph, color: Color, modifier: Modifier = Modifier, size: Dp = glyph.box.dp, filled: Boolean = false) {
    val stroke = remember(glyph) { glyph.stroke?.let(::parse) }
    val fill = remember(glyph) { glyph.fill?.let(::parse) }
    Canvas(modifier.size(size).clipToBounds()) {
        drawGlyph(glyph, stroke, fill, color, filled)
    }
}

private fun DrawScope.drawGlyph(glyph: NeuGlyph, stroke: Path?, fill: Path?, color: Color, filled: Boolean) {
    val unit = size.width / glyph.box
    scale(unit, unit, pivot = Offset.Zero) {
        if (fill != null) drawPath(fill, color, style = Fill)
        if (filled && stroke != null) drawPath(stroke, color, style = Fill)
        if (stroke != null) drawPath(
            stroke, color,
            style = Stroke(
                glyph.strokeWidth,
                cap = StrokeCap.Round,
                join = if (glyph.roundJoin) StrokeJoin.Round else StrokeJoin.Miter,
            ),
        )
    }
}
