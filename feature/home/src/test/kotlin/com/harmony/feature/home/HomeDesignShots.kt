package com.harmony.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.component.amberPalette
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders Home in light and dark for design review (not a pixel assertion).
 * Songs, artists and covers are synthetic. Output: build/shots/home-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
class HomeDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = HomeUiState(
        loaded = true,
        stats = LibraryStats(1306, 120, 84, 6),
        nowPlaying = NowPlayingCard(designSong(1), isPlaying = true, queuePosition = 1, queueSize = 24),
        recentlyPlayed = (2L..7L).map(::designSong),
        playlists = listOf(
            HomePlaylist(11, "Late Night Drive", 24, 5_340_000, (0..3).mapNotNull(::designCover)),
            HomePlaylist(12, "Desk Focus", 41, 9_960_000, (4..7).mapNotNull(::designCover)),
        ),
        quality = QualityStatus(isLossless = true, format = "FLAC", bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_117, hasSong = true),
        recentAlbums = (0..4).map { HomeAlbum(21L + it, listOf("Harbour Lights", "Soft Machines", "Open Water", "Small Hours", "Field Notes")[it], listOf("Mira Sol", "The Paper Coast", "Juno Park", "Lumen Drive", "Vesna Kay")[it], designCover(it)) },
    )

    private fun render(dark: Boolean, name: String) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                Box(Modifier.size(392.dp, 850.dp)) {
                    HomeContent(state = state, palette = amberPalette(), actions = HomeActions(), greeting = "Good evening", online = true, progress = { 0.56f })
                }
            }
        }
        rule.designShot(name)
    }

    @Test fun light() = render(false, "home-light")
    @Test fun dark() = render(true, "home-dark")
}
