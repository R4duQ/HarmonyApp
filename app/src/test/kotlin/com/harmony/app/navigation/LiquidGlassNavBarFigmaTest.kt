package com.harmony.app.navigation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The liquid glass bar against Figma: the reference render (8:2), and every
 * frame of the animated twin's 5.4 s timeline (8:4: Listen → Library → Add
 * → Listen), with the reference's own tabs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w393dp-h600dp-mdpi")
class LiquidGlassNavBarFigmaTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val root = File(System.getProperty("user.dir"), "..").canonicalFile
    private val dir = File(root, "design/figma/navbar")
    private val out = File(root, "build/shots/navbar").apply { mkdirs() }
    private val tabs = listOf(
        GlassTab("Listen", GlassGlyph.Listen), GlassTab("Library", GlassGlyph.Library), GlassTab("Add", GlassGlyph.Add),
    )
    private var selected by mutableIntStateOf(0)

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = true, dynamicColors = false) {
                Column(Modifier.size(393.dp, 132.dp).background(Color(0xFF111411))) {
                    Spacer(Modifier.height(22.dp))
                    LiquidGlassNavBar(tabs, selected, onSelect = { selected = it }, gestureInset = 24.dp)
                }
            }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    private fun grab(): Bitmap {
        rule.waitForIdle()
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(full)) }
        return Bitmap.createBitmap(full, 0, 0, 393, 132)
    }

    /** Mean channel delta outside the system's home indicator (which Figma draws and the app does not). */
    private fun delta(figma: Bitmap, actual: Bitmap, name: String? = null): Double {
        var sum = 0.0; var n = 0
        val diff = name?.let { Bitmap.createBitmap(393 * 3, 132, Bitmap.Config.ARGB_8888) }
        for (y in 0 until 132) for (x in 0 until 393) {
            val a = figma.getPixel(x, y); val b = actual.getPixel(x, y)
            val d = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)), abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
            diff?.setPixel(x, y, a); diff?.setPixel(x + 393, y, b)
            val v = (d * 4).coerceAtMost(255)
            diff?.setPixel(x + 786, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            // The system's gesture handle (Figma draws one: 99 x 4, 12 above the foot).
            if (y in 114..122 && x in 144..250) continue
            sum += d; n++
        }
        if (name != null) File(out, "$name.png").outputStream().use { diff!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return sum / n
    }

    @Test fun reference() {
        show()
        val d = delta(BitmapFactory.decodeFile(File(dir, "navbar_ref.png").path), grab(), "navbar-ref-figma-vs-compose")
        println("navbar reference: mean channel delta %.2f".format(d))
        assertTrue("%.2f".format(d), d < 6.0)
    }

    @Test fun timeline() {
        val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() }
        assumeTrue("ffmpeg is needed to read Figma's video", ffmpeg != null)
        val frames = File(out, "video").apply { deleteRecursively(); mkdirs() }
        ProcessBuilder(ffmpeg, "-loglevel", "error", "-i", File(dir, "navbar_motion.mp4").path, "-vf", "format=rgb24,crop=393:132:0:0", File(frames, "f%03d.png").path)
            .inheritIO().start().waitFor()
        val figma = frames.listFiles()!!.filter { it.name.startsWith("f") }.sorted()
        show()
        // The test clock moves in whole 16 ms frames (asking for 33 ms moves 48): step to the frame at or before each time.
        val origin = rule.mainClock.currentTime
        fun advanceTo(ms: Long) {
            val n = ((ms - (rule.mainClock.currentTime - origin)) / 16) * 16
            if (n > 0) rule.mainClock.advanceTimeBy(n)
        }
        // The timeline's taps: Library at 13.704% of 5.4 s, Add at 39.444%, Listen at 70.741%.
        val taps = ArrayDeque(listOf(740L to 1, 2130L to 2, 3820L to 0))
        val report = StringBuilder()
        var worst = 0.0
        figma.forEachIndexed { i, file ->
            val at = (i * 1000L) / 30
            while (taps.isNotEmpty() && taps.first().first <= at) {
                val (tapAt, tab) = taps.removeFirst()
                advanceTo(tapAt)
                rule.runOnIdle { selected = tab }
                rule.waitForIdle()
            }
            advanceTo(at)
            val shot = grab()
            File(frames, "app%03d.png".format(i + 1)).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val d = delta(BitmapFactory.decodeFile(file.path), shot, if (i % 15 == 0) "navbar-t%04d".format(at) else null)
            worst = maxOf(worst, d)
            report.append("%3d %5d ms  %.2f\n".format(i, at, d))
        }
        File(out, "timeline.txt").writeText(report.toString())
        println("navbar timeline: ${figma.size} frames, worst mean channel delta %.2f".format(worst))
        assertTrue("worst %.2f".format(worst), worst < 8.0)
    }

    /** Harmony's own three tabs, in light and dark, in English and with the longest Romanian label. */
    @Test fun harmonyTabs() {
        rule.mainClock.autoAdvance = false
        val sets = listOf(
            listOf("Listen", "Library", "Add"),
            listOf("Ascultă", "Bibliotecă", "Adaugă"),
        )
        rule.setContent {
            Column {
                sets.forEachIndexed { k, names ->
                    for (dark in listOf(false, true)) HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                        Column(Modifier.size(393.dp, 138.dp).background(com.harmony.core.ui.theme.Room.ground)) {
                            Spacer(Modifier.height(16.dp))
                            LiquidGlassNavBar(
                                listOf(GlassTab(names[0], GlassGlyph.Listen), GlassTab(names[1], GlassGlyph.Library), GlassTab(names[2], GlassGlyph.Add)),
                                selectedIndex = if (dark) 1 else k * 2, onSelect = {}, gestureInset = 24.dp,
                            )
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(full)) }
        File(out, "harmony-tabs.png").outputStream().use { Bitmap.createBitmap(full, 0, 0, 393, 552).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
