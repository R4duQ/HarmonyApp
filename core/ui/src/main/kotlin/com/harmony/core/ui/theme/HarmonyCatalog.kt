package com.harmony.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.R

/**
 * Harmony's look: a record shop's catalogue.
 *
 * One ground for every screen, near-black ink, and two colours with fixed
 * jobs, never decoration:
 *  - [blue], catalogue blue: structure and selection. The selected tab, the
 *    primary action, the crate-divider letters in the library.
 *  - [red], margin red: what is playing right now, and only that. Progress,
 *    the current song in a list, the format card's header rule.
 *
 * Sections are told apart by their titles, not by colour. No glass, glow or
 * gradients: surfaces are flat, separated by hairline [line]s.
 *
 * Contrast, measured on [ground]: ink and muted text pass 4.5:1 in both
 * themes, as do blue and red used as text; [faint] is for large text, icons
 * and rules only.
 */
@Immutable
class HarmonyColors(
    val isDark: Boolean,
    /** The page, on every screen. */
    val ground: Color,
    /** Cards, sheets and the format card: one step off the ground. */
    val surface: Color,
    /** Chips, pressed rows, the artwork well. */
    val raised: Color,
    val ink: Color,
    /** Artists, counts, durations, captions. */
    val muted: Color,
    /** Placeholders, disabled icons. Never body text. */
    val faint: Color,
    /** Hairline separators and outlines. */
    val line: Color,
    val blue: Color,
    val onBlue: Color,
    val blueContainer: Color,
    val onBlueContainer: Color,
    val red: Color,
    val onRed: Color,
    /** The mini player: a solid block that sits above the page, never see-through. */
    val block: Color,
    val onBlock: Color,
    val onBlockMuted: Color,
    /** The live red on the block: the block is dark in both themes, so the bright red. */
    val blockLive: Color = Color(0xFFFF6A5C),
)

val LightHarmonyColors = HarmonyColors(
    isDark = false,
    ground = Color(0xFFEDEEF0),
    surface = Color(0xFFF8F8F9),
    raised = Color(0xFFE2E4E8),
    ink = Color(0xFF121418),
    muted = Color(0xFF555B66),
    faint = Color(0xFF878D97),
    line = Color(0xFFCDD1D8),
    blue = Color(0xFF1D44C2),
    onBlue = Color(0xFFFFFFFF),
    blueContainer = Color(0xFFDBE2F8),
    onBlueContainer = Color(0xFF0D2A85),
    red = Color(0xFFC42A1F),
    onRed = Color(0xFFFFFFFF),
    block = Color(0xFF16191F),
    onBlock = Color(0xFFF2F3F5),
    onBlockMuted = Color(0xFFA3A9B4),
)

val DarkHarmonyColors = HarmonyColors(
    isDark = true,
    ground = Color(0xFF0F1115),
    surface = Color(0xFF171A20),
    raised = Color(0xFF20242C),
    ink = Color(0xFFECEEF2),
    muted = Color(0xFFA0A6B1),
    faint = Color(0xFF6F7682),
    line = Color(0xFF2A2F38),
    blue = Color(0xFF8AA4FF),
    onBlue = Color(0xFF0A1430),
    blueContainer = Color(0xFF1C2A5C),
    onBlueContainer = Color(0xFFD7E0FF),
    red = Color(0xFFFF6A5C),
    onRed = Color(0xFF1F0603),
    block = Color(0xFF242932),
    onBlock = Color(0xFFECEEF2),
    onBlockMuted = Color(0xFFA0A6B1),
)

/** True black for AMOLED: only the ground and surfaces move; ink and colours stay. */
fun HarmonyColors.amoled(): HarmonyColors = HarmonyColors(
    isDark = true, ground = Color.Black, surface = Color(0xFF0C0D10), raised = Color(0xFF17191E),
    ink = ink, muted = muted, faint = faint, line = Color(0xFF22262D),
    blue = blue, onBlue = onBlue, blueContainer = blueContainer, onBlueContainer = onBlueContainer,
    red = red, onRed = onRed, block = Color(0xFF1B1E24), onBlock = onBlock, onBlockMuted = onBlockMuted,
)

/**
 * Material You, when the listener turns it on, repaints the catalogue blue
 * (structure and selection) from the wallpaper. The ground, ink and the red
 * that marks what is playing stay, so the app still reads as Harmony.
 */
fun HarmonyColors.withStructure(primary: Color, onPrimary: Color, container: Color, onContainer: Color) = HarmonyColors(
    isDark = isDark, ground = ground, surface = surface, raised = raised, ink = ink, muted = muted, faint = faint,
    line = line, blue = primary, onBlue = onPrimary, blueContainer = container, onBlueContainer = onContainer,
    red = red, onRed = onRed, block = block, onBlock = onBlock, onBlockMuted = onBlockMuted,
)

/** The Material roles, resolved from the catalogue, so stock M3 components wear it too. */
fun HarmonyColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = blue, onPrimary = onBlue, primaryContainer = blueContainer, onPrimaryContainer = onBlueContainer,
        inversePrimary = blue,
        secondary = ink, onSecondary = ground, secondaryContainer = blueContainer, onSecondaryContainer = onBlueContainer,
        tertiary = red, onTertiary = onRed, tertiaryContainer = raised, onTertiaryContainer = ink,
        background = ground, onBackground = ink,
        surface = ground, onSurface = ink, surfaceVariant = raised, onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        surfaceBright = surface, surfaceDim = ground,
        surfaceContainerLowest = ground, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        inverseSurface = block, inverseOnSurface = onBlock,
        outline = muted, outlineVariant = line,
        error = red, onError = onRed,
        scrim = Color.Black,
    )
}

val LocalHarmonyColors = staticCompositionLocalOf { LightHarmonyColors }

/** The catalogue colours of the current theme; [HarmonyTheme] provides them. */
val Catalog: HarmonyColors
    @Composable @ReadOnlyComposable get() = LocalHarmonyColors.current

/** Archivo: a grotesk with tabular figures, so durations and formats line up in columns. */
val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Archivo,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    // Every figure tabular: times, counts and sample rates align down a list.
    fontFeatureSettings = "tnum",
)

/**
 * One scale for the whole app. Screens pick a role; they do not pick sizes.
 * 11sp is the floor (labelSmall): nothing smaller is readable in a moving car.
 */
val HarmonyTypography = Typography(
    displayLarge = style(44, 48, FontWeight.SemiBold, -1.2),
    displayMedium = style(36, 40, FontWeight.SemiBold, -0.9),
    displaySmall = style(30, 36, FontWeight.SemiBold, -0.6),
    headlineLarge = style(28, 34, FontWeight.SemiBold, -0.5),
    headlineMedium = style(24, 30, FontWeight.SemiBold, -0.3),
    headlineSmall = style(20, 26, FontWeight.SemiBold, -0.2),
    titleLarge = style(20, 26, FontWeight.SemiBold, -0.2),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.1),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal, 0.1),
    bodySmall = style(12, 16, FontWeight.Normal, 0.2),
    labelLarge = style(14, 18, FontWeight.Medium, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.4),
    labelSmall = style(11, 14, FontWeight.Medium, 0.6),
)
