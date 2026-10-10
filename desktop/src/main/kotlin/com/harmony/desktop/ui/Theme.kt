package com.harmony.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Harmony's desktop colours, in a dark and a light version. */
class HarmonyColors(
    val dark: Boolean,
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val chip: Color,
    /** The soft, raised surfaces of the player: the base, and its lit and shaded edges. */
    val neu: Color,
    val neuLight: Color,
    val neuDark: Color,
    /** The dark panel that holds the top tracks, in both themes. */
    val panel: Color,
)

object Accent {
    val Purple = Color(0xFF7C4DFF)
    val PurpleDeep = Color(0xFF5A2BD8)
    val PurpleLight = Color(0xFFB79CFF)
    val Orange = Color(0xFFF2A65A)
    val Cyan = Color(0xFF35E0FF)
    val Pink = Color(0xFFFF5C93)

    /** The progress wave, from the start of the song to where it is. */
    val Wave = listOf(
        Color(0xFF8F5CFF), Color(0xFFE48AB3), Color(0xFFF3A45E), Color(0xFFE9C85A),
        Color(0xFF63C99B), Color(0xFF39B9D3), Color(0xFF6C63FF),
    )

    /** The rim around records and cards. */
    val Rim = listOf(Color(0xFF8F5CFF), Color(0xFF39B9D3), Color(0xFFF3A45E), Color(0xFFE48AB3), Color(0xFF8F5CFF))
}

val DarkColors = HarmonyColors(
    dark = true,
    background = Color(0xFF0F1117),
    sidebar = Color(0xFF0B0D12),
    surface = Color(0xFF171A22),
    surfaceHigh = Color(0xFF1F2430),
    ink = Color(0xFFF4F4FA),
    muted = Color(0xFF9AA3B5),
    line = Color(0x1FFFFFFF),
    chip = Color(0xFF262C38),
    neu = Color(0xFF1D2029),
    neuLight = Color(0xFF2A2E3A),
    neuDark = Color(0xFF0C0E13),
    panel = Color(0xFF14161D),
)

val LightColors = HarmonyColors(
    dark = false,
    background = Color(0xFFF4F2FB),
    sidebar = Color(0xFFEAE6F7),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF0EEF8),
    ink = Color(0xFF15151C),
    muted = Color(0xFF6B6B80),
    line = Color(0x1F15151C),
    chip = Color(0xFFE4F8FB),
    neu = Color(0xFFEEF0F6),
    neuLight = Color(0xFFFFFFFF),
    neuDark = Color(0xFFC9CDDA),
    panel = Color(0xFF1B1C22),
)

val LocalHarmonyColors = staticCompositionLocalOf { DarkColors }

@Composable
fun HarmonyTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(primary = Accent.Purple, background = colors.background, surface = colors.surface, onSurface = colors.ink, onBackground = colors.ink)
    } else {
        lightColorScheme(primary = Accent.Purple, background = colors.background, surface = colors.surface, onSurface = colors.ink, onBackground = colors.ink)
    }
    CompositionLocalProvider(LocalHarmonyColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** "3:07", "1:02:45". */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
