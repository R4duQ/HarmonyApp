package com.harmony.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.theme.CyberGlassColors

/**
 * Artwork for a song without a cover, in Cyber-Glass: a glowing neon audio
 * waveform, cyan into violet, on a dark glass square.
 *
 * Seven bars in a fixed, symmetric silhouette (a waveform, not a meter), so
 * every coverless song wears the same mark and it reads as an icon.
 */
@Composable
fun NeonWaveformArt(modifier: Modifier = Modifier, cornerRadius: Dp = 10.dp) {
    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    listOf(CyberGlassColors.ArtWell, CyberGlassColors.Obsidian),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val heights = floatArrayOf(0.30f, 0.55f, 0.82f, 1.00f, 0.82f, 0.55f, 0.30f)
            val bars = heights.size
            val usable = size.width * 0.62f
            val slot = usable / bars
            val barWidth = slot * 0.46f
            val left = (size.width - usable) / 2f + (slot - barWidth) / 2f
            val maxHeight = size.height * 0.58f
            val mid = size.height / 2f
            val brush = Brush.verticalGradient(
                listOf(CyberGlassColors.Cyan, CyberGlassColors.Violet, CyberGlassColors.Purple),
                startY = mid - maxHeight / 2f,
                endY = mid + maxHeight / 2f,
            )
            for (i in 0 until bars) {
                val h = maxHeight * heights[i]
                val x = left + i * slot
                // The glow: the same bar, wider and faint, under the crisp one.
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x - barWidth * 0.6f, mid - h / 2f - barWidth * 0.6f),
                    size = Size(barWidth * 2.2f, h + barWidth * 1.2f),
                    cornerRadius = CornerRadius(barWidth * 1.1f),
                    alpha = 0.18f,
                )
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x, mid - h / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
    }
}

/**
 * A soft neon glow behind a composable: a radial gradient of [color] that
 * fades out [spread] beyond its edges. Drawn behind, unclipped, so it can
 * spill past the bounds the way light does. Put it before any clip.
 */
fun Modifier.neonGlow(color: Color, spread: Dp = 10.dp, intensity: Float = 0.35f): Modifier =
    drawBehind {
        if (intensity <= 0f) return@drawBehind
        val extra = spread.toPx()
        val radius = size.minDimension / 2f + extra
        drawCircle(
            Brush.radialGradient(
                0f to color.copy(alpha = intensity),
                (size.minDimension / 2f / radius).coerceIn(0f, 1f) to color.copy(alpha = intensity * 0.55f),
                1f to Color.Transparent,
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
