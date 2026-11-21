package com.harmony.feature.player.neu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.harmony.core.ui.component.Artwork
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Inter at [size] with Figma's 130 % line height. */
internal fun interStyle(size: Float, weight: FontWeight, color: Color, tracking: TextUnit = TextUnit.Unspecified) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.3f).sp,
    letterSpacing = tracking,
    color = color,
)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun List<NeuShadow>.scaled(s: Float) = map { it.copy(dx = it.dx * s, dy = it.dy * s, blur = it.blur * s) }

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/**
 * A raised button that sinks when pressed (back, previous, next, top back,
 * more, lossless): the surface moves down [sink], its drop shadows give way
 * to inner ones, and the glyph shifts by [pressedShiftX] and fades to
 * [pressedAlpha], as the Pressed variant has it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NeuPressButton(
    onClick: () -> Unit,
    contentDescription: String,
    width: Dp,
    height: Dp,
    radius: Dp,
    raised: List<NeuShadow>,
    palette: NeuPalette,
    modifier: Modifier = Modifier,
    timing: PressTiming = PressTiming.Standard,
    sink: Dp = 1.5.dp,
    pressedShiftX: Dp = 0.dp,
    pressedAlpha: Float = 1f,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Holds the button at this much pressed (previews and the Figma comparison). */
    pressedOverride: Float? = null,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val p = pressedOverride ?: pressProgress(source, timing)
    Box(
        modifier
            .size(width, height)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = sink.toPx() * p }
                .neuSurface(radius, SolidColor(palette.ground), raised, 1f - p, palette.pressed, p),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.graphicsLayer {
                    translationX = pressedShiftX.toPx() * p
                    alpha = lerp(1f, pressedAlpha, p)
                },
            ) { content() }
        }
    }
}

