package com.harmony.feature.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.harmony.core.ui.component.amberPalette
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An album page (one album of 10 tracks, track 3 playing), light and dark, normal and small phone. Synthetic. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(dark: Boolean, name: String) {
        val songs = (1L..10L).map { designSong(it * 7 - 6).copy(album = "Harbour Lights", albumId = 1, artist = "Mira Sol", trackNumber = it.toInt(), artworkUri = designCover(0), year = 2021) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                Box(Modifier.fillMaxSize()) {
                    AlbumDetailContent(ui = AlbumDetailUi(songs, nowPlayingId = songs[2].id, isPlaying = true), palette = amberPalette(), actions = AlbumDetailActions())
                }
            }
        }
        rule.designShot(name)
    }

    @Test @Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi") fun light() = render(false, "album-light")
    @Test @Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi") fun dark() = render(true, "album-dark")
    @Test @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi") fun small() = render(false, "album-small")
}
