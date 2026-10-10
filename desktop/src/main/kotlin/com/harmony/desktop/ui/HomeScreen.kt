package com.harmony.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.desktop.DesktopApp
import com.harmony.desktop.PlayStat
import com.harmony.desktop.library.LocalTrack
import com.harmony.desktop.player.Art
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the home page picks from the library: worked out once per change. */
private class HomePicks(
    val featured: List<AlbumEntry>,
    val topTracks: List<LocalTrack>,
    val topIsMostPlayed: Boolean,
    val mixName: String?,
    val mixTracks: List<LocalTrack>,
    val mixCovers: List<LocalTrack>,
    val recentAlbums: List<AlbumEntry>,
    val jumpBackIn: List<AlbumEntry>,
)

private fun pick(all: List<LocalTrack>, stats: Map<String, PlayStat>): HomePicks {
    val albumList = albums(all)
    val newest = albumList.sortedByDescending { a -> a.tracks.maxOf { it.modified } }
    val played = all.filter { (stats[it.path]?.count ?: 0) > 0 }.sortedWith(compareByDescending<LocalTrack> { stats[it.path]!!.count }.thenByDescending { stats[it.path]!!.lastPlayed })
    val top = if (played.isNotEmpty()) played.take(10) else all.sortedByDescending { it.modified }.take(10)
    // The mix: the genre played most (or simply most common), else the artist played most.
    val genreWeight = all.filter { !it.genre.isNullOrBlank() }.groupBy { it.genre!!.trim() }
        .mapValues { (_, t) -> t.sumOf { 1 + 4 * (stats[it.path]?.count ?: 0) } }
    val genre = genreWeight.maxByOrNull { it.value }?.key
    val mix = when {
        genre != null -> genre to all.filter { it.genre?.trim() == genre }
        all.isNotEmpty() -> {
            val artist = (played.firstOrNull() ?: all.first()).let { it.albumArtist ?: it.artist }
            artist to all.filter { (it.albumArtist ?: it.artist) == artist }
        }
        else -> null
    }
    val lastPlayedAlbums = albumList
        .map { a -> a to (a.tracks.maxOfOrNull { stats[it.path]?.lastPlayed ?: 0L } ?: 0L) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
    return HomePicks(
        featured = newest.take(5),
        topTracks = top,
        topIsMostPlayed = played.isNotEmpty(),
        mixName = mix?.first,
        mixTracks = mix?.second.orEmpty(),
        mixCovers = mix?.second.orEmpty().filter { it.hasCover }.distinctBy { albumKey(it) }.take(4),
        recentAlbums = newest.take(16),
        jumpBackIn = lastPlayedAlbums.take(16),
    )
}

/**
 * Discover: the newest album up big, your top songs on a dark panel, two
 * mixes to start with one click, and rows of albums.
 */
@Composable
fun HomePage(app: DesktopApp, loader: ImageLoader, onOpenAlbum: (String, String) -> Unit, onSearch: (String) -> Unit) {
    val c = LocalHarmonyColors.current
    val all by app.tracks.collectAsState()
    val stats by app.plays.stats.collectAsState()
    val settings by app.settingsFlow.collectAsState()
    val np by app.player.nowPlaying.collectAsState()
    val picks = remember(all, stats) { pick(all, stats) }
    if (all.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            ScanBar(app)
            EmptyState(
                "Add your music",
                "Choose the folders where your songs are. Harmony reads them, with their covers, and keeps watching for new ones each time it opens.",
            ) {
                ActionButton("Choose a folder", Icons.Rounded.Search, { chooseFolder()?.let(app::addFolder) }, primary = true)
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // The search, up top.
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeSearch(onSearch)
            Spacer(Modifier.weight(1f))
            Text("${all.size} SONGS · ${albums(all).size} ALBUMS", fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
        }
        ScanBar(app)
        Row(Modifier.fillMaxWidth().height(400.dp).padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Hero(app, loader, picks.featured, onOpenAlbum, Modifier.weight(1.55f).fillMaxHeight())
            TopTracks(
                app, picks.topTracks, picks.topIsMostPlayed, np?.key, settings.favorites.toSet(),
                Modifier.weight(1f).fillMaxHeight(),
            )
            Column(Modifier.weight(0.85f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                ShuffleCard(all.size, Modifier.weight(1f).fillMaxWidth()) {
                    if (!app.player.shuffle.value) app.player.toggleShuffle()
                    app.player.playLocal(all, all.indices.random())
                }
                MixCard(picks.mixName, picks.mixTracks.size, picks.mixCovers, loader, Modifier.weight(1f).fillMaxWidth()) {
                    val t = picks.mixTracks
                    if (t.isNotEmpty()) {
                        if (!app.player.shuffle.value) app.player.toggleShuffle()
                        app.player.playLocal(t, t.indices.random())
                    }
                }
            }
        }
        if (picks.jumpBackIn.isNotEmpty()) {
            AlbumRow("JUMP BACK IN", "RECENTLY PLAYED", picks.jumpBackIn, app, loader, onOpenAlbum)
        }
        AlbumRow("THE NEWEST", "RECENTLY ADDED", picks.recentAlbums, app, loader, onOpenAlbum)
    }
}

@Composable
private fun HomeSearch(onSearch: (String) -> Unit) {
    val c = LocalHarmonyColors.current
    var q by remember { mutableStateOf("") }
    Row(
        Modifier.width(320.dp).clip(RoundedCornerShape(10.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = c.muted, modifier = Modifier.size(17.dp))
        Box(Modifier.padding(start = 8.dp).weight(1f)) {
            if (q.isEmpty()) Text("What are you looking for?", fontSize = 13.sp, color = c.muted)
            BasicTextField(
                q, { q = it }, singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = c.ink),
                cursorBrush = SolidColor(Accent.Purple),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) onSearch(q.trim()) }),
                modifier = Modifier.fillMaxWidth().testTag("home_search"),
            )
        }
    }
}

