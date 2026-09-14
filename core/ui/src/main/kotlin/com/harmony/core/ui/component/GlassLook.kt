package com.harmony.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.Song
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Shared glass surface maths
// ---------------------------------------------------------------------------

/** Fully-rounded pill. A percentage keeps it a true capsule at any height. */
private val Capsule = RoundedCornerShape(percent = 50)

/**
 * The frosted fill used by every glass surface in the app: the section's own
 * field colour pulled toward white and held below full opacity.
 *
 * Deriving it from the palette rather than hardcoding a grey is what keeps
 * the look coherent across sections — amber Library and blue EQ each get
 * their own tint of frost instead of one neutral slab dropped on top of five
 * differently-coloured screens.
 */
/**
 * The frosted fill used by every glass surface in the app: the section's
 * own field colour lifted toward the light and held below full opacity.
 *
 * Deriving it from the palette rather than hardcoding a grey is what keeps
 * the look coherent across sections — amber Library and blue EQ each get
 * their own tint of frost instead of one neutral slab dropped on top of
 * five differently-coloured screens.
 *
 * The dark-mode branch is not a nicety, it's a correctness fix. Lifting
 * toward white unconditionally is right on a near-white field and wrong on
 * a near-black one: on #15121F a strength of 0.6 produces a light grey
 * pane, while palette.ink stays #F1EEFF, i.e. near-white text on a
 * near-white card. Dark surfaces have to move a fraction of that distance
 * so the pane separates from the page without ever closing on the ink.
 *
 * Alpha is high on purpose. This is glass you read paragraphs of text
 * through, not a decorative overlay; every point of transparency spent
 * showing the list underneath is contrast taken away from the label on
 * top, and the label is what the card is for.
 */
fun glassFill(palette: EditorialPalette, strength: Float = 0.62f): Color {
    val onDarkField = palette.field.luminance() < 0.5f
    return if (onDarkField) {
        lerp(palette.field, Color.White, strength * 0.16f).copy(alpha = 0.96f)
    } else {
        lerp(palette.field, Color.White, strength).copy(alpha = 0.94f)
    }
}

/**
 * The bright rim that reads as the lit edge of a pane of glass.
 *
 * Weaker on dark surfaces: the same white that reads as a soft catch of
 * light on a near-white pane reads as a hard drawn outline on a near-black
 * one, because the contrast against the fill is several times greater.
 */
fun glassRim(palette: EditorialPalette): Color =
    if (palette.field.luminance() < 0.5f) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color.White.copy(alpha = 0.55f)
    }

// ---------------------------------------------------------------------------
// Search bar
// ---------------------------------------------------------------------------

/**
 * The header row: a full-width glass search pill with an optional trailing
 * circular action button.
 *
 * This replaces the big "Library" wordmark plus circle buttons. The title
 * goes because on a tabbed screen it was saying something the tab row and
 * the nav bar both already said, and it cost 44dp of vertical space on every
 * scroll. The search field earns that space instead: it is the control you
 * actually reach for in a library of 1200+ songs.
 *
 * [onFieldClick] is for the collapsed state — tapping anywhere on the pill
 * activates search. When [active] is true the pill hosts a real text field
 * and [value] / [onValueChange] drive it.
 */
@Composable
fun GlassSearchBar(
    active: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onFieldClick: () -> Unit,
    onClear: () -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    placeholder: String = "Search songs, artists, albums",
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(Capsule)
                .background(glassFill(palette))
                .border(1.dp, glassRim(palette), Capsule)
                .then(
                    if (active) Modifier
                    else Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onFieldClick,
                    ),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (active) {
                // The pill switches from a static row to a real text field on
                // the same composition pass that sets active = true — the
                // field exists now, but nothing has focused it yet, so the
                // keyboard stays down until something asks for focus
                // explicitly. Without this, the first tap only swaps the
                // content and a second tap is what actually raises the
                // keyboard.
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                // BasicTextField, not Material3's TextField: TextField
                // enforces its own 56dp minimum height no matter what
                // container it sits in, but this pill is 44dp tall with a
                // clip on it — anything the field drew past 44dp was being
                // sliced off by that clip, which is what showed up as the
                // text "cutting itself" as you typed. BasicTextField has no
                // imposed minimum; it sizes to exactly what's given here,
                // the same way this control is actually built on iOS.
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    ) {
                        if (value.isEmpty()) {
                            Text(placeholder, color = palette.muted, fontSize = 15.sp, maxLines = 1)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            singleLine = true,
                            textStyle = TextStyle(color = palette.ink, fontSize = 15.sp),
                            cursorBrush = SolidColor(palette.ink),
                        )
                    }
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Clear search",
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        placeholder,
                        color = palette.muted,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

/** Circular glass button sized to sit beside [GlassSearchBar]. */
@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    filled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val container = if (filled) palette.ink.copy(alpha = 0.14f) else glassFill(palette)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .border(1.dp, glassRim(palette), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides palette.ink,
            ) { content() }
        }
    }
    // contentDescription is applied by the caller's semantics when needed;
    // kept in the signature so this stays swap-compatible with
    // EditorialCircleButton.
    @Suppress("UNUSED_EXPRESSION")
    contentDescription
}

