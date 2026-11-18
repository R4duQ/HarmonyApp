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

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = false, dynamicColors = false) {
                Column(Modifier.fillMaxSize().background(CyberGlassColors.Page)) { content() }
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
    private fun SpotiFlac(state: DownloadsViewModel.State) = SpotiFlacDownloadsLayout(
        state = state, palette = CyberGlassPalette, context = LocalContext.current,
        onQueryChange = {}, onSearch = {}, onSelect = {}, onOutputFormatChange = {}, onDownload = {}, onCancelDownload = {},
        onPrepareVerification = {}, onVerificationOpened = {}, onVerificationLaunchFailed = {}, onRefreshVerification = {},
        onCheckVerificationAndRetry = {}, onToggleErrorDetails = {},
    )

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

    @Test fun soulseekSignedIn() = shot("dl-soulseek") {
        SoulseekDownloadsLayout(
            state = DownloadsViewModel.State(soulseekConnection = SoulseekConnectionState(SoulseekConnectionStatus.CONNECTED, "radu", "Connected")),
            palette = CyberGlassPalette,
            onUsernameChange = {}, onPasswordChange = {}, onConnect = {}, onDisconnect = {}, onQueryChange = {},
            onSearchModeChange = {}, onFormatPreferenceChange = {}, onOnlyFreeSlotsChange = {}, onSearch = {}, onDownload = {},
            onDownloadBest = {}, onCancelDownload = {}, onPickDownloadFolder = {}, onUseDefaultDownloadFolder = {},
            onPickUploadFolder = {}, onRefreshUploadFolder = {}, onClearUploadFolder = {},
        )
    }
}
