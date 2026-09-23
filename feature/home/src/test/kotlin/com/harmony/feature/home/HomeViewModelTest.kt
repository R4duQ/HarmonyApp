package com.harmony.feature.home

import com.harmony.core.model.Playlist
import com.harmony.core.model.PlayerState
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.SmartQueueCoordinator
import com.harmony.domain.shuffle.engine.JourneyEngine
import com.harmony.domain.shuffle.engine.SmartShuffleEngine
import com.harmony.domain.shuffle.usecase.StartSmartMixUseCase
import com.harmony.domain.similarity.repository.SimilarityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val songCount = MutableStateFlow(3)
    private val history = MutableStateFlow(listOf(song(1), song(2), song(1), song(3)))
    private val recentlyAdded = MutableStateFlow(listOf(song(7, albumId = 70), song(8, albumId = 70), song(9, albumId = 90)))
    private val playlists = MutableStateFlow(listOf(Playlist(5, "Road Trip", 2, 1)))
    private val player = MutableStateFlow(PlayerState())
    private val albumSongs = mutableMapOf(70L to listOf(song(7), song(8)), 90L to emptyList())
    private val calls = mutableListOf<String>()
    private val queued = mutableListOf<List<Song>>()

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> fake(noinline h: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, a ->
            h(m.name, a ?: emptyArray())
        } as T

    private val library = fake<LibraryRepository> { name, a ->
        when (name) {
            "observeSongCount" -> songCount
            "observeAlbumCount", "observeArtistCount" -> flowOf(2)
            "observeSongsByAlbum" -> flowOf(albumSongs[a[0] as Long].orEmpty())
            else -> error("unexpected $name")
        }
    }
    private val playlistRepo = fake<PlaylistRepository> { name, _ ->
        when (name) {
            "observePlaylistCount" -> flowOf(1)
            "observePlaylists" -> playlists
            "observePlaylistArtwork" -> flowOf(mapOf(5L to listOf("x")))
            "observePlaylistDurations" -> flowOf(mapOf(5L to 600_000L))
            "observeSmartPlaylist" -> recentlyAdded
            "observePlaylistSongs" -> flowOf(listOf(song(1), song(2)))
            else -> error("unexpected $name")
        }
    }
    private val historyRepo = fake<PlaybackHistoryRepository> { name, _ ->
        when (name) {
            "observeRecentlyPlayed" -> history
            else -> error("unexpected $name")
        }
    }
    private val playback = fake<PlaybackController> { name, a ->
        when (name) {
            "getPlayerState" -> player
            "setQueue" -> { @Suppress("UNCHECKED_CAST") queued += a[0] as List<Song>; Unit }
            else -> { calls += name; Unit }
        }
    }

    private fun vm(): HomeViewModel {
        val similarity = fake<SimilarityRepository> { _, _ -> null }
        val engine = SmartShuffleEngine(similarity, null, null, null, Random(1))
        val coordinator = SmartQueueCoordinator(playback, engine, JourneyEngine(similarity, engine), library, historyRepo)
        return HomeViewModel(library, playlistRepo, historyRepo, playback, StartSmartMixUseCase(playback, library, coordinator))
    }

    private fun TestScope.collect(vm: HomeViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.progress.collect {} }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `starts as loading, not as an empty library`() = runTest {
        val vm = vm()
        assertFalse(vm.uiState.value.loaded)
        assertFalse(vm.uiState.value.libraryEmpty)
    }

    @Test
    fun `sections come from the repositories`() = runTest {
        val vm = vm().also { collect(it) }
        val s = vm.uiState.value
        assertTrue(s.loaded)
        assertEquals(listOf(70L, 90L), s.recentAlbums.map { it.id })
        assertEquals(listOf(5L), s.playlists.map { it.id })
        assertEquals(600_000L, s.playlists.single().durationMs)
        assertEquals(3, s.stats.songs)
    }

    @Test
    fun `with an empty player the last played song is offered and not repeated below`() = runTest {
        val vm = vm().also { collect(it) }
        val s = vm.uiState.value
        assertNull(s.nowPlaying)
        assertEquals(1L, s.resumeSong?.id)
        assertEquals(listOf(2L, 3L), s.recentlyPlayed.map { it.id })
    }

    @Test
    fun `the player's song is featured and left out of recently played`() = runTest {
        player.value = PlayerState(currentSong = song(2), queue = listOf(song(1), song(2), song(3)), queueIndex = 1)
        val vm = vm().also { collect(it) }
        val s = vm.uiState.value
        assertEquals(2L, s.nowPlaying?.song?.id)
        assertEquals(2, s.nowPlaying?.queuePosition)
        assertEquals(3, s.nowPlaying?.queueSize)
        assertNull(s.resumeSong)
        assertEquals(listOf(1L, 3L), s.recentlyPlayed.map { it.id })
    }

    @Test
    fun `an empty library is reported as such`() = runTest {
        songCount.value = 0
        history.value = emptyList()
        val vm = vm().also { collect(it) }
        assertTrue(vm.uiState.value.libraryEmpty)
        assertFalse(vm.uiState.value.hasHistory)
    }

    @Test
    fun `play pause toggles the player, or resumes the last song`() = runTest {
        val vm = vm().also { collect(it) }
        vm.togglePlayback()
        assertEquals(listOf(1L), queued.single().map { it.id })

        player.value = PlayerState(currentSong = song(2), isPlaying = true, queue = listOf(song(2)), queueIndex = 0)
        vm.togglePlayback()
        player.value = player.value.copy(isPlaying = false)
        vm.togglePlayback()
        assertEquals(listOf("pause", "play"), calls)
    }

    @Test
    fun `album play loads the album only when asked, and reports an empty one`() = runTest {
        val vm = vm().also { collect(it) }
        vm.playAlbum(70)
        assertEquals(listOf(7L, 8L), queued.single().map { it.id })
        vm.playAlbum(90)
        assertEquals("Nothing to play here yet.", vm.message.value)
    }

    @Test
    fun `playing a recent song queues the row from there`() = runTest {
        val vm = vm().also { collect(it) }
        vm.playRecent(song(3))
        assertEquals(listOf(2L, 3L), queued.single().map { it.id })
    }

    @Test
    fun `progress follows position`() = runTest {
        val vm = vm().also { collect(it) }
        player.value = PlayerState(currentSong = song(1), positionMs = 30_000, durationMs = 120_000)
        assertEquals(0.25f, vm.progress.value, 0.001f)
    }

    private fun song(id: Long, albumId: Long = id) = Song(
        id = id, uri = "u$id", title = "Song $id", artist = "A", album = "Album $albumId", albumId = albumId,
        albumArtist = null, composer = null, year = null, genre = null, discNumber = null,
        trackNumber = null, durationMs = 1000, bitrateKbps = null, sampleRateHz = null,
        bitDepth = null, channels = null, artworkUri = null, embeddedLyrics = null,
        replayGainTrackDb = null, replayGainAlbumDb = null,
    )
}
