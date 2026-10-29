package com.harmony.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.desktop.DesktopApp

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Discover", Icons.Rounded.Explore),
    FAVORITES("Favourites", Icons.Rounded.Favorite),
    SONGS("Songs", Icons.Rounded.MusicNote),
    ALBUMS("Albums", Icons.Rounded.Album),
    ARTISTS("Artists", Icons.Rounded.Person),
    NOW_PLAYING("Now Playing", Icons.Rounded.LibraryMusic),
    EQUALIZER("Equalizer", Icons.Rounded.Tune),
    CONNECT("Connect", Icons.Rounded.Cast),
}

/** Where the main area is: a section, or an album / artist opened from one. */
sealed interface Place {
    data class Top(val section: Section) : Place
    data class AlbumPage(val album: String, val artist: String) : Place
    data class ArtistPage(val artist: String) : Place
    data class Search(val query: String) : Place
}

/**
 * Harmony for Windows: a sidebar, the page, and the player along the bottom.
 */
@Composable
fun HarmonyDesktopApp(app: DesktopApp) {
    val settings by app.settingsFlow.collectAsState()
    val loader = remember(app) { ImageLoader(app.covers) }
    var place by remember { mutableStateOf<Place>(Place.Top(Section.HOME)) }
    val phone by app.player.phone.collectAsState()
    val link by app.connect.link.collectAsState()

    HarmonyTheme(dark = settings.darkTheme) {
        val c = LocalHarmonyColors.current
        Column(Modifier.fillMaxSize().background(c.background)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Sidebar(
                    current = (place as? Place.Top)?.section,
                    phone = phone,
                    reconnecting = link is com.harmony.core.remote.PhoneLink.Reconnecting,
                    dark = settings.darkTheme,
                    onSelect = { place = Place.Top(it) },
                    onToggleTheme = { app.setDarkTheme(!settings.darkTheme) },
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(
                        targetState = place,
                        transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 40 }).togetherWith(fadeOut(tween(120))) },
                    ) { p ->
                        when (p) {
                            is Place.Top -> when (p.section) {
                                Section.HOME -> HomePage(app, loader, onOpenAlbum = { a, ar -> place = Place.AlbumPage(a, ar) }, onSearch = { place = Place.Search(it) })
                                Section.FAVORITES -> SongsPage(app, loader, filterArtist = null, onOpenAlbum = { a, ar -> place = Place.AlbumPage(a, ar) }, favoritesOnly = true)
                                Section.SONGS -> SongsPage(app, loader, filterArtist = null, onOpenAlbum = { a, ar -> place = Place.AlbumPage(a, ar) })
                                Section.ALBUMS -> AlbumsPage(app, loader, onOpen = { a, ar -> place = Place.AlbumPage(a, ar) })
                                Section.ARTISTS -> ArtistsPage(app, loader, onOpen = { place = Place.ArtistPage(it) })
                                Section.NOW_PLAYING -> NowPlayingPage(app, loader, onOpenEqualizer = { place = Place.Top(Section.EQUALIZER) }, onOpenConnect = { place = Place.Top(Section.CONNECT) })
                                Section.EQUALIZER -> EqualizerPage(app)
                                Section.CONNECT -> ConnectPage(app)
                            }
                            is Place.AlbumPage -> AlbumPage(app, loader, p.album, p.artist, onBack = { place = Place.Top(Section.ALBUMS) })
                            is Place.ArtistPage -> SongsPage(app, loader, filterArtist = p.artist, onOpenAlbum = { a, ar -> place = Place.AlbumPage(a, ar) }, onBack = { place = Place.Top(Section.ARTISTS) })
                            is Place.Search -> SongsPage(app, loader, filterArtist = null, onOpenAlbum = { a, ar -> place = Place.AlbumPage(a, ar) }, onBack = { place = Place.Top(Section.HOME) }, initialQuery = p.query)
                        }
                    }
                }
            }
            PlayerBar(app, loader, onOpenNowPlaying = { place = Place.Top(Section.NOW_PLAYING) })
        }
    }
}

