package com.harmony.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.Song
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialRaisedButton
import com.harmony.core.ui.component.amberPalette
import java.util.Calendar

/**
 * The Home dashboard.
 *
 * A single LazyColumn rather than a scrolling Column: the "Continue listening"
 * row and the recent-history items are the only parts that can grow, and a
 * lazy parent means the artwork below the fold is never composed or decoded
 * until it is scrolled to.
 *
 * The mini player and bottom navigation are NOT drawn here. They belong to the
 * app shell, which already renders them above every top-level destination —
 * duplicating them would put two transport bars on screen and a second set of
 * controls over the same playback service. The shell's inner padding is passed
 * down as [contentPadding] so nothing hides underneath them.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onOpenFlacCheck: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onSongClick: (Song) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mixStarting by viewModel.mixStarting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val palette = amberPalette()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.field),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(key = "header") {
                DashboardHeader(
                    palette = palette,
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings,
                )
            }

            item(key = "greeting") { Greeting(palette) }

            item(key = "smart-shuffle") {
                SmartShuffleCard(
                    palette = palette,
                    starting = mixStarting,
                    artwork = state.recentlyPlayed,
                    onStartMix = viewModel::startSmartMix,
                )
            }

            item(key = "stats") { LibraryStatsCard(state.stats, palette, onOpenLibrary) }

            item(key = "continue-title") {
                SectionTitle("Continue listening", palette)
            }
            item(key = "continue-row") {
                ContinueListeningRow(
                    songs = state.recentlyPlayed,
                    palette = palette,
                    libraryEmpty = state.libraryEmpty,
                    // Playback is the screen's own job; the callback stays so a
                    // caller can also react (analytics, navigation) if it wants.
                    onSongClick = { song ->
                        viewModel.playSong(song)
                        onSongClick(song)
                    },
                    onOpenLibrary = onOpenLibrary,
                )
            }

            item(key = "quick-actions") {
                QuickActions(
                    palette = palette,
                    onOpenDownloads = onOpenDownloads,
                    onOpenDiscover = onOpenDiscover,
                    onOpenFlacCheck = onOpenFlacCheck,
                )
            }

            item(key = "quality") {
                QualityCard(state.quality, palette, onOpenFlacCheck)
            }

            item(key = "tail") { Spacer(Modifier.height(12.dp)) }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(14.dp),
                containerColor = palette.ink,
                contentColor = palette.field,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun DashboardHeader(
    palette: EditorialPalette,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The launcher mark, redrawn rather than referenced as a mipmap:
        // launcher icons carry adaptive-icon padding that looks wrong inline.
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "H",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = palette.onAccent,
            )
        }
        Text(
            "Harmony",
            fontSize = 25.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = palette.ink,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        HeaderIconButton(Icons.Rounded.Search, "Search your library", palette, onOpenSearch)
        Spacer(Modifier.width(10.dp))
        HeaderIconButton(Icons.Rounded.Settings, "Settings", palette, onOpenSettings)
    }
}

/** 48dp touch target with a 42dp visual ring, so the outline matches the design
 *  while the tappable area still clears the accessibility floor. */
@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    description: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
            contentColor = palette.ink,
            border = BorderStroke(1.dp, palette.line),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Greeting(palette: EditorialPalette) {
    // Recomputed only when the composable enters; the greeting does not need
    // to tick over at midnight and a timer would cost more than it is worth.
    val greeting = remember { greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp)) {
        Text(
            greeting,
            fontSize = 40.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
            color = palette.ink,
        )
        Text(
            "Your music. Your files. Your rules.",
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = palette.muted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

internal fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..21 -> "Good evening"
    else -> "Good night"
}

// ---------------------------------------------------------------------------
// Smart Shuffle
// ---------------------------------------------------------------------------

@Composable
private fun SmartShuffleCard(
    palette: EditorialPalette,
    starting: Boolean,
    artwork: List<Song>,
    onStartMix: () -> Unit,
) {
    EditorialCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Smart Shuffle",
                    fontSize = 26.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = palette.ink,
                )
                Text(
                    "A mix shaped around your library",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Box(Modifier.padding(top = 18.dp)) {
                    EditorialRaisedButton(
                        text = if (starting) "Starting…" else "Start Mix",
                        onClick = onStartMix,
                        palette = palette,
                        enabled = !starting,
                        contentDescription = "Start Mix. Begins Smart Shuffle from your library.",
                        modifier = Modifier.width(150.dp),
                    )
                    if (starting) {
                        CircularProgressIndicator(
                            color = palette.onAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(18.dp),
                        )
                    }
                }
            }
            ArtworkCluster(artwork, palette)
        }
    }
}

