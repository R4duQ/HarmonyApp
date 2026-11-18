package com.harmony.feature.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.component.greenPalette
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders a playlist page in light and dark for design review. Synthetic songs and covers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
class PlaylistDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(dark: Boolean, name: String) {
        val songs = (1L..24L).map(::designSong)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                Box(Modifier.size(392.dp, 850.dp)) {
                    PlaylistDetailContent(
                        ui = PlaylistDetailUi("Late Night Drive", null, editable = true, songs = songs, nowPlayingId = 3),
                        palette = greenPalette(),
                        actions = PlaylistDetailActions(onBack = {}, onPlay = { _, _ -> }, onShuffle = {}, onPlayNext = {}, onMove = { _, _ -> }),
                    )
                }
            }
        }
        rule.designShot(name)
    }

    @Test fun light() = render(false, "playlist-light")
    @Test fun dark() = render(true, "playlist-dark")
}
