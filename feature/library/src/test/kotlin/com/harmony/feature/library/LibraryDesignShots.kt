package com.harmony.feature.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.component.CrateDivider
import com.harmony.core.ui.component.EditorialTab
import com.harmony.core.ui.component.GlassSearchBar
import com.harmony.core.ui.component.GlassSegmentedTabs
import com.harmony.core.ui.component.GlassSongCard
import com.harmony.core.ui.component.crateLetter
import com.harmony.core.ui.component.libraryPalette
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library's Songs tab as LibraryScreen lays it out (header, tabs, the
 * title-sorted list with its crate dividers), built from the same public
 * components, light and dark. Synthetic songs and covers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
class LibraryDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(dark: Boolean, pinned: Boolean, name: String) {
        val songs = (1L..16L).map(::designSong).sortedBy { it.title.lowercase() }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                val palette = libraryPalette()
                Column(Modifier.size(392.dp, 850.dp).background(palette.field)) {
                    GlassSearchBar(active = false, value = "", onValueChange = {}, onFieldClick = {}, onClear = {}, palette = palette, modifier = Modifier.padding(top = 10.dp))
                    GlassSegmentedTabs(
                        tabs = listOf(
                            EditorialTab("Songs", 1306, Icons.Rounded.MusicNote),
                            EditorialTab("Albums", 120, Icons.Rounded.Album),
                            EditorialTab("Artists", 84, Icons.Rounded.Person),
                        ),
                        selected = 0, onSelect = {}, palette = palette,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(songs.size) { index ->
                                val song = songs[index]
                                Column {
                                    val letter = crateLetter(song.title)
                                    if (index == 0 || crateLetter(songs[index - 1].title) != letter) {
                                        CrateDivider(letter, Modifier.padding(top = if (index == 0) 0.dp else 12.dp, bottom = 4.dp))
                                    }
                                    GlassSongCard(song = song, palette = palette, onClick = {}, onPlayNext = {}, onAddToPlaylist = {}, onRemove = {})
                                }
                            }
                        }
                        if (pinned) CrateDivider("H", Modifier.align(Alignment.TopStart))
                    }
                }
            }
        }
        rule.designShot(name)
    }

    @Test fun light() = render(false, false, "library-light")
    @Test fun dark() = render(true, false, "library-dark")
}
