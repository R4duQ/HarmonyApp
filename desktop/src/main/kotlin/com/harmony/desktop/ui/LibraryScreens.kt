package com.harmony.desktop.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.desktop.DesktopApp
import com.harmony.desktop.library.LocalTrack
import com.harmony.desktop.player.Art
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Asks for a music folder with the system's own folder picker. */
fun chooseFolder(): File? = runCatching {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(File(System.getProperty("user.home"))).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose a music folder"
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}.getOrNull()

@Composable
fun ActionButton(text: String, icon: ImageVector, onClick: () -> Unit, primary: Boolean = false, modifier: Modifier = Modifier) {
    val c = LocalHarmonyColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Brush.linearGradient(listOf(Accent.Purple, Accent.PurpleDeep)) else Brush.linearGradient(listOf(c.chip, c.chip)))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) Color.White else c.ink, modifier = Modifier.size(17.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (primary) Color.White else c.ink, modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit) {
    val c = LocalHarmonyColors.current
    Row(
        Modifier.width(260.dp).clip(RoundedCornerShape(50)).background(c.chip).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = c.muted, modifier = Modifier.size(17.dp))
        Box(Modifier.padding(start = 8.dp).weight(1f)) {
            if (query.isEmpty()) Text("Search songs, artists, albums", fontSize = 13.sp, color = c.muted)
            BasicTextField(
                query, onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = c.ink),
                cursorBrush = SolidColor(Accent.Purple),
                modifier = Modifier.fillMaxWidth().testTag("search"),
            )
        }
    }
}

/** The first song of each album: stands in for the album's cover. */
internal fun albumCovers(tracks: List<LocalTrack>): Map<String, LocalTrack> =
    tracks.groupBy { it.album + "|" + (it.albumArtist ?: it.artist) }.mapValues { (_, t) -> t.firstOrNull { it.hasCover } ?: t.first() }

internal fun albumKey(t: LocalTrack) = t.album + "|" + (t.albumArtist ?: t.artist)

