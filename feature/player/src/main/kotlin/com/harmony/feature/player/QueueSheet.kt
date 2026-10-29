package com.harmony.feature.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.PlayerState
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.formatDuration

/**
 * The queue, as its own surface rather than a plain list bolted under the
 * player.
 *
 * Design notes, and why it is deliberately not the same as Harmony's other
 * lists: everywhere else a row is a thing you tap to play. Here a row is
 * something you *move*, and tapping to play is the secondary action. So the
 * drag handle gets a real 48dp target on the leading edge, the rows are
 * flatter and denser than EditorialCard rows (you are scanning order, not
 * browsing covers), and the currently playing track is lifted out into its
 * own header card instead of being one highlighted row among two hundred.
 *
 * It also takes the player's own palette rather than the Material scheme.
 * Previously it read `MaterialTheme.colorScheme` directly, so with dynamic
 * colour on it rendered green over the player's purple.
 *
 * ## Queue semantics
 *
 * Already-played tracks are hidden from this list but are NOT removed from the
 * player's queue — "previous" has to keep working. So this is a DISPLAY filter
 * only: the list renders from the current track onward, while every index
 * handed back to the player is translated to its absolute queue position, so
 * the two views can never disagree.
 *
 * Section headers render INSIDE the row item at each boundary rather than as
 * separate LazyColumn items, so a row's list index plus the index of the
 * first upcoming song is its queue index.
 *
 * Each row is one queue ENTRY: the same song queued twice is two rows, and
 * moving or removing one never touches the other.
 *
 * ## Drag
 *
 *  - Long-press gated, and only on the handle. That is what stops the gesture
 *    from fighting the bottom sheet's own drag and the list's scroll; the
 *    three consumers never contend for the same pointer.
 *  - Row height is MEASURED, not looked up. The previous version resolved it
 *    from `visibleItemsInfo.firstOrNull { it.index == index }` using the
 *    absolute queue index against LazyColumn's relative indices — those only
 *    coincide while the queue sits at track 0, so every other time it silently
 *    fell back to a hard-coded 64f and the reorder threshold was wrong.
 *    Measuring also means the header on a boundary row cannot skew it.
 *  - Half a row of travel moves the row one slot, locally; the player gets
 *    one move, on drop, with the song it is moving. It refuses a move whose
 *    song is no longer there and keeps moves after the song playing, so a
 *    queue that changed under the finger (the song ended, Smart Shuffle
 *    appended) can't move the wrong song or hide one in the history.
 *  - The gesture finds its row by key at each start, never by a position
 *    remembered from an earlier drag.
 */
@Composable
fun QueueSheet(viewModel: PlayerViewModel) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    QueueSheetContent(
        state = state,
        onPlay = viewModel::onQueueItemClick,
        onRemove = viewModel::onRemoveQueueItem,
        onMove = { from, to, songId, onDone -> viewModel.onMoveQueueItem(from, to, songId, onDone) },
    )
}

/**
 * The queue sheet for [state]. [onMove] moves the queue entry at `from`
 * (holding song `songId`) to `to` and reports whether the player made the
 * move; `from` and `to` are queue indices.
 */
