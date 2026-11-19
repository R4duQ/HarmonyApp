package com.harmony.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders Listen in light, dark and at 200% text for design review (not a
 * pixel assertion). Songs, artists and covers are synthetic.
 * Output: build/shots/listen-*.png.
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

    private fun render(dark: Boolean, name: String, fontScale: Float = 1f, s: HomeUiState = state) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HarmonyTheme(darkTheme = dark, dynamicColors = false, reducedMotion = true) {
                    Box(Modifier.size(392.dp, 850.dp)) {
                        ListenContent(state = s, actions = ListenActions())
                    }
                }
            }
        }
        rule.designShot(name)
    }

    @Test fun light() = render(false, "listen-light")
    @Test fun dark() = render(true, "listen-dark")
    @Test fun largeText() = render(true, "listen-dark-font200", fontScale = 2f)
    @Test fun resume() = render(false, "listen-resume", s = state.copy(nowPlaying = null, resumeSong = designSong(1)))
    @Test fun empty() = render(false, "listen-empty", s = HomeUiState(loaded = true, libraryEmpty = true))
}
