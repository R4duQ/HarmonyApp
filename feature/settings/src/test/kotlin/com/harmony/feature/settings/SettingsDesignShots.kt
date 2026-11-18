package com.harmony.feature.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.ui.theme.HarmonyTheme
import com.harmony.domain.library.gateway.LibraryWriteGateway
import com.harmony.domain.library.gateway.MediaScanGateway
import com.harmony.domain.library.model.ScanKey
import com.harmony.domain.library.model.ScannedTrack
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The whole Settings list in one tall window, light, with default settings
 * (online mixes off), to review its sections and order. Output:
 * build/shots/settings-light.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h3000dp-xhdpi")
class SettingsDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun light() {
        val scanner = object : MediaScanGateway {
            override fun scan(knownKeys: Collection<ScanKey>, force: Boolean) = emptyFlow<com.harmony.domain.library.model.ScanEvent>()
            override fun changes() = emptyFlow<Unit>()
        }
        val writer = object : LibraryWriteGateway {
            override suspend fun currentScanKeys(): List<ScanKey> = emptyList()
            override suspend fun upsert(tracks: List<ScannedTrack>) {}
            override suspend fun removeByUris(uris: List<String>) {}
        }
        val vm = SettingsViewModel(SettingsRepository(rule.activity.applicationContext), ScanLibraryUseCase(scanner, writer))
        rule.mainClock.autoAdvance = false
        rule.setContent { HarmonyTheme(darkTheme = false, dynamicColors = false) { SettingsScreen(onBack = {}, viewModel = vm) } }
        repeat(8) { Thread.sleep(120); rule.mainClock.advanceTimeBy(500); rule.waitForIdle() }
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File(System.getProperty("user.dir"), "../../build/shots").apply { mkdirs() }
        File(dir, "settings-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
