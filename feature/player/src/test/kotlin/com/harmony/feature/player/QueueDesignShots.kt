package com.harmony.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harmony.core.model.MixSource
import com.harmony.core.model.MixState
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.ui.component.libraryPalette
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The queue sheet after "Play next" on two songs with Smart Shuffle on and a
 * mix running, and the mini player on a small phone with a large font. Same
 * synthetic data before and after a change. Output: build/shots/queue-*.png, mini-small-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueueDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun queue(dark: Boolean, name: String) {
        val songs = (1L..14L).map(::designSong)
        val state = PlayerState(
            currentSong = songs[0], queue = songs, queueIndex = 0, isPlaying = true,
            positionMs = 60_000, durationMs = songs[0].durationMs, shuffleMode = ShuffleMode.SMART, playNextCount = 2,
        )
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                Box(Modifier.fillMaxSize().background(playerPalette().background).padding(top = 12.dp)) {
                    QueueSheetContent(
                        state = state, onPlay = {}, onRemove = {}, onMove = { _, _, _, _ -> },
                        mix = MixState(1, songs[0].title, songs[0].artist, 0, MixSource.OFFLINE),
                        mixAvailable = true,
                    )
                }
            }
        }
        rule.designShot(name)
    }

    @Test @Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
    fun queueLight() = queue(false, "queue-light")

    @Test @Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
    fun queueDark() = queue(true, "queue-dark")

    /** A 360 x 640 phone at 130% text: the mini player over the page's last rows. */
    @Test @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
    fun miniSmallPhone() {
        val song = designSong(1).copy(title = "Night Ferry (Extended Harbour Mix)")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                HarmonyTheme(darkTheme = false, dynamicColors = false) {
                    Box(Modifier.fillMaxSize().background(libraryPalette().field), contentAlignment = Alignment.BottomCenter) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 68.dp)) {
                            MiniPlayerCard(
                                ui = MiniPlayerUi(
                                    song, true, 94_000, song.durationMs, true, RepeatMode.OFF, "Pixel Buds Pro", Icons.Rounded.Headphones,
                                    listOf(2 to designSong(2), 3 to designSong(3)), telemetry = "Preparing “Slow Weather” · 45%",
                                ),
                                actions = MiniPlayerActions(),
                            )
                        }
                    }
                }
            }
        }
        rule.designShot("mini-small")
    }
}
