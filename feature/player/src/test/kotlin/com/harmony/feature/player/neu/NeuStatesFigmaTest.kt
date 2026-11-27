package com.harmony.feature.player.neu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.theme.HarmonyTheme
import com.harmony.feature.player.gesture.repoRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
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
 * Every key state against Figma's state panels (15:2 light, 15:116 dark),
 * at the panels' own positions, and the press timing against the
 * prototype's: Pressed in 90 ms, held 90, back in 180.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h560dp-mdpi")
class NeuStatesFigmaTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** name, (x, y) and size of each variant in the panel. */
    private val slots = listOf(
        Triple("back-rest", 35 to 92, 54 to 54), Triple("back-pressed", 126 to 92, 54 to 56),
        Triple("skip-rest", 242 to 92, 54 to 54), Triple("skip-pressed", 333 to 92, 54 to 56),
        Triple("shuffle-off", 449 to 92, 40 to 40), Triple("shuffle-on", 540 to 92, 40 to 40),
        Triple("repeat-off", 656 to 92, 40 to 40), Triple("repeat-on", 747 to 92, 40 to 40),
        Triple("lossless-rest", 34 to 186, 132 to 44), Triple("lossless-pressed", 186 to 186, 132 to 44),
        Triple("play-paused", 36 to 324, 76 to 76), Triple("play-playing", 162 to 324, 76 to 78),
        Triple("topback-rest", 316 to 324, 42 to 42), Triple("topback-pressed", 442 to 324, 42 to 44),
        Triple("more-rest", 576 to 324, 42 to 42), Triple("more-pressed", 702 to 324, 42 to 44),
    )

    @Composable
    private fun Panel(p: NeuPalette, panel: androidx.compose.ui.graphics.Color) {
        Box(Modifier.size(850.dp, 450.dp).background(panel)) {
            @Composable fun at(x: Int, y: Int, content: @Composable () -> Unit) = Box(Modifier.offset(x.dp, y.dp)) { content() }
            for ((pressed, x) in listOf(0f to 35, 1f to 126)) at(x, 92) {
                NeuPreviousButton(0f, {}, "Previous", p, pressedOverride = pressed)
            }
            for ((pressed, x) in listOf(0f to 242, 1f to 333)) at(x, 92) {
                NeuPressButton({}, "Next", 54.dp, 54.dp, 27.dp, p, pressedShiftX = 2.5.dp, pressedAlpha = 0.9f, pressedOverride = pressed) { NeuIcon(NeuGlyph.Next, p.ink) }
            }
            at(449, 92) { NeuToggleButton(false, NeuGlyph.Shuffle, "", p, {}) }
            at(540, 92) { NeuToggleButton(true, NeuGlyph.Shuffle, "", p, {}) }
            at(656, 92) { NeuToggleButton(false, NeuGlyph.Repeat, "", p, {}, dot = p.repeatDot) }
            at(747, 92) { NeuToggleButton(true, NeuGlyph.Repeat, "", p, {}, dot = p.repeatDot) }
            at(34, 186) { NeuFormatPill("Lossless", "", p, {}, pressedOverride = 0f) }
            at(186, 186) { NeuFormatPill("Lossless", "", p, {}, pressedOverride = 1f) }
            at(30, 318) { NeuPlayButton(0f, {}, "", p.let { NeuPalette0(it) }) }
            at(156, 318) { NeuPlayButton(1f, {}, "", p.let { NeuPalette0(it) }) }
            for ((pressed, x) in listOf(0f to 316, 1f to 442)) at(x, 324) {
                NeuPressButton({}, "Back", 42.dp, 42.dp, 21.dp, p, pressedShiftX = (-2.5).dp, pressedOverride = pressed) { NeuIcon(NeuGlyph.Back, p.ink) }
            }
            for ((pressed, x) in listOf(0f to 576, 1f to 702)) at(x, 324) {
                NeuPressButton({}, "More", 42.dp, 42.dp, 21.dp, p, pressedAlpha = 0.6f, pressedOverride = pressed) { NeuIcon(NeuGlyph.More, p.ink) }
            }
        }
    }

    /** The panel shows play / pause without its ring. */
    private fun NeuPalette0(p: NeuPalette) = NeuPalette(
        p.dark, p.ground, p.surface, p.line, p.pressed, p.ink, p.muted, p.accent, p.waveRest, p.repeatDot, p.pill, p.pillLine,
        p.playPaused, p.playPlaying, p.playIcon, null, p.record, p.grooveOdd, p.grooveEven, p.recordShadow, p.scrim,
    )

    private fun check(dark: Boolean, figmaName: String) {
        rule.mainClock.autoAdvance = false
        val figma = BitmapFactory.decodeFile(File(npDir, figmaName).path)
        val panel = androidx.compose.ui.graphics.Color(figma.getPixel(425, 440))
        rule.setContent { HarmonyTheme(darkTheme = dark, dynamicColors = false) { Panel(neuPalette(), panel) } }
        repeat(4) { rule.mainClock.advanceTimeBy(200); rule.waitForIdle() }
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val shot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(shot)) }
        val report = StringBuilder()
        var worst = 0.0
        for ((name, xy, size) in slots) {
            // The key and 4 dp around it.
            var sum = 0.0; var n = 0
            for (y in xy.second - 4 until xy.second + size.second + 4) for (x in xy.first - 4 until xy.first + size.first + 4) {
                val a = figma.getPixel(x, y); val b = shot.getPixel(x, y)
                sum += maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)), abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255))); n++
            }
            val d = sum / n
            worst = maxOf(worst, d)
            report.append("%-16s %.2f\n".format(name, d))
        }
        val out = File(repoRoot, "build/shots/np").apply { mkdirs() }
        File(out, "states-${if (dark) "dark" else "light"}.png").outputStream().use { Bitmap.createBitmap(shot, 0, 0, 850, 450).compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "states-${if (dark) "dark" else "light"}.txt").writeText(report.toString())
        println("states ${if (dark) "dark" else "light"}: worst %.2f\n$report".format(worst))
        assertTrue(report.toString(), worst < 5.0)
    }

    @Test fun light() = check(false, "states_light.png")
    @Test fun dark() = check(true, "states_dark.png")

    @Test fun aTapPlaysThePrototypesPressAndRelease() {
        val samples = mutableListOf<Pair<Long, Float>>()
        lateinit var source: MutableInteractionSource
        lateinit var scope: CoroutineScope
        var p = 0f
        rule.mainClock.autoAdvance = false
        rule.setContent {
            source = remember { MutableInteractionSource() }
            scope = rememberCoroutineScope()
            p = pressProgress(source, PressTiming.Standard)
        }
        rule.waitForIdle()
        rule.runOnIdle {
            scope.launch {
                val press = PressInteraction.Press(Offset.Zero)
                source.emit(press)
                source.emit(PressInteraction.Release(press))
            }
        }
        rule.waitForIdle()
        val start = rule.mainClock.currentTime
        repeat(30) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            samples += (rule.mainClock.currentTime - start) to p
        }
        fun at(ms: Long) = samples.first { it.first >= ms }.second
        // Down by 90 (a frame's grace), held to 180, on its way back at 270, at rest by 360.
        assertEquals(1f, at(112), 0.001f)
        assertEquals(1f, at(176), 0.001f)
        assertTrue(samples.toString(), at(272) in 0.05f..0.95f)
        assertEquals(0f, at(400), 0.001f)
    }
}
