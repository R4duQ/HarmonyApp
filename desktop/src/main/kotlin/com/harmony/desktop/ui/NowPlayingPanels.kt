package com.harmony.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.desktop.DesktopApp
import com.harmony.desktop.player.NowPlaying
import com.harmony.desktop.player.Repeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two panels Now Playing can open on its right. */
enum class NowPlayingPanel { DEVICES, QUEUE }

/**
 * A panel sliding in from the right over Now Playing, on a light scrim that
 * closes it when clicked.
 */
@Composable
fun BoxScope.SidePanel(open: Boolean, onClose: () -> Unit, tag: String, content: @Composable () -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(tween(160)), exit = fadeOut(tween(160)), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }
    AnimatedVisibility(
        open,
        enter = slideInHorizontally(tween(240)) { it / 3 } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(180)) { it / 3 } + fadeOut(tween(150)),
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        val c = LocalHarmonyColors.current
        Box(
            Modifier
                .padding(18.dp)
                .width(400.dp)
                .fillMaxHeight()
                .neumorphic(30.dp, elevation = 18.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(c.neu)
                // Clicks inside don't reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(horizontal = 24.dp, vertical = 22.dp)
                .testTag(tag),
        ) { content() }
    }
}

@Composable
private fun PanelHeader(icon: ImageVector, title: String, onClose: () -> Unit) {
    val c = LocalHarmonyColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 18.dp)) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent.Purple, Accent.PurpleLight))),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.weight(1f).padding(start = 12.dp))
        NeuIconButton(Icons.Rounded.Close, "Close", onClose, size = 38.dp)
    }
}

@Composable
private fun PanelLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold,
        color = LocalHarmonyColors.current.muted, modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** A row in a panel: an icon or picture, two lines, something on the right; highlighted when [selected]. */
@Composable
private fun PanelRow(
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = LocalHarmonyColors.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    selected -> Accent.Purple.copy(alpha = if (c.dark) 0.22f else 0.14f)
                    hovered && onClick != null -> c.ink.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .hoverable(hover)
            .then(if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Accent.PurpleLight.takeIf { c.dark } ?: Accent.Purple else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, accent: Boolean = false) {
    val c = LocalHarmonyColors.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (accent) Accent.Purple.copy(alpha = 0.18f) else c.ink.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (accent) Accent.Purple else c.ink.copy(alpha = 0.75f), modifier = Modifier.size(20.dp)) }
}

/** What kind of output a sound device's name suggests. */
internal fun outputIcon(name: String?): ImageVector {
    val n = name.orEmpty().lowercase()
    return when {
        listOf("headphone", "headset", "earphone", "casti", "căști", "airpods", "buds").any { it in n } -> Icons.Rounded.Headset
        listOf("hdmi", "display", "monitor", "tv", "nvidia", "amd high definition").any { it in n } -> Icons.Rounded.Tv
        else -> Icons.Rounded.Speaker
    }
}

/**
 * Where the music is heard: this computer and its sound outputs (pick one),
 * and the phones that can play here through Harmony Connect.
 */
