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
                    UpNextMenu(song, loader, queueOpen, { queueOpen = false }, onPlay = app.player::playQueueItem)
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
