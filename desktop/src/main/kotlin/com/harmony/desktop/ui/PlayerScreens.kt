package com.harmony.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.desktop.DesktopApp
import com.harmony.desktop.engine.EngineStatus
import com.harmony.desktop.player.NowPlaying
import com.harmony.desktop.player.Repeat

private fun EngineStatus.isPlaying() = this == EngineStatus.PLAYING || this == EngineStatus.LOADING

/** The player along the bottom of the window. */
@Composable
fun PlayerBar(app: DesktopApp, loader: ImageLoader, onOpenNowPlaying: () -> Unit) {
    val c = LocalHarmonyColors.current
    val np by app.player.nowPlaying.collectAsState()
    val engine by app.player.engineState.collectAsState()
    val shuffle by app.player.shuffle.collectAsState()
    val repeat by app.player.repeat.collectAsState()
    val settings by app.settingsFlow.collectAsState()
    val playing = engine.status.isPlaying()
    val position = rememberPosition(playing) { app.player.positionMs() }
    val song = np

    Row(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .background(c.surface)
            .drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }
            .padding(horizontal = 20.dp)
            .testTag("player_bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The song.
        Row(Modifier.width(320.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onOpenNowPlaying),
            ) {
                Disc(song?.art, loader, playing, 72.dp, key = song?.key)
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                AnimatedContent(
                    targetState = song,
                    transitionSpec = { (slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(260))).togetherWith(fadeOut(tween(140))) },
                    contentKey = { it?.key },
                ) { s ->
                    Column {
                        Text(s?.title ?: "Nothing playing", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s?.artist ?: "Pick a song, or play one from your phone", fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (song?.fromPhone != null) {
                    Pill("FROM ${song.fromPhone.uppercase()}", Accent.PurpleLight, Modifier.padding(top = 6.dp), icon = Icons.Rounded.Cast)
                }
            }
        }
        // Controls and the wave.
        Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Rounded.Shuffle, "Shuffle", app.player::toggleShuffle, size = 36.dp, active = shuffle)
                RoundButton(Icons.Rounded.SkipPrevious, "Previous", app.player::previous, size = 40.dp)
                PlayButton(playing, app.player::togglePlay, size = 46.dp)
                RoundButton(Icons.Rounded.SkipNext, "Next", app.player::next, size = 40.dp)
                RoundButton(
                    if (repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat", app.player::cycleRepeat, size = 36.dp, active = repeat != Repeat.OFF,
                )
            }
            Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PositionText(position, 11.sp, c.muted, Modifier.width(44.dp))
                WaveProgress(position, song?.durationMs ?: 0, playing, c.line, app.player::seek, Modifier.weight(1f).height(22.dp))
                Text(formatTime(song?.durationMs ?: 0), fontSize = 11.sp, color = c.muted, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
        }
        // Volume and quality.
        Column(Modifier.width(220.dp), horizontalAlignment = Alignment.End) {
            if (song?.quality != null) Pill(song.quality, Accent.Cyan)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Rounded.VolumeDown, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                var vol by remember { mutableFloatStateOf(settings.volume) }
                HSlider(vol, { vol = it; app.setVolume(it) }, Modifier.width(140.dp).height(24.dp).padding(horizontal = 4.dp))
                Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
            }
            if (engine.status == EngineStatus.ERROR && engine.error != null) {
                Text(engine.error!!, fontSize = 11.sp, color = Accent.Pink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** The song, big: the record, everything about it, the controls and what's next. */
@Composable
fun NowPlayingPage(app: DesktopApp, loader: ImageLoader) {
    val c = LocalHarmonyColors.current
    val np by app.player.nowPlaying.collectAsState()
    val engine by app.player.engineState.collectAsState()
    val shuffle by app.player.shuffle.collectAsState()
    val repeat by app.player.repeat.collectAsState()
    val playing = engine.status.isPlaying()
    val position = rememberPosition(playing) { app.player.positionMs() }
    val song = np
    if (song == null) {
        EmptyState("Nothing is playing", "Pick something from your library, or open Harmony on your phone and play it here through Connect.")
        return
    }
    Row(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(Accent.Purple.copy(alpha = if (c.dark) 0.28f else 0.16f), Color.Transparent), Offset(size.width * 0.25f, size.height * 0.45f), size.minDimension * 0.7f), radius = size.minDimension * 0.7f, center = Offset(size.width * 0.25f, size.height * 0.45f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFF39B9D3).copy(alpha = if (c.dark) 0.18f else 0.10f), Color.Transparent), Offset(size.width * 0.8f, size.height * 0.2f), size.minDimension * 0.6f), radius = size.minDimension * 0.6f, center = Offset(size.width * 0.8f, size.height * 0.2f))
            }
            .padding(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Disc(song.art, loader, playing, 380.dp, key = song.key)
        }
        Column(Modifier.weight(1.1f).padding(start = 40.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (song.fromPhone != null) Pill("PLAYING FROM ${song.fromPhone.uppercase()}", Accent.PurpleLight, icon = Icons.Rounded.Cast)
                if (song.quality != null) Pill(song.quality, Accent.Cyan)
            }
            AnimatedContent(
                targetState = song,
                contentKey = { it.key },
                transitionSpec = { (slideInHorizontally(tween(300)) { it / 5 } + fadeIn(tween(300))).togetherWith(fadeOut(tween(150))) },
            ) { s ->
                Column(Modifier.padding(top = 16.dp)) {
                    Text(s.title, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, fontSize = 20.sp, color = c.ink.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                    Text(s.album, fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                PositionText(position, 12.sp, c.muted, Modifier.width(52.dp))
                WaveProgress(position, song.durationMs, playing, c.line, app.player::seek, Modifier.weight(1f).height(26.dp))
                Text(formatTime(song.durationMs), fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(start = 10.dp))
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Rounded.Shuffle, "Shuffle", app.player::toggleShuffle, size = 46.dp, active = shuffle)
                RoundButton(Icons.Rounded.SkipPrevious, "Previous", app.player::previous, size = 52.dp)
                PlayButton(playing, app.player::togglePlay, size = 64.dp)
                RoundButton(Icons.Rounded.SkipNext, "Next", app.player::next, size = 52.dp)
                RoundButton(if (repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", app.player::cycleRepeat, size = 46.dp, active = repeat != Repeat.OFF)
            }
            UpNextList(song, loader, onPlay = { it?.let(app.player::playQueueItem) }, modifier = Modifier.padding(top = 28.dp))
        }
    }
}

@Composable
private fun UpNextList(song: NowPlaying, loader: ImageLoader, onPlay: (Int?) -> Unit, modifier: Modifier) {
    val c = LocalHarmonyColors.current
    if (song.upNext.isEmpty()) return
    Column(modifier) {
        Text("UP NEXT", fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold, color = c.muted)
        LazyColumn(Modifier.padding(top = 8.dp).height(220.dp)) {
            itemsIndexed(song.upNext) { i, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(enabled = item.queueIndex != null, role = Role.Button) { onPlay(item.queueIndex) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", fontSize = 12.sp, color = c.muted, modifier = Modifier.width(22.dp))
                    ArtImage(item.art, loader, CircleShape, Modifier.size(34.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artist, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    val c = LocalHarmonyColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 460.dp).padding(24.dp)) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent.Purple, Color(0xFF39B9D3)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Cast, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(top = 18.dp))
            Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(18.dp))
                action()
            }
        }
    }
}
