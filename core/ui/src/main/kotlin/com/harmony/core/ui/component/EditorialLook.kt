package com.harmony.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.harmony.core.ui.theme.Catalog
import com.harmony.core.ui.theme.CyberGlassColors
import com.harmony.core.ui.theme.CyberGlassPalette
import com.harmony.core.ui.theme.isCyberGlass

/**
 * The colours a screen hands its components: the page, its ink, and the one
 * structure colour ([accent]). Every section gets the same values now, from
 * the catalogue ([catalogPalette]); the type is kept because most components
 * take one.
 */
@Immutable
data class EditorialPalette(
    /** The full-bleed field the screen sits on. */
    val field: Color,
    /** Titles, borders, primary icons. */
    val ink: Color,
    /** Subtitles, counts, inactive tabs. */
    val muted: Color,
    /** Hairline weight ink for card outlines. */
    val line: Color,
    /** Filled chips: the Play pill, the selected tab's count badge. */
    val accent: Color,
    val onAccent: Color,
)

/**
 * How much bottom clearance a screen's scrollable content needs to leave so
 * its last items can scroll fully clear of the floating mini player + nav
 * bar.
 *
 * That chrome is no longer reserved as layout space by Scaffold — it now
 * floats as a transparent overlay on top of the content (see HarmonyApp),
 * which is what lets its glass show real content through it. The trade is
 * that nothing pushes a screen's own LazyColumn/LazyVerticalGrid content up
 * out of the way anymore; each screen has to reserve this itself via
 * contentPadding, or its last rows sit permanently behind the mini player's
 * opaque yellow and are impossible to scroll into full view.
 *
 * ~190dp covers the mini player (~74dp incl. its own vertical inset), the
 * 10dp gap, the nav bar (~78dp incl. its own vertical inset), plus a margin
 * — tune if either component's own height changes.
 */
val FloatingChromeClearance = 190.dp

/**
 * Clearance for screens that show the mini player but NOT the nav bar —
 * detail pages, Settings, the equalizer, anything reached by pushing onto
 * the back stack rather than by tapping a tab.
 *
 * Separate from [FloatingChromeClearance] because reusing that here would
 * reserve the nav bar's height on screens that never draw one, leaving an
 * obvious dead gap under the last item.
 */
val MiniPlayerClearance = 96.dp

/** Breathing room between a screen's last row and the floating chrome. */
val ContentBottomSpacing = 16.dp

/**
 * Bottom spacing a screen must reserve for the floating chrome, published
 * by the app shell as the chrome's real measured height.
 *
 * The page itself runs the full height, behind the mini player and nav bar
 * — that is what keeps them reading as pills floating over content rather
 * than as two ends of an opaque slab. This value is what stops anything
 * from being stranded under them: with it reserved, the last row can always
 * be scrolled clear of the glass, it just passes beneath on the way.
 *
 * Measured rather than assumed, because the height moves with the download
 * banner, whether the nav bar exists on this route, and the device's
 * gesture inset — every screen that once hardcoded a number was wrong most
 * of the time.
 */
val LocalFloatingChromeHeight = compositionLocalOf { ContentBottomSpacing }

/**
 * The mini player's surface: the catalogue's solid block, the same on every
 * screen and never see-through, so the title on it reads at a glance in a
 * moving car whatever scrolls underneath.
 */
@Composable
fun miniPlayerField(): Color = Catalog.block

/** The mini player's edge: none needed on a solid block; kept transparent for callers. */
val MiniPlayerRim = Color.Transparent

/** Ink on the mini player block. */
val MiniPlayerInk: Color
    @Composable get() = Catalog.onBlock

/** Secondary text on the mini player block. */
val MiniPlayerMuted: Color
    @Composable get() = Catalog.onBlockMuted

/** Selected segment (Songs / Albums / Artists): catalogue blue. */
val SegmentSelectedField: Color
    @Composable get() = Catalog.blue
val SegmentSelectedInk: Color
    @Composable get() = Catalog.onBlue

/** Unselected segments: the raised surface. */
val SegmentField: Color
    @Composable get() = Catalog.raised
val SegmentInk: Color
    @Composable get() = Catalog.ink

/** A tab with nothing in it yet. */
val SegmentFieldMuted: Color
    @Composable get() = Catalog.ground
val SegmentInkMuted: Color
    @Composable get() = Catalog.muted

