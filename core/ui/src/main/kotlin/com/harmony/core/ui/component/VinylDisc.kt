package com.harmony.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * A vinyl record seen from above: a black pressing with fine grooves and the
 * gaps between tracks, the song's artwork as the printed label, and the
 * spindle hole punched through in the field colour.
 *
 * [spin] goes on the part that turns (grooves and label). The sheen is drawn
 * over it without turning, the way light from a lamp stays put on a record
 * that is playing, which is what makes the spin readable.
 */
@Composable
fun VinylDisc(
    artworkUri: String?,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    spin: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val label = maxWidth * LABEL_FRACTION
        val hole = maxWidth * HOLE_FRACTION
        Box(Modifier.fillMaxSize().then(spin), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                // The pressing: a touch lighter towards the rim.
                drawCircle(
                    Brush.radialGradient(listOf(VinylCore, VinylRim), c, r),
                    radius = r, center = c,
                )
                // Grooves, alternating faintly so they shimmer rather than band.
                val grooves = 26
                for (i in 0 until grooves) {
                    val t = i / (grooves - 1f)
                    drawCircle(
                        Color.White.copy(alpha = if (i % 2 == 0) 0.075f else 0.045f),
                        radius = r * (0.50f + 0.46f * t),
                        center = c,
                        style = Stroke(width = r * 0.009f),
                    )
                }
                // The quiet gaps between tracks.
                for (gap in TRACK_GAPS) {
                    drawCircle(Color.Black.copy(alpha = 0.55f), radius = r * gap, center = c, style = Stroke(width = r * 0.022f))
                }
                // Lead-in edge catching the light.
                drawCircle(Color.White.copy(alpha = 0.14f), radius = r * 0.985f, center = c, style = Stroke(width = r * 0.018f))
            }
            if (artworkUri != null) {
                Artwork(
                    artworkUri = artworkUri,
                    contentDescription = null,
                    modifier = Modifier.size(label),
                    cornerRadius = label / 2,
                )
            } else {
                Box(Modifier.size(label).clip(CircleShape).background(palette.accent.copy(alpha = 0.55f)))
            }
            // A thin dark ring where the label meets the vinyl.
            Canvas(Modifier.size(label)) {
                drawCircle(Color.Black.copy(alpha = 0.35f), style = Stroke(width = size.minDimension * 0.035f))
            }
            Box(Modifier.size(hole).clip(CircleShape).background(palette.field))
        }
        // Sheen: two opposite glints across the grooves, fixed in place.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                Brush.sweepGradient(
                    0.00f to Color.Transparent,
                    0.09f to Color.White.copy(alpha = 0.16f),
                    0.18f to Color.Transparent,
                    0.50f to Color.Transparent,
                    0.59f to Color.White.copy(alpha = 0.10f),
                    0.68f to Color.Transparent,
                    1.00f to Color.Transparent,
                    center = c,
                ),
                // Only over the vinyl ring: the paper label doesn't shine.
                radius = r * (1f + LABEL_FRACTION) / 2f, center = c,
                style = Stroke(width = r * (1f - LABEL_FRACTION)),
            )
        }
    }
}

private const val LABEL_FRACTION = 0.46f
private const val HOLE_FRACTION = 0.045f
private val TRACK_GAPS = floatArrayOf(0.63f, 0.77f, 0.89f)
private val VinylCore = Color(0xFF1C1A22)
private val VinylRim = Color(0xFF0D0C11)
