package com.harmony.feature.discover.ui

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.core.model.Song
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.RepeatMode
import com.harmony.core.ui.network.InternetMonitor
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.feature.discover.model.*
import com.harmony.feature.discover.provider.AlbumRecommendationEngine
import com.harmony.feature.discover.provider.DiscoveryPreferencesStore
import com.harmony.feature.discover.provider.ShflAlbumCatalog
import com.harmony.feature.discover.provider.AlbumBatches
import com.harmony.feature.discover.provider.AlbumBatchHistory
import com.harmony.feature.discover.provider.SongTasteEngine
import com.harmony.feature.discover.provider.SongPreviewClient
import com.harmony.feature.discover.provider.SwipeRoundEngine
import com.harmony.feature.discover.provider.RoundAlbumRecommender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class DiscoverUiState(
    val albums: List<AlbumSuggestion> = emptyList(),
    val shelf: AlbumShelf = AlbumShelf.FOR_YOU,
    val genre: AlbumGenre? = null,
    val seed: Long = 0L,
    val savedCount: Int = 0,
    val familiarCount: Int = 0,
    val loading: Boolean = true,
    val libraryUnavailable: Boolean = false,
    val ranked: List<AlbumSuggestion> = emptyList(),
    val remainingCount: Int = 0,
    val canRefresh: Boolean = false,
    val batchRevision: Long = 0,
    val batchNumber: Int = 1,
    val tasteSongs: List<TasteSong> = emptyList(),
    val likedCount: Int = 0,
    val ratedCount: Int = 0,
    val round: SwipeRound = SwipeRound(),
    val roundRecommendation: ExplainedAlbum? = null,
    val canFinishRemaining: Boolean = false,
    val roundLikedCount: Int = 0,
    val roundAlbumSaved: Boolean = false,
    val hasUnratedSongs: Boolean = true,
)

