package com.harmony.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.*
import com.harmony.domain.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryActionsViewModel @Inject constructor(
    private val requests: LibraryFileActions,
    private val deleter: LibraryFileDeleter,
    private val library: LibraryRepository,
    private val albums: AlbumJourneyRepository,
    private val favorites: FavoritesRepository,
    private val playback: PlaybackController,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val journeys = albums.journeys
    val player = playback.playerState
    val localSongs = library.observeSongs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val reviewAlbumId = MutableStateFlow<String?>(null)
    val selectedToKeep = MutableStateFlow<Set<String>>(emptySet())
    val confirmation = MutableStateFlow<List<String>>(saved.get<ArrayList<String>>("deletion_remaining")?.toList().orEmpty())
    val consent = MutableStateFlow<FileDeleteStep?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private var remaining = saved.get<ArrayList<String>>("deletion_remaining")?.toList().orEmpty()
    private var consentBatch = saved.get<ArrayList<String>>("deletion_consent")?.toList().orEmpty()
    private var retryAfterConsent = saved.get<Boolean>("deletion_retry") ?: false
    private var deletedCount = 0
    private var failedCount = 0
    private var reviewAfterDeletion: String? = saved["deletion_album"]

    init {
        viewModelScope.launch { requests.deletionRequests.collect { uris ->
            if (uris.isNotEmpty() && !busy.value && confirmation.value.isEmpty()) {
                confirmation.value = uris
                requests.consumed()
            }
        } }
    }

    fun openReview(id: String) {
        if (busy.value || confirmation.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val album = journeys.value.find { it.id == id } ?: return@launch
                val songs = library.observeSongs().first().filter { s -> album.tracks.any { it.uri == s.uri } }
                val favorites = favorites.favoriteIds(songs.map { it.id })
                selectedToKeep.value = album.tracks.filter { !it.downloaded }.mapNotNull { it.uri }.toSet() +
                    songs.filter { it.id in favorites }.map { it.uri }
                reviewAlbumId.value = id
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = "Could not load your album selection. Try again." }
        }
    }
    fun toggleKeep(uri: String) { selectedToKeep.value = selectedToKeep.value.let { if (uri in it) it - uri else it + uri } }
    fun keepAll() = finishReview(true)
    fun later() = finishReview(false)
    private fun finishReview(done: Boolean) {
        val id = reviewAlbumId.value ?: return
        viewModelScope.launch {
            try {
                albums.review(id, done, if (done) 0 else System.currentTimeMillis() + 24 * 60 * 60 * 1_000L)
                reviewAlbumId.value = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = "Could not save the album decision. Try again." }
        }
    }
    fun deleteUnselected(all: Boolean = false) {
        val album = journeys.value.find { it.id == reviewAlbumId.value } ?: return
        val uris = album.tracks.mapNotNull { it.uri }.distinct().filter { all || it !in selectedToKeep.value }
        if (uris.isEmpty()) { keepAll(); return }
        reviewAfterDeletion = album.id
        saved["deletion_album"] = album.id
        confirmation.value = uris
    }
    fun cancelConfirmation() { if (!busy.value) { confirmation.value = emptyList(); reviewAfterDeletion = null; clearPending() } }

    fun confirmDeletion() {
        if (busy.value || confirmation.value.isEmpty()) return
        remaining = confirmation.value.toList()
        confirmation.value = emptyList()
        deletedCount = 0; failedCount = 0
        saved["deletion_remaining"] = ArrayList(remaining)
        busy.value = true
        process()
    }

    private fun process() = viewModelScope.launch {
        while (remaining.isNotEmpty()) {
            try {
                val step = deleter.next(remaining)
                if (step.consent != null) {
                    consentBatch = step.consentUris; retryAfterConsent = step.retryAfterConsent
                    saved["deletion_consent"] = ArrayList(consentBatch); saved["deletion_retry"] = retryAfterConsent
                    consent.value = step
                    return@launch
                }
                acceptDeleted(step.deleted)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failedCount++; remaining = remaining.drop(1)
                saved["deletion_remaining"] = ArrayList(remaining)
                message.value = e.message ?: "A file could not be deleted and remains in Library."
            }
        }
        finishDeletion()
    }

    fun consentLaunched() { consent.value = null }
    fun consentResult(approved: Boolean) {
        confirmation.value = emptyList()
        busy.value = true
        viewModelScope.launch {
            val batch = consentBatch.toList()
            try {
                if (approved) {
                    val removed = if (retryAfterConsent) deleter.next(batch, allowConsent = false).deleted else deleter.verifyRemoved(batch)
                    acceptDeleted(removed)
                    failedCount += batch.count { it !in removed }
                } else failedCount += batch.size
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failedCount += batch.size; message.value = e.message ?: "Deletion was not completed." }
            remaining = remaining.filterNot { it in batch }
            saved["deletion_remaining"] = ArrayList(remaining)
            consentBatch = emptyList(); saved["deletion_consent"] = arrayListOf<String>()
            if (!approved) { remaining = emptyList(); finishDeletion() } else process()
        }
    }
    private suspend fun acceptDeleted(uris: List<String>) {
        // Persist the confirmed list until DB/queue cleanup succeeds too.
        deleter.cleanLibrary(uris)
        remaining = remaining.filterNot { it in uris }
        saved["deletion_remaining"] = ArrayList(remaining)
        deletedCount += uris.size
    }
    private suspend fun finishDeletion() {
        try {
            if (failedCount == 0) {
                reviewAfterDeletion?.let { albums.review(it, true) }
                reviewAlbumId.value = null
            }
            message.value = if (failedCount == 0) "$deletedCount files deleted from your phone."
                else "$deletedCount deleted · $failedCount not deleted. Check storage access and try again."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message.value = "Files were deleted, but the album decision could not be saved. Please try again." }
        finally { busy.value = false; clearPending() }
    }
    private fun clearPending() {
        saved["deletion_remaining"] = arrayListOf<String>(); saved["deletion_consent"] = arrayListOf<String>()
        saved["deletion_album"] = null
        remaining = emptyList(); reviewAfterDeletion = null
    }
}
