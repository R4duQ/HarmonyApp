package com.harmony.feature.player.neu

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.harmony.core.ui.component.Artwork
import kotlinx.coroutines.launch
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

/** IBM Plex Mono Medium at [size], 130 % line height. */
internal fun plexStyle(size: Float, color: Color, tracking: TextUnit = TextUnit.Unspecified) = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.Medium,
    fontSize = size.sp,
    lineHeight = (size * 1.3f).sp,
    letterSpacing = tracking,
    color = color,
)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** A flat surface with its 1 dp edge inside, as every key in the frames has it. */
internal fun Modifier.neuKey(fill: Color, line: Color, radius: Dp): Modifier =
    background(fill, RoundedCornerShape(radius)).border(1.dp, line, RoundedCornerShape(radius))

// ---------------------------------------------------------------------------
// Keys
// ---------------------------------------------------------------------------

/**
 * A key that dips when pressed (back, previous, next, more, favourite, the
 * mix button): the surface turns lavender and moves down [sink], the glyph
 * shifts by [pressedShiftX] and fades to [pressedAlpha], as the Pressed
 * variants have it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NeuPressButton(
    onClick: () -> Unit,
    contentDescription: String,
    width: Dp,
    height: Dp,
    radius: Dp,
    palette: NeuPalette,
    modifier: Modifier = Modifier,
    timing: PressTiming = PressTiming.Standard,
    sink: Dp = 1.5.dp,
    pressedShiftX: Dp = 0.dp,
    pressedAlpha: Float = 1f,
    fill: Color = palette.surface,
    line: Color = palette.line,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** How much of the pressed look shows (0 hides it: previous while it replays). */
    pressLook: Float = 1f,
    /** Holds the key at this much pressed (previews and the Figma comparison). */
    pressedOverride: Float? = null,
    content: @Composable (pressed: Float) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val p = (pressedOverride ?: pressProgress(source, timing)) * pressLook
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
                .neuKey(lerp(fill, palette.pressed, p), line, radius),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.graphicsLayer {
                    translationX = pressedShiftX.toPx() * p
                    alpha = lerp(1f, pressedAlpha, p)
                },
            ) { content(p) }
        }
    }
}

/**
 * Shuffle and repeat (40 dp): On turns the key lavender, the glyph takes the
 * accent and a 3 dp dot shows under it; 180 ms between them.
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
    dot: Color = palette.accent,
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
            .neuKey(lerp(palette.surface, palette.pressed, p), palette.line, 20.dp),
    ) {
        NeuIcon(glyph, lerp(palette.ink, palette.accent, p), Modifier.offset(11.dp, 11.dp), size = 18.dp)
        Box(
            Modifier
                .offset(18.5.dp, 34.dp)
                .size(3.dp)
                .graphicsLayer { alpha = p }
                .background(dot, RoundedCornerShape(1.5.dp)),
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
 * Play / pause (76 dp) in its 88 dp ring (light). [look] is 0 for Paused
 * (charcoal, the play glyph) and 1 for Playing (the deeper violet, the pause
 * glyph, 1.5 dp lower); 180 ms between them, or the record swap's timing.
 */
@Composable
internal fun NeuPlayButton(
    look: Float,
    onClick: () -> Unit,
    contentDescription: String,
    palette: NeuPalette,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(88.dp)) {
        palette.surround?.let { Box(Modifier.fillMaxSize().border(1.dp, it, CircleShape)) }
        Box(
            Modifier
                .offset(6.dp, 6.dp)
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
                    .graphicsLayer { translationY = 1.5.dp.toPx() * look }
                    .background(lerp(palette.playPaused, palette.playPlaying, look), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NeuIcon(NeuGlyph.Play, palette.playIcon, Modifier.graphicsLayer { alpha = 1f - look })
                NeuIcon(NeuGlyph.Pause, palette.playIcon, Modifier.graphicsLayer { alpha = look })
            }
        }
    }
}

/**
 * Previous (54 dp). More than five seconds into a song it shows the replay
 * arrow ([replay] 1) and takes you back to the start without the pressed
 * look; otherwise the skip glyph, and it presses like next.
 */