/**
 * Shuffle and repeat: Off is raised, On is sunk (the surface stays where it
 * is) with the active dot under the glyph; 180 ms between them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NeuToggleButton(
    on: Boolean,
    glyph: NeuGlyph,
    contentDescription: String,
    palette: NeuPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** A small mark at the glyph's corner, e.g. "1" for repeat one. */
    badge: String? = null,
) {
    val p = stateProgress(on, "toggle-$glyph")
    Box(
        modifier
            .size(40.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .clearAndSetSemantics { this.contentDescription = contentDescription; role = Role.Button }
            .neuSurface(14.dp, SolidColor(palette.ground), palette.raised, 1f - p, palette.pressed, p),
    ) {
        NeuIcon(glyph, palette.ink, Modifier.offset(11.dp, 11.dp), size = 18.dp)
        Box(
            Modifier
                .offset(18.5.dp, 34.dp)
                .size(3.dp)
                .graphicsLayer { alpha = p }
                .background(palette.accent, RoundedCornerShape(1.5.dp)),
        )
        if (badge != null) {
            Text(
                badge,
                style = interStyle(7f, FontWeight.Bold, palette.accent),
                modifier = Modifier.offset(29.dp, 4.dp).graphicsLayer { alpha = p },
            )
        }
    }
}

/**
 * Play / pause in its sculpted surround (light frame 12:8, and the same in
 * dark): Paused is raised, Playing sinks 1.5 dp into inner shadows while the
 * play glyph gives way to pause; 180 ms between them.
 */
@Composable
internal fun NeuPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    palette: NeuPalette,
    modifier: Modifier = Modifier,
) {
    val p = stateProgress(playing, "play-pause")
    Box(modifier.size(94.dp)) {
        // The surround: a 94 dp well around the button, rim lit from above.
        Box(
            Modifier
                .fillMaxSize()
                .neuSurface(33.dp, SolidColor(palette.ground), inner = palette.surroundInset, stroke = palette.surroundStroke, strokeWidth = 0.7.dp),
        )
        Box(
            Modifier
                .offset(9.dp, 9.dp)
                .size(76.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { this.contentDescription = contentDescription },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = 1.5.dp.toPx() * p }
                    .neuSurface(
                        25.dp,
                        palette.playFill.brush(with(LocalDensity.current) { 76.dp.toPx() }),
                        palette.raised, 1f - p, palette.pressed, p,
                        stroke = palette.playStroke, strokeWidth = 0.85.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                NeuIcon(NeuGlyph.Play, palette.playIcon, Modifier.graphicsLayer { alpha = 1f - p })
                NeuIcon(NeuGlyph.Pause, palette.playIcon, Modifier.graphicsLayer { alpha = p })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/** "NOW PLAYING FROM" over the album, between the back and more buttons (76 dp). */
@Composable
internal fun NeuHeader(
    albumName: String?,
    palette: NeuPalette,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    menu: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxWidth().height(76.dp)) {
        NeuPressButton(
            onClick = onBack, contentDescription = "Back — collapse player",
            width = 42.dp, height = 42.dp, radius = 14.dp, raised = palette.raisedLarge, palette = palette,
            pressedShiftX = (-2.5).dp, modifier = Modifier.offset(y = 12.dp),
        ) { NeuIcon(NeuGlyph.Back, palette.ink) }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 52.dp, end = 52.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "NOW PLAYING FROM",
                style = interStyle(8f, FontWeight.SemiBold, palette.muted, tracking = 1.4.sp),
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(7.6.dp))
            Text(
                albumName ?: "Nothing playing",
                style = interStyle(12f, FontWeight.Medium, palette.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Row(Modifier.align(Alignment.TopEnd).offset(y = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            trailing()
            Box {
                NeuPressButton(
                    onClick = onMore, contentDescription = "More options",
                    width = 42.dp, height = 42.dp, radius = 14.dp, raised = palette.raisedLarge, palette = palette,
                    pressedAlpha = 0.6f,
                ) { NeuIcon(NeuGlyph.More, palette.ink) }
                menu()
            }
        }
    }
}

/** Favourite: a 42 dp raised button; the heart fills with the accent when the song is a favourite. */
@Composable
internal fun NeuFavoriteButton(isFavorite: Boolean, onToggle: () -> Unit, palette: NeuPalette, modifier: Modifier = Modifier) {
    val p = stateProgress(isFavorite, "favorite")
    NeuPressButton(
        onClick = onToggle,
        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
        width = 42.dp, height = 42.dp, radius = 14.dp, raised = palette.raisedLarge, palette = palette,
        modifier = modifier,
    ) {
        Box {
            NeuIcon(NeuGlyph.Heart, palette.muted, Modifier.graphicsLayer { alpha = 1f - p })
            NeuIcon(NeuGlyph.Heart, palette.accent, Modifier.graphicsLayer { alpha = p }, filled = true)
        }
    }
}

// ---------------------------------------------------------------------------
// The record
// ---------------------------------------------------------------------------

/** The assembly's size in the frame: the sleeve (250.2) and the record behind it, reaching 309.6 across. */
internal const val ASSEMBLY_W = 309.6f
internal const val ASSEMBLY_H = 250.2f

/**
 * The raised album sleeve with the record behind it: a 225 dp disc with 24
 * grooves (stroke 0.585, alternating) and a 54 dp label, as frame 12:8 draws
 * it. Scales down to fit, never up.
 */
@Composable
internal fun NeuRecord(artworkUri: String?, albumName: String?, palette: NeuPalette, modifier: Modifier = Modifier) {
    // The frame sets the assembly 18 of its 37.8 spare dp from the top, just above centre.
    BoxWithConstraints(modifier, contentAlignment = androidx.compose.ui.BiasAlignment(0f, 18f / 37.8f * 2f - 1f)) {
        val s = min(min(maxWidth.value / ASSEMBLY_W, maxHeight.value / ASSEMBLY_H), 1f).coerceAtLeast(0.1f)
        Box(Modifier.size((ASSEMBLY_W * s).dp, (ASSEMBLY_H * s).dp)) {
            // The record, behind the sleeve.
            Canvas(
                Modifier
                    .offset((84.6f * s).dp, (12.6f * s).dp)
                    .size((225f * s).dp)
                    .clearAndSetSemantics {},
            ) {
                val unit = size.width / 225f
                val c = Offset(size.width / 2f, size.height / 2f)
                val shadow = palette.recordShadow
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = shadow.color.toArgb()
                        maskFilter = android.graphics.BlurMaskFilter(
                            blurRadiusPx(shadow.blur * s, density),
                            android.graphics.BlurMaskFilter.Blur.NORMAL,
                        )
                    }
                    canvas.nativeCanvas.drawCircle(c.x + shadow.dx * s * density, c.y + shadow.dy * s * density, size.width / 2f, paint)
                }
                drawCircle(palette.record, radius = size.width / 2f, center = c)
                val sw = 0.585f * unit
                for (k in 0 until 24) {
                    val d = (216f - k * 5.94f) * unit
                    drawCircle(
                        if (k % 2 == 0) palette.grooveOdd else palette.grooveEven,
                        radius = d / 2f - sw / 2f,
                        center = c,
                        style = Stroke(sw),
                    )
                }
                drawCircle(palette.recordLabel, radius = 27f * unit, center = c)
            }
            // The sleeve, raised, with the cover inset 10.8.
            Box(
                Modifier
                    .size((ASSEMBLY_H * s).dp)
                    .neuSurface((22.5f * s).dp, SolidColor(palette.ground), palette.raisedSleeve.scaled(s)),
            ) {
                Artwork(
                    artworkUri = artworkUri,
                    contentDescription = albumName?.let { "Album art for $it" },
                    modifier = Modifier
                        .offset((10.8f * s).dp, (10.8f * s).dp)
                        .size((228.6f * s).dp),
                    cornerRadius = (15.3f * s).dp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Title, format and details
// ---------------------------------------------------------------------------

@Composable
internal fun NeuTitle(title: String, artist: String, palette: NeuPalette, modifier: Modifier = Modifier, titleSize: Float = 26f, centered: Boolean = false) {
    Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            title,
            style = interStyle(titleSize, FontWeight.Bold, palette.ink),
            maxLines = if (centered) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        Spacer(Modifier.height((6f * titleSize / 26f).dp))
        Text(
            artist,
            style = interStyle(15f, FontWeight.Normal, palette.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

/**
 * The format pill (113 × 30 for "LOSSLESS"): quality glyph, the tag in
 * accent caps, and a chevron; a tap opens the audio details.
 */
@Composable
internal fun NeuFormatPill(label: String, spoken: String, palette: NeuPalette, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val upper = label.uppercase()
    val textWidth = with(LocalDensity.current) {
        val m = androidx.compose.ui.text.rememberTextMeasurer()
        remember(upper, palette) { m.measure(upper, interStyle(9f, FontWeight.SemiBold, palette.accent, tracking = 0.7.sp)).size.width.toDp() }
    }
    val width = max(113f, 29f + textWidth.value + 33f).dp
    NeuPressButton(
        onClick = onClick,
        contentDescription = spoken,
        onClickLabel = "Show audio details",
        width = width, height = 30.dp, radius = 10.dp, raised = palette.raised, palette = palette,
        timing = PressTiming.Lossless, sink = 1.dp, modifier = modifier,
    ) {
        Box(Modifier.size(width, 30.dp)) {
            NeuIcon(NeuGlyph.Lossless, palette.accent, Modifier.offset(10.dp, 8.dp))
            Text(
                upper,
                style = interStyle(9f, FontWeight.SemiBold, palette.accent, tracking = 0.7.sp),
                maxLines = 1,
                modifier = Modifier.offset(29.dp, 9.dp),
            )
            NeuIcon(NeuGlyph.Chevron, palette.muted, Modifier.align(Alignment.TopEnd).offset((-9).dp, 10.dp))
        }
    }
}

/** The facts the details card lists: format, quality, bit depth and rate, bitrate. */
internal fun audioFields(format: String?, lossless: Boolean, sampleRateHz: Int?, bitDepth: Int?, kbps: Int?): List<Pair<String, String>> {
    val rate = sampleRateHz?.takeIf { it > 0 }
    val khz = rate?.let { if (it % 1000 == 0) "${it / 1000}" else "%.1f".format(java.util.Locale.ROOT, it / 1000.0) }
    return buildList {
        add("FORMAT" to (format ?: "Audio"))
        add("QUALITY" to if (lossless) "Lossless" else "Lossy")
        when {
            bitDepth != null && khz != null -> add("BIT/KHZ" to "$bitDepth/$khz")
            khz != null -> add("KHZ" to khz)
        }
        kbps?.takeIf { it > 0 }?.let { add("KBPS" to "$it") }
    }
}

/**
 * The audio details card (frames 15:74 / 15:188), 8 dp under the pill: a
 * mint rule, "AUDIO DETAILS", a close button, and the facts in 80 dp
 * columns. It dissolves in over 180 ms; a tap outside or on × closes it.
 */
@Composable
internal fun NeuAudioDetails(
    shown: Boolean,
    fields: List<Pair<String, String>>,
    palette: NeuPalette,
    width: Dp,
    anchorHeight: Dp,
    onDismiss: () -> Unit,
) {
    val still = com.harmony.core.ui.theme.reducedMotion
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = shown
    if (!visible.currentState && !visible.targetState) return
    val density = LocalDensity.current
    Popup(
        offset = with(density) { IntOffset(0, (anchorHeight + 8.dp).roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(if (still) snap() else tween(180, easing = FigmaEaseOut)),
            exit = fadeOut(if (still) snap() else tween(180, easing = FigmaEaseOut)),
        ) {
            NeuAudioDetailsCard(fields, palette, width, onDismiss, Modifier.padding(bottom = 34.dp, end = 12.dp))
        }
    }
}

/** The card itself, 345 × 104 in the frame. */
@Composable
internal fun NeuAudioDetailsCard(
    fields: List<Pair<String, String>>,
    palette: NeuPalette,
    width: Dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(width)
            .heightIn(min = 104.dp)
            .neuSurface(17.dp, SolidColor(palette.ground), palette.card)
            .semantics(mergeDescendants = true) {
                contentDescription = "Audio details: " + fields.joinToString(", ") { (k, v) -> "${k.lowercase()} $v" }
            },
    ) {
        Box(Modifier.offset(16.dp, 0.dp).width(width - 32.dp).height(2.dp).background(palette.accent, RoundedCornerShape(1.dp)))
        Text("AUDIO DETAILS", style = interStyle(9f, FontWeight.SemiBold, palette.muted, tracking = 0.9.sp), modifier = Modifier.offset(16.dp, 12.dp))
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset((-10).dp, 7.dp)
                .size(30.dp, 26.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = "Close audio details", onClick = onDismiss),
        ) { NeuIcon(NeuGlyph.Close, palette.muted, Modifier.offset(7.dp, 5.dp)) }
        val column = ((width.value - 25f) / 4f).coerceAtMost(80f)
        fields.take(4).forEachIndexed { i, (label, value) ->
            val x = (16f + i * column).dp
            if (i > 0) Box(Modifier.offset(x - 9.dp, 43.dp).size(1.dp, 40.dp).background(palette.muted.copy(alpha = 0.16f)))
            Text(label, style = interStyle(8f, FontWeight.Medium, palette.muted), maxLines = 1, modifier = Modifier.offset(x, 43.dp))
            Text(
                value,
                style = interStyle(if (label == "QUALITY") 13f else 15f, FontWeight.SemiBold, palette.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(x, 61.dp).width((column - 10f).dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The waveform
// ---------------------------------------------------------------------------

/**
 * The inset waveform channel (345 × 60, radius 17): bars 2.7 wide every
 * 4.72 from 12 in, standing on y 48; played in the accent, the rest quiet.
 * Tap or drag to seek; a screen reader sets the position directly.
 */
@Composable
internal fun NeuWaveform(
    progress: Float,
    waveform: FloatArray,
    palette: NeuPalette,
    onScrub: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit,
    onScrubCancel: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 60.dp,
    level: Float = 0f,
) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .neuSurface(17.dp, SolidColor(palette.ground), inner = palette.channel)
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target -> onScrubFinished(target.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onScrubFinished((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                var x = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start -> x = start.x; onScrub((x / size.width).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        x += amount
                        onScrub((x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubFinished((x / size.width).coerceIn(0f, 1f)) },
                    onDragCancel = { onScrubCancel() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (waveform.isEmpty()) return@Canvas
            val u = density
            val barW = 2.7f * u
            val step = 4.72f * u
            val inset = 12f * u
            val count = ((size.width - 2 * inset + (step - barW)) / step).toInt().coerceAtLeast(1)
            // The bars stand on the same line at any height: 48 of 60.
            val base = size.height * 0.8f
            val maxH = size.height * (40f / 60f)
            val scaleY = size.height / (60f * u)
            val playhead = fraction * (count - 1)
            for (i in 0 until count) {
                val src = waveform[(i.toFloat() / count * waveform.size).toInt().coerceIn(0, waveform.lastIndex)]
                val near = (1f - abs(i - playhead) / 4f).coerceIn(0f, 1f)
                val v = (src * (1f + 0.35f * level * near * near)).coerceIn(0f, 1f)
                val h = max(barW, max(8f * u * scaleY, v * maxH))
                val left = inset + i * step
                val played = (i + 0.5f) / count <= fraction
                drawRoundRect(
                    if (played) palette.accent else palette.waveRest,
                    topLeft = Offset(left, base - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The mix and the listening tools
// ---------------------------------------------------------------------------

/** "Start a mix from this song" (309 × 50, radius 17): spark in the accent, the words in ink. */
@Composable
internal fun NeuMixButton(text: String, palette: NeuPalette, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NeuPressButton(
        onClick = onClick, contentDescription = text,
        width = 309.dp, height = 50.dp, radius = 17.dp, raised = palette.raised, palette = palette,
        modifier = modifier,
    ) {
        Box(Modifier.size(309.dp, 50.dp)) {
            NeuIcon(NeuGlyph.Spark, palette.accent, Modifier.offset(18.dp, 15.dp))
            Text(
                text,
                style = interStyle(13f, FontWeight.SemiBold, palette.ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(51.dp, 16.dp).padding(end = 70.dp),
            )
        }
    }
}

/** One listening tool: a 42 × 34 raised key and its label under it. */
internal class NeuTool(
    val label: String,
    val glyph: NeuGlyph?,
    val vector: ImageVector?,
    val active: Boolean,
    val onClick: () -> Unit,
    val clickLabel: String? = null,
)

/** The listening tools (Queue, Steer, where it plays, Sound): 89 dp apart, centred. */
@Composable
internal fun NeuTools(tools: List<NeuTool>, palette: NeuPalette, modifier: Modifier = Modifier) {
    val large = LocalDensity.current.fontScale > 1.3f
    // 89 dp apart whatever the width: the outer labels may reach into the page's margins, as in the frame.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Row(Modifier.requiredWidth((89 * tools.size).dp)) {
        tools.forEach { tool ->
            val tint = if (tool.active) palette.accent else palette.muted
            Column(
                Modifier
                    .width(89.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = tool.clickLabel,
                        onClick = tool.onClick,
                    )
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(42.dp, 34.dp).neuSurface(11.dp, SolidColor(palette.ground), palette.raisedTool),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        tool.glyph != null -> NeuIcon(tool.glyph, tint)
                        tool.vector != null -> Icon(tool.vector, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    tool.label,
                    style = interStyle(10f, FontWeight.Medium, tint),
                    maxLines = if (large) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(86.dp),
                )
            }
        }
    }
    }
}