// ---------------------------------------------------------------------------
// Segmented tabs
// ---------------------------------------------------------------------------

/**
 * The Songs / Albums / Artists control, as a row of separate pills.
 *
 * Drop-in replacement for [EditorialTabs] — same parameters, so switching is
 * a one-word change at the call site.
 *
 * [EditorialTabs] signalled selection with type: the chosen tab went Bold at
 * 21sp while its neighbours sat at Medium. That reflows the row's widths on
 * every tab change, so the labels shuffle sideways as you switch. Here each
 * segment is a fixed-width chip and the selection is carried entirely by
 * fill and ink colour, so nothing reflows.
 *
 * Unlike a true iOS segmented control there is no single track with one
 * capsule sliding inside it. Each pill owns its own background, which is
 * what lets the selected chip go dark and solid while the rest stay as
 * light lavender chips — a stronger, more legible split than a tinted
 * capsule on a tinted track, especially against the near-white field.
 * The trade is that the selection can't slide between positions; it
 * cross-fades in place instead.
 *
 * A tab with no [EditorialTab.count] is drawn one tier quieter, on the
 * assumption that an empty section is worth de-emphasising rather than
 * presenting as an equal peer.
 */
@Composable
fun GlassSegmentedTabs(
    tabs: List<EditorialTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val safeIndex = selected.coerceIn(0, tabs.lastIndex)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == safeIndex
            // Three tiers, not two: selected, present, and empty. The empty
            // tier only applies when the tab is not the current one, since a
            // section you are actively looking at should never be the
            // quietest thing in the row.
            val isQuiet = !isSelected && tab.count == null

            val container by animateColorAsState(
                targetValue = when {
                    isSelected -> SegmentSelectedField
                    isQuiet -> SegmentFieldMuted
                    else -> SegmentField
                },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "segment-container-$index",
            )
            val content by animateColorAsState(
                targetValue = when {
                    isSelected -> SegmentSelectedInk
                    isQuiet -> SegmentInkMuted
                    else -> SegmentInk
                },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "segment-content-$index",
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(Capsule)
                    .background(container)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tab.icon?.let { icon ->
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier
                            .padding(end = 7.dp)
                            .size(17.dp),
                    )
                }
                Text(
                    tab.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Song row
// ---------------------------------------------------------------------------

/**
 * A song row in the glass style. Drop-in replacement for
 * [EditorialSongCard] — identical signature, including the overflow-menu
 * callbacks and [dragHandle] — plus one addition: [onSwipeToQueue].
 *
 * Three changes from the editorial card, all aimed at fitting more of the
 * library on screen without making it feel cramped:
 *
 * The card border goes. [EditorialSongCard] wraps each row in an outlined
 * [EditorialCard], which at list scale draws a boxed grid — 1200 outlines
 * stacked vertically. Here the rows are open and a hairline divider does
 * the separating, which is what iOS list rows do.
 *
 * Artwork drops 56dp to 48dp and loses its border ring, so the thumbnail
 * reads as album art rather than as a framed picture.
 *
 * Duration moves to the right edge on its own. In the editorial card it was
 * bolted onto the artist line behind a bullet, which meant a long artist
 * name pushed the runtime off the end of the row. Right-aligned, it forms a
 * clean column down the list and stays readable at any title length.
 *
 * The open row also brings back swipe-right-to-queue, in the style of
 * [SongRow]: dragging the row reveals a "Play next" affordance behind it
 * and commits past a threshold, then springs back — the row itself is
 * never dismissed. That gesture didn't carry over to [EditorialSongCard]
 * because sliding a bordered card sideways broke the printed-poster
 * illusion the outline created; there's no such outline here, so the
 * gesture is back where it was in the original [SongRow].
 *
 * [onSwipeToQueue] is opt-in per call site, same as in [SongRow]: rows
 * inside the queue sheet or a reorderable playlist already own
 * horizontal/drag semantics of their own, and stacking this on top there
 * would fight them.
 */
@Composable
fun GlassSongCard(
    song: Song,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayNext: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove",
    dragHandle: (@Composable () -> Unit)? = null,
    onSwipeToQueue: (() -> Unit)? = null,
) {
    if (onSwipeToQueue == null) {
        GlassSongRowContent(
            song = song,
            palette = palette,
            onClick = onClick,
            modifier = modifier,
            onPlayNext = onPlayNext,
            onAddToPlaylist = onAddToPlaylist,
            onRemove = onRemove,
            removeLabel = removeLabel,
            dragHandle = dragHandle,
        )
        return
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offsetX = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(1) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = it.width },
    ) {
        // Behind-the-row affordance, revealed as the row slides right. Tinted
        // from the section palette rather than a fixed accent, same as every
        // other glass surface, so it belongs to whichever screen it's on.
        val progress = (offsetX.value / (rowWidth * SwipeQueueFraction)).coerceIn(0f, 1f)
        if (offsetX.value > 1f) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(glassFill(palette, strength = 0.5f))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(
                    Icons.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = palette.ink,
                    modifier = Modifier.graphicsLayer {
                        // Icon settles in as the threshold approaches, so the
                        // commit point is legible before you let go.
                        scaleX = 0.7f + 0.3f * progress
                        scaleY = 0.7f + 0.3f * progress
                        alpha = progress
                    }.size(20.dp),
                )
                Text(
                    "Play next",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.ink,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .graphicsLayer { alpha = progress },
                )
            }
        }

        Box(
            modifier = Modifier
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(song.id) {
                    var total = 0f
                    var committed = false
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f; committed = false },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            // Right-only: a leftward drag stays at rest so the
                            // gesture can't be triggered by accident in the
                            // opposite direction.
                            total = (total + amount).coerceAtLeast(0f)
                            scope.launch { offsetX.snapTo(total) }
                            if (!committed && total >= rowWidth * SwipeQueueFraction) {
                                committed = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDragEnd = {
                            val shouldQueue = total >= rowWidth * SwipeQueueFraction
                            scope.launch {
                                if (shouldQueue) {
                                    onSwipeToQueue()
                                    // Brief settle at the threshold so the
                                    // confirmation is visible, then return.
                                    offsetX.animateTo(rowWidth * SwipeQueueFraction, tween(90))
                                }
                                offsetX.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                            }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f) }
                        },
                    )
                },
        ) {
            GlassSongRowContent(
                song = song,
                palette = palette,
                onClick = onClick,
                modifier = Modifier,
                onPlayNext = onPlayNext,
                onAddToPlaylist = onAddToPlaylist,
                onRemove = onRemove,
                removeLabel = removeLabel,
                dragHandle = dragHandle,
            )
        }
    }
}

