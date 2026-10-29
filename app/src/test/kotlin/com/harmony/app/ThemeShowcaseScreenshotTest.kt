package com.harmony.app

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.harmony.app.navigation.LiquidGlassNavBar
import com.harmony.app.navigation.LiquidGlassNavRail
import com.harmony.app.navigation.NavItem
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.Song
import com.harmony.core.ui.component.ArcSongList
import com.harmony.core.ui.component.EditorialChoiceChips
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.EditorialSettingRow
import com.harmony.core.ui.component.EditorialSlider
import com.harmony.core.ui.component.EditorialSwitch
import com.harmony.core.ui.component.EditorialTab
import com.harmony.core.ui.component.GlassCircleButton
import com.harmony.core.ui.component.GlassSearchBar
import com.harmony.core.ui.component.GlassSegmentedTabs
import com.harmony.core.ui.component.GlassSongCard
import com.harmony.core.ui.component.amberPalette
import com.harmony.core.ui.component.libraryPalette
import com.harmony.core.ui.component.stonePalette
import com.harmony.core.ui.theme.CyberGlassColors
import com.harmony.core.ui.theme.isCyberGlass
import com.harmony.core.ui.theme.HarmonyTheme
import com.harmony.feature.player.MiniPlayerActions
import com.harmony.feature.player.MiniPlayerCard
import com.harmony.feature.player.MiniPlayerUi
import com.harmony.feature.player.VinylArtwork
import com.harmony.feature.player.playerPalette
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The same screens, with the same content, in light and in dark: built from
 * the app's own components (search pill, tabs, song cards, the wheel, the
 * mini player, the navigation bar and rail, the record, the settings
 * controls), laid out the way the shell lays them out. For comparing the two
 * themes side by side; not an assertion of pixels.
 *
 * `./gradlew :app:recordRoborazziStableDebug` writes them to
 * app/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The bare Application: the real one starts Hilt and the playback stack.
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ThemeShowcaseScreenshotTest {

    private fun song(id: Long, title: String, artist: String, album: String, ms: Long, bits: Int? = null, rate: Int? = null) = Song(
        id = id, uri = "content://song/$id", title = title, artist = artist, album = album, albumId = id,
        albumArtist = null, composer = null, year = 2024, genre = null, discNumber = 1, trackNumber = id.toInt(),
        durationMs = ms, bitrateKbps = if (bits == null) 320 else null, sampleRateHz = rate, bitDepth = bits, channels = 2,
        artworkUri = null, embeddedLyrics = null, replayGainTrackDb = null, replayGainAlbumDb = null,
    )

    private val songs = listOf(
        song(1, "9", "Calinacho", "9", 214_000, 24, 96_000),
        song(2, "9 To 5", "Calinacho", "9", 187_000, 24, 96_000),
        song(3, "Aliens", "Calinacho", "9", 201_000, 16, 44_100),
        song(4, "Blond", "Calinacho", "Blond", 176_000, 24, 96_000),
        song(5, "Ultima noapte de dragoste", "Calinacho", "Single", 229_000),
        song(6, "Instant Crush", "Daft Punk", "Random Access Memories", 337_000, 24, 88_200),
        song(7, "Get Lucky", "Daft Punk", "Random Access Memories", 369_000, 24, 88_200),
    )

    private val navItems = listOf(
        NavItem("Home", Icons.Rounded.Home),
        NavItem("Library", Icons.Rounded.LibraryMusic),
        NavItem("Playlists", Icons.Rounded.PlaylistPlay),
        NavItem("Discover", Icons.Rounded.Explore),
        NavItem("Downloads", Icons.Rounded.Download),
    )

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        captureRoboImage("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png") {
            HarmonyTheme(darkTheme = dark, amoled = false, dynamicColors = false) { content() }
        }
    }

    /** The page behind the library, as LibraryScreen paints it. */
    @Composable
    private fun pageBrush(palette: EditorialPalette): Brush =
        if (palette.isCyberGlass) CyberGlassColors.Page else SolidColor(palette.field)

    // ---- The shell: page, then the mini player and the bar floating over it --------

    @Composable
    private fun Shell(palette: EditorialPalette, selected: Int, page: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            page()
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                MiniPlayerCard(
                    ui = MiniPlayerUi(
                        // Paused: the playing card turns the record and breathes a halo for
                        // ever, and a Compose test never goes idle under an endless animation.
                        song = songs[0], isPlaying = false, positionMs = 71_000, durationMs = songs[0].durationMs,
                        shuffleOn = true, repeatMode = RepeatMode.OFF, deviceLabel = "Phone speaker",
                        deviceIcon = Icons.Rounded.Headphones,
                        upNext = listOf(1 to songs[1], 2 to songs[2]),
                        telemetry = "LIVE SPECTRAL EQ",
                    ),
                    actions = MiniPlayerActions(),
                )
                Spacer(Modifier.height(10.dp))
                LiquidGlassNavBar(items = navItems, selectedIndex = selected, onSelect = {}, palette = palette)
            }
        }
    }

    @Composable
    private fun LibraryPage(palette: EditorialPalette, wheel: Boolean) {
        // As LibraryScreen: Cyber-Glass page gradient where it applies, the field otherwise.
        Column(Modifier.fillMaxSize().background(pageBrush(palette))) {
            GlassSearchBar(
                active = false, value = "", onValueChange = {}, onFieldClick = {}, onClear = {},
                palette = palette, modifier = Modifier.padding(top = 10.dp),
                trailing = {
                    GlassCircleButton(onClick = {}, contentDescription = "Wheel", palette = palette, filled = wheel) {
                        Icon(Icons.Rounded.Album, contentDescription = null, modifier = Modifier.size(19.dp))
                    }
                },
            )
            GlassSegmentedTabs(
                tabs = listOf(
                    EditorialTab("Songs", songs.size, Icons.Rounded.MusicNote),
                    EditorialTab("Albums", 4, Icons.Rounded.Album),
                    EditorialTab("Artists", null, Icons.Rounded.Person),
                ),
                selected = 0, onSelect = {}, palette = palette,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (wheel) {
                ArcSongList(itemCount = songs.size, songAt = { songs.getOrNull(it) }, palette = palette, onPlay = {})
            } else {
                songs.forEach { s ->
                    GlassSongCard(song = s, palette = palette, onClick = {}, onPlayNext = {}, onAddToPlaylist = {})
                }
            }
        }
    }

    @Test fun library() = listOf(false, true).forEach { dark ->
        shoot("01_library", dark) {
            val p = libraryPalette()
            Shell(p, selected = 1) { LibraryPage(p, wheel = false) }
        }
    }

    @Test fun libraryWheel() = listOf(false, true).forEach { dark ->
        shoot("02_library_wheel", dark) {
            val p = libraryPalette()
            Shell(p, selected = 1) { LibraryPage(p, wheel = true) }
        }
    }

    /** Home and Downloads wear the amber editorial field; the chrome floats over it. */
    @Test fun homeChrome() = listOf(false, true).forEach { dark ->
        shoot("03_home_chrome", dark) {
            val p = amberPalette()
            Shell(p, selected = 0) {
                Column(Modifier.fillMaxSize().background(p.field).padding(20.dp)) {
                    Text("Good evening", color = p.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Picked from what you play", color = p.muted, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    songs.take(3).forEach { s ->
                        Text(s.title, color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                        Text(s.artist, color = p.muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    /** Settings controls: section labels, rows, switches on and off, chips, a slider. */
    @Test fun settingsControls() = listOf(false, true).forEach { dark ->
        shoot("04_settings_controls", dark) {
            val p = stonePalette()
            Column(Modifier.fillMaxSize().background(p.field).padding(vertical = 16.dp)) {
                EditorialSectionLabel("APPEARANCE", p)
                EditorialSettingRow("Theme", p, subtitle = "Follow the system, or always light or dark") {
                    EditorialChoiceChips(listOf("System", "Light", "Dark"), selectedIndex = 1, onSelect = {}, palette = p)
                }
                EditorialSettingRow("AMOLED black", p, subtitle = "True black in dark theme", trailing = {
                    EditorialSwitch(checked = false, onCheckedChange = {}, palette = p)
                })
                EditorialSettingRow("Dynamic colors", p, subtitle = "Colors from your wallpaper", trailing = {
                    EditorialSwitch(checked = true, onCheckedChange = {}, palette = p)
                })
                EditorialSectionLabel("PLAYBACK", p)
                EditorialSettingRow("Crossfade", p, subtitle = "4 s") {
                    EditorialSlider(value = 0.4f, onValueChange = {}, palette = p)
                }
            }
        }
    }

    /** Vinyl: the record stays a dark object on the player's own field. */
    @Test fun vinyl() = listOf(false, true).forEach { dark ->
        shoot("05_vinyl", dark) {
            val pp = playerPalette()
            Column(
                Modifier.fillMaxSize().background(pp.background).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                VinylArtwork(artworkUri = null, albumName = "9", isPlaying = false, modifier = Modifier.size(320.dp))
                Spacer(Modifier.height(24.dp))
                Text("9", color = pp.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Calinacho", color = pp.muted, fontSize = 16.sp)
            }
        }
    }

    /** Landscape / tablet: the rail at the left, the page beside it. */
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    @Test fun libraryLandscape() = listOf(false, true).forEach { dark ->
        shoot("06_library_landscape", dark) {
            val p = libraryPalette()
            Row(Modifier.fillMaxSize().background(pageBrush(p))) {
                LiquidGlassNavRail(items = navItems, selectedIndex = 1, onSelect = {}, palette = p, modifier = Modifier.padding(8.dp))
                Box(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) { LibraryPage(p, wheel = false) }
            }
        }
    }
}
