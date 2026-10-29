package com.harmony.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTouchInput
import com.harmony.core.model.PlayerState
import com.harmony.core.model.Song
import com.harmony.domain.playback.QueueMoves
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The queue sheet driven the way a finger drives it (long press on a row's
 * handle, then drag), against a player that moves entries as
 * PlaybackConnection does: checked by [QueueMoves], then Player.moveMediaItem
 * (the song playing keeps playing, its index follows it).
 *
 * Every entry has its own tag, so two copies of one song are told apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class QueueSheetInteractionTest {
    @get:Rule val rule = createComposeRule()

    private class Entry(val tag: Int, val song: Song)

    /** The player: a queue of entries; with [answerAtOnce] false the screen hears of a move only on [answer]. */
    private class FakePlayer(titles: List<String>) {
        val entries: MutableList<Entry>
        var current = 0
        var answerAtOnce = true
        var state by mutableStateOf(PlayerState())

        init {
            val ids = HashMap<String, Long>()
            entries = titles.mapIndexed { i, t ->
                val id = ids.getOrPut(t) { ids.size + 1L }
                Entry(i, Song(id, "file:///$id", t, "Artist", "Album", 1, null, null, null, null, null, null,
                    180_000, null, null, null, null, null, null, null, null))
            }.toMutableList()
            answer()
        }

        fun answer() {
            state = PlayerState(currentSong = entries[current].song, queue = entries.map { it.song }, queueIndex = current)
        }

        /** The song playing ends; the next one starts. */
        fun advance() { current++; answer() }

        fun move(from: Int, to: Int, songId: Long, onDone: (Boolean) -> Unit) {
            val move = QueueMoves.resolve(entries.map { it.song.id.toString() }, current, from, to, songId.toString())
            if (move == null) { onDone(false); return }
            val playing = entries[current]
            entries.add(move.to, entries.removeAt(move.from))
            current = entries.indexOf(playing)
            if (answerAtOnce) answer()
            onDone(true)
        }

        fun playingTag() = entries[current].tag
        fun upcomingTags() = entries.drop(current + 1).map { it.tag }
    }

    private fun show(vararg titles: String): FakePlayer {
        val player = FakePlayer(titles.toList())
        rule.setContent {
            QueueSheetContent(player.state, onPlay = {}, onRemove = {}, onMove = player::move)
        }
        rule.waitForIdle()
        return player
    }

    /** The drag handles on screen, top to bottom: (title, top). */
    private fun handles(): List<Pair<String, Float>> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.layoutInfo.isPlaced }
            .mapNotNull { node ->
                val text = node.config[SemanticsProperties.ContentDescription].firstOrNull() ?: return@mapNotNull null
                if (text.startsWith("Reorder ")) text.removePrefix("Reorder ") to node.boundsInRoot.top else null
            }
            .sortedBy { it.second }

    private fun shown() = handles().map { it.first }

    private fun rowHeight(): Float = handles().let { it[it.size - 1].second - it[it.size - 2].second }

    private fun handle(title: String, occurrence: Int = 0) =
        rule.onAllNodesWithContentDescription("Reorder $title", useUnmergedTree = true).let { nodes ->
            val placed = nodes.fetchSemanticsNodes().indices
                .filter { nodes[it].fetchSemanticsNode().layoutInfo.isPlaced }
                .sortedBy { nodes[it].fetchSemanticsNode().boundsInRoot.top }
            nodes[placed[occurrence]]
        }

    /** Long-press the handle of the [occurrence]-th row titled [title] and drag it [rows] rows (down is positive). */
    private fun drag(title: String, rows: Int, occurrence: Int = 0) {
        val step = rowHeight()
        handle(title, occurrence).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(24) {
                moveBy(Offset(0f, rows * step / 24))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
    }

    /** As [drag], with the song ending after the first [rowsBefore] rows of travel. */
    private fun dragAcrossASongChange(player: FakePlayer, title: String, rowsBefore: Int, rowsAfter: Int) {
        val step = rowHeight()
        val node = handle(title)
        node.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(12) { moveBy(Offset(0f, rowsBefore * step / 12)); advanceEventTime(16) }
        }
        rule.waitForIdle()
        player.advance()
        rule.waitForIdle()
        node.performTouchInput {
            repeat(12) { moveBy(Offset(0f, rowsAfter * step / 12)); advanceEventTime(16) }
            up()
        }
        rule.waitForIdle()
    }

    // ---- The cause: a row's gesture used the position it had when it first started listening.

    @Test fun aMovedRowCanBeMovedAgain() {
        val player = show("Now", "A", "B", "C", "D", "E")
        drag("A", 3)
        assertEquals(listOf(2, 3, 4, 1, 5), player.upcomingTags())
        drag("A", -2)
        assertEquals(listOf(2, 1, 3, 4, 5), player.upcomingTags())
        assertEquals(listOf("B", "A", "C", "D", "E"), shown())
    }

    @Test fun rowsShiftedByAMoveDragThemselves() {
        val player = show("Now", "A", "B", "C", "D", "E")
        drag("A", 2)
        assertEquals(listOf(2, 3, 1, 4, 5), player.upcomingTags())
        drag("B", 1)
        assertEquals(listOf(3, 2, 1, 4, 5), player.upcomingTags())
        drag("D", -3)
        assertEquals(listOf(4, 3, 2, 1, 5), player.upcomingTags())
        assertEquals(listOf("D", "C", "B", "A", "E"), shown())
    }

    @Test fun firstToLastAndBack() {
        val player = show("Now", "A", "B", "C", "D", "E")
        drag("A", 4)
        assertEquals(listOf(2, 3, 4, 5, 1), player.upcomingTags())
        drag("A", -4)
        assertEquals(listOf(1, 2, 3, 4, 5), player.upcomingTags())
        drag("E", -4)
        assertEquals(listOf(5, 1, 2, 3, 4), player.upcomingTags())
    }

    @Test fun adjacentSwapsBothWays() {
        val player = show("Now", "A", "B", "C", "D")
        drag("B", 1)
        assertEquals(listOf(1, 3, 2, 4), player.upcomingTags())
        drag("B", -1)
        assertEquals(listOf(1, 2, 3, 4), player.upcomingTags())
        drag("C", -1)
        assertEquals(listOf(1, 3, 2, 4), player.upcomingTags())
    }

    @Test fun onlyTheDraggedCopyOfADuplicateMoves() {
        // X is queued twice: tags 1 and 3.
        val player = show("Now", "X", "Y", "X", "Z")
        drag("X", -2, occurrence = 1)
        assertEquals(listOf(3, 1, 2, 4), player.upcomingTags())
        drag("X", 2, occurrence = 1)
        assertEquals(listOf(3, 2, 4, 1), player.upcomingTags())
        drag("Z", -2)
        assertEquals(listOf(4, 3, 2, 1), player.upcomingTags())
        assertEquals(listOf("Z", "X", "Y", "X"), shown())
    }

    @Test fun quickDragsBeforeThePlayerAnswers() {
        val player = show("Now", "A", "B", "C", "D", "E")
        player.answerAtOnce = false
        drag("A", 1)
        // The player has the move; the screen hasn't heard yet. Drag again.
        drag("A", 1)
        player.answer()
        rule.waitForIdle()
        assertEquals(listOf(2, 3, 1, 4, 5), player.upcomingTags())
        assertEquals(listOf("B", "C", "A", "D", "E"), shown())
    }

    @Test fun longQueue() {
        val player = show(*(listOf("Now") + (1..40).map { "S$it" }).toTypedArray())
        drag("S3", 2)
        drag("S1", 3)
        drag("S3", -4)
        val expected = (1..40).toMutableList().apply {
            add(4, removeAt(2))
            add(3, removeAt(0))
            add(0, removeAt(indexOf(3)))
        }
        assertEquals(expected, player.upcomingTags())
        assertEquals(41, player.entries.size)
    }

    // ---- The song changing around a move.

    @Test fun dragAfterTheSongChanged() {
        val player = show("Now", "A", "B", "C", "D")
        player.advance()
        rule.waitForIdle()
        assertEquals(listOf("B", "C", "D"), shown())
        drag("D", -2)
        assertEquals(1, player.playingTag())
        assertEquals(listOf(4, 2, 3), player.upcomingTags())
        assertEquals(listOf("D", "B", "C"), shown())
    }

    @Test fun theSongEndsWhileDraggingToTheTop() {
        val player = show("Now", "A", "B", "C", "D")
        dragAcrossASongChange(player, "D", rowsBefore = -1, rowsAfter = -2)
        // A plays now, and D comes right after it: not behind it, where Next never goes.
        assertEquals(1, player.playingTag())
        assertEquals(listOf(4, 2, 3), player.upcomingTags())
        assertEquals(5, player.entries.size)
        assertEquals(listOf("D", "B", "C"), shown())
    }

    @Test fun theDraggedSongStartsPlaying() {
        val player = show("Now", "A", "B", "C", "D")
        dragAcrossASongChange(player, "A", rowsBefore = 1, rowsAfter = 1)
        // A started under the finger: it plays where it is, nothing else moves.
        assertEquals(1, player.playingTag())
        assertEquals(listOf(0, 1, 2, 3, 4), player.entries.map { it.tag })
        assertEquals(listOf("B", "C", "D"), shown())
    }

    // ---- The same moves without dragging (TalkBack's actions).

    @Test fun accessibilityMoves() {
        val player = show("Now", "A", "B", "C", "D")
        fun act(title: String, label: String) {
            val node = rule.onAllNodesWithContentDescription("$title by Artist")[0].fetchSemanticsNode()
            val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
            rule.runOnIdle { action.action() }
            rule.waitForIdle()
        }
        act("A", "Move down")
        assertEquals(listOf(2, 1, 3, 4), player.upcomingTags())
        act("A", "Move down")
        assertEquals(listOf(2, 3, 1, 4), player.upcomingTags())
        act("D", "Move up")
        assertEquals(listOf(2, 3, 4, 1), player.upcomingTags())
        drag("B", 3)
        assertEquals(listOf(3, 4, 1, 2), player.upcomingTags())
    }
}
