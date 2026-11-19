package com.harmony.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harmony.core.model.AudioOutput
import com.harmony.core.model.AudioOutputType
import com.harmony.core.model.PlayerState
import com.harmony.core.model.ShuffleMode
import com.harmony.core.ui.theme.HarmonyTheme
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sin

/**
 * Now Playing as the screen draws it (NowPlayingBody, the same composable),
 * with fixed synthetic data: the Vinyl layout in portrait and landscape, on
 * a normal and a small phone, light and dark, plus a hard case (long title,
 * no cover, 130% font). Output: build/shots/np-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingScreenShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(name: String, dark: Boolean, landscape: Boolean, hard: Boolean = false, fontScale: Float? = null) {
        val base = designSong(1)
        val song = if (hard) base.copy(title = "Night Ferry (Extended Harbour Mix, Remastered 2024)", artworkUri = null) else base
        val queue = listOf(song) + (2L..12L).map(::designSong)
        val state = PlayerState(
            currentSong = song, queue = queue, queueIndex = 0, isPlaying = true,
            positionMs = 94_000, durationMs = song.durationMs, shuffleMode = ShuffleMode.SMART, playNextCount = 2,
            audioOutput = AudioOutput(AudioOutputType.BLUETOOTH, "Pixel Buds Pro"),
        )
        // An online mix preparing the next song, worded by the same statusLine the queue uses.
        val mixStatus = MixStatusUi(
            statusLine(com.harmony.domain.online.OnlineProgress.Preparing(com.harmony.domain.online.CatalogTrack("abcdefghijk", "Slow Weather", listOf("The Paper Coast"), null, null, 193_000, com.harmony.domain.online.CatalogKind.SONG, null, true), 0.45f), com.harmony.core.model.MixSource.COMBINED, upcomingEmpty = false),
            working = true,
        )
        val wave = FloatArray(120) { i -> 0.25f + 0.7f * abs(sin(i * 0.37f) * sin(i * 0.11f)) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val d = LocalDensity.current
            val scale = fontScale ?: if (hard) 1.3f else 1f
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                HarmonyTheme(darkTheme = dark, dynamicColors = false, reducedMotion = true) {
                    val palette = playerPalette()
                    val big = scale > 1.3f && !landscape
                    Column(
                        Modifier.fillMaxSize().background(palette.background)
                            .then(if (big) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(horizontal = if (landscape) 24.dp else 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        NowPlayingBody(
                            state = state, song = song, landscape = landscape, palette = palette, isFavorite = true,
                            audioInfo = MiniPlayerAudioInfo(format = "FLAC", bitrateKbps = 2_117, sampleRateHz = 96_000, isLossless = true),
                            output = OutputChoice(form = null, chosen = false, pick = null),
                            journeyProgress = null, smartStyle = SmartShuffleStyle.BALANCED,
                            progress = 0.56f, dragFraction = null, waveform = wave, playScale = 1f,
                            cb = NowPlayingCallbacks(onEqualizer = {}),
                            mixOn = true,
                            mixStatus = mixStatus,
                            reason = "Often follows your last song",
                            steer = SteerCallbacks(steerable = true, energy = 0.5f, mood = com.harmony.core.model.MoodFilter.CALM),
                            scrollable = big,
                        )
                    }
                }
            }
        }
        rule.designShot(name)
    }

    @Test @Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
    fun portrait() { render("np-portrait-light", dark = false, landscape = false) }

    @Test @Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
    fun portraitDark() { render("np-portrait-dark", dark = true, landscape = false) }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
    fun smallPhone() { render("np-small-light", dark = false, landscape = false) }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
    fun smallPhoneHard() { render("np-small-hard-dark", dark = true, landscape = false, hard = true) }

    @Test @Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
    fun portraitLargeText() { render("np-portrait-font200-dark", dark = true, landscape = false, fontScale = 2f) }

    @Test @Config(sdk = [34], qualifiers = "w850dp-h392dp-land-xhdpi")
    fun landscape() { render("np-land-light", dark = false, landscape = true) }

    @Test @Config(sdk = [34], qualifiers = "w640dp-h360dp-land-xhdpi")
    fun landscapeSmallDark() { render("np-land-small-dark", dark = true, landscape = true) }
}
