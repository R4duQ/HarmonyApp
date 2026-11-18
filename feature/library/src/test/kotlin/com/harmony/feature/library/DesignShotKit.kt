package com.harmony.feature.library

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

/** Synthetic covers generated into build/shots/covers (not shipped). */
internal fun designCover(i: Int): String? {
    val f = File(System.getProperty("user.dir"), "../../build/shots/covers/cover${i % 8}.png").canonicalFile
    return if (f.exists()) Uri.fromFile(f).toString() else null
}

private val designTitles = listOf(
    "Night Ferry", "Slow Weather", "Paper Moons", "Afterglow Street", "Glass Harbour",
    "Blue Hour Radio", "Tidewater", "Low Light Parade", "Satellite Hearts", "Winter Garden",
    "Coastline Static", "Driftwood", "Heavy Petals", "Hollow Blue", "Lantern Year", "Marigold",
)
private val designArtists = listOf("Mira Sol", "The Paper Coast", "Juno Park", "Lumen Drive", "Vesna Kay", "Harbor & Pine", "Oda Rivers")
private val designAlbums = listOf("Harbour Lights", "Soft Machines", "Open Water", "Small Hours", "Field Notes", "Evergreen", "Low Tide Letters")

/** A plausible song: invented names, real-looking formats (synthetic). */
internal fun designSong(id: Long): Song {
    val i = (id - 1).toInt()
    val lossless = i % 3 != 2
    return Song(
        id = id, uri = "content://media/$id", title = designTitles[i % designTitles.size],
        artist = designArtists[i % designArtists.size], album = designAlbums[i % designAlbums.size], albumId = (i % designAlbums.size).toLong(),
        albumArtist = null, composer = null, year = 2016 + i % 8, genre = "Dream pop", discNumber = 1,
        trackNumber = i % 12 + 1, durationMs = 168_000L + (i * 23_000L) % 150_000, bitrateKbps = if (lossless) 1411 else 320,
        sampleRateHz = if (i % 4 == 0) 96_000 else 44_100, bitDepth = if (!lossless) null else if (i % 4 == 0) 24 else 16, channels = 2,
        artworkUri = designCover(i), embeddedLyrics = null, replayGainTrackDb = null, replayGainAlbumDb = null,
    )
}
