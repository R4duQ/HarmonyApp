package com.harmony.feature.discover

import com.harmony.core.model.Playlist
import com.harmony.core.model.PlayerState
import com.harmony.core.model.Song
import com.harmony.core.ui.network.InternetState
import com.harmony.domain.library.discovery.DraftStep
import com.harmony.domain.library.discovery.ExplorationLevel
import com.harmony.domain.library.discovery.ListeningEvent
import com.harmony.domain.library.repository.FavoritesRepository
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.feature.discover.provider.DiscoveryClock
import com.harmony.feature.discover.provider.DiscoveryConnectivity
import com.harmony.feature.discover.provider.LocalFileProbe
import com.harmony.feature.discover.provider.RecommendationEngine
import com.harmony.feature.discover.provider.TasteLoader
import com.harmony.feature.discover.ui.DiscoveryEvent
import com.harmony.feature.discover.ui.DiscoveryFlowViewModel
import com.harmony.feature.discover.ui.DiscoveryUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Runs the real ViewModel, engine, mixer and taste profile against fakes
 * for storage, catalogs and the platform. Main is a real single thread, so
 * debounce and background work run as they would in the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryFlowViewModelTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private val songs = MutableStateFlow((1L..6L).map { libSong(it, if (it <= 4) "Muse" else "Foo Fighters") })
    private val events = (0 until 5).map { ListeningEvent(1, now - it * day, true) } + (0 until 5).map { ListeningEvent(5, now - 200 * day - it * day, true) }
    private val playlists = MutableStateFlow(listOf<Playlist>())
    private val saved = CopyOnWriteArrayList<Pair<String, List<Long>>>()
    private val appended = CopyOnWriteArrayList<Pair<Long, List<Long>>>()
    private val net = MutableStateFlow(InternetState(ready = true))
    private val repo = InMemoryDiscoveryRepository()
    private val catalog = FakeCatalog()
    private val players = FakePreviewPlayers()
    private var paused = 0

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> fake(noinline h: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, a -> h(m.name, a ?: emptyArray()) } as T

    private val library = fake<LibraryRepository> { name, _ -> if (name == "observeSongs") songs else error("unexpected $name") }
    private val playlistRepo = fake<PlaylistRepository> { name, a ->
        when (name) {
            "observePlaylists" -> playlists
            "userPlaylistSongIds" -> emptySet<Long>()
            "saveDiscoveryBatch" -> {
                saved += (a[1] as String) to (a[2] as List<Long>)
                playlists.value = playlists.value + Playlist(-42, a[1] as String, (a[2] as List<*>).size, now)
                -42L
            }
            "addSongs" -> { appended += (a[0] as Long) to (a[1] as List<Long>); Unit }
            "rename" -> Unit
            else -> error("unexpected $name")
        }
    }
    private val history = fake<PlaybackHistoryRepository> { name, _ -> if (name == "listeningEvents") events else error("unexpected $name") }
    private val favorites = fake<FavoritesRepository> { name, _ -> if (name == "observeFavorites") flowOf(emptyList<Song>()) else error("unexpected $name") }
    private val playback = fake<PlaybackController> { name, _ ->
        when (name) { "getPlayerState" -> MutableStateFlow(PlayerState()); "pause" -> { paused++; Unit }; else -> error("unexpected $name") }
    }

    private fun viewModel() = DiscoveryFlowViewModel(library, repo, playlistRepo, RecommendationEngine(catalog), catalog,
        TasteLoader(history, favorites, playlistRepo), object : DiscoveryConnectivity { override val state = net },
        playback, players, LocalFileProbe { true }, DiscoveryClock { now })

    private fun DiscoveryFlowViewModel.await(what: String = "condition", cond: (DiscoveryUiState) -> Boolean): DiscoveryUiState =
        runBlocking { try { withTimeout(8_000) { state.first(cond) } } catch (e: Exception) { throw AssertionError("timed out waiting for $what: ${state.value}", e) } }

    /** A download finishing: the library scan now lists a file for this catalog song. */
    private fun downloaded(song: com.harmony.domain.library.repository.DiscoverySong, id: Long) {
        songs.value = songs.value + libSong(id, song.artist, song.title, ms = song.durationMs)
    }

    private fun DiscoveryFlowViewModel.generated(size: Int = 20, level: ExplorationLevel = ExplorationLevel.BALANCED): DiscoveryUiState {
        await("draft") { it.loaded && it.draft != null }
        setSize(size); setLevel(level)
        await("settings") { it.draft?.size == size && it.draft?.level == level }
        generate()
        return await("songs") { it.step == DraftStep.SONGS && !it.generating }
    }

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() { Dispatchers.resetMain(); main.close() }

    @Test fun onlineMixFollowsTheLevelAndExplainsEachSong() {
        val state = viewModel().generated(20, ExplorationLevel.BALANCED)
        val items = state.draft!!.items
        assertEquals(20, items.size)
        assertEquals(12, items.count { it.kind == com.harmony.domain.library.discovery.CandidateKind.CLOSE })
        assertTrue(items.all { it.reason.text.isNotBlank() })
        assertTrue(state.draft!!.generatedOnline)
        assertTrue("Deezer" in state.draft!!.sources)
        assertEquals(items.size, items.map { it.song.identity }.distinct().size)
    }

    @Test fun offlineBuildsFromTheLibraryAndOnlineResultsAreLabelledSavedWhenTheConnectionDrops() {
        net.value = InternetState()
        val vm = viewModel()
        val offline = vm.generated(10)
        assertTrue(offline.draft!!.items.all { it.song.localUri != null })
        assertFalse(offline.draft!!.generatedOnline)
        assertTrue(catalog.calls.isEmpty())

        net.value = InternetState(ready = true)
        vm.await("online") { it.online }
        vm.regenerate()
        vm.await("online mix") { it.draft?.generatedOnline == true && !it.generating }
        net.value = InternetState()
        assertTrue(vm.await("saved label") { !it.online }.showingSaved)
    }

    @Test fun aLateReplacementNeverOverwritesANewerChoice() {
        viewModel().generated(20)
        // A restarted app has no candidate pool, so a replacement must go back to the catalog.
        val vm = viewModel()
        val first = vm.await("restored") { it.step == DraftStep.SONGS }.draft!!.items
        val target = first[3].key
        catalog.gate = CompletableDeferred()
        vm.replace(target)
        vm.await("replacing") { target in it.replacing }
        vm.remove(target)                         // the user removes it while the answer is on its way
        vm.await("removed") { it.draft!!.items.none { i -> i.key == target } }
        catalog.gate!!.complete(Unit)
        val after = vm.await("settled") { target !in it.replacing }.draft!!.items
        assertEquals(19, after.size)                // no replacement dropped in somewhere else
        assertEquals(first.filterNot { it.key == target }.map { it.key }, after.map { it.key })
    }

    @Test fun notInterestedIsNeverOfferedAgainAndKeptSongsStay() {
        val vm = viewModel()
        val items = vm.generated(20).draft!!.items
        val declined = items[1]
        val kept = items[5]
        vm.toggleKeep(kept.key)
        vm.notInterested(declined.key)
        vm.await("declined") { declined.key in repo.state.value.declined && declined.key !in it.replacing }
        vm.regenerate()
        val again = vm.await("regenerated") { !it.generating && it.draft!!.items.none { i -> i.key == declined.key } }.draft!!.items
        assertTrue(again.none { it.key == declined.key })
        assertEquals(kept.key, again[5].key)
        assertTrue(again[5].kept)
    }

    @Test fun moreLikeThisKeepsTheSongAndAddsNeighboursRightAfterIt() {
        val vm = viewModel()
        val items = vm.generated(20).draft!!.items
        val seed = items.first { it.song.artist == "Radiohead" }
        vm.moreLikeThis(seed.key)
        val after = vm.await("more like") { it.message?.startsWith("Added songs like") == true }.draft!!.items
        val at = after.indexOfFirst { it.key == seed.key }
        assertTrue(after[at].kept)
        assertEquals(20, after.size)                      // the size holds: unkept songs at the end made room
        val added = after.filter { n -> items.none { it.key == n.key } }
        assertTrue(added.isNotEmpty())
        // New songs sit right after the one asked about, each naming its link, and the artist cap still holds.
        assertEquals(added.map { it.key }, after.subList(at + 1, at + 1 + added.size).map { it.key })
        assertTrue(added.all { it.reason.text.contains("Radiohead") })
        assertTrue(after.groupingBy { it.song.artist }.eachCount().values.all { it <= 2 })
    }

    @Test fun partialPlaylistIsCreatedOnceOpensAndFillsInAsSongsArrive() {
        val vm = viewModel()
        vm.generated(20)
        vm.goToReview()
        val review = vm.await("review") { it.step == DraftStep.REVIEW && it.progress != null }
        assertEquals(0, review.progress!!.availableCount)   // found in a catalog is not a file
        // Two songs get downloaded and scanned before the user creates the playlist.
        downloaded(review.review!!.songs[0], 90); downloaded(review.review!!.songs[1], 91)
        val available = vm.await("two available") { it.progress?.availableCount == 2 }.progress!!.availableCount

        val opened = CopyOnWriteArrayList<DiscoveryEvent>()
        val job = kotlinx.coroutines.CoroutineScope(main).launch { vm.events.collect { opened += it } }
        vm.createPlaylist(); vm.createPlaylist()        // double tap
        val created = vm.await("created") { it.review?.playlistId != null && !it.creating }
        assertEquals(1, saved.size)
        assertEquals(available, saved.single().second.size)
        assertTrue(created.review!!.locked)
        runBlocking { withTimeout(3_000) { while (opened.isEmpty()) kotlinx.coroutines.delay(20) } }
        assertEquals(DiscoveryEvent.OpenPlaylist(-42), opened.first())

        // A missing song gets downloaded and scanned: it joins the same playlist, once.
        val missing = created.review!!.songs.first { it.key !in created.progress!!.available }
        songs.value = songs.value + libSong(99, missing.artist, missing.title, ms = missing.durationMs)
        vm.await("placed") { it.review!!.placedKeys.contains(missing.key) }
        assertEquals(listOf(-42L to listOf(99L)), appended.toList())
        songs.value = songs.value + libSong(100, "Unrelated", "Other")
        Thread.sleep(1_500)
        assertEquals(1, appended.size)              // nothing placed twice

        // The user deleted the playlist: later arrivals don't recreate or touch it.
        playlists.value = emptyList()
        val next = created.review!!.songs.first { it.key !in created.progress!!.available && it.key != missing.key }
        songs.value = songs.value + libSong(101, next.artist, next.title, ms = next.durationMs)
        vm.await("deleted noticed") { it.review!!.playlistDeleted }
        assertEquals(1, appended.size)
        job.cancel()
    }

    @Test fun nothingAvailableMeansNoPlaylistAndAReason() {
        songs.value = listOf(libSong(1, "Muse"))
        val vm = viewModel()
        vm.generated(10)
        // Keep only catalog songs, none of which is in the library.
        vm.state.value.draft!!.items.filter { it.song.localUri != null }.forEach { vm.remove(it.key) }
        vm.await("remote only") { s -> s.draft!!.items.none { it.song.localUri != null } }
        vm.goToReview()
        vm.await("review") { it.step == DraftStep.REVIEW && it.progress?.availableCount == 0 }
        vm.createPlaylist()
        assertTrue(vm.await("message") { it.message != null }.message!!.startsWith("None of these songs"))
        assertTrue(saved.isEmpty())
    }

    @Test fun theSelectionSurvivesARestartAndEditingIsLockedOnlyAfterCommitting() {
        val first = viewModel().generated(15).draft!!.items.map { it.key }
        val vm = viewModel()
        assertEquals(first, vm.await("restored") { it.step == DraftStep.SONGS }.draft!!.items.map { it.key })
        vm.goToReview(); vm.await("review") { it.step == DraftStep.REVIEW }
        vm.backFromReview()
        assertEquals(DraftStep.SONGS, vm.await("back") { it.step == DraftStep.SONGS }.step)
        assertTrue(repo.state.value.batches.isEmpty())   // an uncommitted review leaves nothing behind
        vm.goToReview(); vm.await("review 2") { it.step == DraftStep.REVIEW }
        downloaded(vm.state.value.review!!.songs[0], 90)
        vm.await("available") { it.progress?.availableCount == 1 }
        vm.createPlaylist(); vm.await("created") { it.review?.playlistId != null }
        vm.backFromReview()
        val fresh = vm.await("new selection") { it.step == DraftStep.PREFERENCES }
        assertTrue(fresh.draft!!.items.isEmpty())
        assertEquals(1, fresh.batches.size)
    }

    @Test fun previewFollowsTheCardOnScreen() {
        val vm = viewModel()
        val items = vm.generated(10).draft!!.items
        vm.togglePreview(items[0])
        runBlocking { withTimeout(3_000) { vm.preview.state.first { it.songId == items[0].key } } }
        runBlocking { withTimeout(3_000) { while (players.created.isEmpty()) kotlinx.coroutines.delay(10) } }
        assertEquals(1, paused)
        vm.focus(items[1].key)
        runBlocking { withTimeout(3_000) { vm.preview.state.first { it.songId == null } } }
        assertTrue(players.created.single().released)
    }

    @Test fun aFailedSaveKeepsTheOldStateAndSaysSo() {
        val vm = viewModel()
        val items = vm.generated(10).draft!!.items
        repo.failWrites = true
        vm.remove(items[0].key)
        assertNotNull(vm.await("message") { it.message != null }.message)
        assertEquals(items.map { it.key }, vm.state.value.draft!!.items.map { it.key })
    }
}