@Composable
internal fun NeuPreviousButton(
    replay: Float,
    onClick: () -> Unit,
    contentDescription: String,
    palette: NeuPalette,
    modifier: Modifier = Modifier,
    pressedOverride: Float? = null,
) {
    NeuPressButton(
        onClick = onClick, contentDescription = contentDescription,
        width = 54.dp, height = 54.dp, radius = 27.dp, palette = palette,
        timing = PressTiming.Skip,
        pressedShiftX = (-2.5).dp, pressedAlpha = 0.9f,
        pressLook = 1f - replay,
        pressedOverride = pressedOverride,
        modifier = modifier,
    ) {
        Box(Modifier.size(23.dp)) {
            NeuIcon(NeuGlyph.Previous, palette.ink, Modifier.graphicsLayer { alpha = 1f - replay })
            NeuIcon(NeuGlyph.Replay, palette.ink, Modifier.graphicsLayer { alpha = replay })
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/** "NOW PLAYING FROM" over the album, between the back and more keys, over a hairline rule (76 dp). */
@Composable
internal fun NeuHeader(
    albumName: String?,
    outgoingAlbum: String?,
    settle: Float,
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
            width = 42.dp, height = 42.dp, radius = 21.dp, palette = palette,
            pressedShiftX = (-2.5).dp, modifier = Modifier.offset(y = 12.dp),
        ) { NeuIcon(NeuGlyph.Back, palette.ink) }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 52.dp, end = 52.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "NOW PLAYING FROM",
                style = plexStyle(9f, palette.muted, tracking = 0.63.sp),
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.3.dp))
            Box(contentAlignment = Alignment.Center) {
                val style = interStyle(12f, FontWeight.Medium, palette.ink)
                if (outgoingAlbum != null) {
                    Text(outgoingAlbum, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer { alpha = 1f - settle })
                }
                Text(
                    albumName ?: "Nothing playing",
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { alpha = if (outgoingAlbum != null) settle else 1f },
                )
            }
        }
        Row(Modifier.align(Alignment.TopEnd).offset(y = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            trailing()
            Box {
                NeuPressButton(
                    onClick = onMore, contentDescription = "More options",
                    width = 42.dp, height = 42.dp, radius = 21.dp, palette = palette,
                    pressedAlpha = 0.6f,
                ) { NeuIcon(NeuGlyph.More, palette.ink) }
                menu()
            }
        }
        Box(Modifier.offset(y = 64.dp).fillMaxWidth().height(1.dp).background(palette.line))
    }
}

/** Favourite: a 42 dp key; the heart fills with the accent when the song is a favourite. */
@Composable
internal fun NeuFavoriteButton(isFavorite: Boolean, onToggle: () -> Unit, palette: NeuPalette, modifier: Modifier = Modifier) {
    val p = stateProgress(isFavorite, "favorite")
    NeuPressButton(
        onClick = onToggle,
        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
        width = 42.dp, height = 42.dp, radius = 21.dp, palette = palette,
        modifier = modifier,
    ) {
        Box {
            NeuIcon(NeuGlyph.Heart, palette.ink, Modifier.graphicsLayer { alpha = 1f - p })
            NeuIcon(NeuGlyph.Heart, palette.accent, Modifier.graphicsLayer { alpha = p }, filled = true)
        }
    }
}

// ---------------------------------------------------------------------------
// The record
// ---------------------------------------------------------------------------

/** The assembly in the frame: the 229 dp cover and the 214 dp record beside it, 336 across. */
internal const val ASSEMBLY_W = 336f
internal const val ASSEMBLY_H = 229f

/** The album section's own height in the frame; the assembly sits 28.6 dp down it. */
internal const val ALBUM_SECTION_H = 288f
private const val ALBUM_TOP = 28.6f

/** The record, relative to the cover. */
private const val RECORD_X = 122f
private const val RECORD_Y = 7.5f
private const val RECORD_D = 214f

/**
 * The cover with the record behind it (Figma's "Album and record
 * assembly"): the record's 24 grooves, its two highlights and its label
 * turn with [spin] and show with [gloss]; [tuck] slides it under the cover.
 * The cover cross-fades from [outgoingArt] to [artworkUri] with [settle].
 * Scales down to fit, never up.
 */
