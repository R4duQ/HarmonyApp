package com.harmony.feature.downloads

import android.graphics.Bitmap
import android.net.Uri
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import com.harmony.core.model.Song
import java.io.File

/** Writes the rendered root to build/shots/<name>.png at the repository root. */
internal fun AndroidComposeTestRule<*, ComponentActivity>.designShot(name: String) {
    // Covers load off the main thread; give them a moment, then settle. The
    // clock is advanced by hand (autoAdvance off) so a spinning record never
    // keeps the test from going idle.
    repeat(8) { Thread.sleep(120); mainClock.advanceTimeBy(500); waitForIdle() }
    val view = activity.findViewById<android.view.View>(android.R.id.content)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { view.draw(Canvas(bitmap)) }
    val dir = File(System.getProperty("user.dir"), "../../build/shots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

