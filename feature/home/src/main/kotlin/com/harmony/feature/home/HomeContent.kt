package com.harmony.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.Song
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialRaisedButton
import com.harmony.core.ui.component.GlassSearchBar

/** Everything Home can ask for. Plain lambdas so tests and previews can pass no-ops. */
@Immutable
internal class HomeActions(
    val onOpenSearch: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenNowPlaying: () -> Unit = {},
    val onTogglePlayback: () -> Unit = {},
    val onStartMix: () -> Unit = {},
    val onPlayRecent: (Song) -> Unit = {},
    val onOpenPlaylist: (Long) -> Unit = {},
    val onPlayPlaylist: (Long) -> Unit = {},
    val onOpenPlaylists: () -> Unit = {},
    val onOpenAlbum: (Long) -> Unit = {},
    val onPlayAlbum: (Long) -> Unit = {},
    val onOpenLibrary: () -> Unit = {},
    val onOpenDiscover: () -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
    val onOpenFlacCheck: () -> Unit = {},
)

internal object HomeTags {
    const val LIST = "home_list"
    const val SEARCH = "home_search"
    const val SETTINGS = "home_settings"
    const val CONTINUE = "home_continue"
    const val CONTINUE_PLAY = "home_continue_play"
    const val CONTINUE_LOADING = "home_continue_loading"
    const val WELCOME = "home_welcome"
    const val WELCOME_LIBRARY = "home_welcome_library"
    const val WELCOME_DISCOVER = "home_welcome_discover"
    const val NO_HISTORY = "home_no_history"
    const val MIX = "home_mix"
    const val DISCOVER = "home_discover"
    const val OFFLINE = "home_offline"
    const val LIBRARY_ROW = "home_library_row"
    const val DOWNLOADS_ROW = "home_downloads_row"
    const val FLAC_ROW = "home_flac_row"
    const val NO_PLAYLISTS = "home_no_playlists"
    const val END = "home_end"
    fun recent(id: Long) = "home_recent_$id"
    fun playlist(id: Long) = "home_playlist_$id"
    fun playlistPlay(id: Long) = "home_playlist_play_$id"
    fun album(id: Long) = "home_album_$id"
    fun albumPlay(id: Long) = "home_album_play_$id"
}

/**
 * Home.
 *
 * Reads top to bottom as "what am I listening to, what did I listen to,
 * what have I got": a quiet header with search and settings; the song in
 * the player (or the last one played) as the one large card; then recently
 * played songs, playlists and recently added albums as rows of covers; then
 * Discover; and last the library and tools, as one grouped card rather than
 * a grid of shortcut tiles.
 *
 * A single LazyColumn so nothing below the fold is composed or decoded until
 * it is scrolled to. Every item has a stable key and a fixed-size placeholder
 * while loading, so nothing jumps when data or images arrive, and the list
 * state is saveable, so coming back to Home lands where it was left.
 */
