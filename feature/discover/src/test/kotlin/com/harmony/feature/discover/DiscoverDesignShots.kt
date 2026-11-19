package com.harmony.feature.discover

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.theme.HarmonyTheme
import com.harmony.feature.discover.ui.DiscoveryActions
import com.harmony.feature.discover.ui.DiscoveryFlowContent
import com.harmony.feature.discover.ui.PreviewState
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Discover's three steps in the Listening Room, light and dark. Output: build/shots/discover-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
class DiscoverDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, dark: Boolean, state: com.harmony.feature.discover.ui.DiscoveryUiState) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HarmonyTheme(darkTheme = dark, dynamicColors = false, reducedMotion = true) {
                Box(Modifier.size(392.dp, 850.dp)) {
                    DiscoveryFlowContent(state, PreviewState(), DiscoveryActions(), bottomChrome = 0.dp)
                }
            }
        }
        repeat(6) { Thread.sleep(120); rule.mainClock.advanceTimeBy(500); rule.waitForIdle() }
        val view = rule.activity.findViewById<android.view.View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        val dir = File(System.getProperty("user.dir"), "../../build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun preferences() = shot("discover-preferences", false, ShotSamples.preferences())
    @Test fun songs() = shot("discover-songs", false, ShotSamples.songs())
    @Test fun songsDark() = shot("discover-songs-dark", true, ShotSamples.songs())
    @Test fun review() = shot("discover-review", false, ShotSamples.review(available = 12))
}
