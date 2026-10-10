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

    HarmonyTheme(dark = settings.darkTheme) {
        val c = LocalHarmonyColors.current
        Column(Modifier.fillMaxSize().background(c.background)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Sidebar(
                    current = (place as? Place.Top)?.section,
                    phone = phone,
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
                                Section.NOW_PLAYING -> NowPlayingPage(app, loader, onOpenEqualizer = { place = Place.Top(Section.EQUALIZER) })
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
private fun Sidebar(current: Section?, phone: String?, dark: Boolean, onSelect: (Section) -> Unit, onToggleTheme: () -> Unit) {
    val c = LocalHarmonyColors.current
    Column(
        Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(c.sidebar)
            .padding(horizontal = 14.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 22.dp)) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(Accent.Purple, Color(0xFF39B9D3)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text("Harmony", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(start = 10.dp))
        }
        NavItem(Section.HOME, Section.HOME == current, onSelect)
        Spacer(Modifier.height(14.dp))
        SectionLabel("MUSIC")
        listOf(Section.SONGS, Section.ALBUMS, Section.ARTISTS, Section.FAVORITES).forEach { NavItem(it, it == current, onSelect) }
        Spacer(Modifier.height(14.dp))
        SectionLabel("PLAYER")
        listOf(Section.NOW_PLAYING, Section.EQUALIZER).forEach { NavItem(it, it == current, onSelect) }
        NavItem(Section.CONNECT, Section.CONNECT == current, onSelect, badge = phone)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button, onClick = onToggleTheme)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
            Text(if (dark) "Light theme" else "Dark theme", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        letterSpacing = 1.6.sp,
        fontWeight = FontWeight.Bold,
        color = LocalHarmonyColors.current.muted,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun NavItem(section: Section, selected: Boolean, onSelect: (Section) -> Unit, badge: String? = null) {
    val c = LocalHarmonyColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Brush.horizontalGradient(listOf(Accent.Purple, Color(0xFF9D6BFF))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(role = Role.Tab) { onSelect(section) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("nav_${section.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon, contentDescription = null, tint = if (selected) Color.White else c.muted, modifier = Modifier.size(19.dp))
        Text(
            section.label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else c.ink.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        if (badge != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF3CDB5A)))
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
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.ink, maxLines = 1)
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