@Composable
private fun Sidebar(current: Section?, phone: String?, reconnecting: Boolean, dark: Boolean, onSelect: (Section) -> Unit, onToggleTheme: () -> Unit) {
    val c = LocalHarmonyColors.current
    Column(
        Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(c.sidebar)
            .drawBehind { drawLine(c.line, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), strokeWidth = 1f) }
            .padding(horizontal = 14.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, bottom = 26.dp)) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFF9B78FF), Accent.PurpleDeep))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
            Text("Harmony", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = c.ink, modifier = Modifier.padding(start = 11.dp))
        }
        NavItem(Section.HOME, Section.HOME == current, onSelect)
        SectionLabel("Music")
        listOf(Section.SONGS, Section.ALBUMS, Section.ARTISTS, Section.FAVORITES).forEach { NavItem(it, it == current, onSelect) }
        SectionLabel("Player")
        listOf(Section.NOW_PLAYING, Section.EQUALIZER).forEach { NavItem(it, it == current, onSelect) }
        // Green while the phone answers, amber while it is being waited for.
        NavItem(
            Section.CONNECT, Section.CONNECT == current, onSelect,
            badge = when {
                reconnecting -> Color(0xFFF2B33D)
                phone != null -> Color(0xFF3CDB5A)
                else -> null
            },
        )
        Spacer(Modifier.weight(1f))
        ThemeSwitch(dark, onToggleTheme)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 10.5.sp,
        letterSpacing = 1.4.sp,
        fontWeight = FontWeight.Bold,
        color = LocalHarmonyColors.current.muted.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun NavItem(section: Section, selected: Boolean, onSelect: (Section) -> Unit, badge: Color? = null) {
    val c = LocalHarmonyColors.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val accent = if (c.dark) Accent.PurpleLight else Accent.Purple
    val bg by animateColorAsState(
        when {
            selected -> Accent.Purple.copy(alpha = if (c.dark) 0.2f else 0.12f)
            hovered -> c.ink.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        tween(140),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .drawBehind {
                // The selected item's accent bar on the left.
                if (selected) drawRoundRect(accent, Offset(0f, size.height * 0.25f), Size(3.dp.toPx(), size.height * 0.5f), CornerRadius(2.dp.toPx()))
            }
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(hover, indication = null, role = Role.Tab) { onSelect(section) }
            .padding(horizontal = 14.dp)
            .testTag("nav_${section.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon, contentDescription = null, tint = if (selected) accent else c.ink.copy(alpha = if (hovered) 0.9f else 0.6f), modifier = Modifier.size(19.dp))
        Text(
            section.label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) c.ink else c.ink.copy(alpha = if (hovered) 0.95f else 0.75f),
            modifier = Modifier.padding(start = 13.dp).weight(1f),
        )
        if (badge != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(badge))
        }
    }
}

/** Light and dark, side by side; the chosen one raised. */
@Composable
private fun ThemeSwitch(dark: Boolean, onToggle: () -> Unit) {
    val c = LocalHarmonyColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.ink.copy(alpha = 0.05f))
            .padding(4.dp)
            .testTag("theme_switch"),
    ) {
        listOf(false to Icons.Rounded.LightMode, true to Icons.Rounded.DarkMode).forEach { (isDark, icon) ->
            val chosen = isDark == dark
            Row(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (chosen) c.surface else Color.Transparent)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button) { if (!chosen) onToggle() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = if (chosen) c.ink else c.muted, modifier = Modifier.size(16.dp))
                Text(if (isDark) "Dark" else "Light", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (chosen) c.ink else c.muted, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** A page's title row: big title, a line under it, and room for actions. */
@Composable
fun PageHeader(title: String, subtitle: String?, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val c = LocalHarmonyColors.current
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            RoundButton(Icons.Rounded.ArrowBack, "Back", onBack, size = 38.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = c.ink, maxLines = 1)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
fun ScanBar(app: DesktopApp) {
    val scan by app.scan.collectAsState()
    val s = scan ?: return
    val c = LocalHarmonyColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp)) {
        Text("Looking through your music… ${s.done} of ${s.total}", fontSize = 12.sp, color = c.muted)
        LinearProgressIndicator(
            progress = if (s.total > 0) s.done.toFloat() / s.total else 0f,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Accent.Purple,
            trackColor = c.line,
        )
    }
}
