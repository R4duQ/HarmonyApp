package com.harmony.feature.playlists

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.harmony.core.ui.component.Artwork
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialSwipeToQueue
import com.harmony.core.ui.component.SwipeDownToDismissState
import com.harmony.core.ui.component.rememberDismissCallback
import com.harmony.core.ui.component.rememberSwipeDownToDismissState
import com.harmony.core.ui.component.swipeDownToDismiss
import com.harmony.core.ui.component.formatDuration
import com.harmony.core.ui.component.glassFill
import com.harmony.core.ui.component.glassRim

/** What the page shows. Null [title]/[songs] mean "still loading". */
@Immutable
internal data class PlaylistDetailUi(
    val title: String?,
    val smartType: SmartPlaylistType?,
    val editable: Boolean,
    val songs: List<Song>?,
    /** The playlist was deleted (or never existed) while this page was open. */
    val missing: Boolean = false,
    val nowPlayingId: Long? = null,
    /** When a user playlist was made; null for smart playlists or while loading. */
    val createdAt: Long? = null,
)

/** Everything the page can ask for. Plain lambdas so previews and tests can pass no-ops. */
@Immutable
internal class PlaylistDetailActions(
    val onBack: () -> Unit,
    val onPlay: (list: List<Song>, index: Int) -> Unit,
    val onShuffle: (List<Song>) -> Unit,
    val onPlayNext: (Song) -> Unit = {},
    val onRemove: (Song) -> Unit = {},
    val onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onAddSongs: () -> Unit = {},
    val onExport: () -> Unit = {},
)

internal object PlaylistDetailTags {
    const val PAGE = "playlist_page"
    const val LIST = "playlist_list"
    const val HERO = "playlist_hero"
    const val TITLE = "playlist_title"
    const val TOP_BAR = "playlist_top_bar"
    const val COMPACT_TITLE = "playlist_compact_title"
    const val BACK = "playlist_back"
    const val MORE = "playlist_more"
    const val PLAY = "playlist_play"
    const val SHUFFLE = "playlist_shuffle"
    const val LOADING = "playlist_loading"
    const val EMPTY = "playlist_empty"
    const val MISSING = "playlist_missing"
    const val ADD_MORE = "playlist_add_more"
    fun row(id: Long) = "playlist_song_$id"
    fun handle(id: Long) = "playlist_handle_$id"
}

private val PageRadius = 28.dp
private val PageInset = 10.dp
private val HeroSide = 22.dp

/**
 * Playlist detail: one glass page that slides in over the section field,
 * with a large cover and title up top and the songs continuing inside the
 * same pane.
 *
 * The top bar is fixed and always interactive — Back on the left, the
 * secondary actions behind ⋯ on the right — and only its glass and its
 * one-line title fade in once the big title has scrolled underneath it, so
 * the same two controls are in the same place in both states.
 *
 * Dragging down on the big title (while the list is at its top) or anywhere
 * on the top bar drags the whole page; see [swipeDownToDismiss].
 */