@Composable
internal fun NeuRecord(
    artworkUri: String?,
    outgoingArt: String?,
    settle: Float,
    labelArt: String?,
    tuck: Float,
    spin: Float,
    gloss: Float,
    albumName: String?,
    palette: NeuPalette,
    modifier: Modifier = Modifier,
) {
    val bias = ALBUM_TOP / (ALBUM_SECTION_H - ASSEMBLY_H) * 2f - 1f
    BoxWithConstraints(modifier, contentAlignment = androidx.compose.ui.BiasAlignment(-1f, bias)) {
        val s = min(min(maxWidth.value / ASSEMBLY_W, maxHeight.value / ASSEMBLY_H), 1f).coerceAtLeast(0.1f)
        Box(Modifier.size((ASSEMBLY_W * s).dp, (ASSEMBLY_H * s).dp)) {
            Box(
                Modifier
                    .offset((RECORD_X * s).dp, (RECORD_Y * s).dp)
                    .graphicsLayer { translationX = -TUCK_DP * s * density * tuck }
                    .size((RECORD_D * s).dp)
                    .clearAndSetSemantics {},
            ) {
                RecordDisc(s, palette)
                // The face that turns: the highlights and the label.
                Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin }) {
                    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = gloss }) { drawHighlights(s) }
                    Artwork(
                        artworkUri = labelArt,
                        contentDescription = null,
                        cornerRadius = (21.2155f * s).dp,
                        modifier = Modifier
                            .offset((85.78f * s).dp, (85.78f * s).dp)
                            .size((42.431f * s).dp),
                    )
                    Box(
                        Modifier
                            .offset((85.78f * s).dp, (85.78f * s).dp)
                            .size((42.431f * s).dp)
                            .border((0.75f * s).dp, Color.White.copy(alpha = 0.16f), CircleShape),
                    )
                }
                // The spindle hole stays put.
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = 2.306f * s * density
                    drawCircle(Color(0xFF15191A), r, c)
                    drawCircle(Color.White.copy(alpha = 0.45f), r + 0.375f * s * density, c, style = Stroke(0.75f * s * density))
                }
            }
            Box(Modifier.size((ASSEMBLY_H * s).dp)) {
                if (outgoingArt != null || settle < 1f) {
                    Artwork(outgoingArt, contentDescription = null, cornerRadius = 0.dp, modifier = Modifier.fillMaxSize())
                }
                Artwork(
                    artworkUri = artworkUri,
                    contentDescription = albumName?.let { "Album art for $it" },
                    cornerRadius = 0.dp,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (outgoingArt != null) settle else 1f },
                )
            }
        }
    }
}

/** The disc, its shadow and its grooves (the parts that don't turn). */
@Composable
private fun RecordDisc(s: Float, palette: NeuPalette) {
    Canvas(Modifier.fillMaxSize()) {
        val unit = size.width / RECORD_D
        val c = Offset(size.width / 2f, size.height / 2f)
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = palette.recordShadow.toArgb()
                maskFilter = android.graphics.BlurMaskFilter(
                    blurRadiusPx(12.6f * s, density),
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
            }
            canvas.nativeCanvas.drawCircle(c.x + 5.4f * unit, c.y + 6.3f * unit, size.width / 2f, paint)
        }
        drawCircle(palette.record, radius = size.width / 2f, center = c)
        val sw = 0.585f * unit
        for (k in 0 until 24) {
            val d = (205.44f - k * 5.6497f) * unit
            drawCircle(
                if (k % 2 == 0) palette.grooveOdd else palette.grooveEven,
                radius = d / 2f - sw / 2f,
                center = c,
                style = Stroke(sw),
            )
        }
    }
}