@Composable
internal fun HomeContent(
    state: HomeUiState,
    palette: EditorialPalette,
    actions: HomeActions,
    greeting: String,
    modifier: Modifier = Modifier,
    progress: () -> Float = { 0f },
    mixStarting: Boolean = false,
    /** null = unknown, treated as online. */
    online: Boolean? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
) {
    val loaded = state.loaded
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(palette.field)
            .testTag(HomeTags.LIST),
        contentPadding = contentPadding,
    ) {
        item(key = "header", contentType = "header") {
            Header(greeting, palette, actions)
        }

        item(key = "continue", contentType = "continue") {
            Box(Modifier.appearOnLoad(loaded, 0)) {
                when {
                    !loaded -> ContinuePlaceholder(palette)
                    state.libraryEmpty -> WelcomeCard(palette, actions)
                    state.nowPlaying != null -> ContinueCard(
                        song = state.nowPlaying.song,
                        isPlaying = state.nowPlaying.isPlaying,
                        eyebrow = if (state.nowPlaying.isPlaying) "NOW PLAYING" else "CONTINUE LISTENING",
                        detail = queueLabel(state.nowPlaying),
                        progress = progress,
                        palette = palette,
                        onOpen = actions.onOpenNowPlaying,
                        openLabel = "Open player",
                        onPlayPause = actions.onTogglePlayback,
                    )
                    state.resumeSong != null -> ContinueCard(
                        song = state.resumeSong,
                        isPlaying = false,
                        eyebrow = "LAST PLAYED",
                        detail = null,
                        progress = null,
                        palette = palette,
                        // Nothing is loaded to "open", so the card plays too.
                        onOpen = actions.onTogglePlayback,
                        openLabel = "Play",
                        onPlayPause = actions.onTogglePlayback,
                    )
                    else -> NoHistoryCard(palette)
                }
            }
        }

        if (!loaded || !state.libraryEmpty) {
            item(key = "mix", contentType = "mix") {
                SmartShuffleCard(
                    palette = palette,
                    starting = mixStarting,
                    enabled = loaded,
                    onStartMix = actions.onStartMix,
                    modifier = Modifier.appearOnLoad(loaded, 1),
                )
            }
        }

        if (!loaded) {
            item(key = "loading-row", contentType = "row") {
                Column {
                    SectionHeader("Recently played", palette)
                    RowPlaceholder(palette)
                }
            }
        }

        if (state.recentlyPlayed.isNotEmpty()) {
            item(key = "recent", contentType = "row") {
                Column(Modifier.appearOnLoad(loaded, 2)) {
                    SectionHeader("Recently played", palette)
                    CoverRow(state.recentlyPlayed, key = { it.id }) { song ->
                        SongCard(song, palette, onPlay = { actions.onPlayRecent(song) })
                    }
                }
            }
        }

        if (loaded && !state.libraryEmpty) {
            item(key = "playlists", contentType = "row") {
                Column(Modifier.appearOnLoad(loaded, 3)) {
                    SectionHeader(
                        "Your playlists",
                        palette,
                        actionLabel = if (state.playlists.isNotEmpty()) "See all" else null,
                        onAction = actions.onOpenPlaylists,
                    )
                    if (state.playlists.isEmpty()) {
                        InlineNote(
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            title = "No playlists yet",
                            body = "Create one in Playlists, or import an M3U file.",
                            palette = palette,
                            onClick = actions.onOpenPlaylists,
                            modifier = Modifier.testTag(HomeTags.NO_PLAYLISTS),
                        )
                    } else {
                        CoverRow(state.playlists, key = { it.id }) { playlist ->
                            PlaylistCard(playlist, palette, actions)
                        }
                    }
                }
            }
        }

        if (state.recentAlbums.isNotEmpty()) {
            item(key = "albums", contentType = "row") {
                Column(Modifier.appearOnLoad(loaded, 4)) {
                    SectionHeader("Recently added", palette)
                    CoverRow(state.recentAlbums, key = { it.id }) { album ->
                        AlbumCard(album, palette, actions)
                    }
                }
            }
        }

        item(key = "discover", contentType = "discover") {
            Column(Modifier.appearOnLoad(loaded, 5)) {
                SectionHeader("Discover", palette)
                DiscoverCard(palette, online, actions.onOpenDiscover)
            }
        }

        item(key = "tools", contentType = "tools") {
            Column(Modifier.appearOnLoad(loaded, 6)) {
                SectionHeader("Library & tools", palette)
                ToolsCard(state, palette, actions)
            }
        }

        item(key = "end", contentType = "end") {
            // Breathing room above the floating mini player and nav bar,
            // on top of the clearance the shell passes in contentPadding.
            Spacer(Modifier.height(28.dp).testTag(HomeTags.END))
        }
    }
}