/**
 * Every section's palette: the catalogue. Sections used to own a hue each
 * (amber Home, green Playlists, coral Discover...); they are told apart by
 * their titles now, and the one structure colour means the same thing on
 * every screen. The named functions stay so screens and tests compile.
 */
@Composable
fun catalogPalette(): EditorialPalette {
    val c = Catalog
    return EditorialPalette(field = c.ground, ink = c.ink, muted = c.muted, line = c.line, accent = c.blue, onAccent = c.onBlue)
}

@Composable
fun amberPalette(): EditorialPalette = catalogPalette()

/** The library's palette. */
@Composable
fun libraryPalette(): EditorialPalette = catalogPalette()

@Composable
fun greenPalette(): EditorialPalette = catalogPalette()

/** Equalizer. */
@Composable
fun bluePalette(): EditorialPalette = catalogPalette()

/** Settings, the tag editor and the FLAC check. */
@Composable
fun stonePalette(): EditorialPalette = catalogPalette()

/** Downloads. */
@Composable
fun tealPalette(): EditorialPalette = catalogPalette()

/** Discover. */
@Composable
fun coralPalette(): EditorialPalette = catalogPalette()

/** Recognize. */
@Composable
fun lavenderPalette(): EditorialPalette = catalogPalette()

/**
 * Cross-fades every color in a palette at once, so moving between sections
 * carries the chrome (mini player, navigation) with it instead of snapping
 * from one section's colors to the next.
 */
@Composable
fun animatedEditorialPalette(
    target: EditorialPalette,
    durationMillis: Int = 260,
): EditorialPalette {
    val spec = tween<Color>(durationMillis)
    val field by animateColorAsState(target.field, spec, label = "palette-field")
    val ink by animateColorAsState(target.ink, spec, label = "palette-ink")
    val muted by animateColorAsState(target.muted, spec, label = "palette-muted")
    val line by animateColorAsState(target.line, spec, label = "palette-line")
    val accent by animateColorAsState(target.accent, spec, label = "palette-accent")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "palette-on-accent")
    return EditorialPalette(field, ink, muted, line, accent, onAccent)
}

/**
 * The outlined rounded card everything on these screens lives in. Fill is
 * the field itself — the outline does all the work, which is what gives the
 * design its printed-poster flatness.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorialCard(
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(EDITORIAL_CARD_RADIUS)
    val cyber = palette.isCyberGlass
    // Cyber-Glass: a dark glass pane with a thin slate-cyan rim instead of
    // the printed outline.
    val border = if (cyber) BorderStroke(1.dp, CyberGlassColors.ChipBorder) else BorderStroke(1.5.dp, palette.line)
    val fill = if (cyber) CyberGlassColors.Surface else palette.field
    // Filled with the field rather than transparent: visually identical
    // against the page, but opaque, so anything revealed behind a card
    // (the swipe-to-queue affordance) can't bleed through it.
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = fill,
            contentColor = palette.ink,
            border = border,
        ) { content() }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = fill,
            contentColor = palette.ink,
            border = border,
        ) { content() }
    }
}

/**
 * A [EditorialCard] drop-in for the iOS-glass sections (currently
 * Downloads): same signature, a lifted translucent pane instead of a flat
 * field with a hairline border.
 *
 * Deliberately its own composable rather than a parameter on EditorialCard:
 * a card that lets real content show through it only makes sense where
 * that content is worth seeing. EditorialCard's other callers (Playlists,
 * EQ, Settings) have nothing behind them worth revealing, and their opaque
 * fill is doing real work — it's what keeps the swipe-to-queue affordance
 * from bleeding through a song row's card. Glass only where translucency
 * earns something.
 *
 * The depth comes from three cues stacked, which is what separates a pane
 * of glass from a tinted rectangle:
 *
 * A cast shadow, tinted with the section's own ink rather than black, so
 * the card sits above the page instead of being painted onto it. Black
 * shadows over a coloured field read as grime; an ink-tinted one reads as
 * the card's own shadow.
 *
 * A vertical fill gradient, lighter at the top. This is the whole "2.5D"
 * effect in one modifier: a flat fill tells you nothing about which way is
 * up, while a fill that brightens toward the top implies a light source
 * above and therefore a surface with a thickness facing it.
 *
 * A rim that is bright along the top edge and fades to almost nothing at
 * the bottom — the lit edge of the pane catching that same light. A rim of
 * even brightness all the way round would flatten the other two cues back
 * out, because a real edge is never equally lit on every side.
 */
