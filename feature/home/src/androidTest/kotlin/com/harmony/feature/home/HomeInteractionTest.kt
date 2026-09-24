package com.harmony.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.harmony.core.model.Song
import com.harmony.core.ui.component.amberPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Interaction tests for Home: open vs play on every card, header actions,
 * the loading / empty / no-history / offline states, scroll restoration and
 * bottom clearance. Runs as an instrumented test (and, in development, on
 * Compose Desktop against the same sources).
 */
class HomeInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    private val log = mutableListOf<String>()

    private val actions = HomeActions(
        onOpenSearch = { log += "search" },
        onOpenSettings = { log += "settings" },
        onOpenNowPlaying = { log += "open-player" },
        onTogglePlayback = { log += "toggle" },
        onStartMix = { log += "mix" },
        onPlayRecent = { log += "play-recent-${it.id}" },
        onOpenPlaylist = { log += "open-playlist-$it" },
        onPlayPlaylist = { log += "play-playlist-$it" },
        onOpenPlaylists = { log += "playlists" },
        onOpenAlbum = { log += "open-album-$it" },
        onPlayAlbum = { log += "play-album-$it" },
        onOpenLibrary = { log += "library" },
        onOpenDiscover = { log += "discover" },
        onOpenDownloads = { log += "downloads" },
        onOpenFlacCheck = { log += "flac" },
    )

    private val full = HomeUiState(
        loaded = true,
        stats = LibraryStats(1306, 120, 84, 4),
        nowPlaying = NowPlayingCard(song(1), isPlaying = false, queuePosition = 3, queueSize = 24),
        recentlyPlayed = listOf(song(2), song(3), song(4)),
        playlists = listOf(HomePlaylist(11, "Road Trip", 24, 5_400_000, emptyList())),
        recentAlbums = listOf(HomeAlbum(21, "Discovery", "Daft Punk", null)),
    )

    private fun setHome(
        state: HomeUiState = full,
        online: Boolean? = true,
        fontScale: Float = 1f,
        widthDp: Int = 360,
        bottomClearance: Int = 0,
        listState: (@androidx.compose.runtime.Composable () -> LazyListState)? = null,
    ) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    Box(Modifier.size(widthDp.dp, 740.dp)) {
                        HomeContent(
                            state = state,
                            palette = amberPalette(),
                            actions = actions,
                            greeting = "Good evening",
                            online = online,
                            contentPadding = PaddingValues(bottom = bottomClearance.dp),
                            listState = listState?.invoke() ?: rememberLazyListState(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun scrollTo(key: String) {
        rule.onNodeWithTag(HomeTags.LIST).performScrollToKey(key)
        rule.waitForIdle()
    }

    @Test
    fun headerSearchAndSettings() {
        setHome()
        rule.onNodeWithTag(HomeTags.SEARCH).performClick()
        rule.onNodeWithTag(HomeTags.SETTINGS).performClick()
        assertEquals(listOf("search", "settings"), log)
    }

    @Test
    fun continueCardOpensThePlayerAndItsButtonOnlyPlays() {
        setHome()
        rule.onNodeWithText("3 of 24 in queue").assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.CONTINUE_PLAY).performClick()
        assertEquals(listOf("toggle"), log)
        rule.onNodeWithTag(HomeTags.CONTINUE).performClick()
        assertEquals(listOf("toggle", "open-player"), log)
    }

    @Test
    fun lastPlayedCardPlaysFromBothTheCardAndTheButton() {
        setHome(full.copy(nowPlaying = null, resumeSong = song(9)))
        rule.onNodeWithText("LAST PLAYED").assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.CONTINUE_PLAY).performClick()
        rule.onNodeWithTag(HomeTags.CONTINUE).performClick()
        assertEquals(listOf("toggle", "toggle"), log)
    }

    @Test
    fun recentSongCardsPlay() {
        setHome()
        rule.onNodeWithTag(HomeTags.recent(3)).performClick()
        assertEquals(listOf("play-recent-3"), log)
    }

    @Test
    fun playlistCardOpensAndItsDiscPlays() {
        setHome()
        scrollTo("playlists")
        rule.onNodeWithTag(HomeTags.playlistPlay(11)).performClick()
        assertEquals(listOf("play-playlist-11"), log)
        rule.onNodeWithTag(HomeTags.playlist(11)).performClick()
        assertEquals(listOf("play-playlist-11", "open-playlist-11"), log)
        rule.onNodeWithText("See all").performClick()
        assertEquals("playlists", log.last())
    }

    @Test
    fun albumCardOpensAndItsDiscPlays() {
        setHome()
        scrollTo("albums")
        rule.onNodeWithTag(HomeTags.albumPlay(21)).performClick()
        rule.onNodeWithTag(HomeTags.album(21)).performClick()
        assertEquals(listOf("play-album-21", "open-album-21"), log)
    }

    @Test
    fun toolsRowsReachTheirPages() {
        setHome()
        scrollTo("tools")
        rule.onNodeWithTag(HomeTags.LIBRARY_ROW).performClick()
        rule.onNodeWithTag(HomeTags.DOWNLOADS_ROW).performClick()
        rule.onNodeWithTag(HomeTags.FLAC_ROW).performClick()
        rule.onNodeWithText("1,306 songs  ·  120 albums  ·  84 artists").assertIsDisplayed()
        assertEquals(listOf("library", "downloads", "flac"), log)
    }

    @Test
    fun discoverOpensAndShowsOfflineWhenOffline() {
        setHome(online = false)
        scrollTo("discover")
        // The note is merged into the card's single clickable node.
        rule.onNodeWithTag(HomeTags.OFFLINE, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.DISCOVER).performClick()
        assertEquals(listOf("discover"), log)
    }

    @Test
    fun loadingShowsPlaceholdersNotEmptyStates() {
        setHome(HomeUiState())
        rule.onNodeWithTag(HomeTags.CONTINUE_LOADING).assertIsDisplayed()
        rule.onNodeWithText("Start Mix").assertIsNotEnabled()
        assertTrue(rule.onAllNodesWithTagCount(HomeTags.WELCOME) == 0)
        assertTrue(rule.onAllNodesWithTagCount(HomeTags.NO_HISTORY) == 0)
    }

    @Test
    fun emptyLibraryWelcomesAndHidesLibrarySections() {
        setHome(HomeUiState(loaded = true, libraryEmpty = true))
        rule.onNodeWithTag(HomeTags.WELCOME).assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.WELCOME_LIBRARY).performClick()
        assertEquals(listOf("library"), log)
        assertEquals(0, rule.onAllNodesWithTagCount(HomeTags.MIX))
        assertEquals(0, rule.onAllNodesWithTagCount(HomeTags.NO_PLAYLISTS))
    }

    @Test
    fun noHistoryAndNoPlaylistsHaveTheirOwnNotes() {
        setHome(HomeUiState(loaded = true, stats = LibraryStats(10, 1, 1, 0)))
        rule.onNodeWithTag(HomeTags.NO_HISTORY).assertIsDisplayed()
        scrollTo("playlists")
        rule.onNodeWithTag(HomeTags.NO_PLAYLISTS).performClick()
        assertEquals(listOf("playlists"), log)
    }

    @Test
    fun scrollPositionSurvivesLeavingAndComingBack() {
        // What NavHost does when an album opens on top of Home and is popped
        // again: Home leaves the composition, its saveable state is parked
        // under its entry's key, and it is recomposed from that state.
        var onHome by mutableStateOf(true)
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val holder = rememberSaveableStateHolder()
                Box(Modifier.size(360.dp, 740.dp)) {
                    if (onHome) {
                        holder.SaveableStateProvider("home") {
                            HomeContent(full, amberPalette(), actions, "Good evening", listState = rememberLazyListState())
                        }
                    } else {
                        Text("Album page")
                    }
                }
            }
        }
        rule.onNodeWithTag(HomeTags.LIST).performScrollToKey("discover")
        rule.waitForIdle()
        val before = rule.onNodeWithTag(HomeTags.DISCOVER).getUnclippedBoundsInRoot().top

        onHome = false
        rule.waitForIdle()
        rule.onNodeWithText("Album page").assertIsDisplayed()
        onHome = true
        rule.waitForIdle()

        assertEquals(before, rule.onNodeWithTag(HomeTags.DISCOVER).getUnclippedBoundsInRoot().top)
    }

    @Test
    fun lastCardScrollsClearOfTheFloatingChrome() {
        setHome(bottomClearance = 190)
        rule.onNodeWithTag(HomeTags.LIST).performScrollToKey("end")
        rule.waitForIdle()
        val rootBottom = rule.onRoot().getUnclippedBoundsInRoot().bottom
        val lastRow = rule.onNodeWithTag(HomeTags.FLAC_ROW).getUnclippedBoundsInRoot().bottom
        assertTrue("last row ($lastRow) must end above the chrome (${rootBottom - 190.dp})", lastRow <= rootBottom - 190.dp)
    }

    @Test
    fun largeFontOnNarrowScreenStillShowsEverything() {
        setHome(fontScale = 2f, widthDp = 320)
        rule.onNodeWithTag(HomeTags.CONTINUE_PLAY).assertIsDisplayed()
        scrollTo("tools")
        rule.onNodeWithTag(HomeTags.FLAC_ROW).assertIsDisplayed()
    }

    @Test
    fun largeFontEmptyLibraryStacksItsButtons() {
        setHome(HomeUiState(loaded = true, libraryEmpty = true), fontScale = 1.6f, widthDp = 320)
        val open = rule.onNodeWithTag(HomeTags.WELCOME_LIBRARY).getUnclippedBoundsInRoot()
        val discover = rule.onNodeWithTag(HomeTags.WELCOME_DISCOVER).getUnclippedBoundsInRoot()
        assertTrue(discover.top >= open.bottom)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun song(id: Long) = Song(
        id = id, uri = "u$id", title = "Song $id", artist = "Artist $id", album = "Album", albumId = id,
        albumArtist = null, composer = null, year = null, genre = null, discNumber = null,
        trackNumber = null, durationMs = 200_000, bitrateKbps = null, sampleRateHz = null,
        bitDepth = null, channels = null, artworkUri = null, embeddedLyrics = null,
        replayGainTrackDb = null, replayGainAlbumDb = null,
    )
}
