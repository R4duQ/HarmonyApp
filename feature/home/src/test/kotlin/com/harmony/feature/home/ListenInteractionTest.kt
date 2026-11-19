package com.harmony.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag

import androidx.compose.ui.test.isHeading
import androidx.compose.ui.unit.dp
import com.harmony.core.model.MoodFilter
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.ui.theme.HarmonyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What each part of Listen does when tapped, and how it reads to a screen reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w392dp-h850dp-xhdpi")
class ListenInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()
    private val actions = ListenActions(
        onTogglePlayback = { log += "toggle" },
        onResume = { log += "resume-${it.id}" },
        onStartMix = { log += "mix" },
        onMood = { log += "mood-$it" },
        onOpenSmart = { log += "smart-$it" },
        onOpenAdd = { log += "add" },
        onOpenDiscover = { log += "discover" },
    )

    private fun show(state: HomeUiState) {
        rule.setContent {
            HarmonyTheme(darkTheme = false, dynamicColors = false, reducedMotion = true) {
                Box(Modifier.size(392.dp, 850.dp)) { ListenContent(state = state, actions = actions) }
            }
        }
    }

    private val playing = HomeUiState(
        loaded = true,
        nowPlaying = NowPlayingCard(designSong(1), isPlaying = true, queuePosition = 1, queueSize = 10),
    )

    @Test fun mixMyLibraryStartsTheMix() {
        show(playing)
        rule.onNodeWithText("Mix my library").performClick()
        assertEquals(listOf("mix"), log)
    }

    @Test fun aMoodChipAsksForThatMood() {
        show(playing)
        rule.onNodeWithText("Calm").performClick()
        rule.onNodeWithText("Late night").performClick()
        assertEquals(listOf("mood-${MoodFilter.CALM}", "mood-${MoodFilter.DARK}"), log)
    }

    @Test fun thePlayingCardPausesInPlace() {
        show(playing)
        rule.onNodeWithText("Pause").performClick()
        assertEquals(listOf("toggle"), log)
    }

    @Test fun whereYouStoppedResumesThatSong() {
        show(HomeUiState(loaded = true, resumeSong = designSong(7)))
        rule.onNodeWithText("Pick up where you left off").assertExists()
        rule.onNodeWithText("Resume").performClick()
        assertEquals(listOf("resume-7"), log)
    }

    @Test fun madeForYouOpensTheSmartPlaylist() {
        show(playing)
        rule.onNodeWithTag("listen").performScrollToNode(hasText("Favourites"))
        rule.onNodeWithText("Favourites").performClick()
        assertEquals(listOf("smart-${SmartPlaylistType.FAVORITES}"), log)
    }

    @Test fun anEmptyLibraryOffersOneWayIn() {
        show(HomeUiState(loaded = true, libraryEmpty = true))
        rule.onNodeWithText("Your library is empty").assertExists()
        rule.onNodeWithText("Add music").assertHasClickAction().performClick()
        assertEquals(listOf("add"), log)
    }

    @Test fun sectionsAreHeadings() {
        show(playing)
        val headings = rule.onAllNodes(isHeading()).fetchSemanticsNodes().size
        assertTrue("Listen, Start something and Made for you at least", headings >= 3)
    }
}