@Composable
fun SongsPage(
    app: DesktopApp,
    loader: ImageLoader,
    filterArtist: String?,
    onOpenAlbum: (String, String) -> Unit,
    onBack: (() -> Unit)? = null,
    initialQuery: String = "",
    favoritesOnly: Boolean = false,
) {
    val c = LocalHarmonyColors.current
    val all by app.tracks.collectAsState()
    val np by app.player.nowPlaying.collectAsState()
    val settings by app.settingsFlow.collectAsState()
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val covers = remember(all) { albumCovers(all) }
    val favorites = settings.favorites.toSet()
    val shown = remember(all, query, filterArtist, favoritesOnly, favorites) {
        val base = when {
            favoritesOnly -> all.filter { it.path in favorites }
            filterArtist == null -> all
            else -> all.filter { it.artist == filterArtist || it.albumArtist == filterArtist }
        }
        val q = query.trim().lowercase()
        if (q.isEmpty()) base else base.filter { q in it.title.lowercase() || q in it.artist.lowercase() || q in it.album.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = if (favoritesOnly) "Favourites" else filterArtist ?: "Songs",
            subtitle = "${shown.size} songs" + if (filterArtist == null && !favoritesOnly) " · ${settings.folders.size} folder${if (settings.folders.size == 1) "" else "s"}" else "",
            onBack = onBack,
        ) {
            SearchBox(query) { query = it }
            ActionButton("Shuffle", Icons.Rounded.Shuffle, {
                if (shown.isNotEmpty()) {
                    if (!app.player.shuffle.value) app.player.toggleShuffle()
                    app.player.playLocal(shown, shown.indices.random())
                }
            })
            ActionButton("Play", Icons.Rounded.PlayArrow, { if (shown.isNotEmpty()) app.player.playLocal(shown, 0) }, primary = true)
        }
        ScanBar(app)
        if (favoritesOnly && shown.isEmpty() && query.isEmpty()) {
            EmptyState("No favourites yet", "Tap the heart on a song, here or in the player, and it shows up here.")
            return@Column
        }
        if (all.isEmpty() && app.scan.value == null) {
            EmptyState(
                "Add your music",
                "Choose the folders where your songs are. Harmony reads them, with their covers, and keeps watching for new ones each time it opens.",
            ) {
                ActionButton("Choose a folder", Icons.Rounded.CreateNewFolder, { chooseFolder()?.let(app::addFolder) }, primary = true)
            }
            return@Column
        }
        // Column titles.
        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell("#", Modifier.width(44.dp))
            HeaderCell("TITLE", Modifier.weight(2.2f))
            HeaderCell("ALBUM", Modifier.weight(1.4f))
            HeaderCell("QUALITY", Modifier.width(120.dp))
            HeaderCell("", Modifier.width(40.dp))
            HeaderCell("TIME", Modifier.width(56.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            itemsIndexed(shown, key = { _, t -> t.path }) { i, t ->
                SongRow(
                    index = i, track = t, cover = covers[albumKey(t)] ?: t, loader = loader,
                    current = np?.key == t.path,
                    favorite = t.path in favorites,
                    onFavorite = { app.toggleFavorite(t.path) },
                    onPlay = { app.player.playLocal(shown, i) },
                    onAlbum = { onOpenAlbum(t.album, t.albumArtist ?: t.artist) },
                )
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = LocalHarmonyColors.current.muted, modifier = modifier)
}

@Composable
private fun SongRow(
    index: Int,
    track: LocalTrack,
    cover: LocalTrack,
    loader: ImageLoader,
    current: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onPlay: () -> Unit,
    onAlbum: () -> Unit,
) {
    val c = LocalHarmonyColors.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    current -> Accent.Purple.copy(alpha = 0.16f)
                    hovered -> c.ink.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp)) {
            if (hovered || current) Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Accent.PurpleLight, modifier = Modifier.size(18.dp))
            else Text("${index + 1}", fontSize = 12.sp, color = c.muted)
        }
        Row(Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(Art.Local(cover), loader, RoundedCornerShape(8.dp), Modifier.size(40.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (current) Accent.PurpleLight else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            track.album, fontSize = 13.sp, color = c.ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f).padding(end = 12.dp).clickable(onClick = onAlbum),
        )
        Box(Modifier.width(120.dp)) {
            Pill(track.quality, if (track.lossless) Accent.Cyan else c.muted)
        }
        Box(Modifier.width(40.dp)) {
            if (favorite || hovered) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
                    tint = if (favorite) Accent.Pink else c.muted,
                    modifier = Modifier.size(18.dp).clickable(onClick = onFavorite),
                )
            }
        }
        Text(formatTime(track.durationMs), fontSize = 12.sp, color = c.muted, modifier = Modifier.width(56.dp))
    }
}

internal data class AlbumEntry(val album: String, val artist: String, val tracks: List<LocalTrack>, val cover: LocalTrack)

internal fun albums(tracks: List<LocalTrack>): List<AlbumEntry> = tracks
    .groupBy { albumKey(it) }
    .map { (_, t) -> AlbumEntry(t.first().album, t.first().albumArtist ?: t.first().artist, t, t.firstOrNull { it.hasCover } ?: t.first()) }
    .sortedBy { it.album.lowercase() }

