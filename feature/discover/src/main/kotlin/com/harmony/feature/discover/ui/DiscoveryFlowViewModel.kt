package com.harmony.feature.discover.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.domain.library.discovery.Candidate
import com.harmony.domain.library.discovery.DiscoveryDraft
import com.harmony.domain.library.discovery.DraftItem
import com.harmony.domain.library.discovery.DraftStep
import com.harmony.domain.library.discovery.ExplorationLevel
import com.harmony.domain.library.discovery.FeedbackKind
import com.harmony.domain.library.discovery.Genres
import com.harmony.domain.library.discovery.LibraryIndex
import com.harmony.domain.library.discovery.PlaylistPlacement
import com.harmony.domain.library.discovery.RecommendationMixer
import com.harmony.domain.library.discovery.TasteFeedback
import com.harmony.domain.library.discovery.TasteProfile
import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.library.repository.DiscoveryBatch
import com.harmony.domain.library.repository.DiscoveryFilter
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.library.repository.RecommendationStamp
import com.harmony.domain.library.repository.SongDiscoveryRepository
import com.harmony.domain.library.repository.SongDiscoveryState
import com.harmony.domain.playback.PlaybackController
import com.harmony.feature.discover.provider.DiscoveryCatalog
import com.harmony.feature.discover.provider.DiscoveryClock
import com.harmony.feature.discover.provider.DiscoveryConnectivity
import com.harmony.feature.discover.provider.LocalFileProbe
import com.harmony.feature.discover.provider.RecommendationEngine
import com.harmony.feature.discover.provider.RecommendationOutcome
import com.harmony.feature.discover.provider.RecommendationRequest
import com.harmony.feature.discover.provider.TasteLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * The three-step Discover flow: preferences → songs → playlist.
 *
 * Durable state (the draft, feedback, saved selections) lives in
 * [SongDiscoveryRepository] and is written after every change; this class
 * only keeps what can be rebuilt: the candidate pool, work in progress, and
 * one-shot messages. Every asynchronous result is applied against the state
 * at the time it lands (by generation token or by song key), so a slow answer
 * never overwrites a newer choice.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class DiscoveryFlowViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val repository: SongDiscoveryRepository,
    private val playlists: PlaylistRepository,
    private val engine: RecommendationEngine,
    private val catalog: DiscoveryCatalog,
    private val taste: TasteLoader,
    private val connectivity: DiscoveryConnectivity,
    private val playback: PlaybackController,
    previewPlayers: PreviewPlayerFactory,
    private val files: LocalFileProbe,
    private val clock: DiscoveryClock,
) : ViewModel() {

    private data class Transient(
        val work: DiscoveryWork = DiscoveryWork.Idle,
        val replacing: Set<String> = emptySet(),
        val notes: List<String> = emptyList(),
        val message: String? = null,
        val taste: TasteSummary = TasteSummary(),
        val genres: List<String> = emptyList(),
        val artistQuery: String = "",
        val artistResults: List<String> = emptyList(),
        val creating: Boolean = false,
    )

    private val transient = MutableStateFlow(Transient())
    private val index: StateFlow<LibraryIndex?> = library.observeSongs()
        .catch { emit(emptyList()); transient.update { t -> t.copy(message = "Your library couldn't be read. Check music permissions.") } }
        .map { withContext(Dispatchers.Default) { LibraryIndex(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mutableEvents = MutableSharedFlow<DiscoveryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<DiscoveryEvent> = mutableEvents.asSharedFlow()

    val preview = PreviewController(viewModelScope, previewPlayers,
        resolve = { song ->
            val local = song.localUri ?: index.value?.match(song)?.uri
            val readable = local?.takeIf { withContext(Dispatchers.IO) { files.readable(it) } }
            if (readable != null) readable to true
            else if (!connectivity.state.value.ready) null
            else catalog.preview(song)?.let { it to false }
        },
        beforeStart = { playback.pause() })

    val state: StateFlow<DiscoveryUiState> = combine(
        repository.state, transient, index, connectivity.state, catalog.status,
    ) { s, t, idx, net, sources ->
        val review = s.revealedBatch?.let { id -> s.batches.firstOrNull { it.id == id } }
        val step = when {
            review != null -> DraftStep.REVIEW
            s.draft?.let { it.step == DraftStep.SONGS && it.items.isNotEmpty() } == true -> DraftStep.SONGS
            else -> DraftStep.PREFERENCES
        }
        DiscoveryUiState(
            loaded = idx != null,
            step = step,
            draft = s.draft,
            review = review,
            progress = if (review != null && idx != null) PlaylistPlacement.progress(review, idx) else null,
            connection = when {
                net.ready -> Connection.ONLINE
                net.secondsUntilReady > 0 -> Connection.RECONNECTING
                else -> Connection.OFFLINE
            },
            reconnectSeconds = net.secondsUntilReady,
            sources = sources.values.toList(),
            work = t.work,
            replacing = t.replacing,
            notes = t.notes,
            message = t.message,
            taste = t.taste,
            genres = t.genres,
            artistQuery = t.artistQuery,
            artistResults = t.artistResults,
            batches = s.batches.asReversed().map { b ->
                BatchSummary(b.id, b.name, b.songs.size,
                    if (idx != null) PlaylistPlacement.progress(b, idx).availableCount else b.uris.size, b.playlistId)
            },
            creating = t.creating,
            libraryEmpty = idx?.songs?.isEmpty() == true,
            filter = s.filter,
        )
    }.flowOn(Dispatchers.Default) // availability matching is per song; keep it off the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoveryUiState())

    /** The candidate pool behind the current draft. Rebuilt on demand after process death. */
    private var pool: List<Candidate> = emptyList()
    private var poolLevel: ExplorationLevel? = null
    private var generation = 0L
    private var generateJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { ensureDraft() }
        // Taste summary for step 1, refreshed when the library or the picks change.
        viewModelScope.launch {
            combine(index.filterNotNull(), repository.state.map { it.draft?.pickedArtists to it.draft?.pickedGenres }.distinctUntilChanged()) { idx, _ -> idx }
                .debounce(300)
                .collect { idx -> refreshTaste(idx) }
        }
        // Genres to pick from: Deezer's list when reachable, the library's own tags otherwise.
        viewModelScope.launch {
            val fromLibrary = index.filterNotNull().first().songs.mapNotNull { it.genre }.flatMap(Genres::split)
                .filter(Genres::valid).groupingBy { Genres.display(it) }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            val online = try { if (connectivity.state.value.ready) catalog.genres().map { it.name } else emptyList() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            transient.update { it.copy(genres = (online + fromLibrary + DEFAULT_GENRES).distinctBy(Genres::family).take(40)) }
        }
        // Songs that became available (downloaded, scanned) join their playlist, even after a restart.
        viewModelScope.launch {
            combine(index.filterNotNull(), repository.state.map { it.batches }.distinctUntilChanged()) { idx, batches -> idx to batches }
                .debounce(1_000)
                .collect { (idx, batches) -> reconcile(idx, batches) }
        }
        // Main playback starting again ends any preview.
        viewModelScope.launch {
            playback.playerState.map { it.isPlaying }.distinctUntilChanged().collect { if (it) preview.stop() }
        }
    }

    // -- Step 1: preferences --------------------------------------------------

    fun setLevel(level: ExplorationLevel) = editDraft { it.copy(level = level) }

    fun setSize(size: Int) {
        val target = size.coerceIn(RecommendationMixer.MIN_SIZE, RecommendationMixer.MAX_SIZE)
        val draft = repository.state.value.draft ?: return
        if (draft.step == DraftStep.SONGS && draft.items.isNotEmpty()) {
            if (target < draft.items.size) {
                // Shrink: drop songs that aren't kept, from the end.
                val drop = draft.items.size - target
                val removable = draft.items.withIndex().filter { !it.value.kept }.map { it.index }.takeLast(drop).toSet()
                editDraft { d -> d.copy(size = target, items = d.items.filterIndexed { i, _ -> i !in removable }) }
            } else {
                editDraft { it.copy(size = target) }
                fill()
            }
        } else editDraft { it.copy(size = target) }
    }

    fun togglePickedArtist(name: String) = editDraft { d ->
        val exists = d.pickedArtists.any { it.equals(name, true) }
        d.copy(pickedArtists = if (exists) d.pickedArtists.filterNot { it.equals(name, true) } else (d.pickedArtists + name).takeLast(12))
    }

    fun togglePickedGenre(name: String) = editDraft { d ->
        val exists = d.pickedGenres.any { Genres.family(it) == Genres.family(name) }
        d.copy(pickedGenres = if (exists) d.pickedGenres.filterNot { Genres.family(it) == Genres.family(name) } else (d.pickedGenres + name).takeLast(8))
    }

    fun setFilter(filter: DiscoveryFilter) = persist { it.copy(filter = filter) }

    /** Artist search for listeners without history. The newest query wins; older answers are dropped. */
    fun searchArtists(query: String) {
        transient.update { it.copy(artistQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) { transient.update { it.copy(artistResults = emptyList()) }; return }
        searchJob = viewModelScope.launch {
            delay(350)
            val local = index.value?.songs.orEmpty().asSequence().map { it.artist }
                .filter { it.contains(query, ignoreCase = true) && TrackIdentity.primaryArtist(it) !in Genres.unknownArtists }
                .distinctBy(TrackIdentity::primaryArtist).take(6).toList()
            val remote = if (connectivity.state.value.ready) try { catalog.searchArtists(query, 8).map { it.name } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } else emptyList()
            if (transient.value.artistQuery == query) {
                transient.update { it.copy(artistResults = (local + remote).distinctBy(TrackIdentity::primaryArtist).take(10)) }
            }
        }
    }

    /** Step 1 → 2. Online sources only with working internet; the library alone otherwise. */
    fun generate() {
        if (state.value.generating) return
        val draft = repository.state.value.draft ?: return
        startGeneration(draft, keepExisting = false)
    }

    // -- Step 2: songs ---------------------------------------------------------

    /** Replaces everything that isn't kept. */
    fun regenerate() {
        val draft = repository.state.value.draft ?: return
        startGeneration(draft, keepExisting = true)
    }

    fun changeLevel(level: ExplorationLevel) {
        val draft = repository.state.value.draft ?: return
        if (draft.level == level) return
        viewModelScope.launch {
            write { s -> s.copy(draft = s.draft?.copy(level = level)) } ?: return@launch
            repository.state.value.draft?.let { startGeneration(it, keepExisting = true) }
        }
    }

    fun toggleKeep(key: String) = editDraft { d -> d.copy(items = d.items.map { if (it.key == key) it.copy(kept = !it.kept) else it }) }

    fun remove(key: String) = editDraft { d -> d.copy(items = d.items.filterNot { it.key == key }) }

    fun replace(key: String) = replaceSlot(key, declined = false)

    /** Never offer this song again, and lean away from it a little. One song, not the artist. */
    fun notInterested(key: String) = replaceSlot(key, declined = true)

    fun moreLikeThis(key: String) {
        val draft = repository.state.value.draft ?: return
        val item = draft.items.firstOrNull { it.key == key } ?: return
        if (key in transient.value.replacing) return
        transient.update { it.copy(replacing = it.replacing + key) }
        viewModelScope.launch {
            try {
                write { s -> s.copy(
                    draft = s.draft?.let { d -> d.copy(items = d.items.map { if (it.key == key) it.copy(kept = true) else it }) },
                    feedback = (s.feedback + TasteFeedback(FeedbackKind.MORE_LIKE_THIS, item.key, item.song.title, item.song.artist, item.song.genres, clock.now())).takeLast(500),
                ) } ?: return@launch
                val profile = loadProfile(draft)
                val outcome = withContext(Dispatchers.Default) { engine.moreLike(item, request(draft, profile)) }
                pool = (pool + outcome.candidates).distinctBy { it.song.identity }
                write { s ->
                    val d = s.draft ?: return@write s
                    val at = d.items.indexOfFirst { it.key == key }
                    if (at < 0) return@write s // the song was removed meanwhile: nothing to anchor to
                    val cap = RecommendationMixer.artistCap(d.size)
                    val perArtist = d.items.groupingBy { TrackIdentity.primaryArtist(it.song.artist) }.eachCount().toMutableMap()
                    val picks = outcome.candidates.sortedByDescending { it.score }.filter { c ->
                        d.items.none { sameSong(it, c) } && c.song.key !in s.declined && c.song.identity !in s.declined
                    }.filter { c ->
                        val a = TrackIdentity.primaryArtist(c.song.artist)
                        ((perArtist[a] ?: 0) < cap).also { ok -> if (ok) perArtist.merge(a, 1, Int::plus) }
                    }.take(MORE_LIKE_COUNT).map { DraftItem(it.song, it.kind, it.reason) }
                    if (picks.isEmpty()) return@write s
                    // Make room by dropping unkept songs from the end, then insert right after the seed.
                    val removable = d.items.withIndex().filter { !it.value.kept && it.index != at }.map { it.index }
                        .takeLast((d.items.size + picks.size - d.size).coerceAtLeast(0)).toSet()
                    val kept = d.items.filterIndexed { i, _ -> i !in removable }.toMutableList()
                    kept.addAll(kept.indexOfFirst { it.key == key } + 1, picks)
                    s.copy(draft = d.copy(items = kept, shown = d.shown + picks.map { it.identity }))
                }
                val added = outcome.candidates.isNotEmpty()
                message(if (added) "Added songs like “${item.song.title}” and kept it." else
                    "Kept “${item.song.title}”. No similar songs found right now.")
                if (outcome.problems.isNotEmpty()) transient.update { it.copy(notes = outcome.problems) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message(e.message ?: "Couldn't find similar songs. Try again.")
            } finally {
                transient.update { it.copy(replacing = it.replacing - key) }
            }
        }
    }

    /** Tops the selection back up to its size after removals. */
    fun fill() {
        viewModelScope.launch {
            val draft = repository.state.value.draft ?: return@launch
            val candidates = candidatesFor(draft) ?: return@launch
            val declined = repository.state.value.declined
            write { s ->
                val d = s.draft ?: return@write s
                val filled = RecommendationMixer.fill(d.items, d.size, candidates, d.level, declined, stale(s, d), seed())
                s.copy(draft = d.copy(items = filled, shown = d.shown + filled.map { it.identity }))
            }
        }
    }

    fun focus(key: String?) = preview.onFocus(key)

    fun togglePreview(item: DraftItem) = preview.toggle(item.song)

    /** Back to a saved selection from step 1. */
    fun resume() = editDraft { if (it.items.isNotEmpty()) it.copy(step = DraftStep.SONGS) else it }

    fun backToPreferences() {
        preview.stop()
        editDraft { it.copy(step = DraftStep.PREFERENCES) }
    }

    // -- Step 3: playlist ---------------------------------------------------------

    fun goToReview() {
        preview.stop()
        viewModelScope.launch {
            val idx = index.value ?: return@launch
            write { s ->
                val d = s.draft ?: return@write s
                if (d.items.isEmpty()) return@write s
                val id = d.batchId ?: d.id
                val existing = s.batches.firstOrNull { it.id == id }
                if (existing?.locked == true) return@write s.copy(revealedBatch = id)
                val songs = d.items.map { it.song }
                val batch = DiscoveryBatch(
                    id = id,
                    name = d.name.ifBlank { defaultName() },
                    songs = songs,
                    uris = songs.mapNotNull { song -> idx.match(song)?.let { song.key to it.uri } }.toMap(),
                    status = "Ready",
                    reasons = d.items.associate { it.key to it.reason.text },
                    source = existing?.source ?: "SPOTIFLAC",
                    format = existing?.format ?: "FLAC_LOSSLESS",
                )
                s.copy(batches = s.batches.filterNot { it.id == id } + batch, revealedBatch = id,
                    draft = d.copy(step = DraftStep.REVIEW, batchId = id, name = batch.name))
            }
        }
    }

    /** Back to editing songs. Not once a download started or a playlist exists: then it's a new selection. */
    fun backFromReview() {
        viewModelScope.launch {
            write { s ->
                val batch = s.revealedBatch?.let { id -> s.batches.firstOrNull { it.id == id } } ?: return@write s.copy(revealedBatch = null)
                val linked = s.draft?.batchId == batch.id
                when {
                    !linked -> s.copy(revealedBatch = null)
                    batch.locked -> s.copy(revealedBatch = null, draft = freshDraft(s.draft))
                    else -> s.copy(revealedBatch = null, batches = s.batches.filterNot { it.id == batch.id },
                        draft = s.draft?.copy(step = DraftStep.SONGS, batchId = null))
                }
            }
        }
    }

    fun openBatch(id: String) = persist { s -> if (s.batches.any { it.id == id }) s.copy(revealedBatch = id) else s }

    fun startNewSelection() = persist { s -> s.copy(revealedBatch = null, draft = freshDraft(s.draft)) }

    fun rename(name: String) {
        val clean = name.trim().take(80)
        if (clean.isBlank()) return
        viewModelScope.launch {
            val batch = state.value.review ?: return@launch
            write { s -> s.copy(
                batches = s.batches.map { if (it.id == batch.id) it.copy(name = clean) else it },
                draft = s.draft?.let { d -> if (d.batchId == batch.id) d.copy(name = clean) else d },
            ) } ?: return@launch
            batch.playlistId?.let { id -> safely("Couldn't rename the playlist.") { playlists.rename(id, clean) } }
        }
    }

    /**
     * Saves the playlist with every song that is playable now; the rest stay
     * in the selection and join the same playlist when they arrive. Safe to
     * tap twice: the second tap is ignored while the first runs, and the
     * playlist id is derived from the selection, so a retry can't duplicate it.
     */
    fun createPlaylist() {
        if (transient.value.creating) return
        val batch = state.value.review ?: return
        batch.playlistId?.let { if (!batch.playlistDeleted) { mutableEvents.tryEmit(DiscoveryEvent.OpenPlaylist(it)); return } }
        transient.update { it.copy(creating = true) }
        viewModelScope.launch {
            try {
                val idx = index.value ?: return@launch
                val current = repository.state.value.batches.firstOrNull { it.id == batch.id } ?: return@launch
                val progress = PlaylistPlacement.progress(current, idx)
                val ids = PlaylistPlacement.toCreate(current, progress)
                if (ids.isEmpty()) { message("None of these songs is in your library yet. Download them first."); return@launch }
                val id = playlists.saveDiscoveryBatch(current.id, current.name, ids)
                val total = current.songs.size
                write { s -> s.copy(batches = s.batches.map { b ->
                    if (b.id != current.id) b else b.copy(playlistId = id, playlistDeleted = false, locked = true,
                        placedKeys = b.placedKeys + progress.available.keys,
                        status = if (ids.size == total) "Saved · $total songs" else "Saved with ${ids.size} of $total · the rest join when downloaded")
                }) }
                mutableEvents.emit(DiscoveryEvent.OpenPlaylist(id))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message(e.message ?: "The playlist couldn't be saved. Try again.")
            } finally {
                transient.update { it.copy(creating = false) }
            }
        }
    }

    fun consumeMessage() = transient.update { it.copy(message = null) }

    override fun onCleared() { preview.stop(); super.onCleared() }

    // -- internals -----------------------------------------------------------------

    private fun startGeneration(draft: DiscoveryDraft, keepExisting: Boolean) {
        preview.stop()
        generateJob?.cancel()
        val mine = ++generation
        transient.update { it.copy(work = DiscoveryWork.Generating("Reading your taste…"), notes = emptyList()) }
        generateJob = viewModelScope.launch {
            try {
                val idx = index.filterNotNull().first()
                val online = connectivity.state.value.ready
                if (!online && idx.songs.isEmpty()) {
                    message("You're offline and your library is empty. Connect to the internet to discover songs.")
                    return@launch
                }
                val profile = loadProfile(draft)
                val outcome = withContext(Dispatchers.Default) {
                    engine.candidates(request(draft, profile, online)) { step ->
                        if (mine == generation) transient.update { it.copy(work = DiscoveryWork.Generating(step)) }
                    }
                }
                if (mine != generation) return@launch
                pool = outcome.candidates; poolLevel = draft.level
                applyMix(mine, outcome, keepExisting)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message(e.message ?: "Couldn't build recommendations. Your picks are saved; try again.")
            } finally {
                if (mine == generation) transient.update { it.copy(work = DiscoveryWork.Idle) }
            }
        }
    }

    private suspend fun applyMix(mine: Long, outcome: RecommendationOutcome, keepExisting: Boolean) {
        var found = 0; var wanted = 0
        write { s ->
            if (mine != generation) return@write s
            val d = s.draft ?: return@write s
            val current = if (keepExisting) d.items else emptyList()
            val stale = stale(s, d) + if (keepExisting) d.items.filterNot { it.kept }.map { it.identity } else emptyList()
            val mix = RecommendationMixer.compose(d.size, d.level, outcome.candidates, current, s.declined, stale, seed())
            found = mix.items.size; wanted = d.size
            if (mix.items.isEmpty()) return@write s
            val now = clock.now()
            s.copy(
                draft = d.copy(step = DraftStep.SONGS, items = mix.items, shown = d.shown + mix.items.map { it.identity },
                    generatedAt = now, generatedOnline = outcome.usedOnline, sources = outcome.sources),
                recommended = (s.recommended.filter { now - it.at < STALE_WINDOW } +
                    mix.items.filterNot { it.kept }.map { RecommendationStamp(it.identity, now) }).takeLast(1_500),
            )
        }
        val notes = outcome.problems.toMutableList()
        when {
            found == 0 -> message(outcome.problems.firstOrNull() ?: if (outcome.usedOnline) "No songs matched yet. Pick a few artists or genres and try again."
                else "Not enough songs in your library for this yet. Connect to the internet for new music.")
            found < wanted -> notes += "Found $found of $wanted songs. Add more artists or genres, or try again later."
        }
        transient.update { it.copy(notes = notes) }
    }

    private fun replaceSlot(key: String, declined: Boolean) {
        val draft = repository.state.value.draft ?: return
        val item = draft.items.firstOrNull { it.key == key } ?: return
        if (key in transient.value.replacing) return
        preview.onFocus(null)
        transient.update { it.copy(replacing = it.replacing + key) }
        viewModelScope.launch {
            try {
                if (declined) write { s -> s.copy(
                    declined = (s.declined + listOfNotNull(item.key, item.identity, item.song.isrc)).toList().takeLast(3_000).toSet(),
                    feedback = (s.feedback + TasteFeedback(FeedbackKind.NOT_INTERESTED, item.key, item.song.title, item.song.artist, item.song.genres, clock.now())).takeLast(500),
                ) } ?: return@launch
                val candidates = candidatesFor(draft)
                val replaced = write { s ->
                    val d = s.draft ?: return@write s
                    val at = d.items.indexOfFirst { it.key == key }
                    if (at < 0) return@write s // removed or replaced meanwhile: this answer is out of date
                    val next = candidates?.let { RecommendationMixer.replacement(d.items, at, it, s.declined, stale(s, d), seed()) }
                    when {
                        next != null -> s.copy(draft = d.copy(items = d.items.toMutableList().also { it[at] = next }, shown = d.shown + next.identity))
                        declined -> s.copy(draft = d.copy(items = d.items.filterNot { it.key == key }))
                        else -> s
                    }
                }
                val stillThere = replaced?.draft?.items?.any { it.key == key } == true
                when {
                    declined && !stillThere -> message("Won't suggest “${item.song.title}” again.")
                    stillThere -> message("No other song fits here right now.")
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message(e.message ?: "Couldn't replace the song. Try again.")
            } finally {
                transient.update { it.copy(replacing = it.replacing - key) }
            }
        }
    }

    /** The pool for the draft's level; rebuilt (library only when offline) if the app was restarted. */
    private suspend fun candidatesFor(draft: DiscoveryDraft): List<Candidate>? {
        if (pool.isNotEmpty() && poolLevel == draft.level) return pool
        val profile = loadProfile(draft)
        val outcome = withContext(Dispatchers.Default) { engine.candidates(request(draft, profile)) }
        if (outcome.problems.isNotEmpty()) transient.update { it.copy(notes = outcome.problems) }
        pool = outcome.candidates; poolLevel = draft.level
        return pool
    }

    private suspend fun loadProfile(draft: DiscoveryDraft): TasteProfile {
        val idx = index.filterNotNull().first()
        return taste.load(idx.songs, repository.state.value, draft.pickedArtists, draft.pickedGenres, clock.now())
    }

    private fun request(draft: DiscoveryDraft, profile: TasteProfile, online: Boolean = connectivity.state.value.ready) =
        RecommendationRequest(profile, index.value ?: LibraryIndex(emptyList()), draft.level, online, clock.now(), seed(),
            repository.state.value.filter, repository.state.value.declined)

    private suspend fun refreshTaste(idx: LibraryIndex) {
        val draft = repository.state.value.draft
        val profile = try { taste.load(idx.songs, repository.state.value, draft?.pickedArtists.orEmpty(), draft?.pickedGenres.orEmpty(), clock.now()) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        transient.update { it.copy(taste = TasteSummary(
            loaded = true,
            hasHistory = profile.hasListeningData,
            artists = profile.likedArtists.filterNot { it.picked }.take(6).mapNotNull { a -> profile.reasonFor(a.name)?.let { a.name to it.text } },
            genres = profile.likedGenres.filterNot { it.picked }.take(5).map { it.name },
        )) }
    }

    /** Adds songs that became playable to playlists made earlier, once each, at the end. */
    private suspend fun reconcile(idx: LibraryIndex, batches: List<DiscoveryBatch>) {
        for (batch in batches) {
            val id = batch.playlistId ?: continue
            if (batch.playlistDeleted) continue
            val additions = PlaylistPlacement.toAppend(batch, PlaylistPlacement.progress(batch, idx))
            if (additions.isEmpty()) continue
            try {
                val exists = playlists.observePlaylists().first().any { it.id == id }
                if (!exists) { write { s -> s.copy(batches = s.batches.map { if (it.id == batch.id) it.copy(playlistDeleted = true) else it }) }; continue }
                playlists.addSongs(id, additions.map { it.second })
                write { s -> s.copy(batches = s.batches.map { if (it.id == batch.id) it.copy(placedKeys = it.placedKeys + additions.map { a -> a.first }) else it }) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                // Retried on the next library or state change.
            }
        }
    }

    private suspend fun ensureDraft() {
        if (repository.state.value.draft == null) write { s -> if (s.draft == null) s.copy(draft = freshDraft(null)) else s }
    }

    private fun freshDraft(previous: DiscoveryDraft?) = DiscoveryDraft(
        id = UUID.randomUUID().toString(),
        level = previous?.level ?: ExplorationLevel.BALANCED,
        size = previous?.size ?: RecommendationMixer.DEFAULT_SIZE,
        pickedArtists = previous?.pickedArtists.orEmpty(),
        pickedGenres = previous?.pickedGenres.orEmpty(),
    )

    private fun stale(s: SongDiscoveryState, d: DiscoveryDraft): Set<String> {
        val now = clock.now()
        return s.recommended.filter { now - it.at < STALE_WINDOW }.map { it.identity }.toSet() + d.shown
    }

    private fun seed(): Long = clock.now() xor generation

    private fun defaultName(): String = "Discover · " + SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(clock.now()))

    private fun editDraft(change: (DiscoveryDraft) -> DiscoveryDraft) = persist { s -> s.draft?.let { s.copy(draft = change(it)) } ?: s }

    private fun persist(change: (SongDiscoveryState) -> SongDiscoveryState) {
        viewModelScope.launch { write(change) }
    }

    /** Durable write; on failure the screen keeps the old state and says so. Returns the new state, or null. */
    private suspend fun write(change: (SongDiscoveryState) -> SongDiscoveryState): SongDiscoveryState? = try {
        repository.update(change); repository.state.value
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        message(e.message ?: "Couldn't save your changes. Free some storage and try again."); null
    }

    private suspend fun safely(fallback: String, block: suspend () -> Unit) = try { block() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { message(e.message ?: fallback) }

    private fun message(text: String) = transient.update { it.copy(message = text) }

    companion object {
        private const val MORE_LIKE_COUNT = 3
        /** Songs recommended in the last 30 days go to the back of the queue. */
        private const val STALE_WINDOW = 30L * 86_400_000L
        val DEFAULT_GENRES = listOf("Pop", "Rock", "Rap/Hip Hop", "Electronic", "R&B", "Jazz", "Classical", "Metal", "Folk", "Latin", "Soul & Funk", "Reggae")
    }
}

private fun sameSong(item: DraftItem, candidate: Candidate): Boolean =
    item.key == candidate.song.key || TrackIdentity.same(item.song.track, candidate.song.track)
