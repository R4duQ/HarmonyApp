package com.harmony.feature.player.gesture

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Stage 1: the resting player against Figma's own render of frame 01 (3:2), pixel by pixel at 1x. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureMiniStaticTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test @Config(sdk = [34], qualifiers = "w420dp-h200dp-mdpi")
    fun `at rest it matches Figma frame 01`() {
        rule.mainClock.autoAdvance = false
        rule.setContent { FrozenPlayer(MiniMode.Rest, 0f) }
        val shot = rule.grab(388, 80)
        shot.save("01-rest-compose")
        val figma = BitmapFactory.decodeFile(File(figmaDir, "frames/frame01.png").path)
        // Shapes, colours, icons, the rail and pause: everything but the type and the cover photo.
        val text = { x: Int, y: Int -> (x in 112..277 && y in 21..58) || (x in 73..111 && y in 21..59) }
        val diff = compare(figma, shot, "01-rest", skip = text)
        println("frame 01 without type and cover: mean channel delta ${"%.2f".format(diff.meanDelta)}, ${"%.2f".format(diff.offPixels)}% of ${diff.compared} px off by > 32")
        assertTrue("$diff", diff.offPixels < 0.5 && diff.meanDelta < 1.5)
        // The type: where its ink is, within 2 px of Figma's (Inter 3.019 here, Figma's own Inter there).
        for ((name, box) in listOf("title" to intArrayOf(113, 20, 276, 42, 0x132D23), "artist" to intArrayOf(113, 42, 276, 58, 0x526E60))) {
            val f = figma.inkBox(box[0], box[1], box[2], box[3], box[4])
            val c = shot.inkBox(box[0], box[1], box[2], box[3], box[4])
            println("$name ink: figma ${f.toList()} compose ${c.toList()}")
            for (i in 0..3) assertTrue("$name edge $i: ${f.toList()} vs ${c.toList()}", kotlin.math.abs(f[i] - c[i]) <= 2)
        }
        // The cover: the same photo, cropped and rounded the same way.
        val cover = compare(figma, shot, "01-cover", skip = { x, y -> !(x in 74..109 && y in 22..57) })
        println("cover: mean channel delta ${"%.2f".format(cover.meanDelta)}")
        assertTrue("$cover", cover.meanDelta < 10.0)
    }
}
