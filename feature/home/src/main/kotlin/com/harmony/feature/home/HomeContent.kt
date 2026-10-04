package com.harmony.feature.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.Song
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.GlassSearchBar
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Everything Home can ask for. Plain lambdas so tests and previews can pass no-ops. */
@Immutable
internal class HomeActions(
    val onOpenSearch: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenRecognize: () -> Unit = {},
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
    const val RECOGNIZE = "home_recognize"
    const val CONTINUE = "home_continue"
    const val CONTINUE_PLAY = "home_continue_play"
    const val CONTINUE_LOADING = "home_continue_loading"
    const val WELCOME = "home_welcome"
    const val WELCOME_LIBRARY = "home_welcome_library"
    const val WELCOME_DISCOVER = "home_welcome_discover"
    const val NO_HISTORY = "home_no_history"
    const val MIX = "home_mix"
    const val GLANCE = "home_glance"
    const val DISCOVER = "home_discover"
    const val OFFLINE = "home_offline"
    const val LIBRARY_ROW = "home_library_row"
    const val DOWNLOADS_ROW = "home_downloads_row"
    const val FLAC_ROW = "home_flac_row"
    const val NO_PLAYLISTS = "home_no_playlists"
    const val FOOTER = "home_footer"
    const val END = "home_end"
    fun recent(id: Long) = "home_recent_$id"
    fun playlist(id: Long) = "home_playlist_$id"
    fun playlistPlay(id: Long) = "home_playlist_play_$id"
    fun album(id: Long) = "home_album_$id"
    fun albumPlay(id: Long) = "home_album_play_$id"
}

/** Colours for the tools and stats, so each kind of thing keeps its own hue across the page. */
private object HomeHues {
    val Songs = Color(0xFFF2A51A)
    val Albums = Color(0xFFE5577A)
    val Artists = Color(0xFF7B6CF6)
    val Playlists = Color(0xFF22B59A)
    val Downloads = Color(0xFF3B8FF0)
    val FlacCheck = Color(0xFF2DBE6C)
    val HeroInk = Color(0xFFF7F4EE)
    val HeroDark = Color(0xFF15121C)
}

