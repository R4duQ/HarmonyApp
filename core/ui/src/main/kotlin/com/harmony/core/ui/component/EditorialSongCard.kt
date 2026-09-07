package com.harmony.core.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.Song

/**
 * The song row used by every editorial-styled list — Library's Songs tab and
 * search results (amber), album detail (amber), playlist detail (green).
 *
 * It lives in core:ui rather than in a feature module because playlist detail
 * and library detail are in different Gradle modules and must not depend on
 * each other; the palette is a parameter, so the same card serves both fields.
 *
 * Swipe right to queue is built in whenever [onPlayNext] is supplied, and the
 * same action is repeated in the ⋮ menu — a swipe nobody can discover is not
 * a feature.
 *
 * @param dragHandle optional reorder grip, drawn at the leading edge. The
 *   caller owns its gesture, which should be long-press-scoped so it doesn't
 *   fight the list's scrolling.
 * @param onRemove adds a destructive entry to the ⋮ menu, labelled [removeLabel].
 */
@Composable
fun EditorialSongCard(
    song: Song,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayNext: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove",
    dragHandle: (@Composable () -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onPlayNext != null || onAddToPlaylist != null || onRemove != null

    val card: @Composable () -> Unit = {
        EditorialCard(
            palette = palette,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dragHandle != null) {
                    dragHandle()
                    Spacer4()
                }
                Box(
                    Modifier
                        .size(56.dp)
                        .border(1.5.dp, palette.line, RoundedCornerShape(12.dp)),
                ) {
                    Artwork(
                        artworkUri = song.artworkUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(1.5.dp),
                        cornerRadius = 10.dp,
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        song.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            song.artist,
                            fontSize = 12.sp,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            "  •  ${formatDuration(song.durationMs)}",
                            fontSize = 12.sp,
                            color = palette.muted,
                            maxLines = 1,
                        )
                    }
                }
                if (hasMenu) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "More options for ${song.title}",
                                tint = palette.muted,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (onPlayNext != null) {
                                DropdownMenuItem(
                                    text = { Text("Play next") },
                                    onClick = { menuOpen = false; onPlayNext() },
                                )
                            }
                            if (onAddToPlaylist != null) {
                                DropdownMenuItem(
                                    text = { Text("Add to playlist") },
                                    onClick = { menuOpen = false; onAddToPlaylist() },
                                )
                            }
                            if (onRemove != null) {
                                DropdownMenuItem(
                                    text = { Text(removeLabel) },
                                    onClick = { menuOpen = false; onRemove() },
                                )
                            }
                        }
                    }
                }
                EditorialCircleButton(
                    onClick = onClick,
                    contentDescription = "Play ${song.title}",
                    palette = palette,
                    size = 40.dp,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    if (onPlayNext == null) {
        Box(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) { card() }
        return
    }
    EditorialSwipeToQueue(
        palette = palette,
        onQueue = onPlayNext,
        icon = Icons.Rounded.QueueMusic,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    ) { card() }
}

@Composable
private fun Spacer4() {
    Box(Modifier.width(8.dp))
}
