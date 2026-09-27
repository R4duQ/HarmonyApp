package com.harmony.feature.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.harmony.core.model.AudioOutputType

/**
 * Glyphs for where the music is playing, drawn on the 24-unit grid of the
 * Material rounded icons so they sit beside them at any size.
 */
internal object OutputIcons {

    /** Headphones with the Bluetooth rune between the ear cups. */
    val BluetoothHeadphones: ImageVector = ImageVector.Builder(
        name = "BluetoothHeadphones", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        // Headband.
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(4f, 14.5f)
            verticalLineTo(12f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 12f)
            verticalLineTo(14.5f)
        }
        // Ear cups.
        path(fill = SolidColor(Color.Black)) {
            roundRect(2.8f, 12.8f, 7.6f, 20.8f, 1.9f)
            roundRect(16.4f, 12.8f, 21.2f, 20.8f, 1.9f)
        }
        // Bluetooth rune.
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(9.9f, 12.6f)
            lineTo(14.1f, 16.4f)
            lineTo(12f, 18.3f)
            verticalLineTo(10.7f)
            lineTo(14.1f, 12.6f)
            lineTo(9.9f, 16.4f)
        }
    }.build()

    /** A car seen from the front with a play mark on its grille: music through Android Auto. */
    val Car: ImageVector = ImageVector.Builder(
        name = "CarPlayback", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            // Cabin, with the windshield cut out.
            moveTo(7.1f, 4.8f)
            horizontalLineTo(16.9f)
            quadTo(17.9f, 4.8f, 18.3f, 5.7f)
            lineTo(20.1f, 10.2f)
            horizontalLineTo(3.9f)
            lineTo(5.7f, 5.7f)
            quadTo(6.1f, 4.8f, 7.1f, 4.8f)
            close()
            moveTo(7.6f, 6.6f)
            lineTo(6.5f, 9.1f)
            horizontalLineTo(17.5f)
            lineTo(16.4f, 6.6f)
            close()
            // Body, with headlights and the play mark cut out.
            roundRect(2.6f, 9.8f, 21.4f, 16.9f, 2.2f)
            circle(6.4f, 13.3f, 1.35f)
            circle(17.6f, 13.3f, 1.35f)
            moveTo(10.7f, 11.4f)
            lineTo(14.1f, 13.35f)
            lineTo(10.7f, 15.3f)
            close()
        }
        // Wheels.
        path(fill = SolidColor(Color.Black)) {
            roundRect(4.3f, 16.2f, 7.9f, 19.6f, 1.2f)
            roundRect(16.1f, 16.2f, 19.7f, 19.6f, 1.2f)
        }
    }.build()

    fun forType(type: AudioOutputType): ImageVector = when (type) {
        AudioOutputType.BLUETOOTH -> BluetoothHeadphones
        AudioOutputType.CAR -> Car
        AudioOutputType.WIRED -> Icons.Rounded.Headphones
        AudioOutputType.USB -> Icons.Rounded.Usb
        AudioOutputType.HDMI -> Icons.Rounded.Tv
        AudioOutputType.SPEAKER, AudioOutputType.OTHER -> Icons.Rounded.Speaker
    }
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
    moveTo(l + radius, t)
    horizontalLineTo(r - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = r, y1 = t + radius)
    verticalLineTo(b - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = r - radius, y1 = b)
    horizontalLineTo(l + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = l, y1 = b - radius)
    verticalLineTo(t + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = l + radius, y1 = t)
    close()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
    moveTo(cx - radius, cy)
    arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + radius, y1 = cy)
    arcTo(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - radius, y1 = cy)
    close()
}
