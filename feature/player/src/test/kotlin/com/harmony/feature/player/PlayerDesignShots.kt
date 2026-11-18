package com.harmony.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.model.RepeatMode
import com.harmony.core.ui.component.libraryPalette
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the mini player on the library's page, light and dark. Synthetic songs and covers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h300dp-xhdpi")
class PlayerDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(dark: Boolean, name: String) {
        val song = designSong(1)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                Box(Modifier.size(392.dp, 300.dp).background(libraryPalette().field), contentAlignment = Alignment.BottomCenter) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        MiniPlayerCard(
                            ui = MiniPlayerUi(
                                song = song, isPlaying = true, positionMs = 94_000, durationMs = song.durationMs,
                                shuffleOn = true, repeatMode = RepeatMode.OFF, deviceLabel = "Phone speaker",
                                deviceIcon = Icons.Rounded.Headphones, upNext = listOf(2 to designSong(2), 3 to designSong(3)),
                            ),
                            actions = MiniPlayerActions(),
                        )
                    }
                }
            }
        }
        rule.designShot(name)
    }

    @Test fun light() = render(false, "mini-light")
    @Test fun dark() = render(true, "mini-dark")
}
