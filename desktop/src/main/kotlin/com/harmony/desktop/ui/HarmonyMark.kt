package com.harmony.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/**
 * Harmony's mark, the same as the phone app's icon
 * (app/src/main/res/drawable/ic_launcher_h.xml): a slanted H of three
 * round-capped bars, #1A1403 on the amber #F3A311, on its 108-unit canvas.
 * Here on a rounded square, as in desktop/icon.svg (the Windows icon).
 * Drawn, not a picture, so it is sharp at any size.
 */
@Composable
fun HarmonyMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 108f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        drawRoundRect(Amber, topLeft = p(5f, 5f), size = Size(98f * u, 98f * u), cornerRadius = CornerRadius(24f * u))
        val w = 12.5f * u
        drawLine(Ink, p(45f, 34f), p(34f, 74f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(Ink, p(74f, 34f), p(63f, 74f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(Ink, p(39.5f, 54f), p(68.5f, 54f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

private val Amber = Color(0xFFF3A311)
private val Ink = Color(0xFF1A1403)
