package com.harmony.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.harmony.core.ui.component.EditorialPalette

/**
 * The roles the former Cyber-Glass look exposed (surfaces, accents, text,
 * rims), kept as an adapter so older components keep compiling. Every role
 * now resolves from the catalogue ([HarmonyColors], [cyberGlassFrom]): new
 * code should read [Catalog] directly.
 *
 * Read it as [CyberGlassColors] inside composables; [HarmonyTheme] provides
 * the one that matches the theme. In a draw or remember lambda, read it into
 * a local first.
 */
@Immutable
class CyberGlassScheme(
    val isDark: Boolean,
    // Surfaces
    /** The page, and the opaque rows on it. */
    val Obsidian: Color,
    val ObsidianDeep: Color,
    /** Search bar and cards. */
    val Surface: Color,
    /** Unselected chips. */
    val Chip: Color,
    val ChipBorder: Color,
    /** Artwork fallback square. */
    val ArtWell: Color,
    /** The mini player card, before its alpha. */
    val Card: Color,
    val CARD_ALPHA: Float,
    /** Recessed pills inside the card (Up Next, the round controls). */
    val Recessed: Color,
    /** The output-device pill. */
    val DevicePill: Color,
    /** The navigation pill's frosted glass, before its alpha. */
    val NavGlass: Color,
    val NAV_ALPHA: Float,
    val NavRim: Color,
    // Accents
    val Cyan: Color,
    val Mint: Color,
    val Azure: Color,
    val Sky: Color,
    val Violet: Color,
    val Purple: Color,
    val Indigo: Color,
    val IndigoDeep: Color,
    /** Ink on an accent fill (the selected chip, a cyan badge). */
    val OnAccent: Color,
    /** Small live text in cyan (the mini player's "LIVE SPECTRAL EQ" line). */
    val LiveInk: Color,
    // Text and lines
    val TextPrimary: Color,
    val TextSilver: Color,
    val TextPlaceholder: Color,
    val TextSecondary: Color,
    val TextQuiet: Color,
    val Divider: Color,
    val ThumbRim: Color,
    /** What casts the floating chrome's shadow. */
    val Shadow: Color,
    /**
     * Scales every neon glow. A glow is light on a dark field; on a light
     * field the same strength reads as a smudge, so light keeps it softer.
     */
    val Glow: Float,
    /** The selected chip's fill. */
    val SelectedChip: Brush,
) {
    /** The search bar's 1dp border. */
    val SearchRim: Brush = Brush.linearGradient(listOf(Indigo, IndigoDeep))
    /** Iridescent rims: the mini player card, the circle button, the record. */
    val Iridescent: Brush = Brush.linearGradient(listOf(Cyan, Violet, Purple, Cyan))
    val IridescentSweep: Brush = Brush.sweepGradient(listOf(Cyan, Violet, Purple, Mint, Cyan))
    /** Progress: violet into cyan into mint. */
    val ProgressColors: List<Color> = listOf(Violet, Cyan, Mint)
    val PlayButton: Brush = Brush.linearGradient(listOf(Violet, Purple))
    /** The page itself: deepening a touch toward the bottom. */
    val Page: Brush = Brush.verticalGradient(listOf(Obsidian, ObsidianDeep))

    /** The library's palette, for the editorial components that take one. */
    val palette: EditorialPalette = EditorialPalette(
        field = Obsidian,
        ink = TextPrimary,
        muted = TextSecondary,
        line = ChipBorder,
        accent = Cyan,
        onAccent = OnAccent,
    )
}

/**
 * The Cyber-Glass roles, resolved from the catalogue ([HarmonyColors]). The
 * neon is gone: every accent role is the catalogue blue, live text is the
 * red, glass is solid surface and [CyberGlassScheme.Glow] is zero, so the
 * components that still read these roles draw flat and quiet.
 */
fun cyberGlassFrom(c: HarmonyColors) = CyberGlassScheme(
    isDark = c.isDark,
    Obsidian = c.ground,
    ObsidianDeep = c.ground,
    Surface = c.surface,
    Chip = c.raised,
    ChipBorder = c.line,
    ArtWell = c.raised,
    Card = c.surface,
    CARD_ALPHA = 1f,
    Recessed = c.raised,
    DevicePill = c.raised,
    NavGlass = c.ground,
    NAV_ALPHA = 1f,
    NavRim = c.line,
    Cyan = c.blue,
    Mint = c.blue,
    Azure = c.blue,
    Sky = c.muted,
    Violet = c.blue,
    Purple = c.blue,
    Indigo = c.line,
    IndigoDeep = c.line,
    OnAccent = c.onBlue,
    LiveInk = c.red,
    TextPrimary = c.ink,
    TextSilver = c.ink,
    TextPlaceholder = c.muted,
    TextSecondary = c.muted,
    TextQuiet = c.faint,
    Divider = c.line,
    ThumbRim = c.line,
    Shadow = Color.Black,
    Glow = 0f,
    SelectedChip = SolidColor(c.blue),
)

val DarkCyberGlass = cyberGlassFrom(DarkRoom.toCatalog())

val LightCyberGlass = cyberGlassFrom(LightRoom.toCatalog())

/** The Cyber-Glass of the current theme; [HarmonyTheme] provides it. */
val LocalCyberGlass = staticCompositionLocalOf { DarkCyberGlass }

/** The Cyber-Glass colors of the current theme. */
val CyberGlassColors: CyberGlassScheme
    @Composable @ReadOnlyComposable get() = LocalCyberGlass.current

/** The library's palette in Cyber-Glass, for the editorial components that take one. */
val CyberGlassPalette: EditorialPalette
    @Composable @ReadOnlyComposable get() = LocalCyberGlass.current.palette

/**
 * Whether the glass components should take their neon branch. Never, now:
 * every screen wears the catalogue, so the components draw one way. Kept so
 * callers compile; their Cyber-Glass branches are dead and can be removed.
 */
val EditorialPalette.isCyberGlass: Boolean
    get() = false
