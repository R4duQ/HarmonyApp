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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/**
 * The "editorial" look shared by the Library (amber) and Playlists (green)
 * screens: one saturated full-bleed field, near-black ink, and content held
 * in thin ink-outlined rounded cards. Same construction, different field —
 * so the two screens read as siblings, and the color itself tells you which
 * part of the app you're in.
 *
 * Like the player, these screens deliberately opt out of Material dynamic
 * colors (the poster-flat field IS the design; wallpaper-derived tints would
 * erase it), but still follow light/dark by reading the ambient scheme's
 * luminance. Dark variants keep each screen's hue at a low register instead
 * of going grey, so the section identity survives at night.
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

private val AmberLight = EditorialPalette(
    field = Color(0xFFF3A311),
    ink = Color(0xFF1A1403),
    muted = Color(0xB31A1403),
    line = Color(0xFF1A1403),
    accent = Color(0xFF1A1403),
    onAccent = Color(0xFFF3A311),
)

private val AmberDark = EditorialPalette(
    field = Color(0xFF241A05),
    ink = Color(0xFFF3D391),
    muted = Color(0x99F3D391),
    line = Color(0x80F3D391),
    accent = Color(0xFFF3A311),
    onAccent = Color(0xFF1A1403),
)

// Green and Blue previously carried Amber's accent (#F3A311) and onAccent
// verbatim — copy-pasted when the palettes were added and never changed. That
// is why the Play button on a green playlist and the preset chips on the blue
// Equalizer rendered gold on screens with no other warm colour in them. Each
// family now carries its own accent, following Amber's own pattern: light
// palettes accent with their dark ink, dark palettes with the family's bright
// hue.
private val GreenLight = EditorialPalette(
    field = Color(0xFF27A15C),
    ink = Color(0xFF07130C),
    muted = Color(0xA607130C),
    line = Color(0xFF07130C),
    accent = Color(0xFF07130C),
    onAccent = Color(0xFFCDEDDA),
)

private val GreenDark = EditorialPalette(
    field = Color(0xFF0B2115),
    ink = Color(0xFFCDEDDA),
    muted = Color(0x99CDEDDA),
    line = Color(0x80CDEDDA),
    accent = Color(0xFF3FBF77),
    onAccent = Color(0xFF07130C),
)

private val BlueLight = EditorialPalette(
    field = Color(0xFF4FA3E3),
    ink = Color(0xFF06131F),
    muted = Color(0xA606131F),
    line = Color(0xFF06131F),
    accent = Color(0xFF06131F),
    onAccent = Color(0xFFDCEEFB),
)

private val BlueDark = EditorialPalette(
    field = Color(0xFF0A1826),
    ink = Color(0xFFC3E0F7),
    muted = Color(0x99C3E0F7),
    line = Color(0x80C3E0F7),
    accent = Color(0xFF4FA3E3),
    onAccent = Color(0xFF06131F),
)

private val StoneLight = EditorialPalette(
    field = Color(0xFFEDE4D3),
    ink = Color(0xFF1E1A12),
    muted = Color(0x991E1A12),
    line = Color(0xFF1E1A12),
    accent = Color(0xFF1E1A12),
    onAccent = Color(0xFFEDE4D3),
)

private val StoneDark = EditorialPalette(
    field = Color(0xFF17150F),
    ink = Color(0xFFE6DDCB),
    muted = Color(0x99E6DDCB),
    line = Color(0x80E6DDCB),
    accent = Color(0xFFE6DDCB),
    onAccent = Color(0xFF17150F),
)

private val TealLight = EditorialPalette(
    field = Color(0xFF1FA6A0),
    ink = Color(0xFF04211F),
    muted = Color(0xB304211F),
    line = Color(0xFF04211F),
    accent = Color(0xFF04211F),
    onAccent = Color(0xFFDFF7F5),
)

private val TealDark = EditorialPalette(
    field = Color(0xFF0C1F1E),
    ink = Color(0xFF9FE0DA),
    muted = Color(0x999FE0DA),
    line = Color(0x809FE0DA),
    accent = Color(0xFF3FBFB6),
    onAccent = Color(0xFF04211F),
)

private val CoralLight = EditorialPalette(
    field = Color(0xFFE9573F),
    ink = Color(0xFF210A05),
    muted = Color(0xB3210A05),
    line = Color(0xFF210A05),
    accent = Color(0xFF210A05),
    onAccent = Color(0xFFFFE7DF),
)

private val CoralDark = EditorialPalette(
    field = Color(0xFF221009),
    ink = Color(0xFFF7C9B9),
    muted = Color(0x99F7C9B9),
    line = Color(0x80F7C9B9),
    accent = Color(0xFFE9573F),
    onAccent = Color(0xFF210A05),
)

private val LavenderLight = EditorialPalette(
    field = Color(0xFFDCD7FA),
    ink = Color(0xFF17142B),
    muted = Color(0xFF5D5878),
    line = Color(0x2917142B),
    accent = Color(0xFF6C4BE8),
    onAccent = Color(0xFFFFFFFF),
)

private val LavenderDark = EditorialPalette(
    field = Color(0xFF15121F),
    ink = Color(0xFFF1EEFF),
    muted = Color(0xFF9E98BF),
    line = Color(0x33F1EEFF),
    accent = Color(0xFF9B85FF),
    onAccent = Color(0xFF17142B),
)

@Composable
fun amberPalette(): EditorialPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) AmberDark else AmberLight

@Composable
fun greenPalette(): EditorialPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) GreenDark else GreenLight

/** Equalizer. Blue reads as "instrument panel" next to the library's amber. */
@Composable
fun bluePalette(): EditorialPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) BlueDark else BlueLight

/**
 * Settings. The one unsaturated field in the set, on purpose — settings is
 * the screen you pass through rather than dwell in, and a shouting color
 * would be competing with the sections that actually hold content.
 */
@Composable
fun stonePalette(): EditorialPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) StoneDark else StoneLight

/**
 * Discover. Warm and loud on purpose — it is the one section that sends you
 * somewhere Harmony doesn't control, so it should not be mistakable for the
 * library's amber at a glance.
 */
/**
 * Downloads. The screen itself renders in teal from the Material scheme; the
 * shell previously handed it lavender, so the mini player and navigation bar
 * came out purple over a teal screen. Same hue family now, until Downloads is
 * converted to the editorial kit properly.
 */
@Composable
fun tealPalette(): EditorialPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) TealDark else TealLight

@Composable
fun coralPalette(): EditorialPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) CoralDark else CoralLight

/**
 * The player's field, expressed as an EditorialPalette so the app shell can
 * treat every destination uniformly. The field values here MUST match
 * PlayerPalette's background, or the player screen gets a seam where the
 * chrome meets it.
 */
@Composable
fun lavenderPalette(): EditorialPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) LavenderDark else LavenderLight

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
    val border = BorderStroke(1.5.dp, palette.line)
    // Filled with the field rather than transparent: visually identical
    // against the page, but opaque, so anything revealed behind a card
    // (the swipe-to-queue affordance) can't bleed through it.
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = palette.field,
            contentColor = palette.ink,
            border = border,
        ) { content() }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = palette.field,
            contentColor = palette.ink,
            border = border,
        ) { content() }
    }
}

/** The outline-only circle: play on cards, search / add / back in headers. */
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
    val container = if (filled) palette.accent else Color.Transparent
    val contentColor = if (filled) palette.onAccent else palette.ink
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = container,
        contentColor = contentColor,
        border = if (filled) null else BorderStroke(1.5.dp, palette.line),
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
data class EditorialTab(val label: String, val count: Int? = null)

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
