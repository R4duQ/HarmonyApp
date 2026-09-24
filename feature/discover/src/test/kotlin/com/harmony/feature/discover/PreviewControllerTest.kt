package com.harmony.feature.discover

import com.harmony.domain.library.repository.DiscoverySong
import com.harmony.feature.discover.ui.PreviewController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewControllerTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val players = FakePreviewPlayers()
    private val answers = HashMap<String, CompletableDeferred<Pair<String, Boolean>?>>()
    private var paused = 0
    private val controller = PreviewController(scope, players,
        resolve = { song -> answers.getOrPut(song.key) { CompletableDeferred() }.await() },
        beforeStart = { paused++ })
    private val a = DiscoverySong("dz:1", "A", "Artist A", provider = "Deezer")
    private val b = DiscoverySong("dz:2", "B", "Artist B", provider = "Deezer")

    private fun answer(song: DiscoverySong, uri: String?) {
        answers.getOrPut(song.key) { CompletableDeferred() }.complete(uri?.let { it to false }); scope.runCurrent()
    }

    @Test fun aLateAnswerForTheSongSwipedAwayFromIsDropped() {
        controller.start(a); scope.runCurrent()
        controller.start(b); scope.runCurrent()        // the user swiped on before A's URL came back
        answer(a, "https://cdn/a.mp3")
        assertTrue(players.created.none { it.uri == "https://cdn/a.mp3" })
        assertEquals("dz:2", controller.state.value.songId)
        assertTrue(controller.state.value.loading)
        answer(b, "https://cdn/b.mp3")
        players.created.single().ready()
        assertTrue(controller.state.value.playing)
        assertEquals("dz:2", controller.state.value.songId)
        assertEquals(2, paused) // main playback paused for each start
    }

    @Test fun anOldPlayerBecomingReadyCannotTakeOverTheCard() {
        controller.start(a); answer(a, "https://cdn/a.mp3")
        val old = players.created.single()
        controller.start(b); scope.runCurrent()
        assertTrue(old.released)
        old.ready()
        assertFalse(controller.state.value.playing)
        assertEquals("dz:2", controller.state.value.songId)
    }

    @Test fun movingToAnotherCardStopsTheClip() {
        controller.start(a); answer(a, "https://cdn/a.mp3"); players.created.single().ready()
        controller.onFocus("dz:1")
        assertTrue(controller.state.value.playing)
        controller.onFocus("dz:2")
        assertNull(controller.state.value.songId)
        assertTrue(players.created.single().released)
    }

    @Test fun loadingNeverSpinsForever() {
        controller.start(a); scope.runCurrent()
        scope.advanceTimeBy(15_001); scope.runCurrent()
        assertFalse(controller.state.value.loading)
        assertEquals("Preview timed out. Try again.", controller.state.value.message)
    }

    @Test fun aSongWithoutPreviewSaysSoAndCanStillBeKept() {
        controller.start(a); answer(a, null)
        assertFalse(controller.state.value.loading)
        assertTrue(controller.state.value.message!!.contains("No preview"))
        assertTrue(players.created.isEmpty())
    }

    @Test fun togglingThePlayingSongStopsIt() {
        controller.start(a); answer(a, "https://cdn/a.mp3"); players.created.single().ready()
        controller.toggle(a)
        assertNull(controller.state.value.songId)
    }

    @Test fun aPlayerErrorBecomesAMessage() {
        controller.start(a); answer(a, "https://cdn/a.mp3")
        players.created.single().error()
        assertFalse(controller.state.value.playing)
        assertTrue(controller.state.value.message!!.startsWith("Preview unavailable"))
    }
}
