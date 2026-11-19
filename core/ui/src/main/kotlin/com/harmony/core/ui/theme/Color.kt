package com.harmony.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Harmony's colours: the Listening Room.
 *
 * A warm room with a record player in it. One accent, the amber glow of a
 * valve amplifier, marks what you can act on and what is playing. Walnut and
 * sleeve paper are the neutrals. The record block (mini player, the Now
 * Playing stage) is dark in both themes, like a record sleeve.
 *
 * Contrast is measured in docs/redesign/contrast.py; every text pair passes
 * 4.5:1 and every non-text pair 3:1, in both themes. One exception, by design:
 * the [amber] fill on the light ground is 1.89:1, so in the light theme amber
 * never carries a state on its own. A selected item also changes its icon,
 * its label ([amberText], semibold) or shows a check.
 */
@Immutable
class RoomColors(
    val isDark: Boolean,
    /** The page, on every screen. */
    val ground: Color,
    /** Cards, sheets, list groups: one step off the ground. */
    val surface: Color,
    /** Chips, pressed rows, fields, the artwork well. */
    val raised: Color,
    val ink: Color,
    /** Secondary text: artists, counts, durations. Passes 4.5:1 on every surface. */
    val muted: Color,
    /** Placeholder icons and dividers you must see. 3:1, never body text. */
    val faint: Color,
    /** Hairlines between rows. Decorative only. */
    val line: Color,
    /** Field, checkbox and radio edges. 3:1. */
    val outline: Color,
    /** The accent as a fill: the primary button, the play button, progress. */
    val amber: Color,
    val onAmber: Color,
    /** The accent as text or icon: the playing song, a selected label, links. */
    val amberText: Color,
    /** Selected chip, the current row, a steering chip that is on. */
    val amberSoft: Color,
    val onAmberSoft: Color,
    /** Success: In your library, Added, Lossless verified. Always with an icon and a word. */
    val moss: Color,
    /** Waiting or needs you. Cool on purpose, so it never reads as the accent. */
    val slate: Color,
    /** Errors and destructive actions. */
    val wine: Color,
    val onWine: Color,
    /** The mini player and the Now Playing stage. */
    val record: Color,
    val onRecord: Color,
    val onRecordMuted: Color,
    /** What is live on the record block: the play button, the progress line. */
    val recordLive: Color,
) {
    fun copy(
        isDark: Boolean = this.isDark, ground: Color = this.ground, surface: Color = this.surface,
        raised: Color = this.raised, ink: Color = this.ink, muted: Color = this.muted, faint: Color = this.faint,
        line: Color = this.line, outline: Color = this.outline, amber: Color = this.amber,
        onAmber: Color = this.onAmber, amberText: Color = this.amberText, amberSoft: Color = this.amberSoft,
        onAmberSoft: Color = this.onAmberSoft, moss: Color = this.moss, slate: Color = this.slate,
        wine: Color = this.wine, onWine: Color = this.onWine, record: Color = this.record,
        onRecord: Color = this.onRecord, onRecordMuted: Color = this.onRecordMuted, recordLive: Color = this.recordLive,
    ) = RoomColors(
        isDark, ground, surface, raised, ink, muted, faint, line, outline, amber, onAmber, amberText, amberSoft,
        onAmberSoft, moss, slate, wine, onWine, record, onRecord, onRecordMuted, recordLive,
    )
}