@Composable
fun GlassCard(
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(EDITORIAL_CARD_RADIUS)
    val onDarkField = palette.field.luminance() < 0.5f
    // A narrow range. 0.76 -> 0.44 was wide enough that the bottom of the
    // card drifted noticeably dirtier than the top, which reads as a
    // smudge rather than as a lit surface.
    val surface = Brush.verticalGradient(
        listOf(glassFill(palette, strength = 0.72f), glassFill(palette, strength = 0.58f)),
    )
    val rim = BorderStroke(
        1.dp,
        Brush.verticalGradient(
            if (onDarkField) {
                listOf(
                    Color.White.copy(alpha = 0.20f),
                    Color.White.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.18f),
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.78f),
                    Color.White.copy(alpha = 0.20f),
                    palette.ink.copy(alpha = 0.07f),
                )
            },
        ),
    )
    val lifted = modifier.shadow(
        elevation = 14.dp,
        shape = shape,
        clip = false,
        // Opaque, deliberately. Compose applies its own alpha curve to
        // these based on elevation, so passing an already-faded colour
        // multiplies the two and the shadow disappears entirely — which is
        // exactly what happened when these were set to 0.22/0.28 alpha.
        ambientColor = if (onDarkField) Color.Black else palette.ink,
        spotColor = if (onDarkField) Color.Black else palette.ink,
    )
    // Surface carries the shape, border and (when clickable) the ripple;
    // its own colour is transparent so the gradient Box inside is what
    // actually fills the card. Setting a colour here as well would paint a
    // flat layer over the gradient and undo it.
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = lifted,
            shape = shape,
            color = Color.Transparent,
            contentColor = palette.ink,
            border = rim,
        ) { Box(Modifier.background(surface)) { content() } }
    } else {
        Surface(
            modifier = lifted,
            shape = shape,
            color = Color.Transparent,
            contentColor = palette.ink,
            border = rim,
        ) { Box(Modifier.background(surface)) { content() } }
    }
}

/**
 * A recessed panel for content nested INSIDE a [GlassCard] — the provider
 * blurb, a login block, a search well.
 *
 * The lighting is deliberately the inverse of [GlassCard]'s. A raised pane
 * is bright along its top edge and casts a shadow below; something pressed
 * into that pane is shadowed along its top edge and catches light along
 * its bottom. Getting that backwards is what makes a nested panel read as
 * a second card sitting on the first rather than as a recess in it, and
 * two stacked raised cards is exactly the muddle this avoids. Note there
 * is no shadow here at all — an inset does not cast one.
 */
@Composable
fun GlassInsetPanel(
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    content: @Composable () -> Unit,
) {
    // On a dark field, "recessed" cannot mean "more ink" — ink IS the light
    // colour there, so tinting with it lightens the well and closes the gap
    // to the text sitting in it. A recess in a dark surface is darker, so
    // the tint has to flip to black.
    val onDarkField = palette.field.luminance() < 0.5f
    val well = if (onDarkField) Color.Black else palette.ink
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        contentColor = palette.ink,
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(
                listOf(
                    well.copy(alpha = if (onDarkField) 0.34f else 0.14f),
                    well.copy(alpha = 0.04f),
                    Color.White.copy(alpha = if (onDarkField) 0.10f else 0.42f),
                ),
            ),
        ),
    ) {
        Box(
            Modifier.background(
                Brush.verticalGradient(
                    listOf(
                        well.copy(alpha = if (onDarkField) 0.22f else 0.075f),
                        well.copy(alpha = if (onDarkField) 0.08f else 0.015f),
                    ),
                ),
            ),
        ) { content() }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorialCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    filled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val cyber = palette.isCyberGlass
    val container = when {
        filled -> palette.accent
        cyber -> CyberGlassColors.Chip
        else -> Color.Transparent
    }
    val contentColor = when {
        filled -> palette.onAccent
        cyber -> CyberGlassColors.Cyan
        else -> palette.ink
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = container,
        contentColor = contentColor,
        border = when {
            filled -> null
            cyber -> BorderStroke(1.dp, CyberGlassColors.Iridescent)
            else -> BorderStroke(1.5.dp, palette.line)
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        }
    }
}

/** The filled pill with a glyph and a verb — "Play", "New", "Import". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorialPill(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = palette.accent,
        contentColor = palette.onAccent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** One entry in [EditorialTabs]. Count is optional — omit rather than show 0-lies. */
data class EditorialTab(
    val label: String,
    val count: Int? = null,
    /**
     * Optional leading glyph, used by [GlassSegmentedTabs]. Defaulted so
     * every existing call site that passes only a label and count still
     * compiles unchanged.
     */
    val icon: ImageVector? = null,
)