/**
 * Home.
 *
 * Reads top to bottom as "what am I listening to, what did I listen to,
 * what have I got": a header that greets and says something true about the
 * day and the library; the song in the player as one immersive card, on a
 * wash of its own cover; Smart Shuffle; recently played; the library at a
 * glance; playlists and recently added albums as rows of covers; Discover as
 * a bright banner; the library and tools; and a quiet sign-off.
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
            .drawBehind {
                // A warm glow at the top of the page, behind the greeting.
                drawCircle(
                    Brush.radialGradient(
                        listOf(palette.accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.1f, 0f),
                        radius = size.width,
                    ),
                    radius = size.width,
                    center = Offset(size.width * 0.1f, 0f),
                )
            }
            .testTag(HomeTags.LIST),
        contentPadding = contentPadding,
    ) {
        item(key = "header", contentType = "header") {
            Header(greeting, subline(state), palette, actions)
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
                        quality = state.quality.takeIf { it.hasSong },
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
                        quality = null,
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
                    songs = state.stats.songs,
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
                    SectionTitle("Recently played", "Jump back in", palette)
                    CoverRow(state.recentlyPlayed, key = { it.id }) { song ->
                        SongCard(song, palette, onPlay = { actions.onPlayRecent(song) })
                    }
                }
            }
        }

        if (loaded && !state.libraryEmpty && state.stats.songs > 0) {
            item(key = "glance", contentType = "glance") {
                Column(Modifier.appearOnLoad(loaded, 3)) {
                    SectionTitle("Your library", "At a glance", palette)
                    Glance(state.stats, palette, actions)
                }
            }
        }

        if (loaded && !state.libraryEmpty) {
            item(key = "playlists", contentType = "row") {
                Column(Modifier.appearOnLoad(loaded, 3)) {
                    SectionTitle(
                        "Your playlists",
                        if (state.playlists.isEmpty()) "Made by you" else "Newest first",
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
                    SectionTitle("Recently added", "Fresh in your library", palette)
                    CoverRow(state.recentAlbums, key = { it.id }) { album ->
                        AlbumCard(album, palette, actions)
                    }
                }
            }
        }

        item(key = "discover", contentType = "discover") {
            Column(Modifier.appearOnLoad(loaded, 5)) {
                SectionTitle("Discover", "New music, found for you", palette)
                DiscoverCard(palette, online, actions.onOpenDiscover)
            }
        }

        item(key = "tools", contentType = "tools") {
            Column(Modifier.appearOnLoad(loaded, 6)) {
                SectionTitle("Library & tools", "Everything in one place", palette)
                ToolsCard(state, palette, actions)
            }
        }

        item(key = "footer", contentType = "footer") {
            Footer(state, palette)
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

/** A friendly line under the greeting that says something true right now. */
private fun subline(state: HomeUiState): String = when {
    !state.loaded -> "Getting your music ready…"
    state.libraryEmpty -> "Let's get your music in here."
    state.nowPlaying?.isPlaying == true -> "Enjoy the music, it's all yours."
    state.nowPlaying != null -> "Your queue is right where you left it."
    state.stats.songs > 0 -> "${formatCount(state.stats.songs)} songs ready when you are."
    else -> "Your music. Your files. Your rules."
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun Header(greeting: String, subline: String, palette: EditorialPalette, actions: HomeActions) {
    // A step smaller at large font sizes, so "Good evening" still breaks
    // between words on a narrow phone instead of inside one.
    val large = LocalDensity.current.fontScale > 1.3f
    val today = remember { LocalDate.now() }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.padding(start = HomeGutter, end = HomeGutter - 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    remember(today) {
                        val day = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                        val month = today.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                        "$day · ${today.dayOfMonth} $month".uppercase()
                    },
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    greeting,
                    fontSize = if (large) 26.sp else 34.sp,
                    lineHeight = if (large) 31.sp else 39.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                    color = palette.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    subline,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            GlassIconButton(
                icon = Icons.Rounded.GraphicEq,
                contentDescription = "Recognize a song",
                palette = palette,
                onClick = actions.onOpenRecognize,
                modifier = Modifier.testTag(HomeTags.RECOGNIZE),
            )
            Spacer(Modifier.width(8.dp))
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
                .padding(top = 8.dp)
                .testTag(HomeTags.SEARCH),
        ) {
            GlassSearchBar(
                active = false,
                value = "",
                onValueChange = {},
                onFieldClick = actions.onOpenSearch,
                onClear = {},
                palette = palette,
                placeholder = "Search songs, albums, artists",
            )
        }
    }
}

/** A section's title, with a short friendly line under it and an optional action. */
@Composable
private fun SectionTitle(
    title: String,
    subtitle: String,
    palette: EditorialPalette,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = HomeGutter, end = HomeGutter - 8.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                subtitle.uppercase(),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.muted,
                maxLines = 1,
            )
            Text(
                title,
                fontSize = 22.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = palette.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .semantics { heading() },
            )
        }
        if (actionLabel != null && onAction != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(palette.accent.copy(alpha = 0.12f))
                    .pressable(onAction)
                    .heightIn(min = 36.dp)
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.accent, maxLines = 1)
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Continue
// ---------------------------------------------------------------------------

/**
 * The song in the player, as the page's one immersive card: a dark stage lit
 * by its own cover's colours, the cover large, its album and year, its sound
 * quality and place in the queue as pills, and how far into it you are.
 */
