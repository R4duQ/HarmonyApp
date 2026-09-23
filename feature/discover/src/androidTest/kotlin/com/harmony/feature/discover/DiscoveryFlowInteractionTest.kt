package com.harmony.feature.discover

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harmony.domain.library.discovery.ExplorationLevel
import com.harmony.feature.discover.ui.Connection
import com.harmony.feature.discover.ui.DiscoveryActions
import com.harmony.feature.discover.ui.DiscoveryFlowContent
import com.harmony.feature.discover.ui.DiscoveryTags
import com.harmony.feature.discover.ui.DiscoveryUiState
import com.harmony.feature.discover.ui.PreviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DiscoveryFlowInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()

    private val actions = DiscoveryActions(
        setLevel = { calls += "level:$it" }, setSize = { calls += "size:$it" }, toggleArtist = { calls += "artist:$it" },
        toggleGenre = { calls += "genre:$it" }, searchArtists = { calls += "search:$it" }, generate = { calls += "generate" },
        resume = { calls += "resume" }, changeLevel = { calls += "change:$it" }, regenerate = { calls += "regenerate" }, fill = { calls += "fill" },
        toggleKeep = { calls += "keep:$it" }, remove = { calls += "remove:$it" }, replace = { calls += "replace:$it" },
        notInterested = { calls += "decline:$it" }, moreLike = { calls += "more:$it" }, focus = { calls += "focus:$it" },
        togglePreview = { calls += "preview:${it.key}" }, backToPreferences = { calls += "back" }, goToReview = { calls += "review" },
        backFromReview = { calls += "backFromReview" }, createPlaylist = { calls += "create" }, rename = { calls += "rename:$it" },
        openBatch = { calls += "open:$it" }, startNew = { calls += "new" },
    )

    private fun show(state: DiscoveryUiState, preview: PreviewState = PreviewState(), width: Int? = null, fontScale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                val modifier = if (width != null) Modifier.width(width.dp) else Modifier
                androidx.compose.foundation.layout.Box(modifier) { DiscoveryFlowContent(state, preview, actions, bottomChrome = 150.dp) }
            }
        }
    }

    @Test fun preferencesExplainTheTasteAndPickALevel() {
        show(DiscoverySamples.preferences())
        rule.onNodeWithText("You've played Muse a lot lately").assertIsDisplayed()
        rule.onNodeWithTag(DiscoveryTags.PREFERENCES).performScrollToKey("level")
        rule.onNodeWithTag(DiscoveryTags.level(ExplorationLevel.SURPRISE_ME)).performClick()
        rule.onNodeWithTag(DiscoveryTags.GENERATE).assertIsEnabled().assertTextContains("Find songs").performClick()
        assertEquals(listOf("level:SURPRISE_ME", "generate"), calls)
    }

    @Test fun sizeCanBeChangedInStepsAndPresets() {
        show(DiscoverySamples.preferences())
        rule.onNodeWithTag(DiscoveryTags.PREFERENCES).performScrollToKey("size")
        rule.onNodeWithTag(DiscoveryTags.SIZE).assertTextContains("50 songs")
        rule.onNodeWithContentDescription("More songs").assertExists()
        rule.onNodeWithContentDescription("Fewer songs").assertExists()
        rule.onNodeWithText("75").performClick()
        assertEquals(listOf("size:75"), calls)
    }

    @Test fun withoutHistoryTheListenerPicksArtistsAndGenres() {
        show(DiscoverySamples.preferences(history = false))
        rule.onNodeWithTag(DiscoveryTags.COLD_START).assertIsDisplayed()
        rule.onNodeWithText("Jazz").performClick()
        rule.onNodeWithText("Radiohead").performClick()
        rule.onNodeWithTag(DiscoveryTags.ARTIST_SEARCH).performTextInput("o")
        assertEquals(listOf("genre:Jazz", "artist:Radiohead"), calls.take(2))
        assertTrue(calls.last().startsWith("search:") && calls.last().contains("o"))
    }

    @Test fun offlineSaysWhatStillWorksAndBuildsFromTheLibrary() {
        show(DiscoverySamples.preferences(Connection.OFFLINE))
        rule.onNodeWithTag(DiscoveryTags.OFFLINE).assertIsDisplayed()
        rule.onNodeWithTag(DiscoveryTags.GENERATE).assertTextContains("Build from my library").assertIsEnabled()
    }

    @Test fun offlineWithAnEmptyLibraryExplainsWhyNothingCanBeBuilt() {
        show(DiscoverySamples.preferences(Connection.OFFLINE).copy(libraryEmpty = true))
        rule.onNodeWithTag(DiscoveryTags.GENERATE).assertIsNotEnabled()
        rule.onNodeWithText("Offline with an empty library: connect to find new songs.").assertIsDisplayed()
    }

    @Test fun generatingShowsProgressAndNeverAcceptsASecondTap() {
        show(DiscoverySamples.preferences().copy(work = com.harmony.feature.discover.ui.DiscoveryWork.Generating("Following related artists…")))
        rule.onNodeWithTag(DiscoveryTags.WORK).assertTextContains("Following related artists…")
        rule.onNodeWithTag(DiscoveryTags.GENERATE).performClick()
        assertTrue(calls.isEmpty())
    }

    @Test fun songActionsTargetTheSongOnScreen() {
        show(DiscoverySamples.songs())
        rule.onNodeWithTag(DiscoveryTags.COUNT).assertTextContains("48 of 50 songs · 1 kept")
        rule.onNodeWithTag(DiscoveryTags.PAGER).performTouchInput { swipeLeft() }   // a fast swipe may fling more than one card
        rule.waitForIdle()
        val focused = calls.last { it.startsWith("focus:") }.removePrefix("focus:")
        assertTrue("$calls", focused != "dz:1")
        calls.clear()
        val card = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(DiscoveryTags.focus(focused)))
        listOf(DiscoveryTags.KEEP, DiscoveryTags.MORE_LIKE, DiscoveryTags.REPLACE, DiscoveryTags.NOT_INTERESTED, DiscoveryTags.REMOVE, DiscoveryTags.PREVIEW)
            .forEach { tag -> rule.onNode(androidx.compose.ui.test.hasTestTag(tag) and card).performClick() }
        // Every action names the card that settled on screen, never the one the swipe started from.
        assertEquals(listOf("keep", "more", "replace", "decline", "remove", "preview").map { "$it:$focused" }, calls)
    }

    @Test fun aReplacementInProgressDisablesThatSongsActionsOnly() {
        show(DiscoverySamples.songs().copy(replacing = setOf("dz:1")))
        rule.onNode(androidx.compose.ui.test.hasTestTag(DiscoveryTags.REPLACE) and androidx.compose.ui.test.hasAnyAncestor(
            androidx.compose.ui.test.hasTestTag(DiscoveryTags.focus("dz:1")))).assertIsNotEnabled()
        rule.onNodeWithTag(DiscoveryTags.REVIEW).assertIsEnabled()
    }

    @Test fun savedResultsAreLabelledWhenOffline() {
        show(DiscoverySamples.songs(Connection.OFFLINE))
        rule.onNodeWithTag(DiscoveryTags.SAVED).assertTextContains("Saved results", substring = true)
        rule.onNodeWithTag(DiscoveryTags.OFFLINE).assertExists()
    }

    @Test fun shortSelectionsOfferToFillUp() {
        show(DiscoverySamples.songs())
        rule.onNodeWithTag(DiscoveryTags.FILL).assertTextContains("Fill to 50").performClick()
        rule.onNodeWithTag(DiscoveryTags.REVIEW).assertTextContains("Review (48)").performClick()
        assertEquals(listOf("fill", "review"), calls.filterNot { it.startsWith("focus:") })
    }

    @Test fun partialSelectionsCreateWithTheAvailableSongsAndSayWhatHappensToTheRest() {
        show(DiscoverySamples.review(available = 12, total = 20, failed = 2))
        rule.onNodeWithText("12 of 20 in your library").assertIsDisplayed()
        rule.onNodeWithText("5 waiting · 1 downloading · 2 failed").assertIsDisplayed()
        rule.onNodeWithTag(DiscoveryTags.CREATE).assertTextContains("Create playlist with the 12 available songs").performClick()
        rule.onNodeWithText("The other 8 stay here and join the same playlist when they're downloaded.").assertIsDisplayed()
        rule.onNodeWithTag(DiscoveryTags.DOWNLOADS).assertExists()
        assertEquals(listOf("create"), calls)
    }

    @Test fun nothingAvailableDisablesCreateWithAReason() {
        show(DiscoverySamples.review(available = 0))
        rule.onNodeWithTag(DiscoveryTags.CREATE).assertIsNotEnabled()
        rule.onNodeWithTag(DiscoveryTags.CREATE_REASON).assertTextContains("None of these songs", substring = true)
    }

    @Test fun aSavedPlaylistOpensInsteadOfBeingCreatedAgain() {
        show(DiscoverySamples.review(available = 12, playlistId = -42))
        rule.onNodeWithTag(DiscoveryTags.OPEN_PLAYLIST).performClick()
        rule.onNodeWithText("Saved. The other 8 join the same playlist when they're downloaded, even after a restart.").assertIsDisplayed()
        rule.onNodeWithTag(DiscoveryTags.BACK).performClick()
        assertEquals(listOf("create", "backFromReview"), calls)
    }

    @Test fun durationIsHonestAboutUnknownLengths() {
        val state = DiscoverySamples.review(available = 20, total = 3)
        val songs = state.review!!.songs.mapIndexed { i, s -> if (i == 0) s.copy(durationMs = 0) else s }
        show(state.copy(review = state.review!!.copy(songs = songs)))
        rule.onNodeWithTag(DiscoveryTags.DURATION).assertTextContains("3 songs · 6 min for the 2 with a known length")
    }

    @Test fun renamingIsCommittedFromTheKeyboard() {
        show(DiscoverySamples.review(available = 20))
        rule.onNodeWithTag(DiscoveryTags.NAME).performTextClearance()
        rule.onNodeWithTag(DiscoveryTags.NAME).performTextInput("Friday mix")
        rule.onNodeWithTag(DiscoveryTags.NAME).performImeAction()
        assertEquals("rename:Friday mix", calls.last())
    }

    @Test fun largeFontOnASmallScreenKeepsEveryStepUsable() {
        show(DiscoverySamples.songs(), width = 320, fontScale = 2f)
        rule.onNodeWithTag(DiscoveryTags.REVIEW).assertIsDisplayed()
        rule.onNodeWithText("An Extraordinarily Long Song Title", substring = true).assertExists()
    }
}
