package com.harmony.feature.player.gesture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Stage 2: the timelines played without a gesture, frame by frame against
 * Figma's own video export of each frame (design/figma/mini-player/video,
 * 30 fps): the same instant rendered by Compose and by Figma, compared over
 * the player. Needs ffmpeg to read the videos (skipped without it).
 * Writes build/shots/mini/rec-<name>.txt and a contact sheet per timeline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureMiniRecordingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun frames(video: String): List<File> {
        val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() }
        assumeTrue("ffmpeg is needed to read Figma's video", ffmpeg != null)
        val out = File(repoRoot, "build/shots/mini/video-$video").apply { deleteRecursively(); mkdirs() }
        ProcessBuilder(ffmpeg, "-loglevel", "error", "-i", File(figmaDir, "video/$video.mp4").path, File(out, "f%03d.png").path)
            .inheritIO().start().waitFor()
        return out.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }
    }

    private fun record(mode: MiniMode, video: String) {
        val figma = frames(video)
        var at by mutableStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent { FrozenPlayer(mode, at) }
        val duration = timelineOf(mode)!!.durationMs
        val report = StringBuilder("t_ms\tmean_delta\toff_pct\n")
        val sheet = Bitmap.createBitmap(388 * 2 + 8, 80 * minOf(figma.size, 12), Bitmap.Config.ARGB_8888)
        val sheetEvery = maxOf(1, figma.size / 12)
        var worst = 0.0
        figma.forEachIndexed { i, file ->
            val t = minOf(i * 1000f / 30f, duration)
            at = t
            val shot = rule.grab(388, 80)
            val reference = BitmapFactory.decodeFile(file.path)
            val diff = compare(reference, shot, "rec-$video-%03d".format(i))
            if (diff.meanDelta < 6.0) File(repoRoot, "build/shots/mini/rec-$video-%03d-figma-vs-compose-diff.png".format(i)).delete()
            worst = maxOf(worst, diff.meanDelta)
            report.append("${t.toInt()}\t${"%.2f".format(diff.meanDelta)}\t${"%.2f".format(diff.offPixels)}\n")
            if (i % sheetEvery == 0 && i / sheetEvery < 12) {
                val row = i / sheetEvery
                val c = android.graphics.Canvas(sheet)
                c.drawBitmap(reference, 0f, row * 80f, null)
                c.drawBitmap(shot, 388f + 8f, row * 80f, null)
            }
        }
        File(repoRoot, "build/shots/mini/rec-$video.txt").writeText(report.toString())
        sheet.save("rec-$video-sheet")
        println("$video: ${figma.size} frames, worst mean channel delta ${"%.2f".format(worst)}\n$report")
        // Video compression and type rendering alone account for about 3 to 5; a wrong pose or timing shows far above.
        org.junit.Assert.assertTrue("$video: a frame is off Figma's by ${"%.2f".format(worst)}", worst < 12.0)
    }

    @Test @Config(sdk = [34], qualifiers = "w420dp-h200dp-mdpi")
    fun skip() = record(MiniMode.Skip, "skip")

    @Test @Config(sdk = [34], qualifiers = "w420dp-h200dp-mdpi")
    fun skipCompleted() = record(MiniMode.SkipCompleted, "skip_completed")

    @Test @Config(sdk = [34], qualifiers = "w420dp-h200dp-mdpi")
    fun replay() = record(MiniMode.Replay, "replay")

    @Test @Config(sdk = [34], qualifiers = "w420dp-h200dp-mdpi")
    fun replayCompleted() = record(MiniMode.ReplayCompleted, "replay_completed")
}
