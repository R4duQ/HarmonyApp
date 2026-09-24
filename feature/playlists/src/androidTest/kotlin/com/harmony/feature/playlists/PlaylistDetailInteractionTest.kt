package com.harmony.feature.playlists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import com.harmony.core.ui.component.greenPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Interaction tests for the playlist page: the swipe-down-to-go-back
 * gesture, and that it leaves scrolling, reordering, swipe-to-queue and the
 * buttons alone. Runs as an instrumented test (and, in development, on
 * Compose Desktop against the same sources).
 */
class PlaylistDetailInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    private var backCalls = 0
    private val played = mutableListOf<Pair<List<Song>, Int>>()
    private val shuffled = mutableListOf<List<Song>>()
    private val moves = mutableListOf<Pair<Int, Int>>()
    private val queued = mutableListOf<Song>()

    private val songs = (1L..30L).map { song(it) }

    private fun setPage(
        ui: PlaylistDetailUi = PlaylistDetailUi("Late Night Drive", null, editable = true, songs = songs),
        fontScale: Float = 1f,
        widthDp: Int = 360,
    ) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    Box(Modifier.size(widthDp.dp, 760.dp)) {
                        PlaylistDetailContent(
                            ui = ui,
                            palette = greenPalette(),
                            actions = PlaylistDetailActions(
                                onBack = { backCalls++ },
                                onPlay = { list, i -> played += list to i },
                                onShuffle = { shuffled += it },
                                onPlayNext = { queued += it },
                                onMove = { f, t -> moves += f to t },
                            ),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun titleTop(): Float = rule.onNodeWithTag(PlaylistDetailTags.TITLE).getUnclippedBoundsInRoot().top.value

    // --- the gesture itself -------------------------------------------------

    @Test
    fun longSwipeDownOnTitleGoesBackOnce() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.TITLE).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 420f, durationMillis = 500)
        }
        rule.waitForIdle()
        assertEquals(1, backCalls)
    }

    @Test
    fun pageFollowsTheFingerThenSettlesBackOnAShortDrag() {
        setPage()
        val restTop = titleTop()
        rule.onNodeWithTag(PlaylistDetailTags.TITLE).performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(0f, 10f)) }
        }
        rule.waitForIdle()
        val dragTop = titleTop()
        assertTrue("page should move with the finger ($restTop -> $dragTop)", dragTop > restTop + 30f)

        rule.onNodeWithTag(PlaylistDetailTags.TITLE).performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0, backCalls)
        assertEquals(restTop, titleTop(), 0.5f)
    }

    @Test
    fun aQuickShortFlickCommits() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.TITLE).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 90f, durationMillis = 50)
        }
        rule.waitForIdle()
        assertEquals(1, backCalls)
    }

    @Test
    fun aSlowShortDragDoesNotCommit() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.TITLE).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 90f, durationMillis = 900)
        }
        rule.waitForIdle()
        assertEquals(0, backCalls)
    }

    @Test
    fun swipeDownOnTheCoverAreaAlsoWorks() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.HERO).performTouchInput {
            swipeDown(startY = top + 200f, endY = top + 620f, durationMillis = 500)
        }
        rule.waitForIdle()
        assertEquals(1, backCalls)
    }

    @Test
    fun swipeDownOnCompactTitleBarGoesBackWhenScrolled() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.LIST).performScrollToIndex(15)
        rule.waitForIdle()
        rule.onNodeWithTag(PlaylistDetailTags.TOP_BAR).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 420f, durationMillis = 500)
        }
        rule.waitForIdle()
        assertEquals(1, backCalls)
    }

    // --- it must not steal other gestures -----------------------------------

    @Test
    fun swipeUpOnTitleScrollsTheList() {
        setPage()
        val restTop = titleTop()
        rule.onNodeWithTag(PlaylistDetailTags.TITLE).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 300f, durationMillis = 400)
        }
        rule.waitForIdle()
        assertEquals(0, backCalls)
        assertTrue("list should have scrolled", titleTop() < restTop - 100f)
    }

    @Test
    fun swipeDownOnThePartlyScrolledHeaderScrollsBackFirst() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.LIST).performTouchInput {
            swipeUp(startY = bottom - 60f, endY = bottom - 180f, durationMillis = 400)
        }
        rule.waitForIdle()
        val scrolledTop = titleTop()
        rule.onNodeWithTag(PlaylistDetailTags.TITLE).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 400f, durationMillis = 500)
        }
        rule.waitForIdle()
        assertEquals("the list scrolls, the page stays", 0, backCalls)
        assertTrue(titleTop() > scrolledTop)
    }

    @Test
    fun swipeDownOnTheSongsNeverGoesBack() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.LIST).performScrollToIndex(6)
        rule.waitForIdle()
        rule.onNodeWithTag(PlaylistDetailTags.row(8)).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 400f, durationMillis = 400)
        }
        rule.waitForIdle()
        assertEquals(0, backCalls)
    }

    @Test
    fun reorderGripMovesSongsWithoutGoingBack() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.LIST).performScrollToIndex(1)
        rule.waitForIdle()
        // The grip's own node is merged into the row's clickable semantics.
        // Song 3, not 1: after the scroll, the first row sits under the
        // (now opaque) top bar, where a drag belongs to the bar.
        rule.onNodeWithTag(PlaylistDetailTags.handle(3), useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(16) { moveBy(Offset(0f, 10f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(0, backCalls)
        assertEquals(1, moves.size)
        assertEquals(2, moves[0].first)
        assertTrue("moved down at least one slot", moves[0].second >= 3)
    }

    @Test
    fun swipeRightOnARowQueuesIt() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.LIST).performScrollToIndex(1)
        rule.waitForIdle()
        // Start on the card itself, not in the row's outer margin.
        rule.onNodeWithTag(PlaylistDetailTags.row(2)).performTouchInput {
            swipeRight(startX = left + 60f, endX = right - 10f, durationMillis = 300)
        }
        rule.waitForIdle()
        assertEquals(listOf(2L), queued.map { it.id })
        assertEquals(0, backCalls)
    }

    // --- buttons --------------------------------------------------------------

    @Test
    fun playShuffleAndRowTapsReachTheirActions() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.PLAY).performClick()
        rule.onNodeWithTag(PlaylistDetailTags.SHUFFLE).performClick()
        rule.onNodeWithTag(PlaylistDetailTags.row(3)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(0, 2), played.map { it.second })
        assertEquals(1, shuffled.size)
        assertEquals(0, backCalls)
    }

    @Test
    fun backButtonGoesBackExactlyOnce() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.BACK).performClick()
        rule.onNodeWithTag(PlaylistDetailTags.BACK).performClick()
        rule.onNodeWithTag(PlaylistDetailTags.TITLE).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 420f, durationMillis = 400)
        }
        rule.waitForIdle()
        assertEquals(1, backCalls)
    }

    @Test
    fun gestureAfterCommitCannotGoBackAgain() {
        setPage()
        rule.onNodeWithTag(PlaylistDetailTags.TITLE).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 420f, durationMillis = 400)
        }
        rule.waitForIdle()
        // The page has slid off screen by now, so press Back through its
        // accessibility action rather than a touch.
        rule.onNodeWithTag(PlaylistDetailTags.BACK).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(1, backCalls)
    }

    // --- states and layout ------------------------------------------------------

    @Test
    fun loadingShowsSkeletonAndDisabledActions() {
        setPage(PlaylistDetailUi(title = null, smartType = null, editable = true, songs = null))
        rule.onNodeWithTag(PlaylistDetailTags.LOADING).assertIsDisplayed()
        rule.onNodeWithTag(PlaylistDetailTags.PLAY).assertIsNotEnabled()
        rule.onNodeWithTag(PlaylistDetailTags.BACK).performClick()
        assertEquals(1, backCalls)
    }

    @Test
    fun emptyUserPlaylistOffersAddSongs() {
        setPage(PlaylistDetailUi("Empty", null, editable = true, songs = emptyList()))
        rule.onNodeWithTag(PlaylistDetailTags.EMPTY).assertIsDisplayed()
        rule.onNodeWithText("Add songs").assertIsDisplayed()
    }

    @Test
    fun emptySmartPlaylistExplainsItself() {
        setPage(PlaylistDetailUi("Favorites", SmartPlaylistType.FAVORITES, editable = false, songs = emptyList()))
        rule.onNodeWithText("No favourites yet").assertIsDisplayed()
    }

    @Test
    fun deletedPlaylistShowsGoneState() {
        setPage(PlaylistDetailUi("Playlist", null, editable = false, songs = emptyList(), missing = true))
        rule.onNodeWithTag(PlaylistDetailTags.MISSING).assertIsDisplayed()
        rule.onNodeWithText("Go back").performClick()
        assertEquals(1, backCalls)
    }

    @Test
    fun largeFontOnNarrowScreenStacksPlayAndShuffle() {
        setPage(fontScale = 2f, widthDp = 320)
        val play = rule.onNodeWithTag(PlaylistDetailTags.PLAY).getUnclippedBoundsInRoot()
        val shuffle = rule.onNodeWithTag(PlaylistDetailTags.SHUFFLE).getUnclippedBoundsInRoot()
        assertTrue("stacked: shuffle below play", shuffle.top >= play.bottom)
    }

    @Test
    fun normalFontPutsPlayAndShuffleSideBySide() {
        setPage()
        val play = rule.onNodeWithTag(PlaylistDetailTags.PLAY).getUnclippedBoundsInRoot()
        val shuffle = rule.onNodeWithTag(PlaylistDetailTags.SHUFFLE).getUnclippedBoundsInRoot()
        assertEquals(play.top.value, shuffle.top.value, 0.5f)
        assertTrue(shuffle.left >= play.right)
    }

    @Test
    fun veryLongTitleStillLeavesRoomForTheActions() {
        setPage(
            PlaylistDetailUi("A".repeat(20) + " very long playlist name that keeps going and going and going", null, true, songs),
            fontScale = 1.6f,
        )
        rule.onNodeWithTag(PlaylistDetailTags.PLAY).assertIsDisplayed()
    }

    private fun song(id: Long) = Song(
        id = id, uri = "content://song/$id", title = "Song $id", artist = "Artist ${id % 4}",
        album = "Album", albumId = id % 5, albumArtist = null, composer = null, year = null,
        genre = null, discNumber = null, trackNumber = null, durationMs = 200_000,
        bitrateKbps = null, sampleRateHz = null, bitDepth = null, channels = null,
        artworkUri = "art-${id % 5}", embeddedLyrics = null, replayGainTrackDb = null,
        replayGainAlbumDb = null,
    )
}
