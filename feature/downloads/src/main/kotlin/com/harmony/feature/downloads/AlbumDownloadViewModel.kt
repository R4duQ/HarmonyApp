package com.harmony.feature.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.harmony.domain.library.repository.*
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import com.harmony.domain.playback.PlaybackController
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.ui.network.InternetMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class AlbumDownloadViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val metadata: AlbumMetadataClient,
    private val albums: AlbumJourneyRepository,
    private val library: LibraryRepository,
    private val playback: PlaybackController,
    private val favorites: FavoritesRepository,
    private val scan: ScanLibraryUseCase,
    private val spoti: SpotiFlacDownloadEngine,
    private val soulseek: SoulseekClient,
    downloadStatusCenter: DownloadStatusCenter,
) : ViewModel() {
    private val work = WorkManager.getInstance(context)
    private val selectionMutex = Mutex()
    val internet = InternetMonitor.get(context).state

    /**
     * Soulseek's live connection state, surfaced so the screen can say up
     * front that the selected engine cannot run.
     *
     * The check in [start] stays where it is — it is the one that actually
     * guards the download — but it only fires when the button is pressed,
     * which meant selecting Soulseek while signed out looked like a working
     * choice right up until it failed.
     */
    val soulseekConnection = soulseek.connectionState
    val downloadProgress = downloadStatusCenter.active
    val journeys = albums.journeys
    val jobs = work.getWorkInfosForUniqueWorkFlow(AlbumDownloadWorker.WORK)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val editions = MutableStateFlow<List<AlbumEdition>>(emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val verificationUrl = MutableStateFlow<String?>(null)
    val favoriteSongs = favorites.observeFavorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var searchKey: String? = null
    private var requestedAlbumId: String? = null
    init {
        viewModelScope.launch { internet.map { it.ready }.distinctUntilChanged().collect { ready ->
            if (!ready) searchKey = null
        } }
    }

    fun search(id: String, title: String, artist: String, artistAliases: Set<String> = emptySet()) {
        if (requestedAlbumId != id) {
            requestedAlbumId = id
            searchKey = null
            editions.value = emptyList()
            verificationUrl.value = null
            message.value = null
        }
        if (!internet.value.ready || busy.value || journeys.value.any { it.id == id } || searchKey == id) return
        searchKey = id
        editions.value = emptyList()
        action(online = true) {
            val result = metadata.search(title, artist, artistAliases)
            if (requestedAlbumId == id) {
                editions.value = result
                message.value = if (result.isEmpty()) "No matching edition returned. Try again later." else null
            }
        }
    }
    fun retrySearch(id: String, title: String, artist: String, artistAliases: Set<String> = emptySet()) {
        searchKey = null; search(id, title, artist, artistAliases)
    }

    fun choose(id: String, edition: AlbumEdition) = action(online = true) {
        check(requestedAlbumId == id && edition in editions.value) { "The edition list changed. Search again." }
        val journey = metadata.load(id, edition)
        val songs = library.observeSongs().first()
        albums.save(journey.copy(tracks = journey.tracks.map { track ->
            AlbumTrackMatcher.match(track, journey.title, songs)?.let { song ->
                track.copy(uri = song.uri, status = "Already in library")
            } ?: track
        }))
        message.value = null
    }

    fun start(id: String, source: DownloadSource, format: SpotiFlacOutputFormat, wifiOnly: Boolean) = action(online = true) {
        selectionMutex.withLock {
            val outputFormat = AlbumDownloadPolicy.formatFor(source, format)
            if (source == DownloadSource.SOULSEEK) check(soulseek.connectionState.value.status == SoulseekConnectionStatus.CONNECTED) {
                "Connect Soulseek in Downloads first, then return to download the selected tracks."
            }
            check(withContext(Dispatchers.IO) { work.getWorkInfosForUniqueWork(AlbumDownloadWorker.WORK).get() }.none { !it.state.isFinished }) {
                "An album is already queued. Pause it or wait for it to finish."
            }
            val current = albums.journeys.value.first { it.id == id }
            val selection = current.selectedMissing.map { it.id }
            check(selection.isNotEmpty()) { "Select at least one missing track to download." }
            albums.save(current.copy(source = source.name, format = outputFormat.name,
                reviewed = false, remindAfter = 0L,
                tracks = if (current.reviewed) current.tracks.map { it.copy(coverage = emptyList(), playbackDurationMs = 0L) } else current.tracks))
            val request = AlbumDownloadWorker.request(id, wifiOnly, selection, source.name, outputFormat.name)
            withContext(Dispatchers.IO) {
                work.enqueueUniqueWork(AlbumDownloadWorker.WORK, ExistingWorkPolicy.KEEP, request).result.get()
                check(work.getWorkInfosForUniqueWork(AlbumDownloadWorker.WORK).get().any { it.id == request.id }) {
                    "Another album was queued first. Wait for it or pause it before starting this album."
                }
            }
            message.value = "${selection.size} tracks queued. Completed files are kept if you pause."
        }
    }
    fun select(id: String, trackId: String? = null, selected: Boolean) {
        if (busy.value) return
        // Serialize rapid checkbox taps rather than dropping them while another
        // preference write is in progress. Start waits for these writes too.
        viewModelScope.launch {
            try { selectionMutex.withLock {
                check(withContext(Dispatchers.IO) { work.getWorkInfosForUniqueWork(AlbumDownloadWorker.WORK).get() }
                    .none { !it.state.isFinished && id in it.tags }) { "Pause the album before changing its selection." }
                val album = albums.journeys.value.first { it.id == id }
                val ids = AlbumDownloadPolicy.selectedIds(album, trackId, selected)
                albums.selectDownloads(id, ids)
            } } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message.value = e.message ?: "Could not save the selected tracks." }
        }
    }
    fun pause() { work.cancelUniqueWork(AlbumDownloadWorker.WORK) }

    fun play(id: String) = action {
        scan(force = false).collect { }
        val album = journeys.value.first { it.id == id }
        val local = library.observeSongs().first().associateBy { it.uri }
        val songs = album.tracks.mapNotNull { local[it.uri] }
        check(songs.isNotEmpty()) { "Files are not indexed yet. Check Library access and retry." }
        playback.setShuffleMode(ShuffleMode.OFF)
        playback.setRepeatMode(RepeatMode.OFF)
        playback.setQueue(songs)
        if (songs.size < album.tracks.size) message.value = "Playing ${songs.size}/${album.tracks.size} available tracks in album order."
    }

    fun toggleFavorite(uri: String) = action {
        val song = library.observeSongs().first().firstOrNull { it.uri == uri }
        check(song != null) { "The track will be available after the library scan finishes." }
        favorites.toggle(song.id)
    }

    fun verify(id: String) = action(online = true) {
        val album = journeys.value.first { it.id == id }
        val track = album.selectedMissing.firstOrNull() ?: return@action
        spoti.rememberPendingVerificationTrackFor(IdentifiedTrack(track.artist, track.title,
            "${track.artist} - ${track.title}", album.coverUrl, album.title, track.durationMs, metadataId = track.id),
            SpotiFlacRequestOwner.ALBUM_DOWNLOAD)
        val challenge = spoti.getVerificationChallenge("tidal-web")
        verificationUrl.value = challenge.verificationUrl
        message.value = if (challenge.authenticated || !challenge.pending) "Provider ready. Tap Resume album." else "Finish verification in your browser, then return and resume."
    }

    private fun action(online: Boolean = false, block: suspend () -> Unit) {
        if (online && !internet.value.ready) { message.value = internet.value.message; return }
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Could not complete the album action. Try again." }
            finally { busy.value = false }
        }
    }
}
