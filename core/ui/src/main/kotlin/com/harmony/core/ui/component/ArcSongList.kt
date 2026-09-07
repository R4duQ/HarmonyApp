package com.harmony.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.Song
import kotlin.math.abs

private val ITEM_HEIGHT = 116.dp
private val ART_SIZE = 88.dp

/** Half-angle of the wheel: the tilt reached at the edge of the viewport. */
private const val MAX_TILT = 20f

/**
 * Radius of the wheel, as a multiple of the viewport's half-height. The
 * pivot sits this far off the LEFT of the screen; items ride the rim.
 * Larger means a flatter, more open curve.
 */
private const val WHEEL_RADIUS = 1.9f

/** Fraction of a true 1:1 roll. Full roll is dizzying at this disc size. */
private const val ROLL_DAMPING = 0.45f

/** Milliseconds per turn of the centre record, and how many before it stops. */
private const val SPIN_MS = 9_000
private const val SPIN_LAPS = 120

/**
 * A song browser laid out as a wheel: the item nearest the middle sits flat
 * and opens into a card with a Play button, while everything else tilts,
 * drifts right, shrinks and fades with distance from the centre.
 *
 * It snaps, so one flick always leaves exactly one song selected — without
 * snapping the "centre" item would be whatever happened to stop there, and
 * the expanded card would flicker between two rows mid-scroll.
 *
 * Cost, stated plainly: this shows about five songs at once against a plain
 * list's nine, and has nowhere to put section headers or an A–Z rail. It's
 * a browsing surface, not a finding one — which is why the Library keeps
 * the plain list as well and lets you switch.
 *
 * Performance note: the per-item transform reads [androidx.compose.foundation.lazy.LazyListState.layoutInfo]
 * from INSIDE the graphicsLayer lambda. That defers the read to the draw
 * phase, so scrolling re-runs the transform without recomposing any item.
 * Only [centeredIndex] causes recomposition, and derivedStateOf limits that
 * to the moments the centre actually changes hands.
 *
 * @param songAt may return null for a not-yet-loaded paged item.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArcSongList(
    itemCount: Int,
    songAt: (Int) -> Song?,
    palette: EditorialPalette,
    onPlay: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: ((Song) -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo
                .minByOrNull { abs((it.offset + it.size / 2f) - mid) }
                ?.index ?: 0
        }
    }

    // The centre record turns like one that's actually playing. The angle is
    // reset to zero each time the centre changes hands, so a record starts
    // from rest rather than snapping to wherever the previous one had got to.
    // One long linear ramp rather than a repeating 0..360 loop: a wrapping
    // animation would jump every cycle once it's scaled by [settle] below.
    val spin = remember { Animatable(0f) }
    LaunchedEffect(centeredIndex) {
        spin.snapTo(0f)
        spin.animateTo(
            targetValue = 360f * SPIN_LAPS,
            animationSpec = tween(SPIN_MS * SPIN_LAPS, easing = LinearEasing),
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // In landscape the list can be shorter than a single row is tall, so
        // the row shrinks to fit rather than overflowing. Everything inside
        // is sized from this, not from the constant.
        val itemH = minOf(ITEM_HEIGHT, maxHeight * 0.62f)
        val artSize = minOf(ART_SIZE, itemH * 0.76f)
        val cardH = minOf(84.dp, itemH * 0.74f)

        // Half a viewport of padding at each end so the first and last songs
        // can reach the centre like every other one. Clamped at zero: when the
        // viewport is shorter than a row this goes negative, and PaddingValues
        // throws on negative values — which is what crashed the app on rotate.
        val endPad = ((maxHeight - itemH) / 2).coerceAtLeast(0.dp)

        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(top = endPad, bottom = endPad),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(itemCount) { index ->
                val song = songAt(index)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemH)
                        .graphicsLayer {
                            val info = listState.layoutInfo
                            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val half = ((info.viewportEndOffset - info.viewportStartOffset) / 2f)
                                .coerceAtLeast(1f)
                            val itemMid = item?.let { it.offset + it.size / 2f } ?: mid
                            // -1 at the top edge, 0 dead centre, +1 at the bottom.
                            val t = ((itemMid - mid) / half).coerceIn(-1f, 1f)
                            val a = abs(t)

                            // A real arc, not a V: the horizontal offset is
                            // the SAGITTA of the swept angle, r(1 - cos t),
                            // which is what puts items on the rim of a circle
                            // pivoting off the left of the screen. Offsetting
                            // linearly with distance (the previous version)
                            // gives two straight diagonals meeting in a point
                            // — visibly not a curve.
                            val theta = Math.toRadians((a * MAX_TILT).toDouble())
                            val radius = half * WHEEL_RADIUS
                            // Same tilt direction on both sides: mirroring it
                            // would read as a fold down the middle rather than
                            // as one wheel turning.
                            rotationZ = a * MAX_TILT
                            translationX = (radius * (1f - kotlin.math.cos(theta))).toFloat()
                            scaleX = 1f - a * 0.20f
                            scaleY = 1f - a * 0.20f
                            alpha = 1f - a * 0.62f
                            // Pivot on the artwork, so the disc stays put and
                            // the title swings around it.
                            transformOrigin = TransformOrigin(0.16f, 0.5f)
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (song == null) {
                        // A page still loading. Draw the disc's outline so the
                        // wheel keeps its rhythm instead of showing a hole.
                        Box(
                            Modifier
                                .padding(start = 14.dp)
                                .size(artSize)
                                .background(palette.muted.copy(alpha = 0.15f), CircleShape),
                        )
                        return@items
                    }
                    val isCentre = index == centeredIndex

                    if (isCentre) {
                        Surface(
                            onClick = { onPlay(index) },
                            modifier = Modifier
                                .padding(start = artSize / 2, end = 16.dp)
                                .fillMaxWidth()
                                .height(cardH),
                            shape = RoundedCornerShape(50),
                            color = palette.field.lighten(),
                            shadowElevation = 10.dp,
                        ) {
                            Row(
                                Modifier.padding(start = artSize / 2 + 18.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        song.title,
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.3).sp,
                                        color = palette.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        song.artist,
                                        fontSize = 13.sp,
                                        color = palette.muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                EditorialPill(
                                    text = "Play",
                                    icon = Icons.Rounded.PlayArrow,
                                    onClick = { onPlay(index) },
                                    palette = palette,
                                )
                                if (onDelete != null) IconButton(onClick = { onDelete(song) }) {
                                    Icon(Icons.Rounded.DeleteOutline, "Delete ${song.title} from phone", tint = palette.muted)
                                }
                            }
                        }
                    } else {
                        Column(
                            Modifier.padding(start = artSize + 26.dp, end = 12.dp),
                        ) {
                            Text(
                                song.title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.3).sp,
                                color = palette.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                song.artist,
                                fontSize = 14.sp,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // The disc, drawn last so it sits over the card's edge —
                    // that overlap is what makes the card read as attached to
                    // the record rather than floating beside it.
                    Box(
                        Modifier
                            .padding(start = 14.dp)
                            .size(artSize)
                            .graphicsLayer {
                                val info = listState.layoutInfo
                                val item = info.visibleItemsInfo
                                    .firstOrNull { it.index == index }
                                val mid = (info.viewportStartOffset +
                                    info.viewportEndOffset) / 2f
                                val half = ((info.viewportEndOffset -
                                    info.viewportStartOffset) / 2f).coerceAtLeast(1f)
                                val itemMid = item?.let { it.offset + it.size / 2f } ?: mid

                                // Rolling: a wheel travelling a distance turns
                                // by that distance over its radius. Damped,
                                // because a true 1:1 roll at this size spins
                                // fast enough to be unreadable.
                                val r = (size.width / 2f).coerceAtLeast(1f)
                                val roll = Math.toDegrees(
                                    ((itemMid - mid) / r).toDouble()
                                ).toFloat() * ROLL_DAMPING

                                // Fades the playing-spin in as the record
                                // settles into the centre, so it doesn't
                                // switch on abruptly at the snap point.
                                val a = (abs(itemMid - mid) / half).coerceIn(0f, 1f)
                                val settle = (1f - a) * (1f - a)
                                rotationZ = roll +
                                    if (index == centeredIndex) spin.value * settle else 0f
                            }
                            .shadow(8.dp, CircleShape)
                            .background(palette.field.lighten(), CircleShape)
                            .padding(3.dp),
                    ) {
                        Artwork(
                            artworkUri = song.artworkUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = artSize / 2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A surface one step lighter than the field, for the card and the disc ring.
 * Derived rather than a new palette token: it only exists to lift these two
 * elements off the field, and every editorial screen needs the same lift.
 */
private fun Color.lighten(): Color = Color(
    red = red + (1f - red) * 0.62f,
    green = green + (1f - green) * 0.62f,
    blue = blue + (1f - blue) * 0.62f,
    alpha = 1f,
)
