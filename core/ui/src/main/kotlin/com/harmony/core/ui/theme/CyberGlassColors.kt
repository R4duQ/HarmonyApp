package com.harmony.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.harmony.core.ui.component.EditorialPalette

/**
 * The Cyber-Glass look: glass surfaces, electric cyan for what is selected
 * or live, neon violet for borders, gradients and the play controls.
 *
 * It dresses the library (search, chips, song rows, album and artist
 * lists), the floating mini player and the navigation pill, in both themes:
 * [DarkCyberGlass] is the original deep-obsidian look, [LightCyberGlass] the
 * same design on pearl glass, with the accents deepened so they keep their
 * contrast on a light field. Now Playing keeps its own palette.
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

/** The original Cyber-Glass. These values are the dark theme as it always was. */
val DarkCyberGlass = CyberGlassScheme(
    isDark = true,
    Obsidian = Color(0xFF0B0F17),
    ObsidianDeep = Color(0xFF0E131F),
    Surface = Color(0xFF131926),
    Chip = Color(0xFF161F30),
    ChipBorder = Color(0xFF2A3950),
    ArtWell = Color(0xFF121A2A),
    Card = Color(0xFF111827),
    CARD_ALPHA = 0.92f,
    Recessed = Color(0xFF1A2333),
    DevicePill = Color(0xFF162A3A),
    NavGlass = Color(0xFF131B2E),
    NAV_ALPHA = 0.90f,
    NavRim = Color(0x4038BDF8),
    Cyan = Color(0xFF00E5FF),
    Mint = Color(0xFF00F5D4),
    Azure = Color(0xFF00BBF9),
    Sky = Color(0xFF38BDF8),
    Violet = Color(0xFF8B5CF6),
    Purple = Color(0xFFA855F7),
    Indigo = Color(0xFF6D28D9),
    IndigoDeep = Color(0xFF4C1D95),
    OnAccent = Color(0xFF0B0F17),
    LiveInk = Color(0xFF00E5FF).copy(alpha = 0.78f),
    TextPrimary = Color(0xFFFFFFFF),
    TextSilver = Color(0xFFE2E8F0),
    TextPlaceholder = Color(0xFFCBD5E1),
    TextSecondary = Color(0xFF94A3B8),
    TextQuiet = Color(0xFF64748B),
    Divider = Color(0x14FFFFFF),
    ThumbRim = Color(0x4400E5FF),
    Shadow = Color.Black,
    Glow = 1f,
    SelectedChip = Brush.horizontalGradient(listOf(Color(0xFF00F5D4), Color(0xFF00BBF9))),
)

/**
 * Cyber-Glass on pearl: the same surfaces, rims, gradients and glow as the
 * dark one, light. Cool white glass on a pale blue-grey field; the cyan,
 * violet and mint accents one or two steps deeper, so text and icons in them
 * stay legible (WCAG, measured): cyan 5.4:1 on white and 4.7:1 on the device
 * pill, the duration's sky blue 5.5:1 and the secondary slate 7.1:1 on the
 * page, white on the selected chip 5.5:1 and on the violet play button
 * 5.7:1. Small live text is full-strength cyan here, not faded as in dark.
 */
val LightCyberGlass = CyberGlassScheme(
    isDark = false,
    Obsidian = Color(0xFFF4F7FC),
    ObsidianDeep = Color(0xFFE8EEF7),
    Surface = Color(0xFFFFFFFF),
    Chip = Color(0xFFEEF2F9),
    ChipBorder = Color(0xFFC9D4E5),
    ArtWell = Color(0xFFE4EAF4),
    Card = Color(0xFFFFFFFF),
    CARD_ALPHA = 0.94f,
    Recessed = Color(0xFFEDF1F8),
    DevicePill = Color(0xFFE0F2FE),
    NavGlass = Color(0xFFFFFFFF),
    NAV_ALPHA = 0.90f,
    NavRim = Color(0x590EA5E9),
    Cyan = Color(0xFF0E7490),
    Mint = Color(0xFF0D9488),
    Azure = Color(0xFF0284C7),
    Sky = Color(0xFF0369A1),
    Violet = Color(0xFF7C3AED),
    Purple = Color(0xFF9333EA),
    Indigo = Color(0xFFC4B5FD),
    IndigoDeep = Color(0xFFA78BFA),
    OnAccent = Color(0xFFFFFFFF),
    LiveInk = Color(0xFF0E7490),
    TextPrimary = Color(0xFF0B1220),
    TextSilver = Color(0xFF1E293B),
    TextPlaceholder = Color(0xFF475569),
    TextSecondary = Color(0xFF475569),
    TextQuiet = Color(0xFF64748B),
    Divider = Color(0x1A0B1220),
    ThumbRim = Color(0x330E7490),
    Shadow = Color(0xFF1E293B),
    Glow = 0.55f,
    SelectedChip = Brush.horizontalGradient(listOf(Color(0xFF0F766E), Color(0xFF0369A1))),
)

/** The Cyber-Glass of the current theme; [HarmonyTheme] provides it. */
val LocalCyberGlass = staticCompositionLocalOf { DarkCyberGlass }

/** The Cyber-Glass colors of the current theme. */
val CyberGlassColors: CyberGlassScheme
    @Composable @ReadOnlyComposable get() = LocalCyberGlass.current

/** The library's palette in Cyber-Glass, for the editorial components that take one. */
val CyberGlassPalette: EditorialPalette
    @Composable @ReadOnlyComposable get() = LocalCyberGlass.current.palette

/**
 * Whether this palette is a Cyber-Glass one (either theme's): the glass
 * components (search bar, chips, song rows, cards) switch to their neon
 * materials for it. The library hands its palette over unanimated, so an
 * exact match is reliable.
 */
val EditorialPalette.isCyberGlass: Boolean
    get() = (field == DarkCyberGlass.Obsidian && accent == DarkCyberGlass.Cyan) ||
        (field == LightCyberGlass.Obsidian && accent == LightCyberGlass.Cyan)
