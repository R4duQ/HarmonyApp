package com.harmony.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.core.model.PlayerState
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.usecase.StartSmartMixUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Live library totals for the "Your Library" card. */
data class LibraryStats(
    val songs: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
    val playlists: Int = 0,
)

/**
 * The audio-quality strip.
 *
 * Derived from the tags of whatever is actually playing — never guessed. When
 * nothing is playing there is nothing honest to report, so the card describes
 * the library instead of inventing a format.
 */
data class QualityStatus(
    val isLossless: Boolean = false,
    /** e.g. "FLAC", or null when unknown. */
    val format: String? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrateKbps: Int? = null,
    val hasSong: Boolean = false,
) {
    /** "FLAC • 24-bit • 96 kHz", omitting whatever the tags did not provide. */
    val detail: String
        get() {
            val parts = buildList {
                format?.let { add(it) }
                bitDepth?.let { add("$it-bit") }
                sampleRateHz?.let { add(formatKhz(it)) }
                if (format == null && bitDepth == null && sampleRateHz == null) {
                    bitrateKbps?.let { add("$it kbps") }
                }
            }
            return parts.joinToString(" • ")
        }

    companion object {
        private fun formatKhz(hz: Int): String {
            val khz = hz / 1000.0
            return if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else "%.1f kHz".format(khz)
        }
    }
}

data class HomeUiState(
    val stats: LibraryStats = LibraryStats(),
    val recentlyPlayed: List<Song> = emptyList(),
    val quality: QualityStatus = QualityStatus(),
    val nowPlaying: Song? = null,
    val isPlaying: Boolean = false,
    val libraryEmpty: Boolean = false,
)

/**
 * Home dashboard state.
 *
 * Every section reads from a repository that already exists; nothing here
 * scans the filesystem, builds a second queue, or holds its own copy of the
 * library. Counts come from COUNT(*) flows rather than from observed lists,
 * so a 20k-song library costs the dashboard a handful of integers.
 *
 * All flows are `stateIn(WhileSubscribed)`, so navigating away stops the
 * collection and coming back re-uses the cached value instead of re-querying —
 * which is what preserves the dashboard across navigation and rotation.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    playlistRepository: PlaylistRepository,
    historyRepository: PlaybackHistoryRepository,
    private val playback: PlaybackController,
    /**
     * Named `startMix`, not `startSmartMix`: a property sharing a name with
     * the member function below would be shadowed by it at the call site,
     * so `startSmartMix()` would resolve to the function itself rather than
     * the use case's `invoke` — silent infinite recursion.
     */
    private val startMix: StartSmartMixUseCase,
) : ViewModel() {

    private val _mixStarting = MutableStateFlow(false)
    val mixStarting: StateFlow<Boolean> = _mixStarting.asStateFlow()

    /** Non-null when Start Mix could not run, e.g. an empty library. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val stats: kotlinx.coroutines.flow.Flow<LibraryStats> = combine(
        libraryRepository.observeSongCount(),
        libraryRepository.observeAlbumCount(),
        libraryRepository.observeArtistCount(),
        playlistRepository.observePlaylistCount(),
    ) { songs, albums, artists, playlists ->
        LibraryStats(songs, albums, artists, playlists)
    }.distinctUntilChanged()

    /**
     * "Continue listening". Capped at a small number because the row is
     * horizontal and only a handful is ever visible — pulling 100 rows to
     * render three would be waste the dashboard does not need.
     */
    private val recentlyPlayed = historyRepository
        .observeRecentlyPlayed(limit = RECENT_LIMIT)
        .distinctUntilChanged()

    private val playerSnapshot = playback.playerState
        .map { it.toSnapshot() }
        .distinctUntilChanged()

    val uiState: StateFlow<HomeUiState> = combine(
        stats,
        recentlyPlayed,
        playerSnapshot,
    ) { libraryStats, recent, snapshot ->
        HomeUiState(
            stats = libraryStats,
            recentlyPlayed = recent,
            quality = snapshot.quality,
            nowPlaying = snapshot.song,
            isPlaying = snapshot.isPlaying,
            libraryEmpty = libraryStats.songs == 0,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    fun startSmartMix() {
        if (_mixStarting.value) return
        _mixStarting.value = true
        viewModelScope.launch {
            try {
                val started = startMix()
                if (!started) {
                    _message.value = "Nothing to mix yet — add music to your library first."
                }
            } finally {
                _mixStarting.value = false
            }
        }
    }

    fun playSong(song: Song) {
        viewModelScope.launch { playback.setQueue(listOf(song), 0, playWhenReady = true) }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private data class PlayerSnapshot(
        val song: Song?,
        val isPlaying: Boolean,
        val quality: QualityStatus,
    )

    /**
     * Reduces the full PlayerState to only what the dashboard draws.
     *
     * Without this the position field alone would emit several times a second
     * and re-run the whole combine — the dashboard does not show a progress
     * bar, so it must not recompose for one.
     */
    private fun PlayerState.toSnapshot(): PlayerSnapshot {
        val song = currentSong
        return PlayerSnapshot(
            song = song,
            isPlaying = isPlaying,
            quality = song?.toQuality() ?: QualityStatus(),
        )
    }

    private companion object {
        const val RECENT_LIMIT = 12
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Reads quality from the track's own tags.
 *
 * An earlier version of this guessed the container from a file extension on
 * `uri`. That was wrong: songs come from MediaStore, so the URI looks like
 * `content://media/external/audio/media/1234` and has no extension at all.
 * Every track therefore fell through to "not lossless", and the card labelled
 * a 24-bit FLAC as "Compressed" while the mini player one row below correctly
 * showed LOSSLESS · FLAC. Reporting the opposite of the truth is worse than
 * reporting nothing.
 *
 * The mini player gets the container by inspecting the file off the main
 * thread; that helper is internal to :feature:player and doing the same work
 * here would mean a second file probe per track change. So this reports only
 * what the scanner already parsed into the row — bit depth, sample rate,
 * bitrate — and does not claim a format it cannot see.
 */
internal fun Song.toQuality(): QualityStatus {
    return QualityStatus(
        // Bit depth is only ever populated for PCM-based formats; lossy
        // codecs have no such concept, so its presence is real evidence
        // rather than a guess at the container.
        isLossless = bitDepth != null,
        format = null,
        bitDepth = bitDepth,
        sampleRateHz = sampleRateHz,
        bitrateKbps = bitrateKbps,
        hasSong = true,
    )
}