private fun queueLabel(card: NowPlayingCard): String? =
    if (card.queueSize > 1) "${card.queuePosition} of ${formatCount(card.queueSize)} in queue" else null

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun Header(greeting: String, palette: EditorialPalette, actions: HomeActions) {
    // A step smaller at large font sizes, so "Good evening" still breaks
    // between words on a narrow phone instead of inside one.
    val large = LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.padding(start = HomeGutter, end = HomeGutter - 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    greeting,
                    fontSize = if (large) 24.sp else 30.sp,
                    lineHeight = if (large) 29.sp else 35.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp,
                    color = palette.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Your music. Your files. Your rules.",
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            GlassIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = "Settings",
                palette = palette,
                onClick = actions.onOpenSettings,
                modifier = Modifier.testTag(HomeTags.SETTINGS),
            )
        }
        // The same glass pill Library uses, inactive: a tap hands straight
        // over to Library's real search, so there is one search in the app.
        // The pill brings its own 16dp side inset; 4dp more lines it up
        // with the 20dp page gutter.
        Box(
            Modifier
                .padding(horizontal = HomeGutter - 16.dp)
                .padding(top = 6.dp)
                .testTag(HomeTags.SEARCH),
        ) {
            GlassSearchBar(
                active = false,
                value = "",
                onValueChange = {},
                onFieldClick = actions.onOpenSearch,
                onClear = {},
                palette = palette,
                placeholder = "Search your library",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Continue
// ---------------------------------------------------------------------------

@Composable
private fun ContinueCard(
    song: Song,
    isPlaying: Boolean,
    eyebrow: String,
    detail: String?,
    progress: (() -> Float)?,
    palette: EditorialPalette,
    onOpen: () -> Unit,
    openLabel: String,
    onPlayPause: () -> Unit,
) {
    // At large font sizes the text gets the card's full width under the
    // cover and button, instead of a sliver between them.
    val largeText = LocalDensity.current.fontScale > 1.3f
    val coverSize = if (largeText) 72.dp else 92.dp
    HomeCard(
        palette = palette,
        modifier = Modifier
            .padding(horizontal = HomeGutter)
            .padding(top = 20.dp)
            .fillMaxWidth()
            .pressable(onOpen, onClickLabel = openLabel, pressedScale = 0.985f)
            .testTag(HomeTags.CONTINUE),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            val texts = @Composable { textModifier: Modifier ->
                Column(textModifier) {
                    Text(
                        eyebrow,
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent,
                        maxLines = 1,
                    )
                    Text(
                        song.title,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        song.artist,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail != null) {
                        Text(
                            detail,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(song.artworkUri, palette, Modifier.size(coverSize), RoundedCornerShape(14.dp), elevation = 8.dp)
                if (largeText) {
                    Spacer(Modifier.weight(1f))
                } else {
                    texts(Modifier.weight(1f).padding(horizontal = 14.dp))
                }
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .pressable(onPlayPause, pressedScale = 0.9f)
                        .semantics { contentDescription = if (isPlaying) "Pause" else "Play ${song.title}" }
                        .testTag(HomeTags.CONTINUE_PLAY),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .shadow(10.dp, CircleShape, ambientColor = palette.accent, spotColor = palette.accent)
                            .clip(CircleShape)
                            .background(palette.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = palette.onAccent,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            if (largeText) texts(Modifier.fillMaxWidth().padding(top = 12.dp))
            if (progress != null) {
                // Drawn, not composed: position ticks twice a second while
                // playing, and reading it only inside drawBehind means those
                // ticks redraw this one line and recompose nothing.
                Box(
                    Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clearAndSetSemantics { }
                        .drawBehind {
                            val r = CornerRadius(size.height / 2)
                            drawRoundRect(palette.line, cornerRadius = r)
                            val done = progress().coerceIn(0f, 1f)
                            if (done > 0f) {
                                drawRoundRect(palette.accent, size = Size(size.width * done, size.height), cornerRadius = r)
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun ContinuePlaceholder(palette: EditorialPalette) {
    HomeCard(
        palette = palette,
        modifier = Modifier
            .padding(horizontal = HomeGutter)
            .padding(top = 20.dp)
            .fillMaxWidth()
            .testTag(HomeTags.CONTINUE_LOADING),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Placeholder(palette, Modifier.size(92.dp), RoundedCornerShape(14.dp))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Placeholder(palette, Modifier.width(90.dp).height(10.dp))
                    Spacer(Modifier.height(10.dp))
                    Placeholder(palette, Modifier.fillMaxWidth(0.8f).height(16.dp))
                    Spacer(Modifier.height(8.dp))
                    Placeholder(palette, Modifier.fillMaxWidth(0.5f).height(12.dp))
                }
                Placeholder(palette, Modifier.size(52.dp), CircleShape)
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeCard(palette: EditorialPalette, actions: HomeActions) {
    HomeCard(
        palette = palette,
        modifier = Modifier
            .padding(horizontal = HomeGutter)
            .padding(top = 20.dp)
            .fillMaxWidth()
            .testTag(HomeTags.WELCOME),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            IconDisc(Icons.Rounded.LibraryMusic, palette, size = 52)
            Text(
                "No music yet",
                fontSize = 22.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                "Add music to your device, then open Library to scan it. You can also find and download music from Discover.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Wraps to a second line when both pills do not fit — a narrow
            // phone, a large font — instead of cutting "Discover" short.
            FlowRow(
                Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Pill("Open Library", Icons.Rounded.LibraryMusic, palette, filled = true, onClick = actions.onOpenLibrary, modifier = Modifier.testTag(HomeTags.WELCOME_LIBRARY))
                Pill("Discover", Icons.Rounded.Explore, palette, filled = false, onClick = actions.onOpenDiscover, modifier = Modifier.testTag(HomeTags.WELCOME_DISCOVER))
            }
        }
    }
}

@Composable
private fun NoHistoryCard(palette: EditorialPalette) {
    HomeCard(
        palette = palette,
        modifier = Modifier
            .padding(horizontal = HomeGutter)
            .padding(top = 20.dp)
            .fillMaxWidth()
            .testTag(HomeTags.NO_HISTORY),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconDisc(Icons.Rounded.History, palette, size = 46)
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    "Nothing played yet",
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                )
                Text(
                    "Play something and it will show up here — or start a mix below.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Smart Shuffle
// ---------------------------------------------------------------------------

@Composable
private fun SmartShuffleCard(
    palette: EditorialPalette,
    starting: Boolean,
    enabled: Boolean,
    onStartMix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeCard(
        palette = palette,
        modifier = modifier
            .padding(horizontal = HomeGutter)
            .padding(top = 14.dp)
            .fillMaxWidth()
            .testTag(HomeTags.MIX),
    ) {
        // Text beside the button while both fit; the button drops below the
        // text at large font sizes rather than wrapping "Start Mix".
        val stacked = LocalDensity.current.fontScale > 1.3f
        val text = @Composable { m: Modifier ->
            Column(m) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Text(
                        "Smart Shuffle",
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    "A mix shaped around your library",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        val button = @Composable {
            Box(contentAlignment = Alignment.Center) {
                EditorialRaisedButton(
                    text = if (starting) "Starting…" else "Start Mix",
                    onClick = onStartMix,
                    palette = palette,
                    enabled = enabled && !starting,
                    contentDescription = "Start Mix. Begins Smart Shuffle from your library.",
                    modifier = Modifier.width(if (stacked) 170.dp else 132.dp),
                )
                if (starting) {
                    CircularProgressIndicator(
                        color = palette.onAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                text(Modifier)
                Spacer(Modifier.height(14.dp))
                button()
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                text(Modifier.weight(1f).padding(end = 12.dp))
                button()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

@Composable
private fun <T> CoverRow(
    items: List<T>,
    key: (T) -> Any,
    content: @Composable (T) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = HomeGutter),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items, key = key) { content(it) }
    }
}

@Composable
private fun RowPlaceholder(palette: EditorialPalette) {
    Row(
        Modifier.padding(horizontal = HomeGutter),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(3) {
            Column(Modifier.width(RowCardWidth)) {
                Placeholder(palette, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(16.dp))
                Spacer(Modifier.height(10.dp))
                Placeholder(palette, Modifier.fillMaxWidth(0.8f).height(13.dp))
                Spacer(Modifier.height(6.dp))
                Placeholder(palette, Modifier.fillMaxWidth(0.5f).height(11.dp))
            }
        }
    }
}

/** A song: the whole card plays it. There is nothing to "open" for a single track. */
@Composable
private fun SongCard(song: Song, palette: EditorialPalette, onPlay: () -> Unit) {
    Column(
        Modifier
            .width(RowCardWidth)
            .pressable(onPlay, onClickLabel = "Play")
            .semantics(mergeDescendants = true) {}
            .testTag(HomeTags.recent(song.id)),
    ) {
        Cover(song.artworkUri, palette, Modifier.fillMaxWidth().aspectRatio(1f), elevation = 6.dp)
        CardTitle(song.title, palette)
        CardSubtitle(song.artist, palette)
    }
}

/** A playlist: the card opens it, the disc on the cover plays it. */
@Composable
private fun PlaylistCard(playlist: HomePlaylist, palette: EditorialPalette, actions: HomeActions) {
    Column(Modifier.width(RowCardWidth)) {
        Box {
            MosaicCover(
                playlist.artwork,
                palette,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pressable({ actions.onOpenPlaylist(playlist.id) }, onClickLabel = "Open")
                    .semantics { contentDescription = "${playlist.name}, playlist" }
                    .testTag(HomeTags.playlist(playlist.id)),
                glyph = Icons.AutoMirrored.Rounded.QueueMusic,
            )
            CoverPlayButton(
                contentDescription = "Play ${playlist.name}",
                palette = palette,
                onClick = { actions.onPlayPlaylist(playlist.id) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .testTag(HomeTags.playlistPlay(playlist.id)),
            )
        }
        Column(Modifier.pressable({ actions.onOpenPlaylist(playlist.id) }, onClickLabel = "Open")) {
            CardTitle(playlist.name, palette)
            CardSubtitle(HomeSections.playlistMeta(playlist), palette)
        }
    }
}

/** An album: the card opens it, the disc on the cover plays it. */
@Composable
private fun AlbumCard(album: HomeAlbum, palette: EditorialPalette, actions: HomeActions) {
    Column(Modifier.width(RowCardWidth)) {
        Box {
            Cover(
                album.artworkUri,
                palette,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pressable({ actions.onOpenAlbum(album.id) }, onClickLabel = "Open")
                    .semantics { contentDescription = "${album.title} by ${album.artist}, album" }
                    .testTag(HomeTags.album(album.id)),
                glyph = AlbumGlyph,
                elevation = 6.dp,
            )
            CoverPlayButton(
                contentDescription = "Play ${album.title}",
                palette = palette,
                onClick = { actions.onPlayAlbum(album.id) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .testTag(HomeTags.albumPlay(album.id)),
            )
        }
        Column(Modifier.pressable({ actions.onOpenAlbum(album.id) }, onClickLabel = "Open")) {
            CardTitle(album.title, palette)
            CardSubtitle(album.artist, palette)
        }
    }
}

@Composable
private fun CardTitle(text: String, palette: EditorialPalette) {
    Text(
        text,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // Horizontal padding, not a clip, keeps the first glyph's side
        // bearing ("Iuly" drawing as "'uly" was a clip artefact).
        modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
    )
}

@Composable
private fun CardSubtitle(text: String, palette: EditorialPalette) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = palette.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 1.dp),
    )
}

// ---------------------------------------------------------------------------
// Discover, library and tools
// ---------------------------------------------------------------------------

@Composable
private fun DiscoverCard(palette: EditorialPalette, online: Boolean?, onOpen: () -> Unit) {
    val offline = online == false
    HomeCard(
        palette = palette,
        modifier = Modifier
            .padding(horizontal = HomeGutter)
            .fillMaxWidth()
            .pressable(onOpen, onClickLabel = "Open Discover", pressedScale = 0.985f)
            .testTag(HomeTags.DISCOVER),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconDisc(if (offline) Icons.Rounded.WifiOff else Icons.Rounded.Explore, palette, size = 48)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    "Find new music",
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
                Text(
                    if (offline) {
                        "Connect to the internet to use Discover and download music."
                    } else {
                        "Browse albums and songs in Discover, then download them to your library."
                    },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                    modifier = if (offline) Modifier.testTag(HomeTags.OFFLINE) else Modifier,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.muted)
        }
    }
}

/**
 * Library, Downloads and FLAC Check as rows of one grouped card, each with a
 * line that says something true about it — the library's real counts, what
 * FLAC Check can read off the track that is playing — so this reads as part
 * of the page rather than as a panel of shortcut buttons.
 */
@Composable
private fun ToolsCard(state: HomeUiState, palette: EditorialPalette, actions: HomeActions) {
    HomeCard(
        palette = palette,
        modifier = Modifier
            .padding(horizontal = HomeGutter)
            .fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            val summary = HomeSections.librarySummary(state.stats)
            ToolRow(
                icon = Icons.Rounded.LibraryMusic,
                title = "Library",
                subtitle = when {
                    !state.loaded -> " "
                    summary.isEmpty() -> "Nothing scanned yet"
                    else -> summary
                },
                palette = palette,
                onClick = actions.onOpenLibrary,
                modifier = Modifier.testTag(HomeTags.LIBRARY_ROW),
            )
            RowDivider(palette)
            ToolRow(
                icon = Icons.Rounded.Download,
                title = "Downloads",
                subtitle = "Queue, progress and finished downloads",
                palette = palette,
                onClick = actions.onOpenDownloads,
                modifier = Modifier.testTag(HomeTags.DOWNLOADS_ROW),
            )
            RowDivider(palette)
            ToolRow(
                icon = Icons.Rounded.GraphicEq,
                title = "FLAC Check",
                subtitle = when {
                    !state.quality.hasSong -> "Inspect the audio quality of your files"
                    state.quality.detail.isNotBlank() -> "Now playing: ${state.quality.detail}"
                    else -> "Inspect the file that is playing"
                },
                palette = palette,
                onClick = actions.onOpenFlacCheck,
                modifier = Modifier.testTag(HomeTags.FLAC_ROW),
            )
        }
    }
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .pressable(onClick, pressedScale = 0.985f)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconDisc(icon, palette, size = 40)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
            Text(
                subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = palette.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.muted)
    }
}

@Composable
private fun RowDivider(palette: EditorialPalette) {
    Box(
        Modifier
            .padding(start = 70.dp, end = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.line),
    )
}

@Composable
private fun InlineNote(
    icon: ImageVector,
    title: String,
    body: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeCard(
        palette = palette,
        modifier = modifier
            .padding(horizontal = HomeGutter)
            .fillMaxWidth()
            .pressable(onClick, pressedScale = 0.985f),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconDisc(icon, palette, size = 40)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
                Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = palette.muted)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.muted)
        }
    }
}

@Composable
private fun IconDisc(icon: ImageVector, palette: EditorialPalette, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(palette.accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size((size * 0.46f).dp))
    }
}

@Composable
private fun Pill(
    label: String,
    icon: ImageVector,
    palette: EditorialPalette,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) palette.accent else palette.accent.copy(alpha = 0.12f))
            .pressable(onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (filled) palette.onAccent else palette.ink
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