/** The groove highlights: two hairline arcs (0.16–0.86 rad on 187, 4.12–4.58 rad on 177), mint at 16 %. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHighlights(s: Float) {
    val unit = size.width / RECORD_D
    val color = Color(0xFFB3D7CE).copy(alpha = 0.1647f)
    fun arc(d: Float, inner: Float, from: Float, to: Float) {
        val r = d / 2f * unit
        val w = r * (1f - inner)
        val rr = r - w / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawArc(
            color,
            startAngle = Math.toDegrees(from.toDouble()).toFloat(),
            sweepAngle = Math.toDegrees((to - from).toDouble()).toFloat(),
            useCenter = false,
            topLeft = Offset(c.x - rr, c.y - rr),
            size = Size(rr * 2f, rr * 2f),
            style = Stroke(w),
        )
    }
    arc(187f, 0.99465f, 0.16f, 0.86f)
    arc(177f, 0.99435f, 4.12f, 4.58f)
}

// ---------------------------------------------------------------------------
// Title, format and details
// ---------------------------------------------------------------------------

/** Title (Inter Bold 30, -3 %) over the artist (Inter 14), cross-fading from [outgoing] with [settle]. */
@Composable
internal fun NeuTitle(
    face: SongFace,
    outgoing: SongFace?,
    settle: Float,
    palette: NeuPalette,
    modifier: Modifier = Modifier,
    titleSize: Float = 30f,
    centered: Boolean = false,
) {
    @Composable
    fun Lines(f: SongFace, alpha: Float) {
        Column(
            Modifier.graphicsLayer { this.alpha = alpha },
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(
                f.title,
                style = interStyle(titleSize, FontWeight.Bold, palette.ink, tracking = (-0.03f * titleSize).sp),
                maxLines = if (centered) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
            Text(
                f.artist,
                style = interStyle(14f, FontWeight.Normal, palette.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
        }
    }
    Box(modifier) {
        if (outgoing != null) Lines(outgoing, 1f - settle)
        Lines(face, if (outgoing != null) settle else 1f)
    }
}

/**
 * The format key ("LOSSLESS", 132 × 36 on a 44 dp target): quality glyph,
 * the tag in Plex Mono, a divider and a chevron that turns up while pressed.
 */
@Composable
internal fun NeuFormatPill(
    label: String,
    spoken: String,
    palette: NeuPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pressedOverride: Float? = null,
) {
    val upper = label.uppercase()
    val style = plexStyle(10f, palette.ink, tracking = 0.7.sp)
    val measurer = rememberTextMeasurer()
    val textWidth = with(LocalDensity.current) { remember(upper, palette) { measurer.measure(upper, style).size.width.toDp() } }
    val width = max(132f, textWidth.value + 79f).dp
    val source = remember { MutableInteractionSource() }
    val p = pressedOverride ?: pressProgress(source, PressTiming.Lossless)
    Box(
        modifier
            .size(width, 44.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Show audio details", onClick = onClick)
            .semantics { contentDescription = spoken },
    ) {
        Box(
            Modifier
                .offset(y = 4.dp)
                .graphicsLayer { translationY = 1.dp.toPx() * p }
                .size(width, 36.dp)
                .neuKey(lerp(palette.pill, palette.pressed, p), palette.pillLine, 8.dp),
        ) {
            NeuIcon(NeuGlyph.Lossless, palette.accent, Modifier.offset(11.dp, 11.dp))
            Text(upper, style = style, maxLines = 1, modifier = Modifier.offset(33.dp, 11.dp))
            Box(Modifier.offset(width - 31.dp, 10.dp).size(1.dp, 16.dp).background(palette.pillLine))
            NeuIcon(NeuGlyph.Chevron, palette.muted, Modifier.offset(width - 21.dp, 13.dp).graphicsLayer { rotationZ = 180f * p })
        }
    }
}

/** One fact on the details card. */
internal class AudioFact(val label: String, val value: String)

/** The details card's four facts: codec, sample rate, bit depth and bitrate ("—" when unknown). */
internal fun audioFields(format: String?, sampleRateHz: Int?, bitDepth: Int?, kbps: Int?): List<AudioFact> {
    val rate = sampleRateHz?.takeIf { it > 0 }
    val khz = rate?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "%.1f kHz".format(java.util.Locale.ROOT, it / 1000.0) }
    return listOf(
        AudioFact("CODEC", format?.uppercase() ?: "—"),
        AudioFact("SAMPLE RATE", khz ?: "—"),
        AudioFact("BIT DEPTH", bitDepth?.takeIf { it > 0 }?.let { "$it-bit" } ?: "—"),
        AudioFact("BITRATE", kbps?.takeIf { it > 0 }?.let { "$it kbps" } ?: "—"),
    )
}

/** Where the details card goes: over the time card, 6 dp above it, as wide. */
internal class DetailsAnchor {
    var bounds by mutableStateOf<Rect?>(null)
}

/** Records the time card's place in the window for the details card. */
internal fun Modifier.detailsAnchor(anchor: DetailsAnchor): Modifier = onGloballyPositioned {
    val p = it.positionInWindow()
    anchor.bounds = Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
}

/** The card's opening, as the "Animated audio details" component plays it. */
internal class DetailsPhases {
    /** Start → Expanded (205 ms ease out): the bar opens into the card. */
    val open = Animatable(0f)
    /** → Primary specs (135 ms ease in and out): the divider and codec / sample rate. */
    val primary = Animatable(0f)
    /** → All specs (130 ms ease in and out): bit depth and bitrate. */
    val rest = Animatable(0f)

    suspend fun play(still: Boolean) {
        if (still) { open.snapTo(1f); primary.snapTo(1f); rest.snapTo(1f); return }
        open.snapTo(0f); primary.snapTo(0f); rest.snapTo(0f)
        kotlinx.coroutines.delay(12)
        open.animateTo(1f, tween(205, easing = FigmaEaseOut))
        kotlinx.coroutines.delay(12)
        primary.animateTo(1f, tween(135, easing = FigmaEaseInOut))
        kotlinx.coroutines.delay(12)
        rest.animateTo(1f, tween(130, easing = FigmaEaseInOut))
    }
}

/**
 * The audio details overlay (frames 15:74 / 15:188): the page dims, and the
 * card opens over the time card in three steps. A tap outside or on ×
 * closes it. The overlay dissolves in and out over 100 ms.
 */
@Composable
internal fun NeuAudioDetails(
    shown: Boolean,
    fields: List<AudioFact>,
    palette: NeuPalette,
    anchor: DetailsAnchor,
    onDismiss: () -> Unit,
) {
    val still = com.harmony.core.ui.theme.reducedMotion
    val fade = remember { Animatable(0f) }
    val phases = remember { DetailsPhases() }
    var present by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown) {
            present = true
            kotlinx.coroutines.coroutineScope {
                launch { fade.animateTo(1f, if (still) snap() else tween(100, easing = FigmaEaseOut)) }
                launch { phases.play(still) }
            }
        } else if (present) {
            fade.animateTo(0f, if (still) snap() else tween(100, easing = FigmaEaseOut))
            present = false
        }
    }
    if (!present) return
    val bounds = anchor.bounds ?: return
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade.value }
                .background(palette.scrim)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            with(density) {
                NeuAudioDetailsCard(
                    fields, palette, bounds.width.toDp(), phases, onDismiss,
                    Modifier.offset { IntOffset(bounds.left.toInt(), (bounds.top - 6.dp.toPx()).toInt()) },
                )
            }
        }
    }
}

