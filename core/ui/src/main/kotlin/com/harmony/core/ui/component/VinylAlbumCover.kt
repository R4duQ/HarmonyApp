package com.harmony.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * An album shown the way a record actually sits: the sleeve square, with the
 * disc drawn part-way out to the right.
 *
 * Same idea as the player's hero artwork, rebuilt for a grid — the disc is
 * static (nothing is playing here) and the proportions are tuned so the
 * peeking edge survives at thumbnail size, where the player's 30% would
 * shrink to a sliver.
 *
 * Sizes itself from its own width, so it works at any cell size.
 */
@Composable
fun VinylAlbumCover(
    artworkUri: String?,
    albumName: String?,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val sleeve = maxWidth * SLEEVE_FRACTION
        val disc = sleeve * 0.94f

        // Record first: behind the sleeve, pinned right.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .size(disc),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(color = VINYL, radius = r, center = c)
                // Grooves tighten toward the rim, as on a real pressing.
                val grooves = 14
                for (i in 0 until grooves) {
                    val t = i / (grooves - 1f)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.10f),
                        radius = r * (0.45f + 0.52f * t * t),
                        center = c,
                        style = Stroke(width = r * 0.013f),
                    )
                }
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.05f),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                    radius = r,
                    center = c,
                )
            }
            // Printed label: the album's own art, so each record looks like
            // its own release rather than a generic black circle.
            val label = disc * 0.36f
            Artwork(
                artworkUri = artworkUri,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(label),
                cornerRadius = label / 2,
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(label)
                    .clip(CircleShape)
                    .background(VINYL.copy(alpha = 0.26f)),
            )
            // Spindle hole, punched in the field colour so it reads as a hole.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(disc * 0.05f)
                    .clip(CircleShape)
                    .background(palette.field),
            )
        }

        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(sleeve)
                .shadow(10.dp, RoundedCornerShape(6.dp))
                .border(1.5.dp, palette.line, RoundedCornerShape(6.dp)),
        ) {
            Artwork(
                artworkUri = artworkUri,
                contentDescription = albumName,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(1.5.dp),
                cornerRadius = 5.dp,
            )
        }
    }
}

/**
 * Sleeve width as a fraction of the whole. Lower than the player's 0.70 on
 * purpose: at grid thumbnail size a 30% reveal is a sliver, and the sleeve
 * is still the dominant element at three quarters.
 */
private const val SLEEVE_FRACTION = 0.76f

private val VINYL = Color(0xFF17151C)