/** The newest albums, one at a time, cover full-bleed with the album over it. */
@Composable
private fun Hero(app: DesktopApp, loader: ImageLoader, featured: List<AlbumEntry>, onOpenAlbum: (String, String) -> Unit, modifier: Modifier) {
    var index by remember(featured) { mutableIntStateOf(0) }
    LaunchedEffect(featured) {
        while (featured.size > 1) {
            // Wall-clock time, so the turning never keeps a test's clock busy.
            withContext(Dispatchers.Default) { delay(9_000) }
            index = (index + 1) % featured.size
        }
    }
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFF1B1C22)).testTag("home_hero")) {
        Crossfade(targetState = featured.getOrNull(index), animationSpec = tween(600)) { a ->
            if (a == null) return@Crossfade
            Box(Modifier.fillMaxSize()) {
                ArtImage(Art.Local(a.cover), loader, RoundedCornerShape(22.dp), Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.82f))),
                    ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(26.dp)) {
                    Text("NEW IN YOUR LIBRARY", fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.75f))
                    Text(
                        buildAnnotatedString {
                            append("New album from ")
                            withStyle(SpanStyle(color = Accent.PurpleLight, fontWeight = FontWeight.Bold)) { append(a.artist.uppercase()) }
                        },
                        fontSize = 28.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(a.album, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroButton("PLAY ALL", filled = true) { app.player.playLocal(sorted(a.tracks), 0) }
                        HeroButton("SHUFFLE") {
                            if (!app.player.shuffle.value) app.player.toggleShuffle()
                            app.player.playLocal(sorted(a.tracks), a.tracks.indices.random())
                        }
                        HeroButton("OPEN") { onOpenAlbum(a.album, a.artist) }
                    }
                }
                if (featured.size > 1) {
                    Row(Modifier.align(Alignment.TopEnd).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        featured.indices.forEach { i ->
                            Box(
                                Modifier.size(if (i == index) 18.dp else 7.dp, 7.dp).clip(CircleShape)
                                    .background(if (i == index) Color.White else Color.White.copy(alpha = 0.45f))
                                    .clickable { index = i },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun sorted(tracks: List<LocalTrack>) = tracks.sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title }))

@Composable
private fun HeroButton(text: String, filled: Boolean = false, onClick: () -> Unit) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        color = if (filled) Color.White else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(if (filled) Modifier.background(Brush.linearGradient(listOf(Accent.Purple, Accent.PurpleDeep))) else Modifier.border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.8f)), RoundedCornerShape(50)))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** The dark panel with the songs played most (or the newest, before anything was played). */
@Composable
private fun TopTracks(
    app: DesktopApp,
    tracks: List<LocalTrack>,
    mostPlayed: Boolean,
    currentKey: String?,
    favorites: Set<String>,
    modifier: Modifier,
) {
    val ink = Color(0xFFF4F4FA)
    val muted = Color(0xFF8E94A6)
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(LocalHarmonyColors.current.panel).padding(vertical = 14.dp).testTag("home_top")) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            Text(if (mostPlayed) "YOUR TOP TRACKS: " else "JUST ADDED: ", fontSize = 10.sp, letterSpacing = 1.4.sp, color = muted)
            Text(if (mostPlayed) "MOST PLAYED" else "NEWEST", fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = ink)
        }
        Column(Modifier.padding(top = 6.dp)) {
            tracks.forEachIndexed { i, t ->
                val current = currentKey == t.path
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (current || hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent)
                        .hoverable(hover)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button) { app.player.playLocal(tracks, i) }
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(26.dp)) {
                        if (current) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(Accent.Pink), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                        } else {
                            Text("${i + 1}", fontSize = 11.sp, color = muted)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, fontSize = 11.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val fav = t.path in favorites
                    Icon(
                        if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (fav) "Remove from favourites" else "Add to favourites",
                        tint = if (fav) Accent.Pink else ink.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp).clickable { app.toggleFavorite(t.path) },
                    )
                }
            }
        }
    }
}