@Composable
fun DevicesPanel(app: DesktopApp, onClose: () -> Unit, onOpenConnect: () -> Unit) {
    val c = LocalHarmonyColors.current
    val settings by app.settingsFlow.collectAsState()
    val phone by app.player.phone.collectAsState()
    val connect by app.connect.state.collectAsState()
    val outputs by produceState<List<String>?>(null) { value = withContext(Dispatchers.IO) { app.outputDevices() } }
    val chosen = settings.outputDevice
    Column(Modifier.fillMaxSize()) {
        PanelHeader(Icons.Rounded.Devices, "Devices", onClose)
        LazyColumn(Modifier.weight(1f)) {
            item {
                PanelLabel("PLAYING ON", Modifier.padding(top = 0.dp))
                PanelRow(
                    selected = true,
                    leading = { RoundIcon(Icons.Rounded.Computer, accent = true) },
                    title = settings.pcName,
                    subtitle = "This computer · ${chosen ?: "System default"}",
                    trailing = { Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Accent.Purple, modifier = Modifier.size(20.dp)) },
                )
                PanelLabel("SOUND OUTPUT")
                PanelRow(
                    selected = chosen == null,
                    onClick = { app.setOutputDevice(null) },
                    modifier = Modifier.testTag("output_default"),
                    leading = { RoundIcon(Icons.Rounded.Speaker, accent = chosen == null) },
                    title = "System default",
                    subtitle = "Whatever Windows uses",
                    trailing = { if (chosen == null) Check() },
                )
            }
            val list = outputs
            if (list == null) {
                item { Text("Looking for outputs…", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(10.dp)) }
            } else {
                // A chosen output that's unplugged now still shows, so it can be seen and changed.
                val all = if (chosen != null && chosen !in list) list + chosen else list
                itemsIndexed(all) { i, name ->
                    val here = name in list
                    PanelRow(
                        selected = name == chosen,
                        onClick = { app.setOutputDevice(name) },
                        modifier = Modifier.testTag("output_$i"),
                        leading = { RoundIcon(outputIcon(name), accent = name == chosen) },
                        title = name,
                        subtitle = if (here) null else "Not connected now: plays on the default",
                        trailing = { if (name == chosen) Check() },
                    )
                }
            }
            item {
                PanelLabel("PHONES · HARMONY CONNECT")
                val names = (listOfNotNull(phone) + connect.phones.values).distinct()
                if (names.isEmpty()) {
                    Text("No phone paired yet.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
                }
                names.forEach { name ->
                    val playing = name == phone
                    PanelRow(
                        selected = playing,
                        leading = { RoundIcon(Icons.Rounded.Smartphone, accent = playing) },
                        title = name,
                        subtitle = if (playing) "Playing here now" else "Paired",
                        trailing = {
                            if (playing) SmallButton("Stop") { app.player.disconnect(name) }
                        },
                    )
                }
                Row(Modifier.padding(top = 10.dp)) {
                    SmallButton("Pair a phone", onOpenConnect)
                }
            }
        }
    }
}

@Composable
private fun Check() {
    Icon(Icons.Rounded.CheckCircle, contentDescription = "Chosen", tint = Accent.Purple, modifier = Modifier.size(20.dp))
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    val c = LocalHarmonyColors.current
    Text(
        label,
        fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = Accent.Purple,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Accent.Purple.copy(alpha = if (c.dark) 0.16f else 0.1f))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * The song playing and what comes after it. The computer's own queue can be
 * jumped around in; a phone's is shown as the phone has it.
 */
@Composable
fun QueuePanel(app: DesktopApp, loader: ImageLoader, song: NowPlaying, onClose: () -> Unit) {
    val c = LocalHarmonyColors.current
    val shuffle by app.player.shuffle.collectAsState()
    val repeat by app.player.repeat.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PanelHeader(Icons.Rounded.QueueMusic, "Queue", onClose)
        PanelLabel("NOW PLAYING", Modifier.padding(top = 0.dp))
        PanelRow(
            selected = true,
            leading = { ArtImage(song.art, loader, RoundedCornerShape(10.dp), Modifier.size(46.dp)) },
            title = song.title,
            subtitle = listOf(song.artist, song.album).filter(String::isNotBlank).joinToString(" · "),
            trailing = { Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Accent.Purple, modifier = Modifier.size(20.dp)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelLabel(if (song.fromPhone != null) "UP NEXT · FROM ${song.fromPhone.uppercase()}" else "UP NEXT", Modifier.weight(1f))
            if (shuffle) ModeChip(Icons.Rounded.Shuffle, "Shuffle")
            if (repeat != Repeat.OFF) ModeChip(if (repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, if (repeat == Repeat.ONE) "One" else "All")
        }
        if (song.upNext.isEmpty()) {
            Text("Nothing after this song.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(start = 10.dp, top = 4.dp))
        }
        LazyColumn(Modifier.weight(1f).testTag("queue_list"), contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(song.upNext) { i, item ->
                PanelRow(
                    onClick = item.queueIndex?.let { qi -> { app.player.playQueueItem(qi) } },
                    leading = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", fontSize = 12.sp, color = c.muted, modifier = Modifier.width(24.dp))
                            ArtImage(item.art, loader, RoundedCornerShape(8.dp), Modifier.size(40.dp))
                        }
                    },
                    title = item.title,
                    subtitle = item.artist,
                )
            }
        }
        if (song.fromPhone != null) {
            Text(
                "This is your phone's queue: change it on the phone, or use next, shuffle and repeat here.",
                fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ModeChip(icon: ImageVector, label: String) {
    Row(
        Modifier
            .padding(start = 6.dp, top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Accent.Purple.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Accent.Purple, modifier = Modifier.size(13.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Accent.Purple)
    }
}
