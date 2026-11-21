package com.harmony.feature.player.neu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.theme.HarmonyTheme
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

/** The audio details card against frames 15:74 (light) and 15:188 (dark), where they place it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h1000dp-mdpi")
class AudioDetailsFigmaTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun check(dark: Boolean, figmaName: String) {
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false) {
                val p = neuPalette()
                Box(Modifier.size(393.dp, 918.dp).background(p.ground)) {
                    NeuAudioDetailsCard(
                        audioFields("FLAC", lossless = true, sampleRateHz = 44_100, bitDepth = 16, kbps = 830),
                        p, 345.dp, {}, Modifier.offset(24.dp, 516.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val shot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(shot)) }
        // Figma's overlay frame has nothing under the card (its canvas shows); compare inside the card's shape.
        val figma = BitmapFactory.decodeFile(File(npDir, figmaName).path)
        var sum = 0.0; var n = 0
        for (y in 516 until 620) for (x in 24 until 369) {
            val cx = x.coerceIn(24 + 17, 369 - 17).toDouble(); val cy = y.coerceIn(516 + 17, 620 - 17).toDouble()
            if ((x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy) > 15.0 * 15.0) continue
            val a = figma.getPixel(x, y); val b = shot.getPixel(x, y)
            sum += maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)), abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255))); n++
        }
        val d = sum / n
        File(repoRoot, "build/shots/np").mkdirs()
        File(repoRoot, "build/shots/np/details-${if (dark) "dark" else "light"}.png").outputStream().use {
            Bitmap.createBitmap(shot, 0, 500, 393, 140).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("audio details ${if (dark) "dark" else "light"}: mean channel delta %.2f".format(d))
        assertTrue("%.2f".format(d), d < 4.0)
    }

    @Test fun light() = check(false, "details_light.png")
    @Test fun dark() = check(true, "details_dark.png")
}
