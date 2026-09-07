package com.harmony.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.component.Artwork

/** Seconds for one full turn of the record. 33⅓ RPM would be 1.8s — far too */
/** fast to look like anything but a spinning wheel at this size. */
private const val ROTATION_MS = 9_000

/** Fraction of the block's width taken by the sleeve. The record is the same */
/** diameter and is pinned to the right edge, so it peeks out by the rest. */
private const val SLEEVE_FRACTION = 0.70f

/**
 * The hero of the Now Playing screen: the album sleeve with the record
 * half-drawn out behind it.
 *
 * The record's motion is the whole point, so it carries playback state
 * directly — it turns while audio plays, coasts to a stop where it was when
 * you pause (rather than snapping back to zero), and slides a little further
 * into the sleeve while stopped. Nothing else on screen has to announce
 * "paused"; the record already did.
 *
 * The label is a circular crop of the album art, the way a pressing plant
 * would print it, so every record in the library looks like its own record.
 */
@Composable
fun VinylArtwork(
    artworkUri: String?,
    albumName: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = playerPalette()

    // One turn per animateTo, looping. Modding back under 360° each lap keeps
    // float precision honest over a long listening session, and because the
    // easing is linear and each lap ends exactly on +360°, the seam is
    // invisible. Pausing cancels the effect mid-lap and the Animatable simply
    // holds its current angle.
    val angle = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            angle.snapTo(angle.value % 360f)
            angle.animateTo(
                targetValue = angle.value + 360f,
                animationSpec = tween(durationMillis = ROTATION_MS, easing = LinearEasing),
            )
        }
    }

    val tuck by animateDpAsState(
        targetValue = if (isPlaying) 0.dp else (-18).dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "record-tuck",
    )

    BoxWithConstraints(modifier) {
        val sleeveSize = maxWidth * SLEEVE_FRACTION
        val recordSize = sleeveSize

        // Record first so it sits behind the sleeve. Semantics are cleared:
        // it's decoration, and the sleeve already carries the album name.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = tuck)
                .size(recordSize)
                .graphicsLayer { rotationZ = angle.value }
                .clearAndSetSemantics {},
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)

                drawCircle(color = palette.vinyl, radius = r, center = c)

                // Grooves: tighter toward the rim, the way a record actually
                // is, and stroked thin enough to read as texture rather than
                // as rings.
                val grooveCount = 26
                for (i in 0 until grooveCount) {
                    val t = i / (grooveCount - 1f)
                    val radius = r * (0.42f + 0.55f * t * t)
                    drawCircle(
                        color = palette.vinylGroove,
                        radius = radius,
                        center = c,
                        style = Stroke(width = r * 0.008f),
                    )
                }

                // A single soft sheen across the disc. It rotates with the
                // record, which is wrong for a real light source but right
                // here — a fixed highlight on a spinning disc reads as a
                // smudge on the screen.
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.Transparent,
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

            // Printed label.
            val labelSize = recordSize * 0.34f
            Artwork(
                artworkUri = artworkUri,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(labelSize),
                cornerRadius = labelSize / 2,
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(labelSize)
                    .clip(CircleShape)
                    .background(palette.vinyl.copy(alpha = 0.28f)),
            )
            // Spindle hole, punched in the screen's own background color so it
            // reads as a hole rather than a dot.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(recordSize * 0.045f)
                    .clip(CircleShape)
                    .background(palette.background),
            )
        }

        Artwork(
            artworkUri = artworkUri,
            contentDescription = albumName?.let { "Album art for $it" },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(sleeveSize)
                .shadow(
                    elevation = 22.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = palette.artShadow,
                    spotColor = palette.artShadow,
                ),
            cornerRadius = 12.dp,
        )
    }
}