data class DiscoverMessage(val text: String, val undoListenedId: String? = null)
data class PreviewState(val songId: String? = null, val loading: Boolean = false,
    val playing: Boolean = false, val message: String? = null)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val store: DiscoveryPreferencesStore,
    private val playback: PlaybackController,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val internet = InternetMonitor.get(context).state
    val ratingBusy = MutableStateFlow(false)
    val preview = MutableStateFlow(PreviewState())
    private val previewClient = SongPreviewClient()
    private var previewPlayer: ExoPlayer? = null
    private var previewJob: Job? = null
    private var previewTimeout: Job? = null
    private val preferences = store.states.stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryPreferences())
    val undoSongId = preferences.map { it.round.songIds.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val songSeed = savedState.get<Long>("discover_seed") ?: Random.nextLong().also {
        savedState["discover_seed"] = it
    }
    private val messageChannel = Channel<DiscoverMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private data class LibraryState(val matches: Map<String, AlbumLibraryMatch>, val unavailable: Boolean = false)
    private data class Options(
        val shelf: AlbumShelf,
        val genre: AlbumGenre?,
        val seed: Long,
        val history: AlbumBatchHistory = AlbumBatchHistory(),
        val revision: Long = 0,
        val batchNumber: Int = 1,
    )

    // Filters, ordering and batch history must change together. Separate SavedStateHandle
    // flows allowed a refresh to combine the previous screen with a newly selected filter.
    private val options = MutableStateFlow(Options(
        shelf = AlbumShelf.entries.firstOrNull { it.name == savedState.get<String>("discover_shelf") } ?: AlbumShelf.FOR_YOU,
        genre = AlbumGenre.entries.firstOrNull { it.name == savedState.get<String>("discover_genre") },
        seed = savedState.get<Long>("discover_album_seed") ?: songSeed,
        history = AlbumBatchHistory(
            seen = savedState.get<ArrayList<String>>("discover_seen")?.toSet().orEmpty(),
            deferred = savedState.get<ArrayList<String>>("discover_deferred")?.toSet().orEmpty(),
        ),
        revision = savedState.get<Long>("discover_batch_revision") ?: 0,
        batchNumber = savedState.get<Int>("discover_batch_number")?.coerceAtLeast(1) ?: 1,
    ))

    private val matches = library.observeSongs().map { songs ->
        LibraryState(AlbumRecommendationEngine.matchLibrary(ShflAlbumCatalog.albums, songs))
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(LibraryState(emptyMap(), unavailable = true))
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<DiscoverUiState> = combine(matches, preferences, options) { local, prefs, selection ->
        val ranked = AlbumRecommendationEngine.suggestions(ShflAlbumCatalog.albums, local.matches, prefs,
            selection.shelf, selection.genre, selection.seed)
        val batchSeen = AlbumBatches.activeSeen(ranked, selection.history.seen)
        val batch = AlbumBatches.page(ranked, selection.history)
        DiscoverUiState(
            albums = batch, ranked = ranked,
            remainingCount = ranked.count { it.album.id !in batchSeen } - batch.size,
            canRefresh = ranked.size > batch.size,
            batchRevision = selection.revision, batchNumber = selection.batchNumber,
            tasteSongs = SongTasteEngine.unplayed(prefs, selection.genre, songSeed),
            likedCount = prefs.likedSongs.size,
            ratedCount = prefs.likedSongs.size + prefs.dislikedSongs.size,
            round = prefs.round,
            roundLikedCount = prefs.round.songIds.count { it in prefs.likedSongs },
            roundAlbumSaved = prefs.round.albumId in prefs.saved,
            roundRecommendation = RoundAlbumRecommender.explain(prefs),
            canFinishRemaining = SwipeRoundEngine.canFinishRemaining(prefs),
            hasUnratedSongs = SwipeRoundEngine.hasUnratedSongs(prefs),
            shelf = selection.shelf, genre = selection.genre, seed = selection.seed,
            savedCount = prefs.saved.size,
            familiarCount = local.matches.count { it.value.entrySong != null || it.value.albumSongs.isNotEmpty() },
            loading = false, libraryUnavailable = local.unavailable,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoverUiState())

    init {
        viewModelScope.launch { internet.collect { if (!it.ready) stopPreview() } }
        viewModelScope.launch { playback.playerState.map { it.isPlaying }.distinctUntilChanged().collect {
            if (it && preview.value.songId != null) stopPreview()
        } }
    }

    fun selectShelf(shelf: AlbumShelf) {
        if (shelf == options.value.shelf) return
        updateOptions(options.value.copy(shelf = shelf, history = AlbumBatchHistory(), batchNumber = 1))
    }

    fun selectGenre(genre: AlbumGenre?) {
        if (genre == options.value.genre) return
        updateOptions(options.value.copy(genre = genre, history = AlbumBatchHistory(), batchNumber = 1))
        stopPreview()
    }

    fun refresh(displayedRevision: Long = uiState.value.batchRevision) {
        if (!internet.value.ready) return
        val state = uiState.value
        val selection = options.value
        // Ignore double taps and callbacks from the deck that is being replaced.
        if (!state.canRefresh || displayedRevision != selection.revision || state.batchRevision != selection.revision) return
        updateOptions(selection.copy(
            seed = selection.seed + 1,
            history = AlbumBatches.advance(state.ranked, selection.history, state.albums),
            batchNumber = selection.batchNumber + 1,
        ))
    }

    private fun updateOptions(next: Options) {
        val updated = next.copy(revision = options.value.revision + 1)
        savedState["discover_current_album"] = null
        savedState["discover_current_album_index"] = 0
        savedState["discover_shelf"] = updated.shelf.name
        savedState["discover_genre"] = updated.genre?.name ?: "all"
        savedState["discover_album_seed"] = updated.seed
        savedState["discover_seen"] = ArrayList(updated.history.seen)
        savedState["discover_deferred"] = ArrayList(updated.history.deferred)
        savedState["discover_batch_revision"] = updated.revision
        savedState["discover_batch_number"] = updated.batchNumber
        options.value = updated
    }

    fun rate(song: TasteSong, liked: Boolean): Job? {
        if (!internet.value.ready || ratingBusy.value || preferences.value.round.completed) return null
        if (song.id in preferences.value.likedSongs || song.id in preferences.value.dislikedSongs) return null
        stopPreview()
        ratingBusy.value = true
        return viewModelScope.launch {
            try {
                val persisted = store.rateSong(song.id, liked)
                if (song.id !in persisted.likedSongs && song.id !in persisted.dislikedSongs) return@launch
                // Wait for the persisted state to reach the UI before accepting another swipe.
                preferences.first { song.id in it.likedSongs || song.id in it.dislikedSongs }
                updateOptions(options.value.copy(history = AlbumBatchHistory(), batchNumber = 1))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { messageChannel.send(DiscoverMessage("Your choice could not be saved. Try again.")) }
            finally { ratingBusy.value = false }
        }
    }

    fun undoRating() {
        val id = preferences.value.round.songIds.lastOrNull() ?: return
        if (ratingBusy.value || !internet.value.ready) return
        stopPreview()
        ratingBusy.value = true
        viewModelScope.launch {
            try {
                val persisted = store.rateSong(id, null)
                if (id in persisted.likedSongs || id in persisted.dislikedSongs) return@launch
                preferences.first { id !in it.likedSongs && id !in it.dislikedSongs }
                updateOptions(options.value.copy(history = AlbumBatchHistory(), batchNumber = 1))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { messageChannel.send(DiscoverMessage("Could not undo that choice. Try again.")) }
            finally { ratingBusy.value = false }
        }
    }

    fun nextRound() = changeRound { store.nextRound() }
    fun finishRemaining() = changeRound { store.finishRemaining() }

    private fun changeRound(action: suspend () -> Unit) {
        if (!internet.value.ready || ratingBusy.value) return
        stopPreview()
        ratingBusy.value = true
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { messageChannel.send(DiscoverMessage("Could not save this round. Please try again.")) }
            finally { ratingBusy.value = false }
        }
    }

    fun playPreview(song: TasteSong) {
        if (!internet.value.ready) return
        if (preview.value.songId == song.id && (preview.value.playing || preview.value.loading)) { stopPreview(); return }
        stopPreview()
        preview.value = PreviewState(song.id, loading = true)
        // Covers library lookup and the network request too, before ExoPlayer exists.
        previewTimeout = viewModelScope.launch {
            delay(20_000)
            if (preview.value.songId == song.id && preview.value.loading) {
                stopPreview()
                preview.value = PreviewState(song.id, message = "Preview timed out. Try again on a stable connection.")
            }
        }
        previewJob = viewModelScope.launch {
            try {
                playback.pause()
                val songs = library.observeSongs().first()
                val local = withContext(Dispatchers.Default) {
                    AlbumRecommendationEngine.matchLibrary(listOf(song.album.copy(entryTracks = listOf(song.title))), songs)[song.album.id]?.entrySong
                }
                val uri = local?.uri ?: previewClient.find(song)
                ensureActive()
                if (uri == null) {
                    previewTimeout?.cancel(); previewTimeout = null
                    preview.value = PreviewState(song.id, message = "No preview for this recording. Skip it or rate it if you know it.")
                    return@launch
                }
                check(internet.value.ready) { internet.value.message }
                ensureActive()
                val player = ExoPlayer.Builder(context).build()
                previewPlayer = player
                player.setAudioAttributes(AudioAttributes.DEFAULT, true)
                player.setHandleAudioBecomingNoisy(true)
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (previewPlayer !== player) return
                        if (state == Player.STATE_ENDED) stopPreview()
                        else if (state == Player.STATE_READY) {
                            previewTimeout?.cancel(); previewTimeout = null
                            preview.value = PreviewState(song.id, playing = player.isPlaying,
                                message = if (local == null) "30-second preview · Deezer" else "30-second excerpt · your library")
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (previewPlayer === player) preview.update { it.copy(playing = isPlaying) }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (previewPlayer !== player) return
                        stopPreview()
                        preview.value = PreviewState(song.id, message = "This preview could not play. Try again or skip this song.")
                    }
                })
                player.setMediaItem(MediaItem.Builder().setUri(uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(30_000).build()).build())
                player.prepare(); player.play()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                previewTimeout?.cancel(); previewTimeout = null
                previewPlayer?.release(); previewPlayer = null
                preview.value = PreviewState(song.id, message = "Preview unavailable right now. Try again or skip this song.")
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel(); previewJob = null
        previewTimeout?.cancel(); previewTimeout = null
        val player = previewPlayer; previewPlayer = null; player?.release()
        preview.value = PreviewState()
    }

    override fun onCleared() { stopPreview(); super.onCleared() }

    fun rememberAlbum(id: String, displayedRevision: Long = uiState.value.batchRevision) {
        if (displayedRevision != options.value.revision || displayedRevision != uiState.value.batchRevision) return
        if (uiState.value.albums.none { it.album.id == id }) return
        savedState["discover_current_album"] = id
        uiState.value.albums.indexOfFirst { it.album.id == id }.takeIf { it >= 0 }?.let {
            savedState["discover_current_album_index"] = it
        }
    }
    fun restoredAlbumId(): String? = savedState["discover_current_album"]
    fun restoredAlbumIndex(): Int = savedState["discover_current_album_index"] ?: 0
    fun toggleSaved(id: String) = mutate { store.toggleSaved(id) }
    fun toggleFamiliar(id: String) = mutate { store.toggleFamiliar(id) }

    fun setListened(id: String, value: Boolean) = mutate {
        store.setListened(id, value)
        if (value) messageChannel.send(DiscoverMessage("Marked as listened. Still available in All albums.", id))
    }

    fun playEntry(item: AlbumSuggestion) { item.startingSong?.let { play(listOf(it)) } }
    fun playLocalAlbum(item: AlbumSuggestion) = play(item.match.albumSongs, inOrder = true)

    private fun play(songs: List<Song>, inOrder: Boolean = false) {
        if (songs.isEmpty()) return
        stopPreview()
        viewModelScope.launch {
            try {
                if (inOrder) {
                    playback.setShuffleMode(ShuffleMode.OFF)
                    playback.setRepeatMode(RepeatMode.OFF)
                }
                playback.setQueue(songs, playWhenReady = true)
            }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                messageChannel.send(DiscoverMessage("These local tracks could not be played. Check that the files are available."))
            }
        }
    }

    private fun mutate(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                messageChannel.send(DiscoverMessage("Your album choice could not be saved. Please try again."))
            }
        }
    }
}
