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
 * Harmony's theme: the record-shop catalogue ([HarmonyColors]) in light, dark
 * and AMOLED, with Archivo on one type scale ([HarmonyTypography]).
 *
 * The catalogue is the base of every Material role, so stock components (sheets,
 * dialogs, snackbars, switches) wear it too. With dynamic colours on (Android
 * 12+), the wallpaper repaints only the structure colour, the catalogue blue;
 * the ground, the ink and the red that marks what is playing stay Harmony's.
 *
 * It also provides the Cyber-Glass scheme ([LocalCyberGlass]) that older
 * components read, now resolved from the same catalogue.
 */
@Composable
fun HarmonyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    dynamicColors: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var colors = if (darkTheme) DarkHarmonyColors else LightHarmonyColors
    if (darkTheme && amoled) colors = colors.amoled()
    if (dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val wallpaper = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        colors = colors.withStructure(
            primary = wallpaper.primary,
            onPrimary = wallpaper.onPrimary,
            container = wallpaper.primaryContainer,
            onContainer = wallpaper.onPrimaryContainer,
        )
    }
    val glass = remember(colors) { cyberGlassFrom(colors) }
    CompositionLocalProvider(
        LocalHarmonyColors provides colors,
        LocalCyberGlass provides glass,
    ) {
        MaterialTheme(colorScheme = colors.toColorScheme(), typography = HarmonyTypography, content = content)
    }
}
