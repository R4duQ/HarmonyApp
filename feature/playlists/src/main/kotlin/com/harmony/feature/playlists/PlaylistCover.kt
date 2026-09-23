package com.harmony.feature.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.glassRim

/** Hairline between mosaic tiles: enough to read as four covers, not one blurred image. */
private val TileGap = 1.5.dp

/**
 * The large header cover.
 *
 * Layout follows how many different albums the playlist draws on, so it
 * always looks deliberate instead of padding holes with repeats:
 *  - none: a tinted placeholder carrying the playlist's glyph;
 *  - one: that cover, full size;
 *  - two: side by side;
 *  - three: one tall cover beside two stacked;
 *  - four or more: a 2x2 grid.
 *
 * Every tile sits on its own placeholder, so a cover that fails to load
 * (a moved file, a revoked grant) shows the glyph instead of a hole.
 */
@Composable
internal fun PlaylistCover(
    artwork: List<String>,
    palette: EditorialPalette,
    smartType: SmartPlaylistType?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(size * 0.085f)
    Box(
        modifier
            .size(size)
            // Shadow tinted with the section's accent rather than black: on
            // the near-white field a black drop reads as dirt, a tinted one
            // as the cover lifting off the glass.
            .shadow(
                elevation = 22.dp,
                shape = shape,
                ambientColor = palette.accent.copy(alpha = 0.35f),
                spotColor = palette.accent.copy(alpha = 0.35f),
            )
            .clip(shape)
            .background(palette.field)
            .border(1.dp, glassRim(palette), shape),
    ) {
        val glyph = smartType?.glyph() ?: Icons.Rounded.QueueMusic
        when (artwork.size) {
            0 -> CoverPlaceholder(palette, glyph, Modifier.fillMaxSize(), glyphSize = size * 0.3f)
            1 -> CoverTile(artwork[0], palette, glyph, Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                HalfTile(artwork[0], palette, glyph)
                HalfTile(artwork[1], palette, glyph)
            }
            3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                HalfTile(artwork[0], palette, glyph)
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(TileGap),
                ) {
                    QuarterTile(artwork[1], palette, glyph)
                    QuarterTile(artwork[2], palette, glyph)
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TileGap)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    HalfTile(artwork[0], palette, glyph)
                    HalfTile(artwork[1], palette, glyph)
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    HalfTile(artwork[2], palette, glyph)
                    HalfTile(artwork[3], palette, glyph)
                }
            }
        }
    }
}

@Composable
private fun RowScope.HalfTile(uri: String, palette: EditorialPalette, glyph: ImageVector) =
    CoverTile(uri, palette, glyph, Modifier.weight(1f).fillMaxHeight())

@Composable
private fun ColumnScope.QuarterTile(uri: String, palette: EditorialPalette, glyph: ImageVector) =
    CoverTile(uri, palette, glyph, Modifier.weight(1f).fillMaxWidth())

/**
 * One cover over its placeholder. The image draws nothing until it has
 * loaded, and nothing at all if it fails, so the placeholder underneath is
 * what shows in both of those states.
 */
@Composable
internal fun CoverTile(
    uri: String?,
    palette: EditorialPalette,
    glyph: ImageVector,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 22.dp,
) {
    Box(modifier) {
        CoverPlaceholder(palette, glyph, Modifier.fillMaxSize(), glyphSize)
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun CoverPlaceholder(
    palette: EditorialPalette,
    glyph: ImageVector,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 22.dp,
) {
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(
                    palette.accent.copy(alpha = 0.30f),
                    palette.accent.copy(alpha = 0.10f),
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            glyph,
            contentDescription = null,
            tint = palette.accent.copy(alpha = 0.85f),
            modifier = Modifier.size(glyphSize),
        )
    }
}

/** A song row's small cover: its own art, or the note glyph on the tinted placeholder. */
@Composable
internal fun SongThumb(uri: String?, palette: EditorialPalette, size: Dp, modifier: Modifier = Modifier) {
    CoverTile(
        uri = uri,
        palette = palette,
        glyph = Icons.Rounded.MusicNote,
        glyphSize = size * 0.4f,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.2f)),
    )
}

internal fun SmartPlaylistType.glyph(): ImageVector = when (this) {
    SmartPlaylistType.FAVORITES -> Icons.Rounded.Favorite
    SmartPlaylistType.MOST_PLAYED -> Icons.Rounded.TrendingUp
    SmartPlaylistType.RECENTLY_ADDED -> Icons.Rounded.NewReleases
    SmartPlaylistType.RECENTLY_PLAYED -> Icons.Rounded.History
    SmartPlaylistType.HIGHEST_ENERGY -> Icons.Rounded.Bolt
    SmartPlaylistType.LOWEST_ENERGY -> Icons.Rounded.SelfImprovement
}
