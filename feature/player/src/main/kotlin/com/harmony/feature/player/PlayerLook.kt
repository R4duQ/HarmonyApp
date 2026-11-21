package com.harmony.feature.player

import com.harmony.core.ui.theme.Catalog

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
 * The roles the Now Playing screen, the queue and the Smart Shuffle sheet
 * draw with, from the Listening Room ([Room]): the page's ground, the amber
 * play button (the one filled amber control), amber text for what you
 * switched on and for the playhead (amber text passes 4.5:1 on the ground;
 * the fill would not, in light), and the record, dark in both themes.
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
    /** Active shuffle/repeat, favorite-on: things you switched on. */
    val accent: Color,
    /** The playhead and the played part of the waveform: what is playing now. */
    val live: Color,
    /** Unplayed waveform. */
    val track: Color,
    val vinyl: Color,
    val vinylGroove: Color,
    val artShadow: Color,
)

/** The player's palette for the current theme, from [Room]. */
@Composable
fun playerPalette(): PlayerPalette {
    val r = com.harmony.core.ui.theme.Room
    return PlayerPalette(
        background = r.ground,
        ink = r.ink,
        muted = r.muted,
        control = r.surface,
        controlEdge = r.line,
        primaryControl = r.amber,
        onPrimaryControl = r.onAmber,
        accent = r.amberText,
        live = r.amberText,
        track = r.line,
        vinyl = r.record,
        vinylGroove = Color(0x1AFFFFFF),
        artShadow = Color.Black,
    )
}

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

/** Now Playing's ground (the neumorphic frames' #E8ECEE, or #25292B in dark), for the strip under the status bar. */
@Composable
fun nowPlayingGround(): Color = com.harmony.feature.player.neu.neuPalette().ground