/**
 * The card (345 × 154): a lavender square and "AUDIO DETAILS", ×, a rule,
 * then codec and sample rate, bit depth and bitrate in two columns.
 * [phases] carries it from the 49 dp bar to the whole card.
 */
@Composable
internal fun NeuAudioDetailsCard(
    fields: List<AudioFact>,
    palette: NeuPalette,
    width: Dp,
    phases: DetailsPhases,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = phases.open.value
    val primary = phases.primary.value
    val rest = phases.rest.value
    Box(modifier.size(width, 154.dp)) {
        Box(
            Modifier
                .offset(y = (13f * (1f - open)).dp)
                .size(width, (49f + 105f * open).dp)
                .graphicsLayer { alpha = 0.86f + 0.14f * open }
                .clip(RoundedCornerShape(13.dp))
                .neuKey(palette.pill, palette.pillLine, 13.dp)
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Audio details: " + fields.joinToString(", ") { "${it.label.lowercase()} ${it.value}" }
                },
        ) {
            Box(Modifier.offset(16.dp, 21.dp).size(7.dp).background(palette.accent, RoundedCornerShape(2.dp)))
            Text("AUDIO DETAILS", style = plexStyle(11f, palette.ink, tracking = 0.33.sp), modifier = Modifier.offset(31.dp, 17.dp))
            Box(
                Modifier
                    .offset(width - 50.dp, 3.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = "Close audio details" },
            ) { NeuIcon(NeuGlyph.Close, palette.ink, Modifier.offset(14.dp, 14.dp)) }
            Box(Modifier.offset(16.dp, 47.dp).size(width - 32.dp, 1.dp).graphicsLayer { alpha = open }.background(palette.pillLine))
            val half = (width.value / 2f).dp
            Box(Modifier.offset(half - 0.5.dp, 58.dp).size(1.dp, 76.dp).graphicsLayer { alpha = primary }.background(palette.pillLine))
            @Composable
            fun Fact(f: AudioFact?, x: Dp, y: Float, alpha: Float, accent: Boolean = false) {
                if (f == null) return
                Box(Modifier.offset(x, y.dp).graphicsLayer { this.alpha = alpha }) {
                    Text(f.label, style = plexStyle(9f, palette.muted, tracking = 0.27.sp), maxLines = 1)
                    Text(
                        f.value,
                        style = plexStyle(15f, if (accent) palette.accent else palette.ink),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.offset(y = 13.dp).width(half - 26.dp),
                    )
                }
            }
            val right = half + 8.5.dp
            Fact(fields.getOrNull(0), 16.dp, 65f - 8f * primary, primary, accent = true)
            Fact(fields.getOrNull(1), right, 65f - 8f * primary, primary)
            Fact(fields.getOrNull(2), 16.dp, 108f - 8f * rest, rest)
            Fact(fields.getOrNull(3), right, 108f - 8f * rest, rest)
        }
    }
}

