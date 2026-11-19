package com.harmony.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.harmony.core.model.AudioOutput
import com.harmony.core.model.AudioOutputType
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.ui.theme.HarmonyTheme
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Accessibility review A3, A8, A9: the mini player is one item with its
 * actions, and the record answers taps and screen-reader actions, so no
 * gesture is the only way to play, pause or change the song.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
class PlayerAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val log = mutableListOf<String>()
    private val song = designSong(1)

    @Test fun theMiniPlayerIsOneItemWithItsActions() {
        rule.setContent {
            HarmonyTheme(darkTheme = false, dynamicColors = false, reducedMotion = true) {
                MiniPlayerCard(
                    MiniPlayerUi(song, isPlaying = true, positionMs = 10_000, durationMs = 100_000, shuffleOn = false,
                        repeatMode = RepeatMode.OFF, deviceLabel = "Phone", deviceIcon = Icons.Rounded.Headphones, upNext = emptyList()),
                    MiniPlayerActions(
                        onExpand = { log += "open" }, onPlayPause = { log += "play" },
                        onNext = { log += "next" }, onPrevious = { log += "previous" },
                    ),
                )
            }
        }
        val card = rule.onNodeWithTag(MiniPlayerTags.CARD).fetchSemanticsNode()
        val description = card.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description, description.startsWith("Now playing: ${song.title}, by ${song.artist}"))
        val actions = card.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Pause", "Next song", "Previous song"), actions.map { it.label })
        actions.forEach { it.action() }
        rule.onNodeWithTag(MiniPlayerTags.CARD).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("play", "next", "previous", "open"), log)
    }

    @Test fun theRecordPlaysPausesAndSkips() {
        val state = PlayerState(
            currentSong = song, queue = listOf(song, designSong(2)), queueIndex = 0, isPlaying = true,
            positionMs = 0, durationMs = song.durationMs, shuffleMode = ShuffleMode.OFF,
            audioOutput = AudioOutput(AudioOutputType.SPEAKER, "Phone"),
        )
        // The waveform listens to live audio forever; the clock is moved by hand.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = false, dynamicColors = false, reducedMotion = true) {
                Column(Modifier.fillMaxSize()) {
                    NowPlayingBody(
                        state = state, song = song, landscape = false, palette = playerPalette(), isFavorite = false,
                        audioInfo = null, output = OutputChoice(null, false, null), journeyProgress = null,
                        smartStyle = SmartShuffleStyle.BALANCED, progress = 0f, dragFraction = null,
                        waveform = FloatArray(10) { 0.5f }, playScale = 1f,
                        cb = NowPlayingCallbacks(
                            onPlayPause = { log += "play" }, onNext = { log += "next" },
                            onPrevious = { log += "previous" }, onQueue = { log += "queue" },
                        ),
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        val record = rule.onNodeWithContentDescription("Cover of ${song.album}")
        record.performClick()
        record.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().forEach { it.action() }
        assertEquals(listOf("play", "next", "previous", "queue"), log)
    }
}
