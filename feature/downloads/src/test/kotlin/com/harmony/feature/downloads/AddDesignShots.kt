package com.harmony.feature.downloads

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.room.StatusLine
import com.harmony.core.ui.room.StatusTone
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Add in light, dark and at 200% text, and where each door leads. Output: build/shots/add-*.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
class AddDesignShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(dark: Boolean, name: String?, fontScale: Float = 1f, actions: AddActions = AddActions()) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HarmonyTheme(darkTheme = dark, dynamicColors = false, reducedMotion = true) {
                    Box(Modifier.size(392.dp, 850.dp)) {
                        AddScreen(actions = actions, inProgress = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatusLine(StatusTone.Waiting, "Tidal wants a quick check in your browser.",
                                    body = "Your download is paused and will continue by itself.", action = "Open browser", onAction = {})
                                StatusLine(StatusTone.Success, "Added to your library: Blue in Green")
                            }
                        })
                    }
                }
            }
        }
        if (name != null) rule.designShot(name)
    }

    @Test fun light() = render(false, "add-light")
    @Test fun dark() = render(true, "add-dark")
    @Test fun largeText() = render(false, "add-light-font200", fontScale = 2f)

    @Test fun doorsLeadWhereTheySay() {
        val log = mutableListOf<String>()
        render(false, null, actions = AddActions(
            onIdentify = { log += "identify" }, onSpotify = { log += "spotify" },
            onDiscover = { log += "discover" }, onComputer = { log += "computer" },
        ))
        rule.mainClock.autoAdvance = true
        listOf("Identify a song", "From Spotify", "Discover new music", "From your computer").forEach {
            rule.onNodeWithText(it).performClick()
        }
        assertEquals(listOf("identify", "spotify", "discover", "computer"), log)
    }
}
