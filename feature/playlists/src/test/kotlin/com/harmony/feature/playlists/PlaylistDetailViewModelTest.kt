package com.harmony.feature.playlists

import androidx.lifecycle.SavedStateHandle
import com.harmony.core.model.Playlist
import com.harmony.core.model.PlayerState
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.playback.usecase.PlaySongsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {

    private val repo = FakePlaylists()
    private val playerState = MutableStateFlow(PlayerState())
    private val queued = mutableListOf<List<Song>>()
    private val playback = Proxy.newProxyInstance(
        PlaybackController::class.java.classLoader,
        arrayOf(PlaybackController::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getPlayerState" -> playerState
            "setQueue" -> { @Suppress("UNCHECKED_CAST") queued += args[0] as List<Song>; Unit }
            else -> Unit
        }
    } as PlaybackController

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(vararg args: Pair<String, Any>) =
        PlaylistDetailViewModel(SavedStateHandle(mapOf(*args)), repo, PlaySongsUseCase(playback), playback)

    @Test
    fun `user playlist starts loading, then shows its songs`() = runTest {
        val vm = vm("playlistId" to 7L)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertNull("loading, not empty", vm.uiState.value.songs)
        assertNull(vm.uiState.value.title)

        repo.playlists.emit(listOf(Playlist(7, "Road Trip", 0, 2)))
        repo.songs.emit(listOf(song(1), song(2)))

        val ui = vm.uiState.value
        assertEquals("Road Trip", ui.title)
        assertEquals(listOf(1L, 2L), ui.songs?.map { it.id })
        assertTrue(ui.editable)
        assertFalse(ui.missing)
    }

    @Test
    fun `a playlist deleted while open shows the gone state`() = runTest {
        val vm = vm("playlistId" to 7L)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        repo.playlists.emit(emptyList())
        repo.songs.emit(emptyList())
        val ui = vm.uiState.value
        assertTrue(ui.missing)
        assertFalse("nothing to edit", ui.editable)
    }

    @Test
    fun `smart playlist has its title at once and is read-only`() = runTest {
        val vm = vm("smartType" to SmartPlaylistType.FAVORITES.name)
        assertEquals("Favorites", vm.uiState.value.title)
        assertFalse(vm.uiState.value.editable)
        assertFalse(vm.canExport)
    }

    @Test
    fun `unknown smart type no longer crashes`() = runTest {
        val vm = vm("smartType" to "NOT_A_TYPE")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertTrue(vm.uiState.value.missing)
    }

    @Test
    fun `now playing follows the player`() = runTest {
        val vm = vm("playlistId" to 7L)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        repo.playlists.emit(listOf(Playlist(7, "Mix", 0, 1)))
        repo.songs.emit(listOf(song(1)))
        playerState.value = PlayerState(currentSong = song(1))
        assertEquals(1L, vm.uiState.value.nowPlayingId)
    }

    @Test
    fun `play and shuffle ignore empty lists and bad indices`() = runTest {
        val vm = vm("playlistId" to 7L)
        vm.play(emptyList(), 0)
        vm.play(listOf(song(1)), 3)
        vm.shuffle(emptyList())
        assertTrue(queued.isEmpty())
        vm.shuffle(listOf(song(1), song(2), song(3)))
        assertEquals(setOf(1L, 2L, 3L), queued.single().map { it.id }.toSet())
    }

    @Test
    fun `export file is named after the playlist`() = runTest {
        val vm = vm("playlistId" to 7L)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        repo.playlists.emit(listOf(Playlist(7, "Summer: 2024/25?", 0, 0)))
        repo.songs.emit(emptyList())
        assertEquals("Summer  2024 25.m3u", vm.exportFileName())
    }

    private class FakePlaylists : PlaylistRepository {
        val playlists = MutableSharedFlow<List<Playlist>>(replay = 1)
        val songs = MutableSharedFlow<List<Song>>(replay = 1)
        override fun observePlaylists(): Flow<List<Playlist>> = playlists
        override fun observePlaylistCount(): Flow<Int> = emptyFlow()
        override fun observePlaylistArtwork(): Flow<Map<Long, List<String>>> = emptyFlow()
        override fun observePlaylistDurations(): Flow<Map<Long, Long>> = emptyFlow()
        override fun observePlaylistSongs(playlistId: Long): Flow<List<Song>> = songs
        override fun observeSmartPlaylist(type: SmartPlaylistType, limit: Int): Flow<List<Song>> = songs
        override suspend fun create(name: String) = 0L
        override suspend fun rename(playlistId: Long, name: String) = Unit
        override suspend fun delete(playlistId: Long) = Unit
        override suspend fun addSongs(playlistId: Long, songIds: List<Long>) = Unit
        override suspend fun replaceSongs(playlistId: Long, songIds: List<Long>) = Unit
        override suspend fun removeSong(playlistId: Long, songId: Long) = Unit
        override suspend fun moveSong(playlistId: Long, fromPosition: Int, toPosition: Int) = Unit
        override suspend fun exportM3u(playlistId: Long) = ""
        override suspend fun importM3u(name: String, m3uContent: String) = 0L
    }

    private fun song(id: Long) = Song(
        id = id, uri = "u$id", title = "Song $id", artist = "A", album = "Al", albumId = 1,
        albumArtist = null, composer = null, year = null, genre = null, discNumber = null,
        trackNumber = null, durationMs = 1000, bitrateKbps = null, sampleRateHz = null,
        bitDepth = null, channels = null, artworkUri = null, embeddedLyrics = null,
        replayGainTrackDb = null, replayGainAlbumDb = null,
    )
}
