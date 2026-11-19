package com.harmony.feature.downloads

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * The Downloads layouts under DownloadsScreen's header, with fixed synthetic
 * state, in one tall window so the order of the blocks shows: SpotiFLAC
 * ready with results, SpotiFLAC downloading, Soulseek signed in.
 * Output: build/shots/dl-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h2000dp-xhdpi")
class DownloadsDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val results = listOf(
        SpotiFlacSearchCandidate("1", "Mira Sol", "Night Ferry", "Harbour Lights", 168_000, null, 98, source = "Tidal"),
        SpotiFlacSearchCandidate("2", "Mira Sol", "Night Ferry (Live)", "Live at the Pier", 201_000, null, 81, source = "Qobuz"),
        SpotiFlacSearchCandidate("3", "The Paper Coast", "Night Ferries", "Soft Machines", 190_000, null, 54, source = "Deezer"),
    )

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false, reducedMotion = true) {
                Column(Modifier.fillMaxSize().background(com.harmony.core.ui.theme.Room.ground)) { content() }
            }
        }
        repeat(6) { Thread.sleep(120); rule.mainClock.advanceTimeBy(500); rule.waitForIdle() }
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File(System.getProperty("user.dir"), "../../build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun SpotiFlac(state: DownloadsViewModel.State, mode: GetMusicMode = GetMusicMode.Songs) =
        GetMusicBody(state = state, mode = mode, onMode = {}, actions = GetMusicActions())

    @Test fun spotiflacReady() = shot("dl-spotiflac-ready") {
        SpotiFlac(DownloadsViewModel.State(query = "night ferry", spotiFlacPreflightReady = true, spotiFlacSearchResults = results))
    }

    @Test fun spotiflacDownloading() = shot("dl-spotiflac-downloading") {
        SpotiFlac(
            DownloadsViewModel.State(
                query = "night ferry", spotiFlacPreflightReady = true, spotiFlacSearchResults = results,
                spotiFlacTransfer = SpotiFlacTransferProgress(SpotiFlacStage.DOWNLOADING, 0.42f, 21_000_000, 50_000_000, "Tidal"),
            ),
        )
    }

    @Test fun spotiflacDark() = shot("dl-spotiflac-dark", dark = true) {
        SpotiFlac(DownloadsViewModel.State(query = "night ferry", spotiFlacPreflightReady = true, spotiFlacSearchResults = results))
    }

    @Test fun spotiflacNeedsCheck() = shot("dl-spotiflac-check") {
        SpotiFlac(
            DownloadsViewModel.State(
                query = "night ferry", spotiFlacSearchResults = results,
                spotiFlacVerificationProvider = "tidal-hifi", spotiFlacVerificationUrl = "https://example.invalid/verify",
            ),
        )
    }

    @Test fun spotiflacFailed() = shot("dl-spotiflac-failed") {
        SpotiFlac(
            DownloadsViewModel.State(
                query = "night ferry", spotiFlacPreflightReady = true, spotiFlacSearchResults = results,
                identifiedTrack = IdentifiedTrack(artist = "Mira Sol", title = "Night Ferry", sourceTitle = "Night Ferry"),
                failedDownloadSource = DownloadSource.SPOTIFLAC,
                error = "No lossless copy on Tidal, Qobuz, Deezer or Amazon.",
                errorDetails = "provider chain: tidal=404 qobuz=404 deezer=404 amazon=timeout",
            ),
        )
    }

    @Test fun youtubeLink() = shot("dl-youtube") {
        SpotiFlac(
            DownloadsViewModel.State(
                query = "https://youtu.be/abc", preferredDownloadSource = DownloadSource.YTCONVERTER,
                identifiedTrack = IdentifiedTrack(artist = "Mira Sol", title = "Night Ferry (Official Audio)", sourceTitle = "Night Ferry"),
            ),
            mode = GetMusicMode.YouTube,
        )
    }

    @Test fun soulseekSignedIn() = shot("dl-soulseek") {
        SpotiFlac(
            DownloadsViewModel.State(
                preferredDownloadSource = DownloadSource.SOULSEEK, query = "night ferry",
                soulseekConnection = SoulseekConnectionState(SoulseekConnectionStatus.CONNECTED, "radu", "Connected"),
                soulseekResults = listOf(
                    SoulseekSearchCandidate("a", "vinylcat", "Music\\Mira Sol\\Night Ferry.flac", 41_000_000, "flac",
                        durationSeconds = 168, sampleRate = 96_000, bitDepth = 24, freeUploadSlot = true,
                        averageSpeedBytesPerSecond = 2_000_000, queueLength = 0, score = 98, recommendation = ""),
                    SoulseekSearchCandidate("b", "harbour", "Mira Sol - Night Ferry.mp3", 7_000_000, "mp3",
                        durationSeconds = 168, bitrateKbps = 320, freeUploadSlot = false,
                        averageSpeedBytesPerSecond = 400_000, queueLength = 4, score = 60, recommendation = ""),
                ),
            ),
        )
    }

    @Test fun soulseekSignIn() = shot("dl-soulseek-signin") {
        SpotiFlac(DownloadsViewModel.State(preferredDownloadSource = DownloadSource.SOULSEEK, soulseekUsername = "radu"))
    }
}
