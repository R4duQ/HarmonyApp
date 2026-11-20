package com.harmony.feature.player.gesture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs

/** The repository root, from the module's test working directory. */
internal val repoRoot: File get() = File(System.getProperty("user.dir"), "../..").canonicalFile

internal val figmaDir: File get() = File(repoRoot, "design/figma/mini-player")

internal val midnight = MiniTrack(key = "midnight", title = "Midnight City", artist = "Luma & The Echo", artwork = "midnight")
internal val solar = MiniTrack(key = "solar", title = "Solar Drift", artist = "NOVA", artwork = "solar")

/** Figma frame 01: 90 of 138 dp. */
internal const val FIGMA_PROGRESS = 90f / 138f

private val art = HashMap<String, androidx.compose.ui.graphics.ImageBitmap>()

/** The design's own sample covers (design/figma/mini-player/art). */
@Composable
internal fun FigmaArtwork(track: MiniTrack, modifier: Modifier) {
    val name = track.artwork as? String ?: return
    val bitmap = art.getOrPut(name) { BitmapFactory.decodeFile(File(figmaDir, "art/$name.jpg").path).asImageBitmap() }
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

internal val labels = GestureMiniLabels(
    nowPlaying = "Now playing: Midnight City, by Luma & The Echo", state = "Playing",
    next = "Next song", replay = "Restart song", play = "Play", pause = "Pause", open = "Open player",
)

/** The player held at [t] ms into [mode], 388 dp wide, as the frame tests and recordings draw it. */
@Composable
internal fun FrozenPlayer(
    mode: MiniMode,
    t: Float,
    current: MiniTrack = midnight,
    incoming: MiniTrack? = if (mode == MiniMode.Replay) midnight else solar,
    width: Float = 388f,
    state: MiniMotionState = rememberMiniMotionState(),
) {
    LaunchedEffect(mode, t) { state.showFrame(mode, t, if (mode == MiniMode.Rest) null else incoming) }
    Box(Modifier.size(width.dp, 80.dp)) {
        GestureMiniPlayer(
            current = current, next = solar, isPlaying = true,
            progress = { FIGMA_PROGRESS }, positionMs = { 120_000L },
            actions = GestureMiniActions(), labels = labels, state = state,
            artwork = { track, m -> FigmaArtwork(track, m) },
        )
    }
}

/** The top-left [w] x [h] px of the screen. */
internal fun AndroidComposeTestRule<*, ComponentActivity>.grab(w: Int, h: Int): Bitmap {
    // The new time is snapped in an effect, then drawn: let both happen before reading the screen.
    repeat(4) { waitForIdle(); mainClock.advanceTimeByFrame() }
    waitForIdle()
    val view = activity.findViewById<android.view.View>(android.R.id.content)
    val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { view.draw(Canvas(full)) }
    return Bitmap.createBitmap(full, 0, 0, w, h)
}

internal fun Bitmap.save(name: String): File {
    val dir = File(repoRoot, "build/shots/mini").apply { mkdirs() }
    return File(dir, "$name.png").also { f -> f.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) } }
}

/** How [actual] differs from [figma], over the pixels Figma paints opaquely. */
internal data class Diff(val meanDelta: Double, val offPixels: Double, val compared: Int)

/** Inside the player's own 388 x 80, r 40 shape (Figma renders its canvas grey around the corners). */
internal fun insidePlayer(x: Int, y: Int, w: Int = 388, h: Int = 80): Boolean {
    val r = 40.0
    val cx = when { x < r -> r; x > w - r -> w - r; else -> x.toDouble() }
    val cy = when { y < r -> r; y > h - r -> h - r; else -> y.toDouble() }
    val dx = x + 0.5 - cx
    val dy = y + 0.5 - cy
    return dx * dx + dy * dy <= (r - 1.5) * (r - 1.5)
}

internal fun compare(
    figma: Bitmap,
    actual: Bitmap,
    name: String,
    skip: (Int, Int) -> Boolean = { _, _ -> false },
): Diff {
    val w = minOf(figma.width, actual.width)
    val h = minOf(figma.height, actual.height)
    val diff = Bitmap.createBitmap(w * 3, h, Bitmap.Config.ARGB_8888)
    var sum = 0.0
    var off = 0
    var n = 0
    for (y in 0 until h) for (x in 0 until w) {
        val a = figma.getPixel(x, y)
        val b = actual.getPixel(x, y)
        diff.setPixel(x, y, a)
        diff.setPixel(x + w, y, b)
        if ((a ushr 24) < 255 || !insidePlayer(x, y, w, h) || skip(x, y)) { diff.setPixel(x + 2 * w, y, 0xFF000000.toInt()); continue }
        val d = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)), abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
        sum += d; n++
        if (d > 32) off++
        val v = (d * 4).coerceAtMost(255)
        diff.setPixel(x + 2 * w, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
    }
    diff.save("$name-figma-vs-compose-diff")
    return Diff(sum / n, off * 100.0 / n, n)
}

/** The box of the pixels in [x0, y0, x1, y1) close to [rgb] (a text colour on the capsule). */
internal fun Bitmap.inkBox(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int, tolerance: Int = 40): IntArray {
    var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
    for (y in y0 until y1) for (x in x0 until x1) {
        val p = getPixel(x, y)
        val d = abs((p shr 16 and 255) - (rgb shr 16 and 255)) + abs((p shr 8 and 255) - (rgb shr 8 and 255)) + abs((p and 255) - (rgb and 255))
        if (d < tolerance * 3) { l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y) }
    }
    return intArrayOf(l, t, r, b)
}