val LightRoom = RoomColors(
    isDark = false,
    ground = Color(0xFFF7F4EF),
    surface = Color(0xFFFFFDFA),
    raised = Color(0xFFEEE8DF),
    ink = Color(0xFF26201A),
    muted = Color(0xFF665A4E),
    faint = Color(0xFF8A7E71),
    line = Color(0xFFE3DBCF),
    outline = Color(0xFF8A7E71),
    amber = Color(0xFFF2A43A),
    onAmber = Color(0xFF2A1803),
    amberText = Color(0xFF8C4F00),
    amberSoft = Color(0xFFFCE6C4),
    onAmberSoft = Color(0xFF4A2C00),
    moss = Color(0xFF3B6B35),
    slate = Color(0xFF335C91),
    wine = Color(0xFFA1283E),
    onWine = Color(0xFFFFFFFF),
    record = Color(0xFF211B16),
    onRecord = Color(0xFFF7F0E6),
    onRecordMuted = Color(0xFFC1B3A3),
    recordLive = Color(0xFFFFB54D),
)

val DarkRoom = RoomColors(
    isDark = true,
    ground = Color(0xFF15120F),
    surface = Color(0xFF1E1A16),
    raised = Color(0xFF29241F),
    ink = Color(0xFFF5EEE5),
    muted = Color(0xFFBCAF9F),
    faint = Color(0xFF877B6E),
    line = Color(0xFF38312A),
    outline = Color(0xFF877B6E),
    amber = Color(0xFFFFB54D),
    onAmber = Color(0xFF2A1700),
    amberText = Color(0xFFFFC470),
    amberSoft = Color(0xFF4A3010),
    onAmberSoft = Color(0xFFFFE2B8),
    moss = Color(0xFF93CC88),
    slate = Color(0xFF9DBDF0),
    wine = Color(0xFFFF8FA0),
    onWine = Color(0xFF3D0612),
    record = Color(0xFF0E0B09),
    onRecord = Color(0xFFF7F0E6),
    onRecordMuted = Color(0xFFC1B3A3),
    recordLive = Color(0xFFFFB54D),
)

/** True black for AMOLED: only the ground and surfaces move; text and accents stay. */
fun RoomColors.amoled(): RoomColors = copy(
    isDark = true,
    ground = Color.Black,
    surface = Color(0xFF0E0B09),
    raised = Color(0xFF1A1611),
    line = Color(0xFF2A241E),
    record = Color(0xFF14100D),
)

/**
 * Material You, when the listener turns it on: the wallpaper repaints only the
 * accent. The ground, the ink and the status colours stay, so the app still
 * reads as Harmony.
 */
fun RoomColors.withWallpaper(primary: Color, onPrimary: Color, container: Color, onContainer: Color): RoomColors =
    copy(
        amber = if (isDark) primary else container,
        onAmber = if (isDark) onPrimary else onContainer,
        amberText = primary,
        amberSoft = container,
        onAmberSoft = onContainer,
    )

/**
 * The Material roles, so stock components wear the room too. Material uses
 * `primary` as a text colour (text buttons, selected tabs), so in the light
 * theme it is [amberText], not the amber fill, which would fail contrast.
 */
fun RoomColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = if (isDark) amber else amberText,
        onPrimary = if (isDark) onAmber else surface,
        primaryContainer = amberSoft, onPrimaryContainer = onAmberSoft,
        inversePrimary = if (isDark) amberText else amber,
        secondary = ink, onSecondary = ground,
        secondaryContainer = amberSoft, onSecondaryContainer = onAmberSoft,
        tertiary = slate, onTertiary = ground,
        tertiaryContainer = raised, onTertiaryContainer = ink,
        background = ground, onBackground = ink,
        surface = ground, onSurface = ink,
        surfaceVariant = raised, onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        surfaceBright = surface, surfaceDim = ground,
        surfaceContainerLowest = ground, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = raised,
        inverseSurface = record, inverseOnSurface = onRecord,
        outline = outline, outlineVariant = line,
        error = wine, onError = onWine,
        errorContainer = raised, onErrorContainer = wine,
        scrim = Color.Black,
    )
}

val LocalRoom = staticCompositionLocalOf { LightRoom }

/** The Listening Room colours of the current theme; [HarmonyTheme] provides them. */
val Room: RoomColors
    @Composable @ReadOnlyComposable get() = LocalRoom.current