@Composable
internal fun QueueSheetContent(
    state: PlayerState,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int, songId: Long, onDone: (Boolean) -> Unit) -> Unit,
) {
    val listState = rememberLazyListState()
    val palette = playerPalette()

    val currentIndex = state.queueIndex.coerceAtLeast(0)
    val firstUpcoming = currentIndex + 1
    val priorityEnd = currentIndex + state.playNextCount
    val nowPlaying = state.queue.getOrNull(currentIndex)
    // Already-played tracks stay in the player's queue so "previous" keeps
    // working; they are only filtered out of this view.
    val queueUpcoming = if (state.queue.isEmpty()) emptyList() else state.queue.drop(firstUpcoming)

    // Every row is keyed by its queue entry, not by its position or its song:
    // the same song can be queued twice, and each copy is its own row (see
    // QueueKeys). The key is also what the drag gesture is bound to.
    val keys = remember { QueueKeys() }
    val queueEntries = remember(queueUpcoming) { keys.entriesFor(queueUpcoming) }

    // Drag state.
    //
    // `localOrder` is a LOCAL copy of the upcoming rows that the drag
    // mutates. While it is non-null the list renders from it and nothing is
    // sent to the player: the whole move commits once, on drop. It then
    // stays until the player's queue comes back with the move (or the move is
    // refused), so the list doesn't snap back to the old order for a frame.
    var localOrder by remember { mutableStateOf<List<QueueEntry>?>(null) }
    var draggedKey by remember { mutableStateOf<Long?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTo by remember { mutableIntStateOf(-1) }
    var dragBase by remember { mutableIntStateOf(0) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // All rows are the same height, so one measurement serves every drag.
    var rowHeightPx by remember { mutableFloatStateOf(0f) }

    val upcoming = localOrder ?: queueEntries

    // A row's gesture keeps running for as long as the row exists, through
    // every move and every change of song. Positions read when it started are
    // stale by the next drag: that was the bug where the second move of a row
    // (or a row shifted by an earlier move) moved another song, jumped the
    // list back to an old order, or dropped a song behind the one playing.
    // So the gesture finds its row in the list as it is NOW, at each start.
    val latestUpcoming by rememberUpdatedState(upcoming)
    val latestFirstUpcoming by rememberUpdatedState(firstUpcoming)
    val latestOnMove by rememberUpdatedState(onMove)

    // The player's answer (a new queue, or another song playing) replaces the local order.
    LaunchedEffect(state.queue, state.queueIndex) {
        if (draggedKey == null) localOrder = null
    }

    // A lazy list keeps its scroll position on the KEY of its first visible
    // row. Reordering past that row then scrolls the list on its own: the row
    // being dragged slid out of view, which ended the drag with nothing moved.
    // Each reorder pins the viewport to where it is instead.
    fun holdViewport() {
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    /**
     * Sends one move: the row at [from] of [before] to [to], [base] being the
     * queue index of the first row. The player checks the song is still
     * there and keeps the move among the upcoming songs.
     */
    fun commitMove(before: List<QueueEntry>, from: Int, to: Int, base: Int) {
        if (from == to || from !in before.indices || to !in before.indices) {
            localOrder = null
            return
        }
        val after = before.moved(from, to)
        keys.onMoved(after)
        holdViewport()
        // Two copies of one song swapped: nothing visible changes, and the
        // player's queue won't either, so there is no answer to wait for.
        localOrder = if (after.map { it.song.id } == before.map { it.song.id }) null else after
        latestOnMove(base + from, base + to, before[from].song.id) { moved ->
            if (!moved) localOrder = null
        }
    }

    // Proportional rather than a hard-coded 480dp: that number wasted space on
    // a tall phone and overflowed in landscape.
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.58f).dp

    Column(Modifier.fillMaxWidth()) {
        QueueHeader(upcoming.size, palette)

        if (nowPlaying != null) {
            NowPlayingCard(
                title = nowPlaying.title,
                artist = nowPlaying.artist,
                artworkUri = nowPlaying.artworkUri,
                palette = palette,
            )
        }

        if (upcoming.isEmpty()) {
            QueueEmptyState(palette)
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight),
        ) {
            itemsIndexed(items = upcoming, key = { _, entry -> entry.key }) { rel, entry ->
                val song = entry.song
                val absolute = firstUpcoming + rel
                val isDragging = entry.key == draggedKey
                val liftScale by animateFloatAsState(
                    targetValue = if (isDragging) 1.02f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "queue-lift",
                )
                val liftShadow by animateFloatAsState(
                    targetValue = if (isDragging) 10f else 0f,
                    label = "queue-shadow",
                )

                Column {
                    when {
                        state.playNextCount > 0 && absolute == firstUpcoming ->
                            QueueSectionLabel("Next up", palette, accent = true)
                        state.playNextCount > 0 && absolute == priorityEnd + 1 ->
                            QueueSectionLabel("Then", palette)
                        state.playNextCount == 0 && absolute == firstUpcoming ->
                            QueueSectionLabel("Up next", palette)
                    }

                    QueueRow(
                        title = song.title,
                        artist = song.artist,
                        artworkUri = song.artworkUri,
                        durationMs = song.durationMs,
                        palette = palette,
                        isDragging = isDragging,
                        canMoveUp = rel > 0,
                        canMoveDown = rel < upcoming.lastIndex,
                        onClick = { onPlay(absolute) },
                        onRemove = { onRemove(absolute) },
                        onMoveUp = { commitMove(upcoming, rel, rel - 1, firstUpcoming) },
                        onMoveDown = { commitMove(upcoming, rel, rel + 1, firstUpcoming) },
                        onMeasured = { height -> if (height > 0) rowHeightPx = height.toFloat() },
                        modifier = Modifier
                            // Non-dragged rows slide out of the way; the dragged
                            // one is positioned by its own translation instead.
                            .then(if (isDragging) Modifier else Modifier.animateItem())
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffset else 0f
                                scaleX = liftScale
                                scaleY = liftScale
                                shadowElevation = liftShadow
                            },
                        dragModifier = Modifier.pointerInput(entry.key) {
                            var rowHeight = 0f
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    // This row's place now: the list as shown
                                    // (a move still on its way to the player
                                    // included) and the queue index it starts at.
                                    val order = latestUpcoming
                                    val index = order.indexOfFirst { it.key == entry.key }
                                    if (index >= 0) {
                                        localOrder = order
                                        draggedKey = entry.key
                                        dragFrom = index
                                        dragTo = index
                                        dragBase = latestFirstUpcoming
                                        dragOffset = 0f
                                        rowHeight = rowHeightPx.takeIf { it > 0f } ?: 72.dp.toPx()
                                    }
                                },
                                onDrag = { change, amount ->
                                    val current = localOrder
                                    if (draggedKey == entry.key && current != null) {
                                        change.consume()
                                        dragOffset += amount.y
                                        // Explicitly typed non-null: reassigning
                                        // a var inside the loop invalidates the
                                        // smart cast from the null check above.
                                        var order: List<QueueEntry> = current
                                        // Reorder LOCALLY only. Half a row of
                                        // travel moves one slot, and the offset
                                        // is reduced by that slot so the row
                                        // keeps tracking the finger.
                                        while (dragOffset > rowHeight / 2 && dragTo < order.lastIndex) {
                                            order = order.moved(dragTo, dragTo + 1)
                                            dragTo += 1
                                            dragOffset -= rowHeight
                                        }
                                        while (dragOffset < -rowHeight / 2 && dragTo > 0) {
                                            order = order.moved(dragTo, dragTo - 1)
                                            dragTo -= 1
                                            dragOffset += rowHeight
                                        }
                                        if (order !== current) {
                                            holdViewport()
                                            localOrder = order
                                        }
                                    }
                                },
                                onDragEnd = {
                                    if (draggedKey == entry.key) {
                                        val order = localOrder
                                        val from = dragFrom
                                        val to = dragTo
                                        draggedKey = null
                                        dragFrom = -1
                                        dragTo = -1
                                        dragOffset = 0f
                                        // One move for the whole gesture, from
                                        // the order the drag started on.
                                        if (order != null) commitMove(order.moved(to, from), from, to, dragBase)
                                        else localOrder = null
                                    }
                                },
                                onDragCancel = {
                                    if (draggedKey == entry.key) {
                                        draggedKey = null
                                        dragFrom = -1
                                        dragTo = -1
                                        dragOffset = 0f
                                        localOrder = null
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun QueueHeader(upcomingCount: Int, palette: PlayerPalette) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp)) {
        Text(
            "Queue",
            fontSize = 26.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = palette.ink,
        )
        Text(
            when (upcomingCount) {
                0 -> "Nothing after this"
                1 -> "1 track up next"
                else -> "$upcomingCount tracks up next"
            },
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = palette.muted,
        )
    }
}

/**
 * The current track, lifted out of the list.
 *
 * It is the one row you never reorder or remove, so giving it the same
 * affordances as the rest was misleading — it had a drag handle that could not
 * legally move anywhere and a disabled remove button.
 */
@Composable
private fun NowPlayingCard(
    title: String,
    artist: String,
    artworkUri: String?,
    palette: PlayerPalette,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.control)
            .padding(12.dp)
            .semantics { contentDescription = "Now playing: $title by $artist" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artworkUri, null, Modifier.size(52.dp), cornerRadius = 10.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    "NOW PLAYING",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = palette.accent,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
            Text(
                title,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 1.dp),
            )
            Text(
                artist,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 1.dp),
            )
        }
    }
}

@Composable
private fun QueueSectionLabel(
    label: String,
    palette: PlayerPalette,
    accent: Boolean = false,
) {
    Text(
        label.uppercase(),
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        color = if (accent) palette.accent else palette.muted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

/**
 * One upcoming track.
 *
 * Flatter and denser than Harmony's browse rows on purpose: in a queue you are
 * reading order and position, not shopping for a cover.
 */
@Composable
private fun QueueRow(
    title: String,
    artist: String,
    artworkUri: String?,
    durationMs: Long,
    palette: PlayerPalette,
    isDragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasured(it.height) }
            .background(
                if (isDragging) palette.control
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            // This was missing: onClick was accepted as a parameter and
            // wired correctly at the call site, but nothing in this row
            // ever attached it to a gesture — so tapping a queued song did
            // nothing. The drag handle's own long-press-and-drag detector
            // lives on its own small Box below and only reacts to a long
            // press, so a plain tap here reaches this instead without the
            // two competing.
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$title by $artist"
                // Reordering by drag is impossible with a screen reader, so
                // the same two moves are exposed as explicit actions.
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction("Move up") { onMoveUp(); true })
                    if (canMoveDown) add(CustomAccessibilityAction("Move down") { onMoveDown(); true })
                    add(CustomAccessibilityAction("Remove from queue") { onRemove(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 48dp target around a 22dp glyph: the old handle was a bare icon and
        // fell well short of the minimum touch size.
        Box(
            Modifier
                .size(48.dp)
                .then(dragModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "Reorder $title",
                tint = palette.muted,
                modifier = Modifier.size(22.dp),
            )
        }
        Artwork(artworkUri, null, Modifier.size(42.dp), cornerRadius = 8.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                title,
                fontSize = 14.5.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 1.dp),
            )
            Text(
                "$artist · ${formatDuration(durationMs)}",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 1.dp),
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove $title from queue",
                tint = palette.muted,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun QueueEmptyState(palette: PlayerPalette) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = palette.muted,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Nothing up next",
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink,
        )
        Text(
            "Turn on Smart Shuffle, or add songs with Play next.",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = palette.muted,
        )
    }
}
