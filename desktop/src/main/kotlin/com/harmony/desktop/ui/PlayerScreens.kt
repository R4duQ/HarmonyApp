package com.harmony.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.FontFamily
import com.harmony.desktop.engine.ToneDesign
import kotlin.math.roundToInt
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
            if (song != null && song.fromPhone == null) {
                val fav = song.key in settings.favorites
                RoundButton(
                    if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (fav) "Remove from favourites" else "Add to favourites",
                    { app.toggleFavorite(song.key) }, size = 34.dp, active = fav,
                )
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

/**
 * The song, as a hi-fi: a soft panel with a volume knob and a bass knob either
 * side of the song's waveform and the transport, the progress groove along the
 * bottom between Mute and EQ.
 */
@Composable
fun NowPlayingPage(app: DesktopApp, loader: ImageLoader, onOpenEqualizer: () -> Unit = {}) {
    val c = LocalHarmonyColors.current
    val np by app.player.nowPlaying.collectAsState()
    val engine by app.player.engineState.collectAsState()
    val shuffle by app.player.shuffle.collectAsState()
    val repeat by app.player.repeat.collectAsState()
    val settings by app.settingsFlow.collectAsState()
    val muted by app.muted.collectAsState()
    val playing = engine.status.isPlaying()
    val position = rememberPosition(playing) { app.player.positionMs() }
    val song = np
    if (song == null) {
        EmptyState("Nothing is playing", "Pick something from your library, or open Harmony on your phone and play it here through Connect.")
        return
    }
    val source = engine.source
    val bars by produceState(source?.let { com.harmony.desktop.engine.Waveforms.cached(it) }, source) {
        value = source?.let { com.harmony.desktop.engine.Waveforms.cached(it) ?: com.harmony.desktop.engine.Waveforms.of(it) }
    }
    val eq = settings.eq
    val local = song.fromPhone == null
    var queueOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(c.neu).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 1180.dp)
                .fillMaxWidth()
                .neumorphic(44.dp, elevation = 22.dp)
                .padding(horizontal = 40.dp, vertical = 30.dp)
                .testTag("now_playing"),
        ) {
            // Top: stop, where the music comes from, and the toggles.
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeuIconButton(Icons.Rounded.PowerSettingsNew, "Stop", app.player::stop, size = 50.dp)
                Box(Modifier.padding(start = 16.dp)) {
                    Row(
                        Modifier
                            .neumorphic(28.dp, elevation = 8.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(role = Role.Button) { queueOpen = true }
                            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
                            .widthIn(min = 240.dp, max = 360.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtImage(song.art, loader, CircleShape, Modifier.size(34.dp))
                        Text(
                            (song.fromPhone?.let { "FROM ${it.uppercase()}" } ?: song.album.uppercase()).ifBlank { "NOW PLAYING" },
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = c.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "What's next", tint = c.ink.copy(alpha = 0.7f), modifier = Modifier.padding(start = 10.dp).size(22.dp))
                    }
                    DropdownMenu(expanded = queueOpen, onDismissRequest = { queueOpen = false }) {
                        Text("UP NEXT", fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        if (song.upNext.isEmpty()) {
                            DropdownMenuItem(text = { Text("Nothing after this song") }, onClick = { queueOpen = false }, enabled = false)
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
                                onClick = { queueOpen = false; item.queueIndex?.let(app.player::playQueueItem) },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val fav = local && song.key in settings.favorites
                    NeuIconButton(
                        if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (fav) "Remove from favourites" else "Add to favourites",
                        { app.toggleFavorite(song.key) }, size = 50.dp, active = fav, enabled = local,
                    )
                    NeuIconButton(Icons.Rounded.Shuffle, "Shuffle", app.player::toggleShuffle, size = 50.dp, active = shuffle)
                    NeuIconButton(if (repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", app.player::cycleRepeat, size = 50.dp, active = repeat != Repeat.OFF)
                    NeuIconButton(Icons.Rounded.QueueMusic, "What's next", { queueOpen = true }, size = 50.dp)
                }
            }

            // Middle: volume, the song, bass.
            Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Knob(
                    value = settings.volume,
                    onChange = { app.setVolume((it * 100).roundToInt() / 100f) },
                    icon = Icons.Rounded.MusicNote, label = "Volume",
                    readout = if (muted) "Muted" else percent(settings.volume),
                    modifier = Modifier.testTag("knob_volume"),
                )
                Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(
                        targetState = song,
                        contentKey = { it.key },
                        transitionSpec = { (fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 6 }).togetherWith(fadeOut(tween(150))) },
                    ) { s ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                s.artist, fontSize = 13.sp, letterSpacing = 4.sp, fontFamily = FontFamily.Monospace, color = c.ink.copy(alpha = 0.75f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    if (song.quality != null) Pill(song.quality, Accent.Purple, Modifier.padding(top = 10.dp))
                    WaveformBars(bars, position, song.durationMs, app.player::seek, Modifier.padding(top = 22.dp).widthIn(max = 420.dp).fillMaxWidth().height(76.dp))
                    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        NeuTransport(Icons.Rounded.ChevronLeft, "Previous", app.player::previous, 54.dp)
                        NeuPlay(playing, app.player::togglePlay)
                        NeuTransport(Icons.Rounded.ChevronRight, "Next", app.player::next, 54.dp)
                    }
                }
                val bass = if (eq.enabled) (eq.bassDb / ToneDesign.MAX_DB).coerceIn(0f, 1f) else 0f
                Knob(
                    value = bass,
                    onChange = { v -> app.setEq(eq.copy(enabled = true, bassDb = ((v * ToneDesign.MAX_DB) * 2).roundToInt() / 2f)) },
                    icon = Icons.Rounded.Headphones, label = "Bass", readout = percent(bass),
                    modifier = Modifier.testTag("knob_bass"),
                )
            }

            // Bottom: mute, the progress groove, EQ.
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeuPill(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (muted) "MUTED" else "MUTE", app::toggleMute, active = muted, modifier = Modifier.testTag("mute"))
                Column(Modifier.weight(1f).padding(horizontal = 48.dp)) {
                    NeuSlider(position, song.durationMs, app.player::seek, Modifier.fillMaxWidth().height(34.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        PositionText(position, 13.sp, c.ink.copy(alpha = 0.8f))
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(song.durationMs), fontSize = 13.sp, color = c.ink.copy(alpha = 0.8f))
                    }
                }
                NeuPill(Icons.Rounded.Tune, "EQ", onOpenEqualizer, active = eq.enabled)
            }
        }
    }
}

/** A round soft transport button with a purple glyph. */
@Composable
private fun NeuTransport(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .neumorphic(size / 2, elevation = 10.dp, circle = true)
            .clip(CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.62f).clip(CircleShape).background(Brush.linearGradient(listOf(Accent.PurpleLight, Accent.Purple))), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.42f))
        }
    }
}

/** The big play / pause: a purple disc in a soft ring. */
@Composable
private fun NeuPlay(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(96.dp)
            .neumorphic(48.dp, elevation = 16.dp, circle = true)
            .clip(CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClickLabel = if (playing) "Pause" else "Play", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent.PurpleLight, Accent.Purple, Accent.PurpleDeep))),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = { (scaleIn(initialScale = 0.5f, animationSpec = tween(180)) + fadeIn(tween(180))).togetherWith(scaleOut(targetScale = 0.5f, animationSpec = tween(120)) + fadeOut(tween(120))) },
            ) { p ->
                Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (p) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(38.dp))
            }
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