/** Fraction of the row width a swipe must cross to commit "add to queue". */
private const val SwipeQueueFraction = 0.32f

@Composable
private fun GlassSongRowContent(
    song: Song,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier,
    onPlayNext: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    removeLabel: String,
    dragHandle: (@Composable () -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onPlayNext != null || onAddToPlaylist != null || onRemove != null

    Column(modifier.fillMaxWidth().background(palette.field)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragHandle != null) {
                dragHandle()
                Spacer(Modifier.width(8.dp))
            }
            Artwork(
                artworkUri = song.artworkUri,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                cornerRadius = 12.dp,
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    song.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    fontSize = 12.sp,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Text(
                formatDuration(song.durationMs),
                fontSize = 12.sp,
                color = palette.muted,
                maxLines = 1,
            )
            if (hasMenu) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "More options for ${song.title}",
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        onPlayNext?.let { action ->
                            DropdownMenuItem(
                                text = { Text("Play next") },
                                onClick = { menuOpen = false; action() },
                            )
                        }
                        onAddToPlaylist?.let { action ->
                            DropdownMenuItem(
                                text = { Text("Add to playlist") },
                                onClick = { menuOpen = false; action() },
                            )
                        }
                        onRemove?.let { action ->
                            DropdownMenuItem(
                                text = { Text(removeLabel) },
                                onClick = { menuOpen = false; action() },
                            )
                        }
                    }
                }
            }
        }
        // Inset to start at the text, not the screen edge: the divider marks
        // where one row's content ends, and running it under the artwork
        // would cut the thumbnails into a ladder.
        Box(
            Modifier
                .padding(start = 76.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(palette.line.copy(alpha = 0.15f)),
        )
    }
}