@Composable
private fun ContinueCard(
    song: Song,
    isPlaying: Boolean,
    eyebrow: String,
    detail: String?,
    quality: QualityStatus?,
    progress: (() -> Float)?,
    palette: EditorialPalette,
    onOpen: () -> Unit,
    openLabel: String,
    onPlayPause: () -> Unit,
) {
    // At large font sizes the text gets the card's full width under the
    // cover, instead of a sliver beside it.
    val largeText = LocalDensity.current.fontScale > 1.3f
    val coverSize = if (largeText) 84.dp else 108.dp
    val shape = RoundedCornerShape(26.dp)
    val ink = HomeHues.HeroInk
    Box(
        Modifier
            .padding(horizontal = HomeGutter)
            .padding(top = 20.dp)
            .fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = palette.accent, spotColor = palette.accent)
            .clip(shape)
            .background(HomeHues.HeroDark)
            .pressable(onOpen, onClickLabel = openLabel, pressedScale = 0.985f)
            .testTag(HomeTags.CONTINUE),
    ) {
        HeroBackdrop(song.artworkUri, palette, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(palette.accent.copy(alpha = 0.22f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isPlaying) {
                        PlayingBars(palette.accent, Modifier.size(11.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        eyebrow,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        letterSpacing = 1.3.sp,
                        fontWeight = FontWeight.Bold,
                        color = lerp(palette.accent, Color.White, 0.25f),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (detail != null) {
                    Text(
                        detail,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = ink.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val texts = @Composable { textModifier: Modifier ->
                Column(textModifier) {
                    Text(
                        song.title,
                        fontSize = 21.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                        color = ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        color = ink.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    val albumLine = listOfNotNull(
                        song.album.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) },
                        song.year?.takeIf { it > 0 }?.toString(),
                    ).joinToString("  ·  ")
                    if (albumLine.isNotEmpty()) {
                        Text(
                            albumLine,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = ink.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    val qualityText = quality?.let(::compactQuality)
                    if (qualityText != null) {
                        Row(
                            Modifier
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.12f))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = palette.accent, modifier = Modifier.size(12.dp))
                            Text(
                                qualityText,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.4.sp,
                                color = ink,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(coverSize)
                        .shadow(14.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black, spotColor = Color.Black),
                ) {
                    Cover(song.artworkUri, palette, Modifier.fillMaxSize(), RoundedCornerShape(16.dp))
                }
                if (!largeText) texts(Modifier.weight(1f).padding(start = 14.dp))
            }
            if (largeText) texts(Modifier.fillMaxWidth().padding(top = 12.dp))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress != null) {
                    Column(Modifier.weight(1f).padding(end = 14.dp)) {
                        // Drawn, not composed: position ticks twice a second while
                        // playing, and reading it only inside drawBehind means those
                        // ticks redraw this one line and recompose nothing.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clearAndSetSemantics { }
                                .drawBehind {
                                    val r = CornerRadius(size.height / 2)
                                    drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = r)
                                    val done = progress().coerceIn(0f, 1f)
                                    if (done > 0f) {
                                        drawRoundRect(
                                            Brush.horizontalGradient(listOf(palette.accent, lerp(palette.accent, Color.White, 0.35f))),
                                            size = Size(size.width * done, size.height),
                                            cornerRadius = r,
                                        )
                                    }
                                },
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            ElapsedText(song.durationMs, progress, ink, Modifier.weight(1f))
                            Text(formatClock(song.durationMs), fontSize = 11.sp, lineHeight = 14.sp, color = ink.copy(alpha = 0.6f))
                        }
                    }
                } else {
                    Text(
                        "Tap to pick up where you left off",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = ink.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f).padding(end = 14.dp),
                    )
                }
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .pressable(onPlayPause, pressedScale = 0.9f)
                        .semantics { contentDescription = if (isPlaying) "Pause" else "Play ${song.title}" }
                        .testTag(HomeTags.CONTINUE_PLAY),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .shadow(12.dp, CircleShape, ambientColor = palette.accent, spotColor = palette.accent)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(lerp(palette.accent, Color.White, 0.2f), palette.accent))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF1A1206),
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "LOSSLESS · FLAC 24/96": short enough for one pill on a narrow phone. */
private fun compactQuality(q: QualityStatus): String? {
    val bits = q.bitDepth
    val rate = q.sampleRateHz
    val format = listOfNotNull(
        q.format,
        if (bits != null && rate != null) "$bits/${if (rate % 1000 == 0) "${rate / 1000}" else "%.1f".format(rate / 1000.0)}" else null,
    ).joinToString(" ").ifBlank { q.bitrateKbps?.let { "$it kbps" } ?: return null }
    return if (q.isLossless) "LOSSLESS · $format" else format
}

/** "1:28": how far in, read from the same progress the bar draws. */
@Composable
private fun ElapsedText(durationMs: Long, progress: () -> Float, color: Color, modifier: Modifier) {
    Text(
        formatClock((durationMs * progress().coerceIn(0f, 1f)).toLong()),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier,
    )
}

private fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * The cover, enlarged and blurred into the card's background, under a dark
 * veil so white text always reads. Blur needs Android 12; older phones get
 * a gradient in the page's accent instead.
 */
@Composable
private fun HeroBackdrop(uri: String?, palette: EditorialPalette, modifier: Modifier) {
    Box(modifier) {
        if (uri != null && android.os.Build.VERSION.SDK_INT >= 31) {
            Artwork(
                uri,
                contentDescription = null,
                cornerRadius = 0.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.6f
                        scaleY = 1.6f
                        alpha = 0.85f
                    }
                    .blur(48.dp, BlurredEdgeTreatment.Rectangle),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(HomeHues.HeroDark.copy(alpha = 0.45f), HomeHues.HeroDark.copy(alpha = 0.82f)),
                    ),
                )
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(palette.accent.copy(alpha = 0.35f), Color.Transparent),
                            center = Offset(size.width, size.height),
                            radius = size.width * 0.7f,
                        ),
                        radius = size.width * 0.7f,
                        center = Offset(size.width, size.height),
                    )
                },
        )
    }
}

