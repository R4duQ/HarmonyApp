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

/**
 * The single light-mode field every section now sits on.
 *
 * The palettes used to each own a saturated full-bleed field — amber for
 * Library, green for Playlists, blue for EQ — which made the section
 * identity impossible to miss but also meant the app had no neutral ground
 * anywhere in light mode. Everything on screen had to fight a shouting
 * background.
 *
 * Section identity now lives in [EditorialPalette.accent] instead: the
 * background is constant and quiet, and each section colours its own
 * controls, chips and progress. Dark mode is untouched — the dark palettes
 * still carry their own tinted fields, where a near-black field reads as
 * depth rather than as noise.
 */
private val HarmonyLightField = Color(0xFFF4FDFF)

/** Titles and primary icons on [HarmonyLightField]. */
private val HarmonyLightInk = Color(0xFF16232B)

/** Subtitles, counts, inactive controls. */
private val HarmonyLightMuted = Color(0x9916232B)

/**
 * Hairline outlines. Much softer than the old light palettes, which used
 * solid ink for [EditorialPalette.line] — a full-strength border was legible
 * against a saturated field but draws as harsh boxing on a near-white one.
 */
private val HarmonyLightLine = Color(0x2916232B)

/**
 * The single dark-mode field every section now sits on, matching the light
 * side's HarmonyLightField above. #15121F is not a new colour invented for
 * this: it's exactly what LavenderDark (the EQ section) already used, and
 * what the Now Playing screen's own separate PlayerPalette uses for its
 * dark background — this just makes every OTHER dark section match a
 * background that two parts of the app were already independently using.
 */
private val HarmonyDarkField = Color(0xFF15121F)

/** Titles and primary icons on [HarmonyDarkField] — also matches PlayerPalette's dark ink. */
private val HarmonyDarkInk = Color(0xFFF1EEFF)

/** Subtitles, counts, inactive controls. Matches PlayerPalette's dark muted. */
private val HarmonyDarkMuted = Color(0xFF9E98BF)

/** Hairline outlines on [HarmonyDarkField]. */
private val HarmonyDarkLine = Color(0x29F1EEFF)

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
 * The mini player's own surface. Fixed per light/dark theme rather than the
 * section palette: the player is the one piece of chrome that persists
 * across every screen, so giving it a constant colour (per theme) is what
 * makes it read as a single object following you around instead of a strip
 * that recolours itself whenever you change tabs.
 *
 * Bright gold in light mode; a deeper mustard gold in dark mode — the same
 * warm hue carried at a tone that reads as "gold on a night sky" rather
 * than as a light-mode colour that just got dimmed.
 *
 * Held well below full opacity. Content no longer scrolls underneath —
 * the shell ends it at this pill's top edge — so what shows through is the
 * page's own flat field rather than passing artwork, and the fill can go
 * much further than it safely could before.
 *
 * 0.72 is the floor, set by DARK mode specifically: this pill carries dark
 * ink on gold, and as the gold thins toward a near-black field the two
 * close on each other. Measured, 0.72 leaves the title at ~4.9:1 and 0.66
 * drops it to ~4.3:1, under the 4.5:1 readability threshold. Light mode
 * sits above 12:1 throughout and is not the constraint.
 */
@Composable
fun miniPlayerField(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFFE1BE1F).copy(alpha = 0.72f)
    } else {
        Color(0xFFFFD54F).copy(alpha = 0.72f)
    }

/**
 * The lit rim along the mini player's edge, matching the nav bar's.
 *
 * Once the surface below it is translucent the pill needs an edge of its
 * own, or it stops reading as an object sitting on top of the page and
 * starts looking like the page is simply tinted yellow in that band.
 */
val MiniPlayerRim = Color(0x73FFFFFF)

/** Ink on [MiniPlayerField] — dark, since the yellow is bright. */
val MiniPlayerInk = Color(0xFF2A2206)

/** Secondary text on [MiniPlayerField]. */
val MiniPlayerMuted = Color(0xA62A2206)

/** Selected segment in the Songs / Albums / Artists control. */
val SegmentSelectedField = Color(0xFF574F63)
val SegmentSelectedInk = Color(0xFFFFFFFF)