/**
 * The orbiting artwork in the reference. Uses real recently-played covers when
 * there are any, and falls back to empty tiles rather than stock images —
 * showing artwork for music the user does not have would be a lie about their
 * library.
 */
@Composable
private fun ArtworkCluster(songs: List<Song>, palette: EditorialPalette) {
    val tiles = songs.take(3)
    Box(
        Modifier
            .size(124.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (tiles.isNotEmpty()) {
            tiles.getOrNull(1)?.let {
                SatelliteCover(it.artworkUri, palette, Modifier.align(Alignment.TopStart))
            }
            tiles.getOrNull(2)?.let {
                SatelliteCover(it.artworkUri, palette, Modifier.align(Alignment.BottomEnd))
            }
        }
        Surface(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(14.dp),
            color = palette.field,
            border = BorderStroke(1.5.dp, palette.line),
        ) {
            val hero = tiles.firstOrNull()
            if (hero != null) {
                Artwork(hero.artworkUri, null, cornerRadius = 14.dp)
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/**
 * A smaller cover behind the hero, ringed in the field colour so the overlap
 * reads as a stack rather than as two artworks colliding.
 */
@Composable
private fun SatelliteCover(
    artworkUri: String?,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(50.dp),
        shape = RoundedCornerShape(11.dp),
        color = palette.field,
        border = BorderStroke(2.dp, palette.field),
    ) {
        Artwork(artworkUri, null, cornerRadius = 9.dp)
    }
}

// ---------------------------------------------------------------------------
// Library stats
// ---------------------------------------------------------------------------

@Composable
private fun LibraryStatsCard(
    stats: LibraryStats,
    palette: EditorialPalette,
    onOpenLibrary: () -> Unit,
) {
    EditorialCard(
        palette = palette,
        onClick = onOpenLibrary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Text(
                "Your Library",
                fontSize = 21.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
            )
            // Four tiles where the reference shows three: playlists is part of
            // the brief. BoxWithConstraints drops to two rows of two when the
            // width (or the user's font scale) cannot fit four across, instead
            // of letting the numbers clip.
            BoxWithConstraints(Modifier.padding(top = 16.dp)) {
                val perRow = if (maxWidth >= 320.dp) 4 else 2
                Column(Modifier.fillMaxWidth()) {
                    STAT_TILES.chunked(perRow).forEach { rowTiles ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            rowTiles.forEach { tile ->
                                StatTile(
                                    icon = tile.icon,
                                    value = tile.value(stats),
                                    label = tile.label,
                                    palette = palette,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(perRow - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

private class StatTileSpec(
    val icon: ImageVector,
    val label: String,
    val value: (LibraryStats) -> Int,
)

private val STAT_TILES = listOf(
    StatTileSpec(Icons.Rounded.MusicNote, "Songs") { it.songs },
    StatTileSpec(Icons.Rounded.Album, "Albums") { it.albums },
    StatTileSpec(Icons.Rounded.Person, "Artists") { it.artists },
    StatTileSpec(Icons.AutoMirrored.Rounded.QueueMusic, "Playlists") { it.playlists },
)

@Composable
private fun StatTile(
    icon: ImageVector,
    value: Int,
    label: String,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.semantics {
            contentDescription = "$value $label"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.line.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.ink, modifier = Modifier.size(18.dp))
        }
        Text(
            formatCount(value),
            fontSize = 20.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Thousands separators, matching the reference's "1,306". */
internal fun formatCount(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

// ---------------------------------------------------------------------------
// Continue listening
// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String, palette: EditorialPalette) {
    Text(
        text,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        color = palette.ink,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
    )
}

@Composable
private fun ContinueListeningRow(
    songs: List<Song>,
    palette: EditorialPalette,
    libraryEmpty: Boolean,
    onSongClick: (Song) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    if (songs.isEmpty()) {
        HistoryEmptyState(palette, libraryEmpty, onOpenLibrary)
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Keyed by song id so scrolling the row does not rebind artwork when
        // history re-emits after a play is recorded.
        items(songs, key = { it.id }) { song ->
            Column(
                Modifier
                    .width(142.dp)
                    // No .clip() around the text: the rounded clip cut the
                    // left side bearing off the first glyph of every artist
                    // name ("Iuly" rendered as "'uly"). Only the artwork
                    // needs rounding, and it rounds itself.
                    .clickable { onSongClick(song) }
                    .semantics {
                        contentDescription = "${song.title} by ${song.artist}. Play."
                    },
            ) {
                Artwork(
                    artworkUri = song.artworkUri,
                    contentDescription = null,
                    cornerRadius = 14.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Text(
                    song.title,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, start = 1.dp),
                )
                Text(
                    song.artist,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(
    palette: EditorialPalette,
    libraryEmpty: Boolean,
    onOpenLibrary: () -> Unit,
) {
    EditorialCard(
        palette = palette,
        onClick = onOpenLibrary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(26.dp),
            )
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    if (libraryEmpty) "No music yet" else "Nothing played yet",
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                )
                Text(
                    if (libraryEmpty) {
                        "Add music to your device, then open Library to scan it."
                    } else {
                        "Play something and it will show up here."
                    },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Quick actions
// ---------------------------------------------------------------------------

@Composable
private fun QuickActions(
    palette: EditorialPalette,
    onOpenDownloads: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenFlacCheck: () -> Unit,
) {
    // Equalizer occupied the middle slot in the reference; Discover replaces
    // it here. Equalizer itself is untouched and still reachable from Settings
    // and the Now Playing overflow menu.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickAction("Downloads", Icons.Rounded.Download, palette, Modifier.weight(1f), onOpenDownloads)
        QuickAction("Discover", Icons.Rounded.Explore, palette, Modifier.weight(1f), onOpenDiscover)
        QuickAction("FLAC Check", Icons.Rounded.GraphicEq, palette, Modifier.weight(1f), onOpenFlacCheck)
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: ImageVector,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 56.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        contentColor = palette.ink,
        border = BorderStroke(1.dp, palette.line),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                label,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Audio quality
// ---------------------------------------------------------------------------

@Composable
private fun QualityCard(
    quality: QualityStatus,
    palette: EditorialPalette,
    onOpenFlacCheck: () -> Unit,
) {
    // Deliberately not a lossless/lossy verdict: this card only has the
    // scanner's tag data, and the mini player directly below already states
    // the container authoritatively after probing the file. Two components
    // disagreeing about the same track is worse than one staying quiet.
    val title = when {
        !quality.hasSong -> "Audio quality"
        (quality.bitDepth ?: 0) >= 24 -> "High resolution"
        quality.isLossless -> "Now playing"
        else -> "Now playing"
    }
    val detail = when {
        !quality.hasSong -> "Play a track to see its details"
        quality.detail.isNotBlank() -> quality.detail
        else -> "Open FLAC Check to inspect this file"
    }
    EditorialCard(
        palette = palette,
        onClick = onOpenFlacCheck,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.line.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = palette.ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
                    .semantics { contentDescription = "$title. $detail. Opens FLAC Check." },
            ) {
                Text(
                    title,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
                Text(
                    detail,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
