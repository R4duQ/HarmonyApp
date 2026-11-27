package com.harmony.feature.player.neu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.harmony.core.model.AudioOutput
import com.harmony.core.model.AudioOutputType
import com.harmony.core.model.PlayerState
import com.harmony.core.ui.theme.HarmonyTheme
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import com.harmony.feature.player.MiniPlayerAudioInfo
import com.harmony.feature.player.NowPlayingBody
import com.harmony.feature.player.NowPlayingCallbacks
import com.harmony.feature.player.OutputChoice
import com.harmony.feature.player.SteerCallbacks
import com.harmony.feature.player.designSong
import com.harmony.feature.player.gesture.repoRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Next, tapped, as the prototype plays it: the app's frames at the times the
 * prototype reaches "Next · Apăsat" (103:1756), "Eticheta se schimbă sub
 * copertă" (75:225), "Discul nou este introdus" (75:380) and "Piesa
 * următoare în redare" (85:794), against Figma's renders of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h1000dp-mdpi")
class RecordSwapFigmaTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val songA = designSong(1).copy(
        title = "sdp interlude", artist = "Travis Scott", album = "Birds in the Trap Sing McKnight",
        durationMs = 191_000, artworkUri = Uri.fromFile(File(npDir, "cover_a.png")).toString(), bitDepth = 16,
    )
    private val songB = designSong(2).copy(
        title = "$$$ (feat. Normani)", artist = "Gunna", album = "One of Wun",
        durationMs = 181_000, artworkUri = Uri.fromFile(File(npDir, "cover_b.png")).toString(), bitDepth = 16,
    )

    private fun stateOf(song: com.harmony.core.model.Song, index: Int, positionMs: Long) = PlayerState(
        currentSong = song, queue = List(18) { when (it) { 0 -> songA; 1 -> songB; else -> designSong(it + 1L) } }, queueIndex = index,
        isPlaying = true, positionMs = positionMs, durationMs = song.durationMs,
        audioOutput = AudioOutput(AudioOutputType.SPEAKER, null),
    )

    private fun capture(): Bitmap {
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(full)) }
        return Bitmap.createBitmap(full, 0, 0, 393, 918)
    }

    private fun delta(figmaName: String, shot: Bitmap, name: String): Double {
        val figma = BitmapFactory.decodeFile(File(npDir, "swap/$figmaName").path)
        var sum = 0.0; var n = 0
        // The page from the header rule to the tools (the frames' status bars are pictures).
        for (y in 34 until 898) for (x in 0 until 393) {
            val a = figma.getPixel(x, y); val b = shot.getPixel(x, y)
            sum += maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)), abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255))); n++
        }
        val dir = File(repoRoot, "build/shots/np/swap").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return sum / n
    }

    private fun run(dark: Boolean, checks: List<Triple<Long, String, Double>>) {
        var state by mutableStateOf(stateOf(songA, 0, 0))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                val neu = neuPalette()
                Column(Modifier.size(393.dp, 918.dp).background(neu.ground)) {
                    Spacer(Modifier.height(34.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        NowPlayingBody(
                            state = state, song = state.currentSong, landscape = false, palette = neu.asPlayerPalette(), isFavorite = false,
                            audioInfo = MiniPlayerAudioInfo(format = "FLAC", bitrateKbps = 830, sampleRateHz = 44_100, isLossless = true),
                            output = OutputChoice(null, false, null), journeyProgress = null,
                            smartStyle = SmartShuffleStyle.BALANCED, progress = 0f, dragFraction = null,
                            waveform = FloatArray(FigmaBars.size) { FigmaBars[it] / 34.3f }, playScale = 1f,
                            cb = NowPlayingCallbacks(onEqualizer = {}, onNext = { state = stateOf(songB, 1, 0) }),
                            steer = SteerCallbacks(steerable = false, energy = null, mood = null),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        // Let the images load and the first spin settle, then hold the label upright as the frames show it.
        repeat(6) { Thread.sleep(100); rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
        rule.onNodeWithContentDescription("Next").performClick()
        val start = rule.mainClock.currentTime
        val report = StringBuilder()
        var failed = false
        for ((at, figma, limit) in checks) {
            while (rule.mainClock.currentTime - start < at) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
            Thread.sleep(150); rule.waitForIdle()
            val d = delta(figma, capture(), "${if (dark) "dark" else "light"}-${figma.removeSuffix(".png")}")
            report.append("%-28s t=%4d  %.2f (limit %.1f)\n".format(figma, rule.mainClock.currentTime - start, d, limit))
            if (d >= limit) failed = true
        }
        println("record swap ${if (dark) "dark" else "light"}:\n$report")
        File(repoRoot, "build/shots/np/swap/${if (dark) "dark" else "light"}.txt").writeText(report.toString())
        assertTrue(report.toString(), !failed)
    }

    @Test fun light() = run(
        dark = false,
        checks = listOf(
            Triple(144L, "light_1_next_pressed.png", 5.0),
            Triple(256L, "light_2_label_changed.png", 5.0),
            Triple(464L, "light_3_inserted.png", 5.0),
            Triple(736L, "light_4_next_playing.png", 5.0),
        ),
    )

    @Test fun dark() = run(
        dark = true,
        checks = listOf(
            Triple(256L, "dark_2_label_changed.png", 5.0),
            Triple(736L, "dark_4_next_playing.png", 5.0),
        ),
    )

    /** The details open from the format key in the prototype's steps, over the time card, and close from ×. */
    @Test fun detailsOpenOverTheTimeCard() {
        val state = stateOf(songA, 0, 41_000)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = false, dynamicColors = false) {
                val neu = neuPalette()
                Column(Modifier.size(393.dp, 918.dp).background(neu.ground)) {
                    Spacer(Modifier.height(34.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        NowPlayingBody(
                            state = state, song = state.currentSong, landscape = false, palette = neu.asPlayerPalette(), isFavorite = false,
                            audioInfo = MiniPlayerAudioInfo(format = "FLAC", bitrateKbps = 830, sampleRateHz = 44_100, isLossless = true),
                            output = OutputChoice(null, false, null), journeyProgress = null,
                            smartStyle = SmartShuffleStyle.BALANCED, progress = 41f / 191f, dragFraction = null,
                            waveform = FloatArray(FigmaBars.size) { FigmaBars[it] / 34.3f }, playScale = 1f,
                            cb = NowPlayingCallbacks(onEqualizer = {}),
                            steer = SteerCallbacks(steerable = false, energy = null, mood = null),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        repeat(4) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
        // Previous says it replays: the song is 41 s in.
        rule.onNodeWithContentDescription("Back to the start").assertExists()
        rule.onNodeWithContentDescription("Lossless", substring = true).performClick()
        repeat(50) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
        val card = rule.onNodeWithContentDescription("Audio details", substring = true).fetchSemanticsNode()
        val wave = rule.onNodeWithContentDescription("Playback position").fetchSemanticsNode()
        val d = card.layoutInfo.density.density
        // The card sits 6 dp above the time card (Figma: 520 against 526); the waveform is 78 dp into the time card.
        val cardTop = card.boundsInWindow.top / d
        val timeCardTop = wave.boundsInWindow.top / d - 78f
        assertTrue("card top $cardTop, time card top $timeCardTop", abs(cardTop - (timeCardTop - 6f)) < 1.5f)
        assertTrue("card left ${card.boundsInWindow.left / d}", abs(card.boundsInWindow.left / d - 24f) < 1.5f)
        rule.onNodeWithContentDescription("Audio details", substring = true).assertExists()
        rule.onNodeWithContentDescription("Close audio details").performClick()
        repeat(12) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
        rule.onNodeWithContentDescription("Audio details", substring = true).assertDoesNotExist()
    }

    /** Playing, the label turns a quarter every 780 ms; paused, it turns back upright and the highlights go, in 180 ms. */
    @Test fun labelSpinsAndStopsUpright() {
        var playing by mutableStateOf(true)
        lateinit var motion: NowPlayingMotion
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = false, dynamicColors = false) {
                motion = rememberNowPlayingMotion(songA, 191_000, playing, 0, 0f)
            }
        }
        repeat(3) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
        val a0 = motion.spin.value
        val t0 = rule.mainClock.currentTime
        while (rule.mainClock.currentTime - t0 < 780) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
        val turned = motion.spin.value - a0
        assertTrue("turned $turned in ${rule.mainClock.currentTime - t0} ms", abs(turned - 90f * (rule.mainClock.currentTime - t0) / 780f) < 3f)
        assertTrue(motion.gloss.value > 0.99f && motion.playLook.value > 0.99f)
        playing = false
        repeat(16) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
        assertTrue("angle ${motion.spin.value}", motion.spin.value == 0f)
        assertTrue(motion.gloss.value < 0.01f && motion.playLook.value < 0.01f)
    }
}