/** Unselected segments — a light lavender chip. */
val SegmentField = Color(0xFFEDE7F6)
val SegmentInk = Color(0xFF3F3A4A)

/** The quietest segment tier, for a tab with nothing in it yet. */
val SegmentFieldMuted = Color(0xFFF4F0FA)
val SegmentInkMuted = Color(0xFF8A8496)

private val AmberLight = EditorialPalette(
    field = HarmonyLightField,
    ink = HarmonyLightInk,
    muted = HarmonyLightMuted,
    line = HarmonyLightLine,
    accent = Color(0xFFF3A311),
    onAccent = Color(0xFF1A1403),
)

private val AmberDark = EditorialPalette(
    field = HarmonyDarkField,
    ink = HarmonyDarkInk,
    muted = HarmonyDarkMuted,
    line = HarmonyDarkLine,
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
    field = HarmonyLightField,
    ink = HarmonyLightInk,
    muted = HarmonyLightMuted,
    line = HarmonyLightLine,
    accent = Color(0xFF1E9153),
    onAccent = Color(0xFFFFFFFF),
)

private val GreenDark = EditorialPalette(
    field = HarmonyDarkField,
    ink = HarmonyDarkInk,
    muted = HarmonyDarkMuted,
    line = HarmonyDarkLine,
    accent = Color(0xFF3FBF77),
    onAccent = Color(0xFF07130C),
)

private val BlueLight = EditorialPalette(
    field = HarmonyLightField,
    ink = HarmonyLightInk,
    muted = HarmonyLightMuted,
    line = HarmonyLightLine,
    accent = Color(0xFF2C87CE),
    onAccent = Color(0xFFFFFFFF),
)

private val BlueDark = EditorialPalette(
    field = HarmonyDarkField,
    ink = HarmonyDarkInk,
    muted = HarmonyDarkMuted,
    line = HarmonyDarkLine,
    accent = Color(0xFF4FA3E3),
    onAccent = Color(0xFF06131F),
)

private val StoneLight = EditorialPalette(
    field = HarmonyLightField,
    ink = HarmonyLightInk,
    muted = HarmonyLightMuted,
    line = HarmonyLightLine,
    accent = Color(0xFF6E6A5F),
    onAccent = Color(0xFFFFFFFF),
)

private val StoneDark = EditorialPalette(
    field = HarmonyDarkField,
    ink = HarmonyDarkInk,
    muted = HarmonyDarkMuted,
    line = HarmonyDarkLine,
    accent = Color(0xFFE6DDCB),
    onAccent = Color(0xFF17150F),
)

private val TealLight = EditorialPalette(
    field = HarmonyLightField,
    ink = HarmonyLightInk,
    muted = HarmonyLightMuted,
    line = HarmonyLightLine,
    accent = Color(0xFF128F8A),
    onAccent = Color(0xFFFFFFFF),
)

private val TealDark = EditorialPalette(
    field = HarmonyDarkField,
    ink = HarmonyDarkInk,
    muted = HarmonyDarkMuted,
    line = HarmonyDarkLine,
    accent = Color(0xFF3FBFB6),
    onAccent = Color(0xFF04211F),
)

private val CoralLight = EditorialPalette(
    field = HarmonyLightField,
    ink = HarmonyLightInk,
    muted = HarmonyLightMuted,
    line = HarmonyLightLine,
    accent = Color(0xFFDA4A31),
    onAccent = Color(0xFFFFFFFF),
)

private val CoralDark = EditorialPalette(
    field = HarmonyDarkField,
    ink = HarmonyDarkInk,
    muted = HarmonyDarkMuted,
    line = HarmonyDarkLine,
    accent = Color(0xFFE9573F),
    onAccent = Color(0xFF210A05),
)

private val LavenderLight = EditorialPalette(
    field = HarmonyLightField,
    ink = HarmonyLightInk,
    muted = HarmonyLightMuted,
    line = HarmonyLightLine,
    accent = Color(0xFF6C4BE8),
    onAccent = Color(0xFFFFFFFF),
)

private val LavenderDark = EditorialPalette(
    field = HarmonyDarkField,
    ink = HarmonyDarkInk,
    muted = HarmonyDarkMuted,
    line = HarmonyDarkLine,
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
