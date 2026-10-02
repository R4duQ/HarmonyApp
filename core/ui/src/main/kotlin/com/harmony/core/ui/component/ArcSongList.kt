package com.harmony.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.harmony.core.model.Song
import kotlin.math.abs

private val ITEM_HEIGHT = 120.dp
private val ART_SIZE = 92.dp

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
        val cardH = minOf(100.dp, itemH * 0.84f)
        val discStart = 14.dp

        // Half a viewport of padding at each end so the first and last songs
        // can reach the centre like every other one. Clamped at zero: when the
        // viewport is shorter than a row this goes negative, and PaddingValues
        // throws on negative values — which is what crashed the app on rotate.
        val endPad = ((maxHeight - itemH) / 2).coerceAtLeast(0.dp)

        // The rail the records ride on, drawn through the centre of every disc
        // position, and a glow where the centre record sits. It is the same
        // curve the rows follow below: r(1 - cos θ) sideways for a tilt of θ.
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.height / 2f
            val half = mid.coerceAtLeast(1f)
            val radius = half * WHEEL_RADIUS
            val x0 = (discStart + artSize / 2).toPx()
            val rail = Path()
            for (step in 0..64) {
                val t = -1f + 2f * step / 64f
                val theta = Math.toRadians((abs(t) * MAX_TILT).toDouble())
                val x = x0 + (radius * (1f - kotlin.math.cos(theta))).toFloat()
                val y = mid + t * half
                if (step == 0) rail.moveTo(x, y) else rail.lineTo(x, y)
            }
            // Strongest at the centre, gone by the edges.
            val along = { peak: Color -> Brush.verticalGradient(listOf(Color.Transparent, peak, Color.Transparent)) }
            drawPath(rail, along(palette.accent.copy(alpha = 0.10f)), style = Stroke(artSize.toPx() * 1.18f, cap = StrokeCap.Round))
            drawPath(rail, along(palette.line), style = Stroke(1.dp.toPx()))
            drawCircle(
                Brush.radialGradient(listOf(palette.accent.copy(alpha = 0.30f), Color.Transparent), Offset(x0, mid), artSize.toPx() * 1.05f),
                radius = artSize.toPx() * 1.05f, center = Offset(x0, mid),
            )
        }

        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(top = endPad, bottom = endPad),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(itemCount) { index ->
                val song = songAt(index)
                val isCentre = index == centeredIndex
                // The centre record grows a little as it settles, the way the
                // one on the platter stands out from the ones in the crate.
                val disc by animateDpAsState(if (isCentre) artSize * 1.12f else artSize, spring(dampingRatio = 0.6f), label = "disc")
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
                            // Pivot on the disc's centre, so the disc stays on
                            // the rail and the title swings around it.
                            transformOrigin = TransformOrigin(
                                ((discStart + artSize / 2).toPx() / size.width).coerceIn(0f, 1f), 0.5f,
                            )
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (song == null) {
                        // A page still loading: a blank pressing keeps the
                        // wheel's rhythm instead of showing a hole.
                        VinylDisc(
                            artworkUri = null,
                            palette = palette,
                            modifier = Modifier.padding(start = discStart).size(artSize).alpha(0.35f),
                        )
                        return@items
                    }

                    if (isCentre) {
                        CentreCard(
                            song = song,
                            palette = palette,
                            onPlay = { onPlay(index) },
                            onDelete = onDelete?.let { delete -> { delete(song) } },
                            modifier = Modifier
                                .padding(start = discStart + artSize / 2, end = 14.dp)
                                .fillMaxWidth()
                                .height(cardH),
                            textStart = artSize / 2 + 20.dp,
                        )
                    } else {
                        Column(
                            Modifier.padding(start = discStart + artSize + 14.dp, end = 12.dp),
                        ) {
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
                                fontSize = 14.sp,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // The disc, drawn last so it sits over the card's edge —
                    // that overlap is what makes the card read as attached to
                    // the record rather than floating beside it. Grown discs
                    // stay centred on the rail.
                    VinylDisc(
                        artworkUri = song.artworkUri,
                        palette = palette,
                        modifier = Modifier
                            .padding(start = discStart - (disc - artSize) / 2)
                            .size(disc)
                            .shadow(if (isCentre) 14.dp else 6.dp, CircleShape),
                        spin = Modifier.graphicsLayer {
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
                        },
                    )
                }
            }
        }

        // Where you are in the library: the wheel has no A–Z rail, so this
        // is the one sense of position it gives.
        if (itemCount > 0) {
            Text(
                "${"%,d".format(centeredIndex + 1)} of ${"%,d".format(itemCount)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.muted,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 16.dp)
                    .zIndex(1f)
                    .clip(CircleShape)
                    .background(cardSurface(palette))
                    .border(BorderStroke(1.dp, palette.line.copy(alpha = 0.5f)), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        // Soft edges, so records fade into the page instead of being cut.
        val fade = minOf(64.dp, maxHeight / 6)
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(fade)
                .background(Brush.verticalGradient(listOf(palette.field, Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(fade)
                .background(Brush.verticalGradient(listOf(Color.Transparent, palette.field))),
        )
    }
}

/**
 * The selected song: title, artist and album, its audio quality and length,
 * a round Play button, and a menu for the rest.
 * Deleting sits in the menu, away from Play, because it removes the file.
 */
@Composable
private fun CentreCard(
    song: Song,
    palette: EditorialPalette,
    onPlay: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier,
    textStart: Dp,
) {
    val shape = RoundedCornerShape(30.dp)
    Surface(
        onClick = onPlay,
        modifier = modifier,
        shape = shape,
        color = cardSurface(palette),
        border = BorderStroke(1.dp, palette.line.copy(alpha = 0.5f)),
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier.padding(start = textStart, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 13.sp,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    qualityLabel(song)?.let { quality ->
                        Text(
                            quality,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = palette.accent,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(CircleShape)
                                .background(palette.accent.copy(alpha = 0.14f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        formatDuration(song.durationMs),
                        fontSize = 11.sp,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(46.dp)
                    .shadow(8.dp, CircleShape, ambientColor = palette.accent, spotColor = palette.accent)
                    .clip(CircleShape)
                    .background(palette.accent)
                    .clickable(onClick = onPlay)
                    .semantics { contentDescription = "Play ${song.title}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(28.dp))
            }
            if (onDelete != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, "More for ${song.title}", tint = palette.muted)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete from phone") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

/**
 * "HI-RES 24/96", "LOSSLESS 16/44.1" or "320 KBPS", from what the scan read;
 * null when it read nothing useful.
 */
internal fun qualityLabel(song: Song): String? {
    val bits = song.bitDepth
    val rate = song.sampleRateHz
    if (bits != null && bits > 0 && rate != null && rate > 0) {
        val khz = if (rate % 1000 == 0) "${rate / 1000}" else "${rate / 1000}.${(rate % 1000) / 100}"
        return (if (bits >= 24 || rate > 48_000) "HI-RES" else "LOSSLESS") + " $bits/$khz"
    }
    return song.bitrateKbps?.takeIf { it > 0 }?.let { "$it KBPS" }
}

/**
 * The centre card's surface: near-white on the light field, one step up from
 * the field in dark mode. The old card lightened the field by 62% in both,
 * which in dark mode made a pale grey card under white text.
 */
private fun cardSurface(palette: EditorialPalette): Color =
    if (palette.field.luminance() < 0.5f) lerp(palette.field, palette.ink, 0.10f)
    else lerp(palette.field, Color.White, 0.85f)
