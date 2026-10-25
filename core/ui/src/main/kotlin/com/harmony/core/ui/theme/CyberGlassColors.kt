package com.harmony.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.harmony.core.ui.component.EditorialPalette

/**
 * The Dark Cyber-Glass look: deep obsidian surfaces, electric cyan for what
 * is selected or live, neon violet for borders, gradients and the play
 * controls.
 *
 * Dark theme only. It dresses the library (search, chips, song rows, album
 * and artist lists), the floating mini player and the navigation pill; Now
 * Playing keeps its own palette. In light theme those screens keep the
 * editorial look.
 */
object CyberGlassColors {
    // Surfaces
    val Obsidian = Color(0xFF0B0F17)
    val ObsidianDeep = Color(0xFF0E131F)
    /** Search bar and cards. */
    val Surface = Color(0xFF131926)
    /** Unselected chips. */
    val Chip = Color(0xFF161F30)
    val ChipBorder = Color(0xFF2A3950)
    /** Artwork fallback square. */
    val ArtWell = Color(0xFF121A2A)
    /** The mini player card, before its alpha. */
    val Card = Color(0xFF111827)
    const val CARD_ALPHA = 0.92f
    /** Recessed pills inside the card (Up Next, the round controls). */
    val Recessed = Color(0xFF1A2333)
    /** The output-device pill. */
    val DevicePill = Color(0xFF162A3A)
    /** The navigation pill's frosted glass, before its alpha. */
    val NavGlass = Color(0xFF131B2E)
    const val NAV_ALPHA = 0.90f
    val NavRim = Color(0x4038BDF8)

    // Accents
    val Cyan = Color(0xFF00E5FF)
    val Mint = Color(0xFF00F5D4)
    val Azure = Color(0xFF00BBF9)
    val Sky = Color(0xFF38BDF8)
    val Violet = Color(0xFF8B5CF6)
    val Purple = Color(0xFFA855F7)
    val Indigo = Color(0xFF6D28D9)
    val IndigoDeep = Color(0xFF4C1D95)

    // Text and lines
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSilver = Color(0xFFE2E8F0)
    val TextPlaceholder = Color(0xFFCBD5E1)
    val TextSecondary = Color(0xFF94A3B8)
    val TextQuiet = Color(0xFF64748B)
    val Divider = Color(0x14FFFFFF)
    val ThumbRim = Color(0x4400E5FF)

    // Gradients
    /** The selected chip's fill. */
    val SelectedChip = Brush.horizontalGradient(listOf(Mint, Azure))
    /** The search bar's 1dp border. */
    val SearchRim = Brush.linearGradient(listOf(Indigo, IndigoDeep))
    /** Iridescent rims: the mini player card, the circle button, the record. */
    val Iridescent = Brush.linearGradient(listOf(Cyan, Violet, Purple, Cyan))
    val IridescentSweep = Brush.sweepGradient(listOf(Cyan, Violet, Purple, Mint, Cyan))
    /** Progress: violet into cyan into mint. */
    val ProgressColors = listOf(Violet, Cyan, Mint)
    val PlayButton = Brush.linearGradient(listOf(Violet, Purple))
    /** The page itself: obsidian deepening a touch toward the bottom. */
    val Page = Brush.verticalGradient(listOf(Obsidian, ObsidianDeep))
}

/** The library's palette in Cyber-Glass, for the editorial components that take one. */
val CyberGlassPalette = EditorialPalette(
    field = CyberGlassColors.Obsidian,
    ink = CyberGlassColors.TextPrimary,
    muted = CyberGlassColors.TextSecondary,
    line = CyberGlassColors.ChipBorder,
    accent = CyberGlassColors.Cyan,
    onAccent = CyberGlassColors.Obsidian,
)

/**
 * Whether this palette is [CyberGlassPalette]: the glass components (search
 * bar, chips, song rows, cards) switch to their neon materials for it. The
 * library hands its palette over unanimated, so an exact match is reliable.
 */
val EditorialPalette.isCyberGlass: Boolean
    get() = field == CyberGlassColors.Obsidian && accent == CyberGlassColors.Cyan

/** Cyber-Glass is the dark theme's look for the library and the floating chrome. */
@Composable
fun cyberGlassActive(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
