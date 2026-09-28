package com.harmony.feature.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PhoneAndroid
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
import com.harmony.core.model.OutputForm

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
        bluetoothRune(centerX = 12f, top = 10.7f, bottom = 18.3f, halfWidth = 2.1f)
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

    /** True wireless earbuds: two buds with ear tips turned inward, stems down, the Bluetooth rune between. */
    val Earbuds: ImageVector = ImageVector.Builder(
        name = "Earbuds", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Left bud: head, ear tip, stem.
            circle(6.6f, 8.0f, 3.6f)
            circle(9.5f, 5.9f, 1.7f)
            roundRect(3.9f, 9.2f, 6.9f, 18.4f, 1.5f)
            // Right bud, mirrored.
            circle(17.4f, 8.0f, 3.6f)
            circle(14.5f, 5.9f, 1.7f)
            roundRect(17.1f, 9.2f, 20.1f, 18.4f, 1.5f)
        }
        bluetoothRune(centerX = 12f, top = 13f, bottom = 20.2f, halfWidth = 1.8f)
    }.build()

    /** Neckband: a band round the neck, a control pod at each end and the buds on short cables. */
    val Neckband: ImageVector = ImageVector.Builder(
        name = "Neckband", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
            moveTo(5.5f, 10f)
            verticalLineTo(13.6f)
            arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 18.5f, y1 = 13.6f)
            verticalLineTo(10f)
        }
        path(fill = SolidColor(Color.Black)) {
            roundRect(4.0f, 6.4f, 7.0f, 12.2f, 1.5f)
            roundRect(17.0f, 6.4f, 20.0f, 12.2f, 1.5f)
            circle(8.6f, 3.3f, 1.8f)
            circle(15.4f, 3.3f, 1.8f)
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1f, strokeLineCap = StrokeCap.Round) {
            moveTo(5.5f, 6.4f)
            quadTo(5.5f, 3.6f, 7.1f, 3.4f)
            moveTo(18.5f, 6.4f)
            quadTo(18.5f, 3.6f, 16.9f, 3.4f)
        }
        bluetoothRune(centerX = 12f, top = 9.3f, bottom = 16.7f, halfWidth = 1.9f)
    }.build()

    /** Wired in-ear earphones: two buds whose cables meet at the jack. */
    val WiredEarphones: ImageVector = ImageVector.Builder(
        name = "WiredEarphones", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            circle(6.8f, 4.9f, 2.6f)
            circle(17.2f, 4.9f, 2.6f)
            roundRect(10.5f, 15.6f, 13.5f, 19.8f, 0.9f)
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
            moveTo(6.8f, 7.4f)
            curveTo(6.8f, 11.2f, 12f, 11.4f, 12f, 15.6f)
            moveTo(17.2f, 7.4f)
            curveTo(17.2f, 11.2f, 12f, 11.4f, 12f, 15.6f)
            moveTo(12f, 19.8f)
            verticalLineTo(22f)
        }
    }.build()

    /** A portable Bluetooth speaker: a capsule with two drivers. */
    val PortableSpeaker: ImageVector = ImageVector.Builder(
        name = "PortableSpeaker", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            roundRect(2f, 6.8f, 22f, 18f, 5.6f)
            circle(7.6f, 12.4f, 3f)
            circle(16.4f, 12.4f, 3f)
        }
        path(fill = SolidColor(Color.Black)) {
            circle(7.6f, 12.4f, 1.25f)
            circle(16.4f, 12.4f, 1.25f)
            roundRect(9.8f, 4.6f, 14.2f, 5.9f, 0.65f)
        }
    }.build()

    /** Which kinds a listener may choose for an output of [type]. */
    fun pickable(type: AudioOutputType): List<OutputForm> = when (type) {
        AudioOutputType.BLUETOOTH -> listOf(OutputForm.EARBUDS, OutputForm.HEADPHONES, OutputForm.NECKBAND,
            OutputForm.SPEAKER, OutputForm.CAR_STEREO, OutputForm.HEARING_AID)
        AudioOutputType.WIRED, AudioOutputType.USB -> listOf(OutputForm.EARPHONES, OutputForm.HEADPHONES, OutputForm.SPEAKER)
        else -> emptyList()
    }

    fun canPickForm(type: AudioOutputType): Boolean = pickable(type).isNotEmpty()

    /** The icon for a kind of device; Bluetooth headphones carry the rune, wired ones don't. */
    fun forForm(form: OutputForm, bluetooth: Boolean = true): ImageVector = when (form) {
        OutputForm.EARBUDS -> Earbuds
        OutputForm.HEADPHONES -> if (bluetooth) BluetoothHeadphones else Icons.Rounded.Headphones
        OutputForm.NECKBAND -> Neckband
        OutputForm.EARPHONES -> WiredEarphones
        OutputForm.SPEAKER -> PortableSpeaker
        OutputForm.CAR_STEREO -> Car
        OutputForm.HEARING_AID -> Icons.Rounded.Hearing
    }

    /** The icon for the current output: its kind when known, otherwise its connection. */
    fun forOutput(type: AudioOutputType, form: OutputForm?): ImageVector = when {
        type == AudioOutputType.CAR -> Car
        form != null && canPickForm(type) -> forForm(form, bluetooth = type == AudioOutputType.BLUETOOTH)
        else -> forType(type)
    }

    fun forType(type: AudioOutputType): ImageVector = when (type) {
        AudioOutputType.BLUETOOTH -> BluetoothHeadphones
        AudioOutputType.CAR -> Car
        AudioOutputType.WIRED -> Icons.Rounded.Headphones
        AudioOutputType.USB -> Icons.Rounded.Usb
        AudioOutputType.HDMI -> Icons.Rounded.Tv
        // The phone's own speaker: a phone, so it can't be mistaken for a Bluetooth speaker.
        AudioOutputType.SPEAKER, AudioOutputType.OTHER -> Icons.Rounded.PhoneAndroid
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

/** The Bluetooth rune as a stroke, sized to sit between the parts of a device. */
private fun ImageVector.Builder.bluetoothRune(centerX: Float, top: Float, bottom: Float, halfWidth: Float) {
    val quarter = (bottom - top) / 4f
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(centerX - halfWidth, top + quarter)
        lineTo(centerX + halfWidth, bottom - quarter)
        lineTo(centerX, bottom)
        verticalLineTo(top)
        lineTo(centerX + halfWidth, top + quarter)
        lineTo(centerX - halfWidth, bottom - quarter)
    }
}
