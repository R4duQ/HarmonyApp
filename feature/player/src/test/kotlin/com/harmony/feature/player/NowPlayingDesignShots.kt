package com.harmony.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sin

/**
 * The face of Now Playing (record, title, format card, waveform), built from
 * the screen's own components in its column, light and dark. The screen
 * itself needs a PlayerViewModel, so its top bar and transport are not here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h760dp-xhdpi")
class NowPlayingDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(dark: Boolean, name: String) {
        val song = designSong(1)
        val wave = FloatArray(120) { i -> 0.25f + 0.7f * abs(sin(i * 0.37f) * sin(i * 0.11f)) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                val palette = playerPalette()
                Column(Modifier.size(392.dp, 760.dp).background(palette.background).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(64.dp))
                    VinylArtwork(song.artworkUri, song.album, isPlaying = false, modifier = Modifier.fillMaxWidth().aspectRatio(1.25f))
                    Spacer(Modifier.height(24.dp))
                    Text(song.title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize), color = palette.ink)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                    NowPlayingAudioQuality(
                        info = MiniPlayerAudioInfo(format = "FLAC", bitrateKbps = 2_117, sampleRateHz = 96_000, isLossless = true),
                        bitDepth = 24, palette = palette, centered = false, modifier = Modifier.padding(top = 12.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    WaveformSeekBar(
                        progress = 0.56f, waveform = wave, onScrub = {}, onScrubFinished = {}, onScrubCancel = {},
                        playedColor = palette.live, trackColor = palette.track, modifier = Modifier.fillMaxWidth().height(56.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("1:34", style = MaterialTheme.typography.labelMedium, color = palette.muted, modifier = Modifier.weight(1f))
                        Text("2:48", style = MaterialTheme.typography.labelMedium, color = palette.muted)
                    }
                }
            }
        }
        rule.designShot(name)
    }

    @Test fun light() = render(false, "player-light")
    @Test fun dark() = render(true, "player-dark")
}
