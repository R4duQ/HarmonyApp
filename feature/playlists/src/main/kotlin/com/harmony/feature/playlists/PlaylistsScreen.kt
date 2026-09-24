package com.harmony.feature.playlists

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialCircleButton
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialTab
import com.harmony.core.ui.component.EditorialTabs
import com.harmony.core.ui.component.EmptyState
import com.harmony.core.ui.component.greenPalette
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.playback.usecase.PlaySongsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val playSongs: PlaySongsUseCase,
) : ViewModel() {

    /**
     * Playlists joined with their cover art and runtime.
     *
     * Three Room flows combined rather than one query per card: a per-card
     * lookup would fire N queries on every list change and would not be
     * reactive when a cover is replaced in the tag editor. All three re-emit
     * on their own, so the rows stay live.
     */
    val playlistCards: StateFlow<List<PlaylistCardUi>> = combine(
        playlistRepository.observePlaylists(),
        playlistRepository.observePlaylistArtwork(),
        playlistRepository.observePlaylistDurations(),
    ) { playlists, artwork, durations ->
        playlists.map { playlist ->
            PlaylistCardUi(
                id = playlist.id,
                name = playlist.name,
                songCount = playlist.songCount,
                durationMs = durations[playlist.id] ?: 0L,
                artwork = artwork[playlist.id].orEmpty(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String) {
        viewModelScope.launch { playlistRepository.create(name.trim()) }
    }

    fun rename(playlistId: Long, name: String) {
        viewModelScope.launch { playlistRepository.rename(playlistId, name.trim()) }
    }

    fun delete(playlistId: Long) {
        viewModelScope.launch { playlistRepository.delete(playlistId) }
    }

    fun importM3u(name: String, content: String) {
        viewModelScope.launch { playlistRepository.importM3u(name, content) }
    }

    /** The row's Play button: one snapshot of the list, straight into the queue. */
    fun play(playlistId: Long) {
        viewModelScope.launch {
            val songs = playlistRepository.observePlaylistSongs(playlistId).first()
            if (songs.isNotEmpty()) playSongs(songs, 0)
        }
    }

    fun playSmart(type: SmartPlaylistType) {
        viewModelScope.launch {
            val songs = playlistRepository.observeSmartPlaylist(type).first()
            if (songs.isNotEmpty()) playSongs(songs, 0)
        }
    }
}

/** One playlist row: the stored playlist plus its derived cover art and runtime. */
data class PlaylistCardUi(
    val id: Long,
    val name: String,
    val songCount: Int,
    val durationMs: Long,
    /** Up to four distinct covers, in playing order. */
    val artwork: List<String>,
) {
    /** "12 songs · 48 min", degrading when the runtime is unknown. */
    val summary: String
        get() {
            val songs = if (songCount == 1) "1 song" else "$songCount songs"
            val minutes = (durationMs / 60_000L).toInt()
            return when {
                songCount == 0 -> "Empty"
                minutes <= 0 -> songs
                minutes < 60 -> "$songs · $minutes min"
                else -> "$songs · ${minutes / 60} h ${minutes % 60} min"
            }
        }
}

/**
 * Playlists, in the green "editorial" treatment: same outlined-card
 * construction as the amber Library so the two read as siblings, with the
 * amber Play pill as the one filled element per card.
 *
 * Smart playlists and user playlists were previously one list with the
 * smart ones pinned on top; they're now two tabs ("Smart" / "Yours"). Same
 * reasoning as the old pinning — smart lists are queries that always exist
 * and never need managing — but as tabs the split also explains itself, and
 * rename/delete only ever appearing under "Yours" stops reading as an
 * inconsistency. The tab counts double as a summary before you switch.
 *
 * The two ExtendedFloatingActionButtons (New playlist / Import M3U) became
 * header circles: stacked FABs would cover the pill of every card they
 * float over, and on the poster-flat field a Material FAB looks pasted on.
 */
@Composable
fun PlaylistsScreen(
    onPlaylistClick: (Long) -> Unit,
    onSmartPlaylistClick: (SmartPlaylistType) -> Unit,
    onSpotifyTransfer: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val cards by viewModel.playlistCards.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistCardUi?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistCardUi?>(null) }
    // Saveable so opening a playlist and coming back doesn't reset you to
    // the Smart tab — see the same note in LibraryScreen.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val palette = greenPalette()

    val importScope = rememberCoroutineScope()
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?: "Imported playlist"
            // This callback runs on the main thread, and the picked document
            // can be anywhere a provider chooses to put it — including a
            // cloud provider that fetches it over the network on first read.
            // Reading it inline was a stall of unbounded length on the UI
            // thread; a large playlist from Drive is an ANR, not a hiccup.
            importScope.launch {
                val content = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (content != null) viewModel.importM3u(name, content)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field),
    ) {
        // ---- Header: big title + circled New / Import ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 20.dp, top = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                "Playlists",
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EditorialCircleButton(
                    onClick = onSpotifyTransfer,
                    contentDescription = "Transfer a Spotify playlist",
                    palette = palette,
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(19.dp))
                }
                EditorialCircleButton(
                    onClick = {
                        importPicker.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "*/*"))
                    },
                    contentDescription = "Import M3U playlist",
                    palette = palette,
                ) {
                    Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(19.dp))
                }
                EditorialCircleButton(
                    onClick = { showCreateDialog = true },
                    contentDescription = "New playlist",
                    palette = palette,
                    filled = true,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(21.dp))
                }
            }
        }

        EditorialTabs(
            tabs = listOf(
                EditorialTab("Smart", SmartPlaylistType.entries.size),
                EditorialTab("Yours", cards.size.takeIf { it > 0 }),
            ),
            selected = selectedTab,
            onSelect = { selectedTab = it },
            palette = palette,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )

        // Hoisted out of AnimatedContent, one per tab: created inside it,
        // each list's state lived and died with the tab's content, so the
        // scroll position was not reliably there when coming back from a
        // playlist. Both are saveable and belong to this back stack entry.
        val smartListState = rememberLazyListState()
        val yoursListState = rememberLazyListState()
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn().togetherWith(fadeOut()) },
            label = "playlists-tabs",
        ) { tab ->
            when (tab) {
                0 -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = smartListState,
                    contentPadding = PaddingValues(top = 2.dp, bottom = LocalFloatingChromeHeight.current),
                ) {
                    items(SmartPlaylistType.entries, key = { it.name }) { type ->
                        SmartPlaylistRow(
                            type = type,
                            palette = palette,
                            onOpen = { onSmartPlaylistClick(type) },
                            onPlay = { viewModel.playSmart(type) },
                        )
                    }
                }
                else -> {
                    if (cards.isEmpty()) {
                        EmptyState(
                            "No playlists yet",
                            "Tap + to make one, import M3U, or transfer from Spotify.",
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = yoursListState,
                            contentPadding = PaddingValues(top = 2.dp, bottom = LocalFloatingChromeHeight.current),
                        ) {
                            items(cards, key = { it.id }) { card ->
                                PlaylistRow(
                                    card = card,
                                    palette = palette,
                                    onOpen = { onPlaylistClick(card.id) },
                                    onPlay = { viewModel.play(card.id) },
                                    onRename = { renameTarget = card },
                                    onDelete = { deleteTarget = card },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        PlaylistDialog(
            palette = palette,
            title = "New playlist",
            confirmLabel = "Create",
            confirmEnabled = name.isNotBlank(),
            onConfirm = { viewModel.create(name); showCreateDialog = false },
            onDismiss = { showCreateDialog = false },
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        }
    }

    renameTarget?.let { card ->
        var name by remember(card.id) { mutableStateOf(card.name) }
        PlaylistDialog(
            palette = palette,
            title = "Rename playlist",
            confirmLabel = "Save",
            confirmEnabled = name.isNotBlank(),
            onConfirm = { viewModel.rename(card.id, name); renameTarget = null },
            onDismiss = { renameTarget = null },
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        }
    }

    deleteTarget?.let { card ->
        PlaylistDialog(
            palette = palette,
            title = "Delete \"${card.name}\"?",
            confirmLabel = "Delete",
            onConfirm = { viewModel.delete(card.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        ) {
            Text(
                "This removes the playlist. Your songs themselves are not affected.",
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = palette.muted,
            )
        }
    }
}

private val MOSAIC_SIZE = 60.dp

/**
 * One playlist row: cover mosaic, name, "12 songs · 48 min", Play, overflow.
 *
 * The previous card stacked a 22sp title, a count line and a full-width Play
 * pill, so four playlists filled a phone screen. Folding the metadata into a
 * single line beside a 60dp mosaic roughly doubles the density, which is the
 * point of a list.
 */
@Composable
private fun PlaylistRow(
    card: PlaylistCardUi,
    palette: EditorialPalette,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    EditorialCard(
        palette = palette,
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .semantics { contentDescription = "${card.name}. ${card.summary}" },
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverMosaic(card.artwork, palette)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    card.name,
                    fontSize = 18.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = palette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    card.summary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            PlayCircle(
                onClick = onPlay,
                palette = palette,
                description = "Play ${card.name}",
                enabled = card.songCount > 0,
            )
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Options for ${card.name}",
                        tint = palette.muted,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/**
 * Smart playlists get an icon tile rather than a mosaic.
 *
 * They are queries, not stored lists, so illustrating them with covers would
 * mean running six more queries to picture something with no fixed contents.
 * The icon plus a description of what the query does also replaces the old
 * "Updates itself", which was identical on all six rows and said nothing.
 */
@Composable
private fun SmartPlaylistRow(
    type: SmartPlaylistType,
    palette: EditorialPalette,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    EditorialCard(
        palette = palette,
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(MOSAIC_SIZE)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.line.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    type.icon(),
                    contentDescription = null,
                    tint = palette.ink,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    type.displayTitle(),
                    fontSize = 18.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    type.description(),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            PlayCircle(
                onClick = onPlay,
                palette = palette,
                description = "Play ${type.displayTitle()}",
            )
        }
    }
}

/**
 * Up to four covers in a 2x2 grid, degrading gracefully: one cover fills the
 * tile, three pad the fourth slot with the first, none falls back to a
 * placeholder rather than an empty hole.
 */
@Composable
private fun CoverMosaic(artwork: List<String>, palette: EditorialPalette) {
    Box(
        Modifier
            .size(MOSAIC_SIZE)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.line.copy(alpha = 0.20f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            artwork.isEmpty() -> Icon(
                Icons.Rounded.LibraryMusic,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(24.dp),
            )
            artwork.size == 1 -> Artwork(
                artworkUri = artwork[0],
                contentDescription = null,
                cornerRadius = 12.dp,
                modifier = Modifier.size(MOSAIC_SIZE),
            )
            else -> {
                val tiles = List(4) { index -> artwork.getOrNull(index) ?: artwork[0] }
                Column {
                    Row { MosaicTile(tiles[0]); MosaicTile(tiles[1]) }
                    Row { MosaicTile(tiles[2]); MosaicTile(tiles[3]) }
                }
            }
        }
    }
}

@Composable
private fun MosaicTile(uri: String) {
    Artwork(
        artworkUri = uri,
        contentDescription = null,
        cornerRadius = 0.dp,
        modifier = Modifier.size(MOSAIC_SIZE / 2),
    )
}

/** The one filled control per row. */
@Composable
private fun PlayCircle(
    onClick: () -> Unit,
    palette: EditorialPalette,
    description: String,
    enabled: Boolean = true,
) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = description },
            shape = RoundedCornerShape(50),
            color = if (enabled) palette.accent else Color.Transparent,
            contentColor = if (enabled) palette.onAccent else palette.muted,
            border = if (enabled) null else BorderStroke(1.dp, palette.line),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Editorial-shaped dialog, so create/rename/delete stop looking like stock Material. */
@Composable
private fun PlaylistDialog(
    palette: EditorialPalette,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, color = palette.ink)
        },
        text = content,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, fontWeight = FontWeight.Bold, color = palette.ink)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = palette.muted) } },
        shape = RoundedCornerShape(20.dp),
        containerColor = palette.field,
        titleContentColor = palette.ink,
        textContentColor = palette.ink,
    )
}

private fun SmartPlaylistType.icon(): ImageVector = when (this) {
    SmartPlaylistType.FAVORITES -> Icons.Rounded.Favorite
    SmartPlaylistType.MOST_PLAYED -> Icons.Rounded.Repeat
    SmartPlaylistType.RECENTLY_ADDED -> Icons.Rounded.NewReleases
    SmartPlaylistType.RECENTLY_PLAYED -> Icons.Rounded.History
    SmartPlaylistType.HIGHEST_ENERGY -> Icons.Rounded.Bolt
    SmartPlaylistType.LOWEST_ENERGY -> Icons.Rounded.NightsStay
}

private fun SmartPlaylistType.description(): String = when (this) {
    SmartPlaylistType.FAVORITES -> "Everything you've starred."
    SmartPlaylistType.MOST_PLAYED -> "Your highest play counts, first."
    SmartPlaylistType.RECENTLY_ADDED -> "Newest files found by the scanner."
    SmartPlaylistType.RECENTLY_PLAYED -> "What you've listened to lately."
    SmartPlaylistType.HIGHEST_ENERGY -> "Loud and driving, from the analysis pass."
    SmartPlaylistType.LOWEST_ENERGY -> "Quiet and slow, from the analysis pass."
}
