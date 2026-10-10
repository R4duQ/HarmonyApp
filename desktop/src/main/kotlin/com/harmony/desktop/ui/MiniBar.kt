package com.harmony.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.desktop.DesktopApp
import com.harmony.desktop.engine.EngineStatus
import com.harmony.desktop.player.NowPlaying
import com.harmony.desktop.player.Repeat

/** The bar is dark in both themes, like a hi-fi's display strip. */
private val BarColor = Color(0xFF1F2026)
private val BarInk = Color.White
private val BarMuted = Color(0xFF9A9CA8)
private val BarTrack = Color(0xFF3A3C46)

/**
 * The player along the bottom of the window: the song on the left, the
 * transport in the middle, what's next and the volume on the right, and how
 * far into the song along the bottom edge.
 */
@Composable
fun PlayerBar(app: DesktopApp, loader: ImageLoader, onOpenNowPlaying: () -> Unit) {
    val np by app.player.nowPlaying.collectAsState()
    val engine by app.player.engineState.collectAsState()
    val shuffle by app.player.shuffle.collectAsState()
    val repeat by app.player.repeat.collectAsState()
    val settings by app.settingsFlow.collectAsState()
    val muted by app.muted.collectAsState()
    val playing = engine.status == EngineStatus.PLAYING || engine.status == EngineStatus.LOADING
    val position = rememberPosition(playing) { app.player.positionMs() }
    val song = np
    var queueOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(BarColor).testTag("player_bar")) {
        Box(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp)) {
            // The song.
            Row(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.34f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button, onClick = onOpenNowPlaying)
                        .semantics { contentDescription = "Open Now Playing" },
                ) {
                    ArtImage(song?.art, loader, RoundedCornerShape(6.dp), Modifier.fillMaxSize())
                }
                AnimatedContent(
                    targetState = song,
                    transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(120))) },
                    contentKey = { it?.key },
                    modifier = Modifier.weight(1f, fill = false).padding(start = 14.dp),
                ) { s ->
                    Column(Modifier.clickable(enabled = s != null, onClick = onOpenNowPlaying)) {
                        Text(
                            s?.title ?: "Nothing playing", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = BarInk,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val error = engine.error.takeIf { engine.status == EngineStatus.ERROR }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                            if (error == null && s?.fromPhone != null) {
                                Icon(Icons.Rounded.Cast, contentDescription = null, tint = Accent.PurpleLight, modifier = Modifier.size(13.dp))
                                Text(s.fromPhone, fontSize = 12.sp, color = Accent.PurpleLight, maxLines = 1, modifier = Modifier.padding(start = 4.dp, end = 6.dp))
                            }
                            Text(
                                error ?: s?.let { listOf(it.artist, it.album).filter(String::isNotBlank).joinToString(" • ") }
                                    ?: "Pick a song, or play one from your phone",
                                fontSize = 12.sp, color = if (error != null) Accent.Pink else BarMuted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (song != null && song.fromPhone == null) {
                    val fav = song.key in settings.favorites
                    BarIcon(
                        if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (fav) "Remove from favourites" else "Add to favourites",
                        { app.toggleFavorite(song.key) }, tint = if (fav) Accent.Pink else BarInk,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            // The transport.
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(Icons.Rounded.Shuffle, "Shuffle", app.player::toggleShuffle, tint = if (shuffle) Accent.PurpleLight else BarMuted)
                BarIcon(Icons.Rounded.SkipPrevious, "Previous", app.player::previous, size = 26.dp)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(2.dp, BarInk, CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button, onClick = app.player::togglePlay)
                        .semantics { contentDescription = if (playing) "Pause" else "Play" }
                        .testTag("bar_play"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = BarInk, modifier = Modifier.size(26.dp))
                }
                BarIcon(Icons.Rounded.SkipNext, "Next", app.player::next, size = 26.dp)
                BarIcon(
                    if (repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", app.player::cycleRepeat,
                    tint = if (repeat != Repeat.OFF) Accent.PurpleLight else BarMuted,
                )
            }

            // Time, what's next, volume.
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                if (song != null) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 18.dp)) {
                        Row {
                            PositionText(position, 12.sp, BarInk)
                            Text(" / " + formatTime(song.durationMs), fontSize = 12.sp, color = BarMuted)
                        }
                        if (song.quality != null) {
                            Text(song.quality, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = Accent.Cyan, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
                Box {
                    BarIcon(Icons.Rounded.QueueMusic, "What's next", { queueOpen = true }, enabled = song != null)
                    if (song != null) UpNextMenu(song, loader, queueOpen, { queueOpen = false }, onPlay = app.player::playQueueItem)
                }
                BarIcon(
                    if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeDown, if (muted) "Unmute" else "Mute", app::toggleMute,
                    tint = if (muted) Accent.Pink else BarMuted, modifier = Modifier.padding(start = 14.dp),
                )
                BarSlider(if (muted) 0f else settings.volume, { app.setVolume((it * 100).toInt() / 100f) }, Modifier.width(120.dp).height(24.dp).padding(horizontal = 6.dp))
                Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = BarMuted, modifier = Modifier.size(18.dp))
            }
        }
        BarProgress(position, song?.durationMs ?: 0, app.player::seek)
    }
}

/** A plain icon button, as on the design: no plate, just the glyph. */
@Composable
private fun BarIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = BarInk,
    size: Dp = 20.dp,
    enabled: Boolean = true,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        modifier
            .size(size + 14.dp)
            .clip(CircleShape)
            .background(if (hovered && enabled) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(hover)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size))
    }
}

