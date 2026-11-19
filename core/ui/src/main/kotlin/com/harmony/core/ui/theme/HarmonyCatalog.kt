package com.harmony.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Compatibility: the previous "catalogue" colour names.
 *
 *
 * Screens that have not moved to [Room] yet read these names; they resolve
 * from the Listening Room through [toCatalog], so every screen wears one
 * palette. Delete this file when the last screen reads [Room].
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

/**
 * The old catalogue names, resolved from the Listening Room ([RoomColors]),
 * for screens that still read [Catalog]. Structure and selection (the old
 * blue) and what is playing (the old red) both become the amber accent as
 * text; the old dark block becomes the record block. New code reads [Room].
 */
fun RoomColors.toCatalog(): HarmonyColors = HarmonyColors(
    isDark = isDark,
    ground = ground, surface = surface, raised = raised,
    ink = ink, muted = muted, faint = faint, line = line,
    blue = amberText,
    onBlue = if (isDark) onAmber else surface,
    blueContainer = amberSoft, onBlueContainer = onAmberSoft,
    red = amberText,
    onRed = if (isDark) onAmber else surface,
    block = record, onBlock = onRecord, onBlockMuted = onRecordMuted, blockLive = recordLive,
)

val LocalHarmonyColors = staticCompositionLocalOf { LightRoom.toCatalog() }

/** The catalogue colours of the current theme; [HarmonyTheme] provides them. */
val Catalog: HarmonyColors
    @Composable @ReadOnlyComposable get() = LocalHarmonyColors.current