@Composable
internal fun PlaylistDetailContent(
    ui: PlaylistDetailUi,
    palette: EditorialPalette,
    actions: PlaylistDetailActions,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    dismissState: SwipeDownToDismissState = rememberSwipeDownToDismissState(),
) {
    val onDismiss = rememberDismissCallback(actions.onBack)
    val requestBack: () -> Unit = { dismissState.requestDismiss(onDismiss) }

    var heroHeight by remember { mutableIntStateOf(1) }
    var topBarHeight by remember { mutableIntStateOf(0) }
    val collapse by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val travel = (heroHeight - topBarHeight).coerceAtLeast(1)
                    (listState.firstVisibleItemScrollOffset / (travel * 0.72f)).coerceIn(0f, 1f)
                }
            }
        }
    }
    val atTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // Drag-reorder, previewed locally and written once on drop (see
    // PlaylistDetailViewModel.moveSong for why).
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTo by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val songs = ui.songs
    // The order just dropped, held until the database re-emits. Without it
    // the row snapped back to its old slot for a frame between the drop and
    // Room's update, then jumped again.
    var pendingOrder by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(songs) { pendingOrder = null }
    val base = pendingOrder ?: songs
    // Read by the grip's gesture, which outlives the composition it started in.
    val latestBase by rememberUpdatedState(base)
    val display = remember(base, dragFrom, dragTo) {
        if (base == null || dragFrom < 0 || dragTo < 0 || dragFrom == dragTo) {
            base
        } else {
            base.toMutableList().apply { add(dragTo, removeAt(dragFrom)) }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(palette.field)
            .onSizeChanged { dismissState.pageHeightPx = it.height.toFloat() }
            .testTag(PlaylistDetailTags.PAGE),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val offset = dismissState.offsetPx
                    translationY = offset
                    // A little recession as it goes, the way a sheet leaves:
                    // it reads as the page lifting off rather than a
                    // screenshot sliding down.
                    val lift = if (size.height > 0f) (offset / (size.height * 0.5f)).coerceIn(0f, 1f) else 0f
                    val scale = 1f - 0.06f * lift
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    alpha = 1f - 0.25f * lift
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PageInset)
                    .clip(RoundedCornerShape(topStart = PageRadius, topEnd = PageRadius))
                    .background(
                        Brush.verticalGradient(
                            listOf(glassFill(palette, 0.78f), glassFill(palette, 0.58f)),
                            endY = 1400f,
                        ),
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(glassRim(palette), Color.Transparent), endY = 600f),
                        RoundedCornerShape(topStart = PageRadius, topEnd = PageRadius),
                    )
                    .testTag(PlaylistDetailTags.LIST),
                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
            ) {
                // Everything above the songs is one item, so the rows keep
                // their indices (the reorder and scroll logic count on it).
                item(key = "hero", contentType = "hero") {
                    Column {
                        Hero(
                            ui = ui,
                            palette = palette,
                            topInset = with(LocalDensity.current) { topBarHeight.toDp() },
                            modifier = Modifier
                                .onSizeChanged { heroHeight = it.height.coerceAtLeast(1) }
                                .testTag(PlaylistDetailTags.HERO)
                                .swipeDownToDismiss(dismissState, onDismiss, canStart = { atTop }),
                        )
                        if (!ui.missing && (songs == null || songs.isNotEmpty())) {
                            ActionRow(
                                enabled = !songs.isNullOrEmpty(),
                                palette = palette,
                                onPlay = { songs?.takeIf { it.isNotEmpty() }?.let { actions.onPlay(it, 0) } },
                                onShuffle = { songs?.takeIf { it.isNotEmpty() }?.let(actions.onShuffle) },
                            )
                        }
                        if (!songs.isNullOrEmpty() && !ui.missing) {
                            Column(
                                Modifier.padding(start = HeroSide - 8.dp, end = HeroSide - 8.dp, top = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                StatTiles(songs, palette)
                                AboutCard(songs, palette)
                                TopArtists(songs, palette)
                                SongsHeader(songs, ui.editable, palette, Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                        } else {
                            Spacer(Modifier.height(14.dp))
                            Box(
                                Modifier
                                    .padding(horizontal = HeroSide)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(palette.line.copy(alpha = palette.line.alpha * 0.8f)),
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                when {
                    ui.missing -> item(key = "missing") {
                        MessagePanel(
                            palette = palette,
                            glyph = Icons.Rounded.QueueMusic,
                            title = "This playlist is gone",
                            body = "It was deleted, so there is nothing left to show here.",
                            actionLabel = "Go back",
                            actionIcon = Icons.AutoMirrored.Rounded.ArrowBack,
                            onAction = requestBack,
                            modifier = Modifier.testTag(PlaylistDetailTags.MISSING),
                        )
                    }

                    display == null -> {
                        items(count = 6, key = { "skeleton-$it" }, contentType = { "skeleton" }) { index ->
                            SkeletonRow(
                                palette = palette,
                                widthFraction = 0.45f + (index % 3) * 0.15f,
                                modifier = if (index == 0) Modifier.testTag(PlaylistDetailTags.LOADING) else Modifier,
                            )
                        }
                    }

                    display.isEmpty() -> item(key = "empty") {
                        val (title, body) = emptyCopy(ui)
                        MessagePanel(
                            palette = palette,
                            glyph = ui.smartType?.glyph() ?: Icons.Rounded.QueueMusic,
                            title = title,
                            body = body,
                            actionLabel = if (ui.editable) "Add songs" else null,
                            actionIcon = Icons.Rounded.Add,
                            onAction = actions.onAddSongs,
                            modifier = Modifier.testTag(PlaylistDetailTags.EMPTY),
                        )
                    }

                    else -> {
                        itemsIndexed(display, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { index, song ->
                            val isDragging = index == dragTo && dragFrom >= 0
                            val currentIndex by rememberUpdatedState(index)
                            val lastIndex by rememberUpdatedState(display.lastIndex)
                            val haptics = LocalHapticFeedback.current
                            SongRow(
                                song = song,
                                position = index,
                                palette = palette,
                                playing = song.id == ui.nowPlayingId,
                                dragging = isDragging,
                                onClick = { actions.onPlay(display, index) },
                                onPlayNext = { actions.onPlayNext(song) },
                                onRemove = if (ui.editable) ({ actions.onRemove(song) }) else null,
                                dragHandleModifier = if (!ui.editable) null else Modifier
                                    .testTag(PlaylistDetailTags.handle(song.id))
                                    // Keyed on the song, not the index: keying
                                    // on the index restarts the handler the
                                    // moment the row changes slot, cancelling
                                    // the drag one position in.
                                    .pointerInput(song.id) {
                                        var rowHeight = 1f
                                        detectDragGestures(
                                            onDragStart = {
                                                dragFrom = currentIndex
                                                dragTo = currentIndex
                                                dragOffset = 0f
                                                rowHeight = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.key == song.id }
                                                    ?.size?.toFloat() ?: 1f
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                while (dragOffset > rowHeight / 2 && dragTo < lastIndex) {
                                                    dragTo += 1
                                                    dragOffset -= rowHeight
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                while (dragOffset < -rowHeight / 2 && dragTo > 0) {
                                                    dragTo -= 1
                                                    dragOffset += rowHeight
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            },
                                            onDragEnd = {
                                                if (dragFrom >= 0 && dragTo >= 0 && dragFrom != dragTo) {
                                                    pendingOrder = latestBase?.toMutableList()
                                                        ?.apply { add(dragTo, removeAt(dragFrom)) }
                                                    actions.onMove(dragFrom, dragTo)
                                                }
                                                dragFrom = -1; dragTo = -1; dragOffset = 0f
                                            },
                                            onDragCancel = { dragFrom = -1; dragTo = -1; dragOffset = 0f },
                                        )
                                    },
                                modifier = Modifier
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffset else 0f
                                    }
                                    .testTag(PlaylistDetailTags.row(song.id)),
                            )
                        }
                        if (ui.editable) {
                            item(key = "add-more") {
                                AddMoreRow(
                                    palette = palette,
                                    onClick = actions.onAddSongs,
                                    modifier = Modifier.testTag(PlaylistDetailTags.ADD_MORE),
                                )
                            }
                        }
                        item(key = "footer") { PlaylistFooter(display, ui.createdAt, palette) }
                    }
                }
            }

            TopBar(
                title = ui.title.orEmpty(),
                palette = palette,
                collapse = { collapse },
                editable = ui.editable,
                canExport = ui.editable && !ui.songs.isNullOrEmpty(),
                onBack = requestBack,
                onAddSongs = actions.onAddSongs,
                onExport = actions.onExport,
                modifier = Modifier
                    .onSizeChanged { topBarHeight = it.height }
                    .swipeDownToDismiss(dismissState, onDismiss),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(
    title: String,
    palette: EditorialPalette,
    collapse: () -> Float,
    editable: Boolean,
    canExport: Boolean,
    onBack: () -> Unit,
    onAddSongs: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nearly opaque once collapsed: rows scroll underneath, and a see-through
    // bar put their titles behind the compact one.
    val barFill = glassFill(palette, 0.86f).compositeOver(palette.field).copy(alpha = 0.97f)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PageInset)
            .clip(RoundedCornerShape(topStart = PageRadius, topEnd = PageRadius))
            .drawBehind {
                val a = collapse()
                if (a > 0f) {
                    drawRect(barFill.copy(alpha = barFill.alpha * a))
                    drawRect(
                        palette.line.copy(alpha = palette.line.alpha * a),
                        topLeft = Offset(0f, size.height - 1.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()),
                    )
                }
            }
            .testTag(PlaylistDetailTags.TOP_BAR),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                palette = palette,
                onClick = onBack,
                modifier = Modifier.testTag(PlaylistDetailTags.BACK),
            )
            Text(
                title,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    // Only once the big title is actually under the bar, so
                    // the name is never printed twice at the same time.
                    .graphicsLayer { alpha = ((collapse() - 0.55f) / 0.45f).coerceIn(0f, 1f) }
                    .testTag(PlaylistDetailTags.COMPACT_TITLE),
            )
            if (editable) {
                MoreMenu(
                    palette = palette,
                    canExport = canExport,
                    onAddSongs = onAddSongs,
                    onExport = onExport,
                )
            } else {
                // Keeps the title centred when there is no menu.
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun MoreMenu(
    palette: EditorialPalette,
    canExport: Boolean,
    onAddSongs: () -> Unit,
    onExport: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassIconButton(
            icon = Icons.Rounded.MoreHoriz,
            contentDescription = "More playlist actions",
            palette = palette,
            onClick = { open = true },
            modifier = Modifier.testTag(PlaylistDetailTags.MORE),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Add songs") },
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = { open = false; onAddSongs() },
            )
            DropdownMenuItem(
                text = { Text("Export as M3U") },
                leadingIcon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
                enabled = canExport,
                onClick = { open = false; onExport() },
            )
        }
    }
}

/** 48dp touch target, 42dp glass disc. */
@Composable
private fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(glassFill(palette, 0.9f))
                // palette.line, not glassRim: the white rim vanishes on the
                // near-white page in light mode.
                .border(1.dp, palette.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.ink, modifier = Modifier.size(22.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Hero
// ---------------------------------------------------------------------------

@Composable
private fun Hero(
    ui: PlaylistDetailUi,
    palette: EditorialPalette,
    topInset: Dp,
    modifier: Modifier = Modifier,
) {
    val songs = ui.songs
    val artwork = remember(songs) { PlaylistDetailFormat.coverArtwork(songs.orEmpty()) }
    Box(modifier.fillMaxWidth()) {
        HeroBackdrop(artwork.firstOrNull(), palette, Modifier.matchParentSize())
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = topInset, start = HeroSide, end = HeroSide),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    // A soft pool of the accent under the cover, so a cover-less
                    // playlist doesn't look like a grey placeholder page. Drawn
                    // behind rather than laid out, so it costs no height.
                    .drawBehind {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(palette.accent.copy(alpha = 0.20f), Color.Transparent),
                                center = center,
                                radius = size.height * 0.75f,
                            ),
                            radius = size.height * 0.75f,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // At large font sizes the text below needs the height more than
                // the artwork does, so the cover gives some of it back.
                val fontScale = LocalDensity.current.fontScale
                val shrink = if (fontScale > 1.3f) 0.78f else 1f
                val cover = min(maxWidth * 0.66f, 260.dp) * shrink
                if (songs == null && !ui.missing) {
                    SkeletonBlock(palette, Modifier.size(cover), RoundedCornerShape(cover * 0.085f))
                } else {
                    CoverStack(artwork, palette, ui.smartType, cover)
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ui.smartType != null) "SMART PLAYLIST" else "PLAYLIST",
                    fontSize = 11.sp,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent,
                )
                if (ui.smartType != null) {
                    Text(
                        "LIVE",
                        fontSize = 9.sp,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.onAccent,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(palette.accent)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (ui.title == null) {
                SkeletonBlock(palette, Modifier.width(190.dp).height(28.dp), RoundedCornerShape(8.dp))
            } else {
                // Long names step down a size so they wrap into two or three
                // balanced lines instead of three lines of one word each.
                val long = ui.title.length > 24
                Text(
                    ui.title,
                    fontSize = if (long) 26.sp else 32.sp,
                    lineHeight = if (long) 31.sp else 37.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.7).sp,
                    color = palette.ink,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag(PlaylistDetailTags.TITLE),
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                ui.missing -> Unit
                songs != null && songs.isEmpty() && ui.smartType == null -> Unit
                songs == null -> SkeletonBlock(palette, Modifier.width(140.dp).height(14.dp), RoundedCornerShape(6.dp))
                else -> {
                    val artists = remember(songs) { PlaylistDetailFormat.artists(songs, shown = 2) }
                    if (artists != null) {
                        Text(
                            "with $artists",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = palette.muted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (ui.smartType != null) {
                        Text(
                            smartRule(ui.smartType),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = palette.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    val playing = remember(songs, ui.nowPlayingId) { songs.firstOrNull { it.id == ui.nowPlayingId } }
                    if (playing != null) {
                        NowPlayingChip(playing, palette, Modifier.padding(top = 14.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** What a smart playlist collects, in one line. */
private fun smartRule(type: SmartPlaylistType): String = when (type) {
    SmartPlaylistType.FAVORITES -> "Every song you've marked as a favourite · updates automatically"
    SmartPlaylistType.MOST_PLAYED -> "Your most played songs · updates automatically"
    SmartPlaylistType.RECENTLY_ADDED -> "The newest songs in your library · updates automatically"
    SmartPlaylistType.RECENTLY_PLAYED -> "What you've played lately · updates automatically"
    SmartPlaylistType.HIGHEST_ENERGY -> "Your most energetic songs · updates automatically"
    SmartPlaylistType.LOWEST_ENERGY -> "Your calmest songs · updates automatically"
}

/**
 * The lead cover, enlarged and blurred into a wash of its colours behind
 * the header, fading out into the page. Blur needs Android 12; older phones
 * keep the plain glass page and the accent pool.
 */
@Composable
private fun HeroBackdrop(artwork: String?, palette: EditorialPalette, modifier: Modifier) {
    if (artwork == null || android.os.Build.VERSION.SDK_INT < 31) return
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Fade to nothing towards the bottom, so the glass page takes over.
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black.copy(alpha = 0.7f), 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Artwork(
            artwork,
            contentDescription = null,
            cornerRadius = 0.dp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.5f
                    scaleY = 1.5f
                    alpha = if (palette.field.luminance() < 0.5f) 0.55f else 0.38f
                }
                .blur(60.dp, BlurredEdgeTreatment.Rectangle),
        )
        // Keep the title readable on any cover.
        Box(Modifier.fillMaxSize().background(palette.field.copy(alpha = 0.25f)))
    }
}

/**
 * The playlist's cover with two more of its records peeking out behind it,
 * tilted, like a stack in a crate. Fewer than three covers: just the cover.
 */
@Composable
private fun CoverStack(artwork: List<String>, palette: EditorialPalette, smartType: SmartPlaylistType?, size: Dp) {
    Box(contentAlignment = Alignment.Center) {
        if (artwork.size >= 3) {
            val back = size * 0.86f
            listOf(-9f to artwork[2], 8f to artwork[1]).forEachIndexed { i, (angle, uri) ->
                Box(
                    Modifier
                        .graphicsLayer {
                            rotationZ = angle
                            translationX = (if (i == 0) -1 else 1) * size.toPx() * 0.17f
                            translationY = size.toPx() * 0.02f
                        }
                        .shadow(10.dp, RoundedCornerShape(back * 0.08f))
                        .size(back),
                ) {
                    Artwork(uri, contentDescription = null, modifier = Modifier.fillMaxSize(), cornerRadius = back * 0.08f)
                    // Pushed back: a little darker than the cover in front.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(back * 0.08f))
                            .background(Color.Black.copy(alpha = 0.28f)),
                    )
                }
            }
        }
        PlaylistCover(artwork = artwork, palette = palette, smartType = smartType, size = size)
    }
}

/**
 * Play and Shuffle, side by side while both labels fit, stacked when they
 * wouldn't — a narrow phone at a large font size would otherwise ellipsize
 * "Shuffle" down to "Shu…".
 */
@Composable
private fun ActionRow(
    enabled: Boolean,
    palette: EditorialPalette,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = HeroSide)) {
        val fontScale = LocalDensity.current.fontScale
        // Icon + padding (~52dp) plus the label (~60dp at 1x) per button,
        // plus the gap between them.
        val needed = 12.dp + (52.dp + 60.dp * fontScale) * 2
        if (maxWidth >= needed) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayButton(enabled, palette, onPlay, Modifier.weight(1f))
                ShuffleButton(enabled, palette, onShuffle, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayButton(enabled, palette, onPlay, Modifier.fillMaxWidth())
                ShuffleButton(enabled, palette, onShuffle, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PlayButton(enabled: Boolean, palette: EditorialPalette, onClick: () -> Unit, modifier: Modifier) {
    ActionButton(
        label = "Play",
        icon = Icons.Rounded.PlayArrow,
        container = palette.accent,
        content = palette.onAccent,
        rim = null,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .shadow(if (enabled) 10.dp else 0.dp, RoundedCornerShape(50), spotColor = palette.accent, ambientColor = palette.accent)
            .testTag(PlaylistDetailTags.PLAY),
    )
}

@Composable
private fun ShuffleButton(enabled: Boolean, palette: EditorialPalette, onClick: () -> Unit, modifier: Modifier) {
    ActionButton(
        label = "Shuffle",
        icon = Icons.Rounded.Shuffle,
        container = palette.accent.copy(alpha = 0.13f).compositeOver(glassFill(palette, 0.9f)),
        content = palette.ink,
        rim = glassRim(palette),
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.testTag(PlaylistDetailTags.SHUFFLE),
    )
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    rim: Color?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(container.copy(alpha = if (enabled) container.alpha else container.alpha * 0.45f))
            .then(if (rim != null) Modifier.border(1.dp, rim, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) content else content.copy(alpha = 0.5f)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/**
 * One song: its place in the playlist, cover, title, artist and album,
 * length and sound quality, options.
 *
 * Tapping plays from here; ⋯ holds Play next and Remove; swiping right
 * queues it (the same Play next, for people who know the gesture). The row
 * is opaque — the swipe reveals the "Play next" plate from underneath, and a
 * translucent row would show it through at rest.
 *
 * The left gutter shows the row's number, or the playing bars on the song
 * that's playing; in an editable playlist it is also the grip to drag by.
 */
@Composable
private fun SongRow(
    song: Song,
    position: Int,
    palette: EditorialPalette,
    playing: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onRemove: (() -> Unit)?,
    dragHandleModifier: Modifier?,
    modifier: Modifier = Modifier,
) {
    val rowFill = glassFill(palette, 0.62f).compositeOver(palette.field)
    val fill = when {
        dragging -> glassFill(palette, 0.9f).compositeOver(palette.field)
        playing -> palette.accent.copy(alpha = 0.13f).compositeOver(rowFill)
        else -> rowFill
    }
    val shape = RoundedCornerShape(18.dp)
    EditorialSwipeToQueue(
        palette = palette,
        onQueue = onPlayNext,
        icon = Icons.Rounded.QueueMusic,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (dragging) Modifier.shadow(12.dp, shape, ambientColor = palette.accent, spotColor = palette.accent)
                    else Modifier,
                )
                .clip(shape)
                .background(fill)
                .then(if (playing) Modifier.border(1.dp, palette.accent.copy(alpha = 0.35f), shape) else Modifier)
                .clickable(onClickLabel = "Play", onClick = onClick)
                .heightIn(min = 72.dp)
                .padding(end = 0.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                (dragHandleModifier ?: Modifier)
                    .width(40.dp)
                    .heightIn(min = 52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (playing) {
                    PlayingBars(palette.accent, Modifier.size(14.dp))
                } else {
                    Text(
                        PlaylistDetailFormat.position(position),
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
                if (dragHandleModifier != null) {
                    Icon(
                        Icons.Rounded.DragHandle,
                        contentDescription = "Reorder ${song.title}",
                        tint = if (dragging) palette.ink else palette.muted.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 3.dp).size(16.dp),
                    )
                }
            }
            SongThumb(song.artworkUri, palette, size = 54.dp)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            ) {
                Text(
                    song.title,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (playing) palette.accent else palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = palette.ink.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
                val album = song.album.trim().takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) }
                val detail = listOfNotNull(album, song.year?.takeIf { it > 0 }?.toString()).joinToString("  ·  ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            // Length on top, quality under it; never cut, the text gives way first.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatDuration(song.durationMs),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.muted,
                    maxLines = 1,
                )
                QualityBadge(song, palette, Modifier.padding(top = 4.dp))
            }
            RowMenu(song = song, palette = palette, onPlayNext = onPlayNext, onRemove = onRemove)
        }
    }
}

@Composable
private fun RowMenu(
    song: Song,
    palette: EditorialPalette,
    onPlayNext: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = { open = true })
                .semantics { contentDescription = "More options for ${song.title}" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = palette.muted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                onClick = { open = false; onPlayNext() },
            )
            if (onRemove != null) {
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                    onClick = { open = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun AddMoreRow(palette: EditorialPalette, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(52.dp * 0.2f))
                .border(1.5.dp, palette.accent.copy(alpha = 0.45f), RoundedCornerShape(52.dp * 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
        }
        Text(
            "Add songs",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.accent,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------

@Composable
private fun MessagePanel(
    palette: EditorialPalette,
    glyph: ImageVector,
    title: String,
    body: String,
    actionLabel: String?,
    actionIcon: ImageVector,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, contentDescription = null, tint = palette.accent, modifier = Modifier.size(28.dp))
        }
        Text(
            title,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(18.dp))
            ActionButton(
                label = actionLabel,
                icon = actionIcon,
                container = palette.accent,
                content = palette.onAccent,
                rim = null,
                enabled = true,
                onClick = onAction,
                modifier = Modifier.widthIn(min = 160.dp),
            )
        }
    }
}

private fun emptyCopy(ui: PlaylistDetailUi): Pair<String, String> = when (ui.smartType) {
    null -> "No songs yet" to "Add songs from your library, or use Add to playlist from any song's menu."
    SmartPlaylistType.FAVORITES -> "No favourites yet" to "Songs you mark as favourite collect here."
    SmartPlaylistType.MOST_PLAYED, SmartPlaylistType.RECENTLY_PLAYED ->
        "Nothing played yet" to "Play some music and this list fills itself in."
    SmartPlaylistType.RECENTLY_ADDED -> "Nothing new yet" to "Songs appear here as your library scan finds them."
    SmartPlaylistType.HIGHEST_ENERGY, SmartPlaylistType.LOWEST_ENERGY ->
        "Still listening" to "Energy playlists fill in as audio analysis finishes in the background."
}

@Composable
private fun SkeletonRow(palette: EditorialPalette, widthFraction: Float, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(palette, Modifier.size(52.dp), RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            SkeletonBlock(palette, Modifier.fillMaxWidth(widthFraction).height(14.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.height(8.dp))
            SkeletonBlock(palette, Modifier.fillMaxWidth(widthFraction * 0.6f).height(11.dp), RoundedCornerShape(6.dp))
        }
    }
}

@Composable
private fun SkeletonBlock(palette: EditorialPalette, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .background(palette.line.copy(alpha = 0.55f)),
    )
}