/**
 * Digits sitting dead-center in a small circle need three things that aren't
 * defaults: font padding off (Android adds asymmetric ascent/descent padding
 * that pushes short text visibly low), line height pinned to the font size,
 * and line-height trimmed at both ends so the leading isn't distributed
 * around the glyphs. Without these, a 10sp numeral in a 20dp circle sits a
 * pixel or two below center — which is exactly the sort of thing you notice
 * without being able to name it.
 */
@OptIn(ExperimentalTextApi::class)
private val BadgeNumberStyle = TextStyle(
    fontSize = 10.sp,
    lineHeight = 10.sp,
    fontWeight = FontWeight.SemiBold,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * The big text tabs with circled counts. The count badge is part of the tab
 * label (mock style), so it doubles as a glanceable summary of the library
 * — you know you have 5 artists before you've opened the tab.
 */
@Composable
fun EditorialTabs(
    tabs: List<EditorialTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selected
            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                contentColor = if (isSelected) palette.ink else palette.muted,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
                ) {
                    Text(
                        tab.label,
                        fontSize = 21.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = (-0.3).sp,
                    )
                    if (tab.count != null) {
                        val badgeModifier = Modifier
                            .padding(start = 6.dp)
                            // Circle at 1-2 digits; widens into a stadium past
                            // that rather than letting "126" spill out of a
                            // fixed 20dp box.
                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .let {
                                if (isSelected) it.background(palette.accent, CircleShape)
                                else it.border(1.dp, palette.muted, CircleShape)
                            }
                            .padding(horizontal = 5.dp)
                        Box(modifier = badgeModifier, contentAlignment = Alignment.Center) {
                            Text(
                                tab.count.toString(),
                                color = if (isSelected) palette.onAccent else palette.muted,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                style = BadgeNumberStyle,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "2hr 15min" / "48min" — the mock's album-length format. */
fun formatLongDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}hr ${minutes}min" else "${minutes}min"
}

/** Corner radius shared by cards and the affordance revealed behind them. */
val EDITORIAL_CARD_RADIUS = 18.dp

/** Fraction of the card width a swipe must cross to commit "play next". */
private const val SWIPE_QUEUE_FRACTION = 0.32f

/**
 * Wraps an [EditorialCard] in swipe-right-to-queue.
 *
 * Same contract as the gesture on the old Material [SongRow]: right-only so
 * it can't fire in the opposite direction by accident, a haptic tick the
 * moment you cross the commit threshold (so you know before you let go), and
 * a spring back afterwards — queueing doesn't remove the song from the list
 * it lives in, so the card must return to its place.
 *
 * The affordance behind is clipped to the card's own radius, so the reveal
 * looks like it's sliding out from under the card rather than out of a
 * rectangle the card happens to sit on.
 */
@Composable
fun EditorialSwipeToQueue(
    palette: EditorialPalette,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Play next",
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offsetX = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width },
    ) {
        if (offsetX.value > 1f) {
            val progress = (offsetX.value / (width * SWIPE_QUEUE_FRACTION)).coerceIn(0f, 1f)
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(EDITORIAL_CARD_RADIUS))
                    .background(palette.accent)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = palette.onAccent,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                // Settles in as the threshold approaches, so
                                // the commit point is legible mid-gesture.
                                scaleX = 0.7f + 0.3f * progress
                                scaleY = 0.7f + 0.3f * progress
                                alpha = progress
                            },
                    )
                }
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onAccent,
                    modifier = Modifier
                        .padding(start = if (icon != null) 10.dp else 0.dp)
                        .graphicsLayer { alpha = progress },
                )
            }
        }

        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(Unit) {
                    var total = 0f
                    var committed = false
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f; committed = false },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            total = (total + amount).coerceAtLeast(0f)
                            scope.launch { offsetX.snapTo(total) }
                            if (!committed && total >= width * SWIPE_QUEUE_FRACTION) {
                                committed = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDragEnd = {
                            val shouldQueue = total >= width * SWIPE_QUEUE_FRACTION
                            scope.launch {
                                if (shouldQueue) {
                                    onQueue()
                                    // Brief settle at the threshold so the
                                    // confirmation is visible, then return.
                                    offsetX.animateTo(width * SWIPE_QUEUE_FRACTION, tween(90))
                                }
                                offsetX.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                    )
                },
        ) { content() }
    }
}
