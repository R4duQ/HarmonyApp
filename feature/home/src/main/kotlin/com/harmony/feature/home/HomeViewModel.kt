package com.harmony.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.core.model.PlayerState
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.usecase.StartSmartMixUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

/** The "Continue" card: what is loaded in the player right now. */
data class NowPlayingCard(
    val song: Song,
    val isPlaying: Boolean,
    /** 1-based position in the queue, and the queue's length. */
    val queuePosition: Int,
    val queueSize: Int,
)

data class HomeUiState(
    /** False until every section has its first real value — the page shows placeholders, not "empty". */
    val loaded: Boolean = false,
    val stats: LibraryStats = LibraryStats(),
    /** The player's current song, if the queue has one (restored or playing). */
    val nowPlaying: NowPlayingCard? = null,
    /** With an empty player: the most recently played song, offered to resume. */
    val resumeSong: Song? = null,
    /** History without the song featured above and without repeats. */
    val recentlyPlayed: List<Song> = emptyList(),
    val playlists: List<HomePlaylist> = emptyList(),
    val recentAlbums: List<HomeAlbum> = emptyList(),
    val quality: QualityStatus = QualityStatus(),
    val libraryEmpty: Boolean = false,
) {
    val hasHistory: Boolean get() = resumeSong != null || recentlyPlayed.isNotEmpty() || nowPlaying != null
}

/**
 * Home state.
 *
 * Every section reads a repository that already exists; nothing here scans
 * the filesystem, builds a second queue, or holds its own copy of the
 * library. Counts are COUNT(*) flows; every list is capped at what a
 * horizontal row can show, so a 20k-song library costs Home a few dozen rows.
 *
 * All flows are `stateIn(WhileSubscribed)`, so navigating away stops the
 * collection and coming back re-uses the cached value instead of re-querying
 * — which, with the list state being saveable, is what brings Home back
 * exactly as it was left.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
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

    /** Non-null when an action could not run, e.g. Start Mix on an empty library. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val stats: Flow<LibraryStats> = combine(
        libraryRepository.observeSongCount(),
        libraryRepository.observeAlbumCount(),
        libraryRepository.observeArtistCount(),
        playlistRepository.observePlaylistCount(),
    ) { songs, albums, artists, playlists ->
        LibraryStats(songs, albums, artists, playlists)
    }.distinctUntilChanged()

    /** One more than a row holds, so the featured song can be dropped without leaving a gap. */
    private val history = historyRepository
        .observeRecentlyPlayed(limit = HomeSections.ROW_LIMIT + 6)
        .distinctUntilChanged()

    private val playlists: Flow<List<HomePlaylist>> = combine(
        playlistRepository.observePlaylists(),
        playlistRepository.observePlaylistArtwork(),
        playlistRepository.observePlaylistDurations(),
    ) { all, artwork, durations -> HomeSections.playlists(all, artwork, durations) }
        .distinctUntilChanged()

    private val recentAlbums: Flow<List<HomeAlbum>> = playlistRepository
        .observeSmartPlaylist(SmartPlaylistType.RECENTLY_ADDED, limit = RECENTLY_ADDED_WINDOW)
        .map { HomeSections.recentAlbums(it) }
        .distinctUntilChanged()

    private val playerSnapshot = playback.playerState
        .map { it.toSnapshot() }
        .distinctUntilChanged()

    val uiState: StateFlow<HomeUiState> = combine(
        combine(stats, history, ::Pair),
        combine(playlists, recentAlbums, ::Pair),
        playerSnapshot,
    ) { (libraryStats, recent), (lists, albums), snapshot ->
        val featured = snapshot.card?.song ?: recent.firstOrNull()
        HomeUiState(
            loaded = true,
            stats = libraryStats,
            nowPlaying = snapshot.card,
            resumeSong = if (snapshot.card == null) recent.firstOrNull() else null,
            recentlyPlayed = HomeSections.recentlyPlayed(recent, featured),
            playlists = lists,
            recentAlbums = albums,
            quality = snapshot.quality,
            libraryEmpty = libraryStats.songs == 0,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    /**
     * How far through the current song, 0..1. Separate from [uiState] on
     * purpose: position moves twice a second while playing, and only the
     * Continue card's progress line should redraw for it — not the page.
     */
    val progress: StateFlow<Float> = playback.playerState
        .map { s -> if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0f)

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

    /** Play/pause for the Continue card; resumes the last-played song when the player is empty. */
    fun togglePlayback() {
        val card = uiState.value.nowPlaying
        when {
            card == null -> uiState.value.resumeSong?.let(::playSong)
            card.isPlaying -> playback.pause()
            else -> playback.play()
        }
    }

    fun playSong(song: Song) {
        viewModelScope.launch { playback.setQueue(listOf(song), 0, playWhenReady = true) }
    }

    /** Plays a history row from that song onwards, in the order the row shows. */
    fun playRecent(song: Song) {
        val row = uiState.value.recentlyPlayed
        val index = row.indexOfFirst { it.id == song.id }
        if (index < 0) return playSong(song)
        viewModelScope.launch { playback.setQueue(row, index, playWhenReady = true) }
    }

    fun playAlbum(albumId: Long) = playList { libraryRepository.observeSongsByAlbum(albumId).first() }

    fun playPlaylist(playlistId: Long) = playList { playlistRepository.observePlaylistSongs(playlistId).first() }

    /** Songs are fetched only when Play is pressed — Home never loads a whole album or playlist to draw a card. */
    private fun playList(load: suspend () -> List<Song>) {
        viewModelScope.launch {
            val songs = load()
            if (songs.isEmpty()) {
                _message.value = "Nothing to play here yet."
            } else {
                playback.setQueue(songs, 0, playWhenReady = true)
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private data class PlayerSnapshot(
        val card: NowPlayingCard?,
        val quality: QualityStatus,
    )

    /**
     * Reduces the full PlayerState to only what the page draws.
     *
     * Without this the position field alone would emit several times a second
     * and re-run the whole combine; position has its own flow ([progress]).
     */
    private fun PlayerState.toSnapshot(): PlayerSnapshot {
        val song = currentSong
        return PlayerSnapshot(
            card = song?.let {
                NowPlayingCard(
                    song = it,
                    isPlaying = isPlaying,
                    queuePosition = (queueIndex + 1).coerceAtLeast(1),
                    queueSize = queue.size,
                )
            },
            quality = song?.toQuality() ?: QualityStatus(),
        )
    }

    private companion object {
        /** Recently added songs to group into albums: enough for ten albums of normal length. */
        const val RECENTLY_ADDED_WINDOW = 120
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
