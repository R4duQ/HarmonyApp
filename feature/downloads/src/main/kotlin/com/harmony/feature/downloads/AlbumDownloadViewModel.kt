package com.harmony.feature.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.domain.library.repository.*
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import com.harmony.domain.playback.PlaybackController
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.ui.network.InternetMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The album sheet: choose an edition, the tracks and the source, then
 * download, pause, resume, cancel or retry, through the app's one album
 * queue ([AlbumDownloadQueue]).
 */
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
    private val queue: AlbumDownloadQueue,
    soulseek: SoulseekClient,
) : ViewModel() {
    val internet = InternetMonitor.get(context).state

    /**
     * Soulseek's live connection state, surfaced so the screen can say up
     * front that the selected engine cannot run. The queue checks it again
     * when the album is queued.
     */
    val soulseekConnection = soulseek.connectionState
    val journeys = albums.journeys
    val batches = queue.batches
    /** "Mornye" or "SpotiFLAC", whichever this build downloads with. */
    val engineName = spoti.engineName
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
                track.copy(uri = song.uri, status = AlbumTrackStatus.IN_LIBRARY)
            } ?: track
        }))
        message.value = null
    }

    /** Download all / the selected tracks: the album joins the queue. */
    fun start(id: String, source: DownloadSource, format: SpotiFlacOutputFormat, wifiOnly: Boolean) = action(online = true) {
        val count = queue.queue(id, source, format, wifiOnly)
        val ahead = queue.batches.value.count { it.albumId != id && it.active }
        message.value = if (ahead > 0) "$count tracks queued after ${if (ahead == 1) "1 album" else "$ahead albums"}."
            else "$count tracks queued. Saved tracks are kept if you pause."
    }
    fun pause(id: String) = action { queue.pause(id) }
    fun resume(id: String) = action(online = true) { queue.resume(id) }
    fun cancel(id: String) = action { queue.cancel(id); message.value = "Album cancelled. Saved tracks stay on your phone." }
    fun retryTrack(id: String, trackId: String) = action(online = true) { queue.retryTrack(id, trackId) }
    fun skipTrack(id: String, trackId: String) = action { queue.skipTrack(id, trackId) }

    fun select(id: String, trackId: String? = null, selected: Boolean) {
        // Rapid checkbox taps are serialized by the queue, not dropped.
        viewModelScope.launch {
            try { queue.select(id, trackId, selected) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Could not save the selected tracks." }
        }
    }

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
        message.value = if (challenge.authenticated || !challenge.pending) "Provider ready. Tap Resume." else "Finish verification in your browser, then return and resume."
    }

    fun dismissMessage() { message.value = null }

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
