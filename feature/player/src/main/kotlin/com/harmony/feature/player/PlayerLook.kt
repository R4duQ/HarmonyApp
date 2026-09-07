package com.harmony.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Now Playing screen has its own small palette rather than borrowing the
 * app's Material scheme.
 *
 * Reason: the design is built on one specific soft-lavender field with very
 * low-contrast tonal controls, and Material's dynamic colors would repaint
 * that field with whatever the wallpaper happens to be — which is exactly the
 * thing the design is trading away. Every other screen still follows the
 * user's dynamic/dark/AMOLED settings untouched; only the player opts out.
 *
 * It still follows light/dark, by reading the active scheme's luminance
 * instead of taking a parameter — so the AMOLED and dark-mode toggles keep
 * working here without any new plumbing through the ViewModel or navigation.
 */
@Immutable
data class PlayerPalette(
    /** The full-bleed field the whole screen sits on. */
    val background: Color,
    /** Title text and primary icons. */
    val ink: Color,
    /** Artist, captions, inactive icons. */
    val muted: Color,
    /** Fill for the small tonal circles (back, overflow, favorite, transport). */
    val control: Color,
    /** Hairline around those circles — carries the shape at low contrast. */
    val controlEdge: Color,
    /** The one raised control: play/pause. */
    val primaryControl: Color,
    val onPrimaryControl: Color,
    /** Played waveform, active shuffle/repeat, favorite-on. */
    val accent: Color,
    /** Unplayed waveform. */
    val track: Color,
    val vinyl: Color,
    val vinylGroove: Color,
    val artShadow: Color,
)

private val LightPlayerPalette = PlayerPalette(
    background = Color(0xFFDCD7FA),
    ink = Color(0xFF17142B),
    muted = Color(0xFF5D5878),
    control = Color(0xFFE8E4FD),
    controlEdge = Color(0x1417142B),
    primaryControl = Color(0xFFFFFFFF),
    onPrimaryControl = Color(0xFF17142B),
    accent = Color(0xFF6C4BE8),
    track = Color(0xFFB4ACDD),
    vinyl = Color(0xFF17151C),
    vinylGroove = Color(0x1FFFFFFF),
    artShadow = Color(0xFF2A2350),
)

private val DarkPlayerPalette = PlayerPalette(
    background = Color(0xFF15121F),
    ink = Color(0xFFF1EEFF),
    muted = Color(0xFF9E98BF),
    control = Color(0xFF232032),
    controlEdge = Color(0x1AFFFFFF),
    primaryControl = Color(0xFFEDE9FF),
    onPrimaryControl = Color(0xFF17142B),
    accent = Color(0xFF9B85FF),
    track = Color(0xFF393452),
    vinyl = Color(0xFF0B0A10),
    vinylGroove = Color(0x14FFFFFF),
    artShadow = Color(0xFF000000),
)

/**
 * Picks the light or dark player palette from the ambient Material scheme, so
 * the player follows the app's theme setting (system / light / dark / AMOLED)
 * without needing it passed down.
 */
@Composable
fun playerPalette(): PlayerPalette =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkPlayerPalette
    else LightPlayerPalette

/**
 * The tonal circle used for every secondary control on this screen.
 *
 * Deliberately not [androidx.compose.material3.FilledIconButton]: that would
 * pull the Material scheme's container color back in, and it has no border
 * slot — and at this contrast level the hairline is doing most of the work of
 * making the target readable as a button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    containerColor: Color = playerPalette().control,
    contentColor: Color = playerPalette().ink,
    borderColor: Color = playerPalette().controlEdge,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        // The label lives on the Surface so TalkBack announces one button,
        // not a button wrapping a separately-labelled icon.
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                content()
            }
        }
    }
}
