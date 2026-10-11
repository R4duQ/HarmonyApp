package com.harmony.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import com.harmony.desktop.engine.ToneDesign
import kotlin.math.roundToInt
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
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
fun NowPlayingPage(app: DesktopApp, loader: ImageLoader, onOpenEqualizer: () -> Unit = {}, onOpenConnect: () -> Unit = {}, initialPanel: NowPlayingPanel? = null) {
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
    var panel by remember { mutableStateOf(initialPanel) }
    fun toggle(p: NowPlayingPanel) { panel = if (panel == p) null else p }
    Box(Modifier.fillMaxSize().background(c.neu)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // A soft accent glow behind the panel.
                    drawCircle(
                        Brush.radialGradient(listOf(Accent.Purple.copy(alpha = if (c.dark) 0.10f else 0.07f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.45f), radius = size.minDimension * 0.75f),
                        radius = size.minDimension * 0.75f, center = Offset(size.width / 2, size.height * 0.45f),
                    )
                }
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The knobs grow with the window, within reason.
            val knob = (maxHeight * 0.34f).coerceIn(200.dp, 290.dp)
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
                    DevicePill(app, song, open = panel == NowPlayingPanel.DEVICES, onClick = { toggle(NowPlayingPanel.DEVICES) }, modifier = Modifier.padding(start = 16.dp))
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
                        NeuIconButton(
                            Icons.Rounded.QueueMusic, "Queue", { toggle(NowPlayingPanel.QUEUE) }, size = 50.dp,
                            active = panel == NowPlayingPanel.QUEUE, modifier = Modifier.testTag("np_queue"),
                        )
                    }
                }

                // Middle: volume, the song, bass.
                Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Knob(
                        value = settings.volume,
                        onChange = { app.setVolume((it * 100).roundToInt() / 100f) },
                        icon = Icons.Rounded.MusicNote, label = "Volume",
                        readout = if (muted) "Muted" else percent(settings.volume),
                        modifier = Modifier.testTag("knob_volume"), size = knob,
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedContent(
                            targetState = song,
                            contentKey = { it.key },
                            transitionSpec = { (fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 6 }).togetherWith(fadeOut(tween(150))) },
                        ) { s ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s.title, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    s.artist.uppercase(), fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold, color = c.ink.copy(alpha = 0.6f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                        if (song.quality != null) Pill(song.quality, Accent.Purple, Modifier.padding(top = 10.dp))
                        WaveformBars(bars, position, song.durationMs, app.player::seek, Modifier.padding(top = 26.dp).widthIn(max = 460.dp).fillMaxWidth().height(80.dp))
                        Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                            NeuTransport(Icons.Rounded.SkipPrevious, "Previous", app.player::previous, 62.dp)
                            NeuPlay(playing, app.player::togglePlay)
                            NeuTransport(Icons.Rounded.SkipNext, "Next", app.player::next, 62.dp)
                        }
                    }
                    val bass = if (eq.enabled) (eq.bassDb / ToneDesign.MAX_DB).coerceIn(0f, 1f) else 0f
                    Knob(
                        value = bass,
                        onChange = { v -> app.setEq(eq.copy(enabled = true, bassDb = ((v * ToneDesign.MAX_DB) * 2).roundToInt() / 2f)) },
                        icon = Icons.Rounded.Headphones, label = "Bass", readout = percent(bass),
                        modifier = Modifier.testTag("knob_bass"), size = knob,
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
        SidePanel(panel == NowPlayingPanel.DEVICES, { panel = null }, "devices_panel") {
            DevicesPanel(app, onClose = { panel = null }, onOpenConnect = onOpenConnect)
        }
        SidePanel(panel == NowPlayingPanel.QUEUE, { panel = null }, "queue_panel") {
            QueuePanel(app, loader, song, onClose = { panel = null })
        }
    }
}

/**
 * Where the music plays, at the top left: this computer and its sound output,
 * or the phone it comes from. Opens the Devices panel.
 */
@Composable
private fun DevicePill(app: DesktopApp, song: com.harmony.desktop.player.NowPlaying, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHarmonyColors.current
    val settings by app.settingsFlow.collectAsState()
    val output = settings.outputDevice ?: "System default"
    Row(
        modifier
            .neumorphic(28.dp, elevation = 8.dp, pressed = open)
            .clip(RoundedCornerShape(28.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClickLabel = "Devices", onClick = onClick)
            .padding(start = 8.dp, end = 18.dp, top = 7.dp, bottom = 7.dp)
            .widthIn(min = 240.dp, max = 380.dp)
            .testTag("np_devices"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent.Purple, Accent.PurpleLight))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (song.fromPhone != null) Icons.Rounded.Smartphone else outputIcon(settings.outputDevice),
                contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                song.fromPhone?.let { "FROM ${it.uppercase()}" } ?: "THIS PC",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (song.fromPhone != null) "Playing on this PC · $output" else output,
                fontSize = 11.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = c.ink.copy(alpha = 0.7f), modifier = Modifier.padding(start = 10.dp).size(22.dp))
    }
}

/** Previous / next: a soft round button with an accent glyph; it sinks while pressed. */
@Composable
private fun NeuTransport(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp) {
    val c = LocalHarmonyColors.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(size)
            .neumorphic(size / 2, elevation = 10.dp, pressed = pressed, circle = true)
            .clip(CircleShape)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = description,
            tint = if (hovered) Accent.PurpleLight.takeIf { c.dark } ?: Accent.PurpleDeep else Accent.Purple.takeUnless { c.dark } ?: Accent.PurpleLight.copy(alpha = 0.9f),
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

/** The big play / pause: a glowing purple disc in a soft ring; it gives a little when pressed. */
@Composable
private fun NeuPlay(playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.94f else if (hovered) 1.03f else 1f, tween(120))
    Box(
        Modifier
            .size(100.dp)
            .neumorphic(50.dp, elevation = 16.dp, circle = true)
            .clip(CircleShape)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = if (playing) "Pause" else "Play", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(74.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .drawBehind {
                    // A soft purple glow under the disc.
                    drawIntoCanvas { canvas ->
                        val paint = androidx.compose.ui.graphics.Paint()
                        val fp = paint.asFrameworkPaint()
                        fp.maskFilter = org.jetbrains.skia.MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, 12.dp.toPx())
                        fp.color = Accent.Purple.copy(alpha = 0.55f).toArgb()
                        canvas.drawCircle(Offset(size.width / 2, size.height / 2 + 6.dp.toPx()), size.minDimension / 2 - 4.dp.toPx(), paint)
                    }
                }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF9B78FF), Accent.Purple, Accent.PurpleDeep))),
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