@Composable
fun AlbumsPage(app: DesktopApp, loader: ImageLoader, onOpen: (String, String) -> Unit) {
    val all by app.tracks.collectAsState()
    val list = remember(all) { albums(all) }
    val c = LocalHarmonyColors.current
    Column(Modifier.fillMaxSize()) {
        PageHeader("Albums", "${list.size} albums") {
            ActionButton("Add folder", Icons.Rounded.CreateNewFolder, { chooseFolder()?.let(app::addFolder) })
            ActionButton("Rescan", Icons.Rounded.Refresh, app::rescan)
        }
        ScanBar(app)
        LazyVerticalGrid(
            GridCells.Adaptive(190.dp),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(list, key = { it.album + "|" + it.artist }) { a ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button) { onOpen(a.album, a.artist) }
                        .padding(6.dp),
                ) {
                    ArtImage(Art.Local(a.cover), loader, RoundedCornerShape(16.dp), Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(a.album, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text("${a.artist} · ${a.tracks.size} songs", fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun AlbumPage(app: DesktopApp, loader: ImageLoader, album: String, artist: String, onBack: () -> Unit) {
    val all by app.tracks.collectAsState()
    val np by app.player.nowPlaying.collectAsState()
    val c = LocalHarmonyColors.current
    val tracks = remember(all, album, artist) {
        all.filter { it.album == album && (it.albumArtist ?: it.artist) == artist }
            .sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title }))
    }
    val cover = tracks.firstOrNull { it.hasCover } ?: tracks.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        PageHeader(album, null, onBack = onBack)
        Row(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
            Column(Modifier.width(300.dp)) {
                ArtImage(cover?.let { Art.Local(it) }, loader, RoundedCornerShape(22.dp), Modifier.size(300.dp))
                Text(artist, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.padding(top = 16.dp))
                val total = tracks.sumOf { it.durationMs }
                Text(
                    listOfNotNull(tracks.firstOrNull()?.year?.toString(), "${tracks.size} songs", formatTime(total)).joinToString(" · "),
                    fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp),
                )
                tracks.firstOrNull()?.let { Pill(it.quality, if (it.lossless) Accent.Cyan else c.muted, Modifier.padding(top = 10.dp)) }
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Play", Icons.Rounded.PlayArrow, { app.player.playLocal(tracks, 0) }, primary = true)
                    ActionButton("Shuffle", Icons.Rounded.Shuffle, {
                        if (!app.player.shuffle.value) app.player.toggleShuffle()
                        if (tracks.isNotEmpty()) app.player.playLocal(tracks, tracks.indices.random())
                    })
                }
            }
            LazyColumn(Modifier.weight(1f).padding(start = 32.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(tracks, key = { _, t -> t.path }) { i, t ->
                    val current = np?.key == t.path
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (current) Accent.Purple.copy(alpha = 0.16f) else Color.Transparent)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(role = Role.Button) { app.player.playLocal(tracks, i) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${t.trackNumber ?: i + 1}", fontSize = 13.sp, color = c.muted, modifier = Modifier.width(36.dp))
                        Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (current) Accent.PurpleLight else c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatTime(t.durationMs), fontSize = 12.sp, color = c.muted)
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistsPage(app: DesktopApp, loader: ImageLoader, onOpen: (String) -> Unit) {
    val all by app.tracks.collectAsState()
    val c = LocalHarmonyColors.current
    val artists = remember(all) {
        all.groupBy { it.albumArtist ?: it.artist }.map { (name, t) -> Triple(name, t, t.firstOrNull { it.hasCover } ?: t.first()) }.sortedBy { it.first.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Artists", "${artists.size} artists")
        LazyVerticalGrid(
            GridCells.Adaptive(170.dp),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(artists, key = { it.first }) { (name, tracks, cover) ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button) { onOpen(name) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ArtImage(Art.Local(cover), loader, CircleShape, Modifier.fillMaxWidth().aspectRatio(1f).border(2.dp, Brush.sweepGradient(Accent.Rim), CircleShape))
                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text("${tracks.size} songs", fontSize = 12.sp, color = c.muted)
                }
            }
        }
    }
}

@Composable
fun FolderList(app: DesktopApp) {
    val settings by app.settingsFlow.collectAsState()
    val c = LocalHarmonyColors.current
    Column {
        settings.folders.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f, fontSize = 13.sp, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Remove", fontSize = 12.sp, color = Accent.Pink, modifier = Modifier.clickable { app.removeFolder(f) }.padding(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        ActionButton("Add folder", Icons.Rounded.CreateNewFolder, { chooseFolder()?.let(app::addFolder) })
    }
}
