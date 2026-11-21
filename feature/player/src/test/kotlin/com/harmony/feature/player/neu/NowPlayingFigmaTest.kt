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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.model.AudioOutput
import com.harmony.core.model.AudioOutputType
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
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

/** Figma's 68 bars in frame 12:8 (heights in dp, of a 40 dp reach). */
internal val FigmaBars = intArrayOf(
    8, 23, 32, 32, 26, 18, 14, 15, 17, 16, 9, 20, 32, 39, 36, 25, 11, 16, 19, 18, 16, 17, 22, 29, 32, 27, 14, 18, 31, 35, 30, 22,
    15, 12, 14, 15, 11, 15, 27, 37, 38, 30, 16, 13, 21, 21, 18, 16, 19, 25, 30, 28, 19, 13, 27, 35, 34, 26, 17, 11, 11, 12, 12, 11,
    22, 33, 38, 34,
)

internal val npDir: File get() = File(repoRoot, "design/figma/now-playing")

/**
 * Now Playing as the app draws it (NowPlayingBody, the screen's own
 * composable), against Figma's render of frames 12:8 (light) and 12:18
 * (dark), pixel for pixel at 393 × 918, with the frames' own song.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h1000dp-mdpi")
class NowPlayingFigmaTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(dark: Boolean, playing: Boolean = false, shuffle: Boolean = false, repeat: Boolean = false) {
        val song = designSong(1).copy(
            title = "sdp interlude", artist = "Travis Scott", album = "Birds in the Trap Sing McKnight",
            durationMs = 191_000, artworkUri = Uri.fromFile(File(npDir, "art_square.png")).toString(), bitDepth = 16,
        )
        val queue = List(17) { if (it == 0) song else designSong(it + 1L) }
        val state = PlayerState(
            currentSong = song, queue = queue, queueIndex = 0, isPlaying = playing,
            positionMs = 41_000, durationMs = 191_000,
            shuffleMode = if (shuffle) ShuffleMode.SMART else ShuffleMode.OFF,
            repeatMode = if (repeat) RepeatMode.ALL else RepeatMode.OFF,
            audioOutput = AudioOutput(AudioOutputType.SPEAKER, null),
        )
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                val neu = neuPalette()
                Column(Modifier.size(393.dp, 918.dp).background(neu.ground)) {
                    Spacer(Modifier.height(34.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        NowPlayingBody(
                            state = state, song = song, landscape = false, palette = neu.asPlayerPalette(), isFavorite = false,
                            audioInfo = MiniPlayerAudioInfo(format = "FLAC", bitrateKbps = 830, sampleRateHz = 44_100, isLossless = true),
                            output = OutputChoice(null, false, null), journeyProgress = null,
                            smartStyle = SmartShuffleStyle.BALANCED, progress = 41f / 191f, dragFraction = null,
                            waveform = FloatArray(FigmaBars.size) { FigmaBars[it] / 40f }, playScale = 1f,
                            cb = NowPlayingCallbacks(onEqualizer = {}),
                            steer = SteerCallbacks(steerable = false, energy = null, mood = null),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    private fun capture(): Bitmap {
        repeat(10) { Thread.sleep(100); rule.mainClock.advanceTimeBy(400); rule.waitForIdle() }
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(full)) }
        return Bitmap.createBitmap(full, 0, 0, 393, 918)
    }

    /** The frame's sections (y ranges), named, for the report. */
    private val sections = listOf(
        "header" to (34 until 110), "record" to (110 until 398), "details" to (398 until 548),
        "waveform" to (548 until 642), "controls" to (642 until 756), "mix" to (756 until 838), "tools" to (838 until 898),
    )

    private fun compareTo(figmaName: String, name: String): Map<String, Double> {
        val figma = BitmapFactory.decodeFile(File(npDir, figmaName).path)
        val actual = capture()
        val dir = File(repoRoot, "build/shots/np").apply { mkdirs() }
        val diff = Bitmap.createBitmap(393 * 3, 918, Bitmap.Config.ARGB_8888)
        val sums = DoubleArray(sections.size)
        val counts = IntArray(sections.size)
        for (y in 0 until 918) for (x in 0 until 393) {
            val a = figma.getPixel(x, y)
            val b = actual.getPixel(x, y)
            diff.setPixel(x, y, a)
            diff.setPixel(x + 393, y, b)
            val d = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)), abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
            val v = (d * 4).coerceAtMost(255)
            diff.setPixel(x + 786, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            val s = sections.indexOfFirst { y in it.second }
            if (s >= 0) { sums[s] += d.toDouble(); counts[s]++ }
        }
        File(dir, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "$name-figma-vs-compose-diff.png").outputStream().use { diff.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val result = sections.indices.associate { sections[it].first to sums[it] / counts[it] }
        val total = sums.sum() / counts.sum()
        val report = "$name: mean channel delta %.2f  ".format(total) + result.entries.joinToString("  ") { "%s %.2f".format(it.key, it.value) }
        File(dir, "$name.txt").writeText(report + "\n")
        println(report)
        return result + ("all" to total)
    }

    @Test fun light() {
        render(dark = false)
        val r = compareTo("np_light.png", "np-light")
        assertTrue(r.toString(), r.getValue("all") < 6.0)
    }

    @Test fun dark() {
        render(dark = true)
        val r = compareTo("np_dark.png", "np-dark")
        // Dark's play button and surround are the light frame's newer ones (by request), so that section differs on purpose.
        assertTrue(r.toString(), r.getValue("all") < 7.0)
    }
}