// ---------------------------------------------------------------------------
// The time card
// ---------------------------------------------------------------------------

/**
 * The time card (345 × 138, radius 12): "ELAPSED" over the time in a
 * dot-matrix face, the waveform standing on a hairline (bars 2.15 wide
 * every 4.7, played in the accent), and the start and the length under
 * it. Tap or drag the waveform to seek; a screen reader sets the position.
 */
@Composable
internal fun NeuTimeCard(
    elapsed: String,
    elapsedAlpha: Float,
    elapsedMs: Int,
    duration: String,
    outgoingDuration: String?,
    settle: Float,
    progress: Float,
    waveform: FloatArray,
    palette: NeuPalette,
    onScrub: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit,
    onScrubCancel: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    level: Float = 0f,
) {
    val fraction = progress.coerceIn(0f, 1f)
    val timeSize = if (compact) 36f else 56f
    val drop = if (compact) 20f else 0f
    val height = (138f - drop).dp
    // The card is laid out to the dp; its small labels grow with the system font only so far.
    val fontScale = LocalDensity.current.fontScale
    val labelSp = (10f * min(fontScale, 1.3f) / fontScale).sp
    val small = plexStyle(10f, palette.muted).copy(fontSize = labelSp, lineHeight = (13f / fontScale).sp)
    Box(modifier.fillMaxWidth().height(height).neuKey(palette.surface, palette.line, 12.dp)) {
        Text("ELAPSED", style = small.copy(letterSpacing = 0.6.sp), modifier = Modifier.offset(14.dp, 11.dp))
        // The time keeps its size in dp: it is a display, and the card has room for no more.
        val px = with(LocalDensity.current) { timeSize.dp.toSp() }
        val line = with(LocalDensity.current) { (timeSize + 2f).dp.toSp() }
        Crossfade(
            targetState = elapsed,
            animationSpec = tween(elapsedMs, easing = if (elapsedMs > 200) FigmaEaseInOut else FigmaEaseOut),
            label = "elapsed",
            // Bitcount's line box sits 6 dp lower than Figma sets it at y 19 (in proportion when smaller).
            modifier = Modifier.offset(14.dp, (if (compact) 24f - 6f * timeSize / 56f else 13f).dp).graphicsLayer { alpha = elapsedAlpha },
        ) { text ->
            Text(
                text,
                style = TextStyle(fontFamily = Bitcount, fontWeight = FontWeight.SemiBold, fontSize = px, lineHeight = line, letterSpacing = (-0.01f * timeSize).sp, color = palette.ink),
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "Elapsed $text" },
            )
        }
        Box(
            Modifier
                .offset(y = (78f - drop).dp)
                .fillMaxWidth()
                .height(40.dp)
                .semantics {
                    contentDescription = "Playback position"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target -> onScrubFinished(target.coerceIn(0f, 1f)); true }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset -> onScrubFinished(((offset.x - 14.dp.toPx()) / (size.width - 28.dp.toPx())).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    var x = 0f
                    fun at(v: Float) = ((v - 14.dp.toPx()) / (size.width - 28.dp.toPx())).coerceIn(0f, 1f)
                    detectHorizontalDragGestures(
                        onDragStart = { start -> x = start.x; onScrub(at(x)) },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            x += amount
                            onScrub(at(x))
                        },
                        onDragEnd = { onScrubFinished(at(x)) },
                        onDragCancel = { onScrubCancel() },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val u = density
                // The baseline, 1 dp, under the bars' feet.
                drawRect(palette.line, topLeft = Offset(14f * u, 36f * u), size = Size(size.width - 28f * u, 1f * u))
                if (waveform.isEmpty()) return@Canvas
                val barW = 2.15f * u
                val step = 4.7f * u
                val inset = 14f * u
                val count = ((size.width - 2 * inset - barW) / step).toInt().coerceAtLeast(0) + 1
                val base = 37f * u
                val playhead = fraction * (count - 1)
                for (i in 0 until count) {
                    val src = waveform[(i.toFloat() / count * waveform.size).toInt().coerceIn(0, waveform.lastIndex)]
                    val near = (1f - kotlin.math.abs(i - playhead) / 4f).coerceIn(0f, 1f)
                    val v = (src * (1f + 0.35f * level * near * near)).coerceIn(0f, 1f)
                    val h = max(7f * u, v * 34.3f * u)
                    val played = (i + 0.5f) / count <= fraction
                    drawRoundRect(
                        if (played) palette.accent else palette.waveRest,
                        topLeft = Offset(inset + i * step, base - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(0.4f * u),
                    )
                }
            }
        }
        Text("0:00", style = small.copy(letterSpacing = 0.6.sp), modifier = Modifier.offset(14.dp, (121f - drop).dp))
        Box(Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = (121f - drop).dp)) {
            if (outgoingDuration != null) Text(outgoingDuration, style = small, modifier = Modifier.graphicsLayer { alpha = 1f - settle })
            Text(duration, style = small, modifier = Modifier.graphicsLayer { alpha = if (outgoingDuration != null) settle else 1f })
        }
    }
}

