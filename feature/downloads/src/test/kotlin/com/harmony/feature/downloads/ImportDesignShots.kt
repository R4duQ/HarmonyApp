package com.harmony.feature.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.component.greenPalette
import com.harmony.core.ui.theme.CyberGlassColors
import com.harmony.core.ui.theme.CyberGlassPalette
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * YT Converter with a pasted link, and the Spotify import's selection card
 * over four tracks in four states, with fixed synthetic data.
 * Output: build/shots/yt-*.png, spotify-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h1100dp-xhdpi")
class ImportDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = false, dynamicColors = false, reducedMotion = true) {
                Column(Modifier.fillMaxSize().background(com.harmony.core.ui.theme.Room.ground), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
            }
        }
        repeat(6) { Thread.sleep(120); rule.mainClock.advanceTimeBy(500); rule.waitForIdle() }
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File(System.getProperty("user.dir"), "../../build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun spotifyGuide() = shot("spotify-guide") {
        SpotifyTransferContent(SpotifyPlaylistTransferState(clientId = ""), SpotifyTransferActions())
    }

    @Test fun spotifyPlaylists() = shot("spotify-playlists") {
        SpotifyTransferContent(
            SpotifyPlaylistTransferState(
                connected = true,
                playlists = listOf(
                    SpotifyPlaylistSummary("p1", "Road Trip", "", "radu", 40, null, null, null, canImportItems = true),
                    SpotifyPlaylistSummary("p2", "Discover Weekly", "", "Spotify", 30, null, null, null, canImportItems = false),
                ),
            ),
            SpotifyTransferActions(),
        )
    }

    @Test fun spotifySelection() = shot("spotify-selection") {
        fun t(i: Int, title: String, status: PlaylistTransferTrackStatus, local: Long? = null, detail: String? = null) = PlaylistTransferTrack(
            SpotifyPlaylistTrack("id$i", title, "Mira Sol", "Harbour Lights", 200_000, null, null, null, i), localSongId = local, status = status, detail = detail,
        )
        val tracks = listOf(
            t(0, "Night Ferry", PlaylistTransferTrackStatus.IN_LIBRARY, local = 1),
            t(1, "Slow Weather", PlaylistTransferTrackStatus.READY),
            t(2, "Paper Moons", PlaylistTransferTrackStatus.FAILED, detail = "No source had this recording"),
            t(3, "Afterglow Street", PlaylistTransferTrackStatus.READY),
            t(4, "Glass Harbour", PlaylistTransferTrackStatus.READY),
        )
        SpotifyTransferContent(
            SpotifyPlaylistTransferState(
                connected = true,
                selectedPlaylist = SpotifyPlaylistSummary("p1", "Road Trip", "", "radu", 5, null, null, null, canImportItems = true),
                tracks = tracks,
            ),
            SpotifyTransferActions(),
        )
    }
}