/** "Shuffle everything": the whole library, mixed, from one big play button. */
@Composable
private fun ShuffleCard(count: Int, modifier: Modifier, onPlay: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFC2185B), Accent.PurpleDeep)))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onPlay)
            .testTag("home_shuffle"),
    ) {
        // A field of dots, like a map of everything you have.
        Canvas(Modifier.fillMaxSize()) {
            val step = 9.dp.toPx()
            var y = step
            var row = 0
            while (y < size.height) {
                var x = if (row % 2 == 0) step else step * 1.5f
                while (x < size.width) {
                    val k = kotlin.math.sin(x * 0.013f + y * 0.021f) + kotlin.math.cos(x * 0.007f - y * 0.017f)
                    if (k > 0.2f) drawCircle(Color.White.copy(alpha = 0.16f), radius = 1.6.dp.toPx(), center = Offset(x, y))
                    x += step
                }
                y += step
                row++
            }
        }
        Text("RECOMMENDED: SHUFFLE EVERYTHING", fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
        Box(Modifier.align(Alignment.Center).size(58.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(Color.White, radius = size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(2.dp.toPx())) }
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Shuffle everything", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(13.dp))
            Text("$count SONGS, MIXED", fontSize = 10.sp, letterSpacing = 1.4.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** A mix of the genre (or artist) played most, over a collage of its covers. */
@Composable
private fun MixCard(name: String?, count: Int, covers: List<LocalTrack>, loader: ImageLoader, modifier: Modifier, onPlay: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFFE07A3F))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, enabled = name != null, onClick = onPlay)
            .testTag("home_mix"),
    ) {
        if (covers.isNotEmpty()) {
            Column(Modifier.fillMaxSize()) {
                covers.chunked(2).take(2).forEach { pair ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        pair.forEach { t -> ArtImage(Art.Local(t), loader, RoundedCornerShape(0.dp), Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE07A3F).copy(alpha = 0.55f), Color(0xFFB24A1C).copy(alpha = 0.92f)))))
        Text("MOODS: ${name?.uppercase() ?: "YOUR MIX"}", fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("${name ?: "Your"} mix", fontSize = 26.sp, fontWeight = FontWeight.Light, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count SONGS · BASED ON WHAT YOU PLAY", fontSize = 10.sp, letterSpacing = 1.2.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** "THE NEWEST: RECENTLY ADDED", then a row of covers. */
@Composable
private fun AlbumRow(title: String, subtitle: String, list: List<AlbumEntry>, app: DesktopApp, loader: ImageLoader, onOpenAlbum: (String, String) -> Unit) {
    val c = LocalHarmonyColors.current
    Column(Modifier.padding(top = 26.dp)) {
        Row(Modifier.padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title: ", fontSize = 12.sp, letterSpacing = 1.4.sp, color = c.muted)
            Text(subtitle, fontSize = 12.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = c.ink)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list, key = { it.album + "|" + it.artist }) { a ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Column(
                    Modifier
                        .width(150.dp)
                        .hoverable(hover)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button) { onOpenAlbum(a.album, a.artist) },
                ) {
                    Box {
                        ArtImage(Art.Local(a.cover), loader, RoundedCornerShape(6.dp), Modifier.fillMaxWidth().aspectRatio(1f))
                        if (hovered) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Accent.Purple)
                                    .clickable { app.player.playLocal(sorted(a.tracks), 0) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${a.album}", tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Text(a.album, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(a.artist, fontSize = 11.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
