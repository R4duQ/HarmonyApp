package com.harmony.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.harmony.core.model.AudioOutput
import com.harmony.domain.playback.ConnectComputer
import com.harmony.domain.playback.ConnectState
import com.harmony.domain.playback.PhoneCache
import com.harmony.domain.playback.UnsureHandoff
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Play on sheet's stage 10 parts: "Continue here" after a handoff that
 * ended unsure, and the computer's songs kept on this phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlayOnSheetTest {
    @get:Rule val rule = createComposeRule()

    private val mb = 1024L * 1024
    private val pc = ConnectComputer("pc1", "Desk PC", "192.168.1.5", 47000, paired = true, nearby = true)
    private val cache = PhoneCache(usedBytes = 300 * mb, limitBytes = 1024 * mb, choices = listOf(512 * mb, 1024 * mb, 2048 * mb, 5120 * mb))

    private fun show(state: ConnectState, actions: PlayOnActions) = rule.setContent {
        MaterialTheme {
            PlayOnSheet(state, AudioOutput(), OutputChoice(null, chosen = false, pick = null), actions)
        }
    }

    @Test fun anUnsureReturnAsksAndContinueHerePlaysHere() {
        val calls = mutableListOf<String>()
        show(
            ConnectState(computers = listOf(pc), unsure = UnsureHandoff("Desk PC", mayPlayThere = true)),
            PlayOnActions(continueHere = { calls += "here" }, dismissUnsure = { calls += "leave" }),
        )
        rule.onNodeWithTag(PlayOnTags.UNSURE).assertExists()
        rule.onNodeWithText("Desk PC didn't say it stopped, so it may still be playing. The music is paused on this phone.").assertExists()
        rule.onNodeWithTag(PlayOnTags.CONTINUE_HERE).performClick()
        rule.onNodeWithTag(PlayOnTags.LEAVE_IT).performClick()
        assertEquals(listOf("here", "leave"), calls)
    }

    @Test fun anUnsureMoveSaysItMayPlayThere() {
        show(
            ConnectState(computers = listOf(pc), active = pc, unsure = UnsureHandoff("Desk PC", mayPlayThere = true)),
            PlayOnActions(),
        )
        rule.onNodeWithText("Keep it there").assertExists()
    }

    @Test fun noUnsureNoQuestion() {
        show(ConnectState(computers = listOf(pc)), PlayOnActions())
        rule.onNodeWithTag(PlayOnTags.UNSURE).assertDoesNotExist()
    }

    @Test fun theCacheShowsItsUseAndTakesANewLimitOrEmpties() {
        val limits = mutableListOf<Long>()
        var emptied = 0
        show(
            ConnectState(computers = listOf(pc), phoneCache = cache),
            PlayOnActions(setCacheLimit = { limits += it }, emptyCache = { emptied++ }),
        )
        rule.onNodeWithTag(PlayOnTags.CACHE).performScrollTo()
        rule.onNodeWithText("300 MB used of 1 GB", substring = true).assertExists()
        rule.onNodeWithTag(PlayOnTags.cacheLimit(1024 * mb)).assertIsSelected()
        rule.onNodeWithTag(PlayOnTags.cacheLimit(5120 * mb)).performScrollTo().performClick()
        rule.onNodeWithTag(PlayOnTags.CACHE_EMPTY).performScrollTo().performClick()
        assertEquals(listOf(5120 * mb), limits)
        assertEquals(1, emptied)
    }

    @Test fun anEmptyCacheCantBeEmptiedAgain() {
        show(ConnectState(computers = listOf(pc), phoneCache = cache.copy(usedBytes = 0)), PlayOnActions())
        rule.onNodeWithTag(PlayOnTags.CACHE_EMPTY).performScrollTo().assertIsNotEnabled()
    }

    @Test fun withoutAPairedComputerOrCopiesThereIsNoCacheCard() {
        show(ConnectState(computers = listOf(pc.copy(paired = false)), phoneCache = cache.copy(usedBytes = 0)), PlayOnActions())
        rule.onNodeWithTag(PlayOnTags.CACHE).assertDoesNotExist()
    }

    @Test fun sizesReadPlainly() {
        assertEquals("512 MB", sizeLabel(512 * mb))
        assertEquals("1 GB", sizeLabel(1024 * mb))
        assertEquals("1.5 GB", sizeLabel(1536 * mb))
        assertEquals("0 MB", sizeLabel(0))
    }
}
