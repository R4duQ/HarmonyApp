package com.harmony.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The album cards in Downloads: the queue's batches and their controls. */
@HiltViewModel
class AlbumQueueViewModel @Inject constructor(
    private val queue: AlbumDownloadQueue,
    spoti: SpotiFlacDownloadEngine,
) : ViewModel() {
    val batches: StateFlow<List<AlbumBatch>> = queue.batches
    /** "Mornye" or "SpotiFLAC". */
    val engineName: String = spoti.engineName
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun pause(albumId: String) = act { queue.pause(albumId) }
    fun resume(albumId: String) = act { queue.resume(albumId) }
    fun cancel(albumId: String) = act { queue.cancel(albumId) }
    fun retryTrack(albumId: String, trackId: String) = act { queue.retryTrack(albumId, trackId) }
    fun skipTrack(albumId: String, trackId: String) = act { queue.skipTrack(albumId, trackId) }
    fun dismissMessage() { _message.value = null }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _message.value = e.message ?: "Could not change this album download." }
        }
    }
}
