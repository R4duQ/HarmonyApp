package com.harmony.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Harmony's theme: the Listening Room ([RoomColors], [RoomType], [RoomMotion])
 * in light, dark and AMOLED.
 *
 * The room is the base of every Material role, so stock components (sheets,
 * dialogs, snackbars, switches) wear it too. With wallpaper colours on
 * (Android 12+), only the accent is repainted; the ground, the ink and the
 * status colours stay Harmony's.
 *
 * It also provides the older names screens still read ([Catalog],
 * [LocalCyberGlass]), resolved from the same room, and whether the system
 * asked for animations to be removed ([reducedMotion]).
 */
@Composable
fun HarmonyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    dynamicColors: Boolean = true,
    reducedMotion: Boolean = rememberSystemReducedMotion(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var room = if (darkTheme) DarkRoom else LightRoom
    if (darkTheme && amoled) room = room.amoled()
    if (dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val wallpaper = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        room = room.withWallpaper(
            primary = wallpaper.primary,
            onPrimary = wallpaper.onPrimary,
            container = wallpaper.primaryContainer,
            onContainer = wallpaper.onPrimaryContainer,
        )
    }
    val catalog = remember(room) { room.toCatalog() }
    val glass = remember(catalog) { cyberGlassFrom(catalog) }
    CompositionLocalProvider(
        LocalRoom provides room,
        LocalHarmonyColors provides catalog,
        LocalCyberGlass provides glass,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(colorScheme = room.toColorScheme(), typography = HarmonyTypography, content = content)
    }
}