/** Three bars bouncing out of step: something is playing. */
@Composable
private fun PlayingBars(color: Color, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "bars")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0.9f, 0.35f, infiniteRepeatable(tween(410, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.45f, 0.95f, infiniteRepeatable(tween(610, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(modifier) {
        val w = size.width / 5f
        listOf(a, b, c).forEachIndexed { i, h ->
            val barH = size.height * h
            drawRoundRect(color, Offset(w * i * 2, size.height - barH), Size(w, barH), CornerRadius(w / 2))
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
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Placeholder(palette, Modifier.width(110.dp).height(18.dp), RoundedCornerShape(50))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Placeholder(palette, Modifier.size(108.dp), RoundedCornerShape(16.dp))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Placeholder(palette, Modifier.fillMaxWidth(0.8f).height(18.dp))
                    Spacer(Modifier.height(8.dp))
                    Placeholder(palette, Modifier.fillMaxWidth(0.5f).height(12.dp))
                    Spacer(Modifier.height(8.dp))
                    Placeholder(palette, Modifier.fillMaxWidth(0.6f).height(10.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Placeholder(palette, Modifier.weight(1f).height(5.dp), RoundedCornerShape(50))
                Spacer(Modifier.width(14.dp))
                Placeholder(palette, Modifier.size(54.dp), CircleShape)
            }
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
            GradientTile(Icons.Rounded.LibraryMusic, palette.accent, size = 54)
            Text(
                "Welcome to Harmony",
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
            GradientTile(Icons.Rounded.History, palette.accent, size = 48)
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

/** Smart Shuffle as a bright card in the page's colour, with a white Start Mix button. */
@Composable
private fun SmartShuffleCard(
    palette: EditorialPalette,
    songs: Int,
    starting: Boolean,
    enabled: Boolean,
    onStartMix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val ink = Color(0xFF1A1206)
    Box(
        modifier
            .padding(horizontal = HomeGutter)
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(palette.accent, Color.White, 0.25f), palette.accent, lerp(palette.accent, Color(0xFFE5577A), 0.35f))))
            .drawBehind {
                // Sparkle rings in the corner.
                val c = Offset(size.width * 0.92f, size.height * 0.1f)
                for (k in 1..4) {
                    drawCircle(Color.White.copy(alpha = 0.16f - k * 0.03f), radius = size.height * 0.28f * k, center = c, style = Stroke(1.5.dp.toPx()))
                }
            }
            .testTag(HomeTags.MIX),
    ) {
        // Text beside the button while both fit; the button drops below the
        // text at large font sizes rather than wrapping "Start Mix".
        val stacked = LocalDensity.current.fontScale > 1.3f
        val text = @Composable { m: Modifier ->
            Column(m) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        "Smart Shuffle",
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Text(
                    "A mix shaped around your library",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = ink.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    if (songs > 0) "Picks from ${formatCount(songs)} songs · learns as you skip" else "Learns as you listen and skip",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = ink.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        val button = @Composable {
            Row(
                Modifier
                    .shadow(if (enabled) 8.dp else 0.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = if (enabled) 1f else 0.55f))
                    .clickable(enabled = enabled && !starting, role = Role.Button, onClickLabel = "Start Mix", onClick = onStartMix)
                    .semantics { contentDescription = "Start Mix. Begins Smart Shuffle from your library." }
                    .heightIn(min = 46.dp)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (starting) {
                    CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(if (starting) "Starting…" else "Start Mix", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                text(Modifier)
                Spacer(Modifier.height(14.dp))
                button()
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 16.dp, top = 18.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                text(Modifier.weight(1f).padding(end = 12.dp))
                button()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// At a glance
// ---------------------------------------------------------------------------

/** Songs, albums, artists and playlists, each a tile in its own colour. */
@Composable
private fun Glance(stats: LibraryStats, palette: EditorialPalette, actions: HomeActions) {
    Column(
        Modifier.padding(horizontal = HomeGutter).testTag(HomeTags.GLANCE),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlanceTile(Icons.Rounded.MusicNote, HomeHues.Songs, formatCount(stats.songs), if (stats.songs == 1) "song" else "songs", palette, actions.onOpenLibrary, Modifier.weight(1f))
            GlanceTile(Icons.Rounded.Album, HomeHues.Albums, formatCount(stats.albums), if (stats.albums == 1) "album" else "albums", palette, actions.onOpenLibrary, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlanceTile(Icons.Rounded.Person, HomeHues.Artists, formatCount(stats.artists), if (stats.artists == 1) "artist" else "artists", palette, actions.onOpenLibrary, Modifier.weight(1f))
            GlanceTile(Icons.AutoMirrored.Rounded.QueueMusic, HomeHues.Playlists, formatCount(stats.playlists), if (stats.playlists == 1) "playlist" else "playlists", palette, actions.onOpenPlaylists, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GlanceTile(
    icon: ImageVector,
    hue: Color,
    value: String,
    label: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(hue.copy(alpha = 0.16f), hue.copy(alpha = 0.05f))))
            .border(1.dp, hue.copy(alpha = 0.22f), shape)
            .pressable(onClick, pressedScale = 0.97f)
            .semantics(mergeDescendants = true) {}
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientTile(icon, hue, size = 40)
        Column(Modifier.padding(start = 12.dp)) {
            Text(value, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = palette.ink, maxLines = 1)
            Text(label, fontSize = 12.sp, lineHeight = 15.sp, color = palette.muted, maxLines = 1)
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

/** A small dark label on a cover's corner: a length, a quality, "NEW". */
@Composable
private fun CoverTag(text: String, modifier: Modifier, background: Color = Color.Black.copy(alpha = 0.55f), color: Color = Color.White) {
    Text(
        text,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
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
        Box {
            Cover(song.artworkUri, palette, Modifier.fillMaxWidth().aspectRatio(1f), elevation = 6.dp)
            if (song.durationMs > 0) CoverTag(formatClock(song.durationMs), Modifier.align(Alignment.BottomStart))
            if ((song.bitDepth ?: 0) >= 24 || (song.sampleRateHz ?: 0) > 48_000) {
                CoverTag("HI-RES", Modifier.align(Alignment.TopEnd), background = palette.accent, color = Color(0xFF1A1206))
            }
        }
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
            CoverTag(
                if (playlist.songCount == 1) "1 SONG" else "${formatCount(playlist.songCount)} SONGS",
                Modifier.align(Alignment.TopStart),
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
            CoverTag("NEW", Modifier.align(Alignment.TopStart), background = palette.accent, color = Color(0xFF1A1206))
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

/** Discover as a bright banner with a clear call to action; grey and honest when offline. */
@Composable
private fun DiscoverCard(palette: EditorialPalette, online: Boolean?, onOpen: () -> Unit) {
    val offline = online == false
    val shape = RoundedCornerShape(24.dp)
    val colors = if (offline) {
        listOf(Color(0xFF6B6F7A), Color(0xFF3F434D))
    } else {
        listOf(Color(0xFFFF7A59), Color(0xFFE5577A), Color(0xFF7B6CF6))
    }
    Box(
        Modifier
            .padding(horizontal = HomeGutter)
            .fillMaxWidth()
            .shadow(if (offline) 0.dp else 14.dp, shape, ambientColor = colors[1], spotColor = colors[1])
            .clip(shape)
            .background(Brush.linearGradient(colors))
            .drawBehind {
                // Record grooves rising out of the corner.
                val c = Offset(size.width * 1.02f, size.height * 1.05f)
                for (k in 1..6) {
                    drawCircle(Color.White.copy(alpha = 0.14f), radius = size.height * 0.22f * k, center = c, style = Stroke(1.5.dp.toPx()))
                }
            }
            .pressable(onOpen, onClickLabel = "Open Discover", pressedScale = 0.985f)
            .testTag(HomeTags.DISCOVER),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (offline) Icons.Rounded.WifiOff else Icons.Rounded.Explore, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Text(
                "Find new music",
                fontSize = 21.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                if (offline) {
                    "Connect to the internet to use Discover and download music."
                } else {
                    "Browse albums and songs in Discover, then download them to your library."
                },
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color.White.copy(alpha = 0.85f),
                modifier = (if (offline) Modifier.testTag(HomeTags.OFFLINE) else Modifier).padding(top = 4.dp, end = 40.dp),
            )
            Row(
                Modifier
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (offline) "Open anyway" else "Explore",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors[1],
                )
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors[1], modifier = Modifier.size(18.dp))
            }
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
                hue = HomeHues.Songs,
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
                hue = HomeHues.Downloads,
                title = "Downloads",
                subtitle = "Queue, progress and finished downloads",
                palette = palette,
                onClick = actions.onOpenDownloads,
                modifier = Modifier.testTag(HomeTags.DOWNLOADS_ROW),
            )
            RowDivider(palette)
            ToolRow(
                icon = Icons.Rounded.GraphicEq,
                hue = HomeHues.FlacCheck,
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
    hue: Color,
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
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientTile(icon, hue, size = 42)
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
            .padding(start = 72.dp, end = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.line),
    )
}

/** The page's sign-off: the app's mark, its motto, and the library in one line. */
@Composable
private fun Footer(state: HomeUiState, palette: EditorialPalette) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = HomeGutter, vertical = 26.dp)
            .testTag(HomeTags.FOOTER),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GradientTile(Icons.Rounded.GraphicEq, palette.accent, size = 36)
        Text(
            "Harmony",
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "Your music. Your files. Your rules.",
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
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
            GradientTile(icon, HomeHues.Playlists, size = 42)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
                Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = palette.muted)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = palette.muted)
        }
    }
}

/** A rounded square in a hue's gradient with a white icon: the page's icon style. */
@Composable
private fun GradientTile(icon: ImageVector, hue: Color, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.3f).dp))
            .background(Brush.linearGradient(listOf(lerp(hue, Color.White, 0.25f), hue))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.52f).dp))
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