/** A slim white-on-grey line with a knob that shows on hover. */
@Composable
private fun BarSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val change by rememberUpdatedState(onChange)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var width by remember { mutableStateOf(1f) }
    fun at(x: Float) = (x / width).coerceIn(0f, 1f)
    Canvas(
        modifier
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) { detectTapGestures { change(at(it.x)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> ch.consume(); change(at(ch.position.x)) } }
            .semantics { contentDescription = "Volume" }
            .testTag("bar_volume"),
    ) {
        width = size.width
        val mid = size.height / 2
        val x = size.width * value.coerceIn(0f, 1f)
        val stroke = 3.dp.toPx()
        drawLine(BarTrack, Offset(0f, mid), Offset(size.width, mid), strokeWidth = stroke, cap = StrokeCap.Round)
        if (x > 0f) drawLine(BarInk, Offset(0f, mid), Offset(x, mid), strokeWidth = stroke, cap = StrokeCap.Round)
        if (hovered) drawCircle(BarInk, radius = 6.dp.toPx(), center = Offset(x, mid))
    }
}

/**
 * How far into the song, as a thin line along the bottom edge; it thickens
 * under the mouse, and a click or a drag moves the song there.
 */
@Composable
private fun BarProgress(position: State<Long>, durationMs: Long, onSeek: (Long) -> Unit) {
    val seek by rememberUpdatedState(onSeek)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val thickness by animateDpAsState(if (hovered || dragging != null) 6.dp else 3.dp, tween(140))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .hoverable(hover)
            .pointerHoverIcon(if (durationMs > 0) PointerIcon.Hand else PointerIcon.Default)
            .pointerInput(durationMs) { detectTapGestures { o -> if (durationMs > 0) seek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) } }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { ch, _ -> ch.consume(); dragging = (ch.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let { if (durationMs > 0) seek((it * durationMs).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null },
                )
            }
            .semantics { contentDescription = "Song position" }
            .testTag("bar_progress"),
    ) {
        val t = dragging ?: if (durationMs > 0) (position.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val h = thickness.toPx()
        val top = size.height - h
        drawRect(BarTrack.copy(alpha = 0.6f), Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, h))
        val x = size.width * t
        if (x > 0f) {
            drawRect(Brush.horizontalGradient(listOf(Accent.Purple, Accent.Pink), endX = x.coerceAtLeast(1f)), Offset(0f, top), androidx.compose.ui.geometry.Size(x, h))
        }
    }
}

/** The songs after this one, as a menu; picking one plays it (computer's own queue only). */
@Composable
fun UpNextMenu(song: NowPlaying, loader: ImageLoader, expanded: Boolean, onDismiss: () -> Unit, onPlay: (Int) -> Unit) {
    val c = LocalHarmonyColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.widthIn(min = 280.dp)) {
        Text("UP NEXT", fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (song.upNext.isEmpty()) {
            DropdownMenuItem(text = { Text("Nothing after this song") }, onClick = onDismiss, enabled = false)
        }
        song.upNext.forEach { item ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artist, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                leadingIcon = { ArtImage(item.art, loader, RoundedCornerShape(8.dp), Modifier.size(36.dp)) },
                enabled = item.queueIndex != null,
                onClick = { onDismiss(); item.queueIndex?.let(onPlay) },
            )
        }
    }
}