// ---------------------------------------------------------------------------
// The mix and the listening tools
// ---------------------------------------------------------------------------

/** "Start a mix from this song" (309 × 50, radius 12): spark in the accent, the words in ink. */
@Composable
internal fun NeuMixButton(text: String, palette: NeuPalette, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NeuPressButton(
        onClick = onClick, contentDescription = text,
        width = 309.dp, height = 50.dp, radius = 12.dp, palette = palette,
        sink = 1.dp,
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

/** One listening tool: a 42 × 34 key and its label under it. */
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
                val source = remember { MutableInteractionSource() }
                val p = pressProgress(source, PressTiming.Standard)
                Column(
                    Modifier
                        .width(89.dp)
                        .clickable(
                            interactionSource = source,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = tool.clickLabel,
                            onClick = tool.onClick,
                        )
                        .semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tint = if (tool.active) palette.accent else palette.ink
                    Box(
                        Modifier
                            .size(42.dp, 34.dp)
                            .graphicsLayer { translationY = 1.5.dp.toPx() * p }
                            .neuKey(lerp(if (tool.active) palette.pressed else palette.surface, palette.pressed, p), palette.line, 17.dp),
                    ) {
                        val icon = Modifier.offset(11.dp, 7.dp)
                        when {
                            tool.glyph != null -> NeuIcon(tool.glyph, tint, icon)
                            tool.vector != null -> Icon(tool.vector, contentDescription = null, tint = tint, modifier = icon.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(
                        tool.label,
                        style = interStyle(10f, FontWeight.Medium, if (tool.active) palette.accent else palette.muted),
                        maxLines = if (large) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(86.dp),
                    )
                    // The row is 60 in the frame: 34 key, 9, the 13 label, 4.
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
