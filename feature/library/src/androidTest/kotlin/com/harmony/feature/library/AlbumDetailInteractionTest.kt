package com.harmony.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harmony.core.model.Song
import com.harmony.core.ui.component.amberPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Interaction tests for the album page: playing from the right place in
 * album order, the artist link, the menus, the gap rows, the states and
 * swipe-down-to-go-back. Runs as an instrumented test (and, in development,
 * on Compose Desktop against the same sources).
 */
class AlbumDetailInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    private var backCalls = 0
    private val played = mutableListOf<Pair<List<Song>, Int>>()
    private val shuffled = mutableListOf<List<Song>>()
    private val queuedNext = mutableListOf<Song>()
    private val addedToPlaylist = mutableListOf<List<Long>>()
    private val deleted = mutableListOf<List<Song>>()
    private val albumNext = mutableListOf<List<Song>>()
    private val artists = mutableListOf<String>()
    private val openedAlbums = mutableListOf<Long>()

    private fun song(n: Int, artist: String = "Mira Sol", albumArtist: String? = "Mira Sol") = Song(
        n.toLong(), "content://media/$n", "Track $n", artist, "Harbour Lights", 7, albumArtist, null, 2019, "Indie pop",
        null, n, 200_000, 1_411, 44_100, 16, 2, null, null, null, null,
    )

    /** Tracks 1–12 without 7, deliberately given out of order. */
    private val songs = (12 downTo 1).filter { it != 7 }.map { song(it) }
    private val others = listOf(OtherAlbum(9, "Evergreen", 2022, null, 8), OtherAlbum(3, "Open Water", 2016, null, 10))

    private fun setPage(ui: AlbumDetailUi = AlbumDetailUi(songs, moreByArtist = others)) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        AlbumDetailContent(
                            ui = ui,
                            palette = amberPalette(),
                            actions = AlbumDetailActions(
                                onBack = { backCalls++ },
                                onPlay = { list, i -> played += list to i },
                                onShuffle = { shuffled += it },
                                onPlayNext = { queuedNext += it },
                                onAddToPlaylist = { addedToPlaylist += it },
                                onDelete = { deleted += it },
                                onPlayAlbumNext = { albumNext += it },
                                onOpenArtist = { artists += it },
                                onOpenAlbum = { openedAlbums += it },
                            ),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun trackNumbers(list: List<Song>) = list.map { it.trackNumber }

    @Test
    fun playStartsAtTrackOneInAlbumOrder() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.PLAY).performClick()
        assertEquals(1, played.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12), trackNumbers(played[0].first))
        assertEquals(0, played[0].second)
    }

    @Test
    fun tappingATrackPlaysTheAlbumFromThatTrack() {
        setPage()
        // Scroll an earlier track to the top so track 9 sits mid-screen, clear of the top bar.
        rule.onNodeWithTag(AlbumDetailTags.LIST).performScrollToKey(6L)
        rule.onNodeWithTag(AlbumDetailTags.row(9)).performClick()
        val (list, index) = played.single()
        assertEquals(9, list[index].trackNumber)
        assertEquals(11, list.size)
    }

    @Test
    fun shuffleGetsTheWholeAlbum() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.SHUFFLE).performClick()
        assertEquals(11, shuffled.single().size)
    }

    @Test
    fun theMissingTrackIsShownWhereItBelongs() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.LIST).performScrollToKey("gap-0-7")
        rule.onNodeWithTag(AlbumDetailTags.gap(null, 7)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Track 7 isn't in your library").assertIsDisplayed()
    }

    @Test
    fun artistLinkOpensTheArtist() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.ARTIST).assertHasClickAction().performClick()
        assertEquals(listOf("Mira Sol"), artists)
    }

    @Test
    fun aCompilationHasNoArtistLinkAndCreditsEachTrack() {
        setPage(AlbumDetailUi(listOf(song(1, artist = "Vesna Kay", albumArtist = null), song(2, artist = "Juno Park", albumArtist = null))))
        rule.onNodeWithTag(AlbumDetailTags.ARTIST).assertHasNoClickAction()
        rule.onNodeWithText(AlbumDetailFormat.VARIOUS_ARTISTS).assertIsDisplayed()
        rule.onNodeWithText("Vesna Kay").assertIsDisplayed()
    }

    @Test
    fun trackMenuQueuesAddsAndDeletesThatTrack() {
        setPage()
        rule.onNodeWithContentDescription("More options for Track 1").performClick()
        rule.onNodeWithText("Play next").performClick()
        assertEquals(1, queuedNext.single().trackNumber)

        rule.onNodeWithContentDescription("More options for Track 2").performClick()
        rule.onNodeWithText("Add to playlist").performClick()
        assertEquals(listOf(2L), addedToPlaylist.single())

        rule.onNodeWithContentDescription("More options for Track 3").performClick()
        rule.onNodeWithText("Delete from phone").performClick()
        assertEquals(3, deleted.single().single().trackNumber)
    }

    @Test
    fun albumMenuActsOnTheWholeAlbumInOrder() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.MORE).performClick()
        rule.onNodeWithText("Add to playlist").performClick()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 8L, 9L, 10L, 11L, 12L), addedToPlaylist.single())

        rule.onNodeWithTag(AlbumDetailTags.MORE).performClick()
        rule.onNodeWithText("Play next").performClick()
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12), trackNumbers(albumNext.single()))

        rule.onNodeWithTag(AlbumDetailTags.MORE).performClick()
        rule.onNodeWithText("Go to artist").performClick()
        assertEquals(listOf("Mira Sol"), artists)
    }

    @Test
    fun swipingATrackRightQueuesIt() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.row(2)).performTouchInput { swipeRight(startX = left + 20f, endX = right - 10f, durationMillis = 300) }
        rule.waitForIdle()
        assertEquals(2, queuedNext.single().trackNumber)
        assertTrue(played.isEmpty())
    }

    @Test
    fun moreByOpensTheOtherAlbum() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.LIST).performScrollToKey("more-by")
        rule.onNodeWithTag(AlbumDetailTags.other(3)).performClick()
        assertEquals(listOf(3L), openedAlbums)
    }

    @Test
    fun theAboutCardListsTheFacts() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.LIST).performScrollToKey("about")
        rule.onNodeWithText("11 of 12 tracks").assertIsDisplayed()
        rule.onNodeWithText("Lossless · 16-bit / 44.1 kHz").assertIsDisplayed()
    }

    @Test
    fun loadingThenMissingStates() {
        setPage(AlbumDetailUi(null))
        rule.onNodeWithTag(AlbumDetailTags.LOADING).assertIsDisplayed()
        rule.onNodeWithTag(AlbumDetailTags.MORE).assertDoesNotExist()
    }

    @Test
    fun aMissingAlbumOffersAWayBack() {
        setPage(AlbumDetailUi(emptyList()))
        rule.onNodeWithTag(AlbumDetailTags.MISSING).assertIsDisplayed()
        rule.onNodeWithText("Go back").performClick()
        rule.waitForIdle()
        assertEquals(1, backCalls)
    }

    @Test
    fun backIsCalledOnceEvenOnADoubleTap() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.BACK).performClick()
        rule.onNodeWithTag(AlbumDetailTags.BACK).performClick()
        rule.waitForIdle()
        assertEquals(1, backCalls)
    }

    @Test
    fun aLongSwipeDownOnTheTitleGoesBack() {
        setPage()
        rule.onNodeWithTag(AlbumDetailTags.TITLE).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 420f, durationMillis = 500)
        }
        rule.waitForIdle()
        assertEquals(1, backCalls)
    }

    @Test
    fun theCurrentTrackShowsItsState() {
        setPage(AlbumDetailUi(songs, nowPlayingId = 2L, isPlaying = true))
        rule.onNodeWithContentDescription("Now playing").assertIsDisplayed()
    }
}
