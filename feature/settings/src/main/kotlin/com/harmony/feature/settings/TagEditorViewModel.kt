package com.harmony.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.SongEditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TagEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val edits: SongEditRepository,
) : ViewModel() {

    data class State(
        val query: String = "",
        val results: List<Song> = emptyList(),
        val selected: Song? = null,
        /**
         * The song as its FILE describes it. The selected song comes from
         * songs_effective, which already has edits applied — comparing
         * against it would make a second save look like "nothing changed".
         */
        val original: Song? = null,
        /** Artwork already saved by a previous edit, if any. */
        val savedArtworkUri: String? = null,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtist: String = "",
        /** Null means "unchanged"; set when the user picks a new image. */
        val newArtworkUri: String? = null,
        val artworkCleared: Boolean = false,
        val saved: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
    ) {
        /** What the artwork preview should show right now. */
        val previewArtwork: String?
            get() = when {
                artworkCleared -> null
                newArtworkUri != null -> newArtworkUri
                savedArtworkUri != null -> savedArtworkUri
                else -> selected?.artworkUri
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var selectionJob: Job? = null
    private var artworkJob: Job? = null
    private var writeJob: Job? = null
    private var selectionVersion = 0L

    fun onQueryChange(q: String) {
        searchJob?.cancel()
        _state.update { it.copy(query = q, results = emptyList(), saved = false, error = null) }
        searchJob = viewModelScope.launch {
            try {
                if (q.isNotBlank()) delay(200)
                val hits = if (q.isBlank()) emptyList() else library.search(q).first().songs
                if (_state.value.query == q) _state.update { it.copy(results = hits.take(30)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = "Could not search the library. Try again.") }
            }
        }
    }

    /** Loads the song plus any override already saved for it. */
    fun select(song: Song) {
        selectionJob?.cancel()
        artworkJob?.cancel()
        val version = ++selectionVersion
        _state.update { it.copy(selected = null, busy = true, saved = false, error = null) }
        selectionJob = viewModelScope.launch {
          try {
            val existing = edits.observe(song.id).first()
            val original = library.originalSongById(song.id) ?: song
            val effective = library.songById(song.id) ?: original
            if (version != selectionVersion) return@launch
            _state.update {
                it.copy(
                    selected = effective,
                    original = original,
                    savedArtworkUri = existing?.artworkUri,
                    // The current effective values, so the fields show what
                    // you'd see in the library rather than empty boxes.
                    title = existing?.title ?: original.title,
                    artist = existing?.artist ?: original.artist,
                    album = existing?.album ?: original.album,
                    albumArtist = existing?.albumArtist ?: original.albumArtist ?: "",
                    newArtworkUri = null,
                    artworkCleared = existing?.artworkCleared ?: false,
                    saved = false,
                    busy = false,
                )
            }
          } catch (cancelled: CancellationException) {
              throw cancelled
          } catch (_: Exception) {
              if (version == selectionVersion) _state.update {
                  it.copy(busy = false, error = "Could not load this song. Try again.")
              }
          }
        }
    }

    private fun change(edit: (State) -> State) {
        if (!_state.value.busy) _state.update { edit(it).copy(saved = false, error = null) }
    }

    fun onTitleChange(v: String) = change { it.copy(title = v) }
    fun onArtistChange(v: String) = change { it.copy(artist = v) }
    fun onAlbumChange(v: String) = change { it.copy(album = v) }
    fun onAlbumArtistChange(v: String) =
        change { it.copy(albumArtist = v) }

    /** Reads the picked image and stores it as this song's artwork. */
    fun pickArtwork(uri: String) {
        if (_state.value.busy || writeJob?.isActive == true) return
        val song = _state.value.selected ?: return
        val version = selectionVersion
        _state.update { it.copy(busy = true, saved = false, error = null) }
        artworkJob = viewModelScope.launch {
          try {
            val bytes = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(android.net.Uri.parse(uri))
                    ?: error("Image unavailable")
                input.use { stream ->
                    java.io.ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 32 * 1024 * 1024) { "Image too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            }
            val stored = edits.saveArtwork(song.id, bytes)
            if (version == selectionVersion) _state.update {
                it.copy(newArtworkUri = stored, artworkCleared = false, saved = false)
            }
          } catch (cancelled: CancellationException) {
              throw cancelled
          } catch (_: Exception) {
              if (version == selectionVersion) _state.update { it.copy(error = "Could not load the image. Choose an image smaller than 32 MB.") }
          } finally {
              if (version == selectionVersion) _state.update { it.copy(busy = false) }
          }
        }
    }

    fun clearArtwork() =
        change { it.copy(artworkCleared = true, newArtworkUri = null) }

    fun restoreArtwork() =
        change { it.copy(artworkCleared = false, newArtworkUri = null) }

    fun save() {
        val s = _state.value
        if (s.busy || writeJob?.isActive == true) return
        val song = s.selected ?: return
        val version = selectionVersion
        _state.update { it.copy(busy = true, saved = false, error = null) }
        writeJob = viewModelScope.launch {
          try {
            // Compare against the FILE's tags, not the effective ones.
            // Using song.title here was the bug: after one save, the
            // effective title already equals what's in the box, so the
            // next save stored null and deleted the override — the name
            // reverted the moment you changed anything else, such as the
            // artwork.
            val base = s.original ?: song
            edits.save(
                songId = song.id,
                edit = SongEditRepository.SongEdit(
                    title = s.title.takeIf { it != base.title },
                    artist = s.artist.takeIf { it != base.artist },
                    album = s.album.takeIf { it != base.album },
                    albumArtist = s.albumArtist.takeIf { it != (base.albumArtist ?: "") },
                    // Carry a previously saved cover forward. Writing
                    // newArtworkUri alone wiped it whenever this save
                    // didn't happen to include a fresh pick.
                    artworkUri = s.newArtworkUri ?: s.savedArtworkUri,
                    artworkCleared = s.artworkCleared,
                ),
            )
            if (version == selectionVersion) _state.update { it.copy(saved = true, savedArtworkUri = s.newArtworkUri ?: s.savedArtworkUri) }
          } catch (cancelled: CancellationException) {
              throw cancelled
          } catch (_: Exception) {
              if (version == selectionVersion) _state.update { it.copy(error = "Could not save the changes. Try again.") }
          } finally {
              if (version == selectionVersion) _state.update { it.copy(busy = false) }
          }
        }
    }

    /** Throws away every override for this song. */
    fun revert() {
        if (_state.value.busy || writeJob?.isActive == true) return
        val song = _state.value.selected ?: return
        val version = selectionVersion
        _state.update { it.copy(busy = true, saved = false, error = null) }
        writeJob = viewModelScope.launch {
          try {
            edits.revert(song.id)
            if (version == selectionVersion) select(song)
          } catch (cancelled: CancellationException) {
              throw cancelled
          } catch (_: Exception) {
              if (version == selectionVersion) _state.update { it.copy(error = "Could not restore the original tags. Try again.") }
          } finally {
              if (version == selectionVersion) _state.update { it.copy(busy = false) }
          }
        }
    }

    fun back() {
        selectionVersion++
        selectionJob?.cancel()
        artworkJob?.cancel()
        _state.update { it.copy(selected = null, busy = false, saved = false, error = null) }
    }
}
