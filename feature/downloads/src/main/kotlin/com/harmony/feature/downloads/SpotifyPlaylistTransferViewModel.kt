package com.harmony.feature.downloads

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.core.database.dao.DownloadRecordDao
import com.harmony.core.database.entity.DownloadRecordEntity
import com.harmony.core.model.Song
import com.harmony.core.datastore.SettingsRepository
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaylistRepository
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpotifyPlaylistTransferState(
    val clientId: String = "",
    val connected: Boolean = false,
    val playlistLink: String = "",
    val playlists: List<SpotifyPlaylistSummary> = emptyList(),
    val selectedPlaylist: SpotifyPlaylistSummary? = null,
    val tracks: List<PlaylistTransferTrack> = emptyList(),
    val isLoadingPlaylists: Boolean = false,
    val isImportingPlaylist: Boolean = false,
    val source: DownloadSource = DownloadSource.SPOTIFLAC,
    val spotiFlacOutputFormat: SpotiFlacOutputFormat = SpotiFlacOutputFormat.FLAC_LOSSLESS,
    val soulseekFormatPreference: SoulseekFormatPreference = SoulseekFormatPreference.FLAC_ONLY,
    val soulseekConnection: SoulseekConnectionState = SoulseekConnectionState(),
    val isTransferring: Boolean = false,
    val showTransferConfirmation: Boolean = false,
    val currentTrackKey: String? = null,
    val activeDetail: String? = null,
    val activeProgress: Float? = null,
    val completedDownloads: Int = 0,
    val attemptedDownloads: Int = 0,
    val pausedForVerification: Boolean = false,
    val isCheckingVerification: Boolean = false,
    val verificationChallenge: SpotiFlacVerificationChallenge? = null,
    val resultPlaylistId: Long? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val localMatchCount: Int get() = tracks.count { it.localSongId != null }
    val missingCount: Int get() = tracks.count { it.localSongId == null }
    val selectedMissingCount: Int get() = tracks.count { it.localSongId == null && it.selected }
    val failedCount: Int get() = tracks.count { it.status == PlaylistTransferTrackStatus.FAILED }
}

@HiltViewModel
class SpotifyPlaylistTransferViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val spotify: SpotifyPlaylistClient,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val downloadRepository: MusicDownloadRepository,
    private val spotiFlacEngine: SpotiFlacDownloadEngine,
    private val soulseekClient: SoulseekClient,
    private val downloadRecordDao: DownloadRecordDao,
    private val scanLibrary: ScanLibraryUseCase,
    private val settings: SettingsRepository,
    downloadStatusCenter: DownloadStatusCenter,
) : ViewModel() {
    private val statusCenter = downloadStatusCenter.forOwner()
    private val spotiFlacUiPrefs =
        context.getSharedPreferences(SPOTIFLAC_UI_PREFERENCES, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        SpotifyPlaylistTransferState(
            clientId = spotify.savedClientId(),
            connected = spotify.hasSession(),
            source = downloadRepository.lastDownloadSource(),
            spotiFlacOutputFormat = SpotiFlacOutputFormat.fromName(
                spotiFlacUiPrefs.getString(KEY_SPOTIFLAC_OUTPUT_FORMAT, null),
            ),
            soulseekConnection = soulseekClient.connectionState.value,
        ),
    )
    val state: StateFlow<SpotifyPlaylistTransferState> = _state.asStateFlow()

    private var transferJob: Job? = null

    init {
        viewModelScope.launch {
            spotify.authorizationEvents.collect { event ->
                when (event) {
                    is SpotifyAuthorizationEvent.Connected -> {
                        _state.update { it.copy(connected = true, message = event.message, error = null) }
                        refreshPlaylists()
                    }
                    is SpotifyAuthorizationEvent.Failed -> {
                        _state.update { it.copy(error = event.message, message = null) }
                    }
                }
            }
        }
        viewModelScope.launch {
            spotiFlacEngine.verificationEvents.collect { result ->
                if (spotiFlacEngine.pendingVerificationOwner() !=
                    SpotiFlacRequestOwner.PLAYLIST_TRANSFER
                ) return@collect
                if (result.accepted) {
                    resumeAfterProviderVerification()
                } else if (_state.value.pausedForVerification) {
                    _state.update { it.copy(error = result.message, isCheckingVerification = false) }
                }
            }
        }
        viewModelScope.launch {
            soulseekClient.connectionState.collect { connection ->
                _state.update { it.copy(soulseekConnection = connection) }
            }
        }
        viewModelScope.launch {
            soulseekClient.transferProgress.collect { progress ->
                val current = _state.value
                if (progress == null || !current.isTransferring || current.source != DownloadSource.SOULSEEK) {
                    return@collect
                }
                val remote = current.tracks
                    .firstOrNull { it.remote.stableKey == current.currentTrackKey }
                    ?.remote ?: return@collect
                val detail = progress.queuePlace?.let { "Queue position $it" } ?: progress.status
                _state.update { it.copy(activeDetail = detail, activeProgress = progress.fraction) }
                publishActive(remote, detail, progress.fraction)
            }
        }
        viewModelScope.launch {
            settings.settings.collect { saved ->
                _state.update {
                    it.copy(
                        soulseekFormatPreference =
                            SoulseekFormatPreference.fromName(saved.soulseekFormatPreference),
                    )
                }
            }
        }
        if (spotify.hasSession()) refreshPlaylists()
    }

    fun setClientId(value: String) {
        val normalized = value.trim()
        if (_state.value.clientId != normalized) spotify.cancelAuthorization()
        _state.update { it.copy(clientId = normalized, error = null, message = null) }
    }

    fun createSpotifyAuthorizationUri(): Uri? = runCatching {
        spotify.createAuthorizationUri(_state.value.clientId)
    }.onSuccess {
        _state.update {
            it.copy(
                clientId = spotify.savedClientId(),
                error = null,
                message = "Finish signing in with Spotify in your browser. " +
                    "If Spotify shows an error, return here and tap Connection help.",
            )
        }
    }.onFailure { failure ->
        _state.update { it.copy(error = failure.message, message = null) }
    }.getOrNull()

    fun showSpotifyConnectionHelp() {
        spotify.cancelAuthorization()
        _state.update {
            it.copy(
                message = null,
                error = "If Spotify says 'client_id: Invalid', it does not recognize the app ID. " +
                    "Open Developer Dashboard and copy Client ID from an active app's Settings / Basic Information. " +
                    "Replace the ID above and tap Connect. Harmony cannot generate or verify an app ID locally. " +
                    "For a redirect error, copy and save the exact Redirect URI shown below in that same Spotify app.",
            )
        }
    }

    fun spotifyBrowserLaunchFailed() {
        spotify.cancelAuthorization()
        _state.update {
            it.copy(error = "Harmony could not open a browser. Enable a browser and tap Connect again.", message = null)
        }
    }

    fun refreshPlaylists() {
        if (_state.value.isLoadingPlaylists) return
        _state.update { it.copy(isLoadingPlaylists = true, error = null) }
        viewModelScope.launch {
            runCatching { spotify.loadPlaylists() }
                .onSuccess { playlists ->
                    _state.update {
                        it.copy(
                            connected = true,
                            isLoadingPlaylists = false,
                            playlists = playlists,
                            message = if (playlists.isEmpty()) {
                                "Spotify returned no playlists for this account."
                            } else {
                                "Choose one of your Spotify playlists."
                            },
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            isLoadingPlaylists = false,
                            error = failure.message ?: "Spotify playlists could not be loaded.",
                        )
                    }
                }
        }
    }

    fun disconnectSpotify() {
        val current = _state.value
        if (current.isTransferring || current.isCheckingVerification ||
            current.isLoadingPlaylists || current.isImportingPlaylist
        ) return
        spotiFlacEngine.clearPendingVerificationTrackFor(SpotiFlacRequestOwner.PLAYLIST_TRANSFER)
        spotify.disconnect()
        _state.update {
            SpotifyPlaylistTransferState(
                clientId = it.clientId,
                source = it.source,
                soulseekFormatPreference = it.soulseekFormatPreference,
                soulseekConnection = it.soulseekConnection,
                message = "Spotify disconnected from Playlist Transfer.",
            )
        }
    }

    fun setPlaylistLink(value: String) {
        _state.update { it.copy(playlistLink = value, error = null) }
    }

    fun importPlaylistLink() {
        val id = SpotifyPlaylistClient.extractPlaylistId(_state.value.playlistLink)
        if (id == null) {
            _state.update { it.copy(error = "Paste a complete Spotify playlist link.") }
            return
        }
        importPlaylist(id)
    }

    fun importPlaylist(summary: SpotifyPlaylistSummary) {
        if (!summary.canImportItems) {
            _state.update {
                it.copy(
                    error = "Spotify currently allows item import only for playlists you own or collaborate on.",
                )
            }
            return
        }
        importPlaylist(summary.id)
    }

    private fun importPlaylist(playlistId: String) {
        if (_state.value.isImportingPlaylist || _state.value.isTransferring) return
        _state.update {
            it.copy(
                isImportingPlaylist = true,
                selectedPlaylist = null,
                tracks = emptyList(),
                resultPlaylistId = null,
                message = "Reading Spotify playlist metadata…",
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                val (summary, remoteTracks) = spotify.loadPlaylist(playlistId)
                val library = libraryRepository.observeSongs().first()
                val matches = SpotifyPlaylistMatcher.matchAll(remoteTracks, library)
                val matched = remoteTracks.mapIndexed { index, remote ->
                    val match = matches[index]
                    if (match == null) {
                        PlaylistTransferTrack(remote = remote)
                    } else {
                        PlaylistTransferTrack(
                            remote = remote,
                            localSongId = match.song.id,
                            confidence = match.confidence,
                            status = PlaylistTransferTrackStatus.IN_LIBRARY,
                            selected = false,
                            detail = match.confidence.label,
                        )
                    }
                }
                summary to matched
            }.onSuccess { (summary, tracks) ->
                _state.update {
                    it.copy(
                        isImportingPlaylist = false,
                        selectedPlaylist = summary,
                        tracks = tracks,
                        playlistLink = "",
                        completedDownloads = 0,
                        attemptedDownloads = 0,
                        message = "${tracks.count { row -> row.localSongId != null }} already in Harmony · " +
                            "${tracks.count { row -> row.localSongId == null }} available to locate.",
                    )
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        isImportingPlaylist = false,
                        error = failure.message ?: "The Spotify playlist could not be imported.",
                        message = null,
                    )
                }
            }
        }
    }

    fun clearSelectedPlaylist() {
        if (_state.value.isTransferring || _state.value.isCheckingVerification) return
        spotiFlacEngine.clearPendingVerificationTrackFor(SpotiFlacRequestOwner.PLAYLIST_TRANSFER)
        _state.update {
            it.copy(
                selectedPlaylist = null,
                tracks = emptyList(),
                resultPlaylistId = null,
                verificationChallenge = null,
                pausedForVerification = false,
                message = "Choose another Spotify playlist.",
                error = null,
            )
        }
    }

    fun selectSource(source: DownloadSource) {
        if (_state.value.isTransferring || _state.value.isCheckingVerification) return
        if (_state.value.pausedForVerification || source != _state.value.source) {
            spotiFlacEngine.clearPendingVerificationTrackFor(
                SpotiFlacRequestOwner.PLAYLIST_TRANSFER,
            )
        }
        downloadRepository.rememberDownloadSource(source)
        _state.update {
            it.copy(
                source = source,
                verificationChallenge = null,
                pausedForVerification = false,
                error = null,
            )
        }
    }

    fun setSpotiFlacOutputFormat(format: SpotiFlacOutputFormat) {
        if (_state.value.isTransferring || _state.value.isCheckingVerification) return
        _state.update { it.copy(spotiFlacOutputFormat = format) }
        spotiFlacUiPrefs.edit().putString(KEY_SPOTIFLAC_OUTPUT_FORMAT, format.name).apply()
    }

    fun toggleTrack(key: String) {
        if (_state.value.isTransferring || _state.value.isCheckingVerification) return
        _state.update { current ->
            current.copy(
                tracks = current.tracks.map { row ->
                    if (row.remote.stableKey == key && row.localSongId == null) {
                        row.copy(selected = !row.selected)
                    } else {
                        row
                    }
                },
            )
        }
    }

    fun selectAllMissing(selected: Boolean) {
        if (_state.value.isTransferring || _state.value.isCheckingVerification) return
        _state.update { current ->
            current.copy(
                tracks = current.tracks.map { row ->
                    if (row.localSongId == null) row.copy(selected = selected) else row
                },
            )
        }
    }

    fun retryFailedTracks() {
        if (_state.value.isTransferring || _state.value.isCheckingVerification) return
        _state.update { current ->
            current.copy(
                tracks = current.tracks.map { row ->
                    if (row.status == PlaylistTransferTrackStatus.FAILED) {
                        row.copy(status = PlaylistTransferTrackStatus.READY, selected = true, detail = null)
                    } else {
                        row
                    }
                },
                error = null,
                message = "Failed tracks are selected for another attempt.",
            )
        }
    }

    fun requestTransfer() {
        val current = _state.value
        if (current.isTransferring || current.isCheckingVerification) return
        if (current.selectedPlaylist == null) {
            _state.update { it.copy(error = "Import a Spotify playlist first.") }
            return
        }
        if (current.source == DownloadSource.SOULSEEK &&
            current.selectedMissingCount > 0 &&
            current.soulseekConnection.status != SoulseekConnectionStatus.CONNECTED
        ) {
            _state.update { it.copy(error = "Connect Soulseek in Downloads before starting this batch.") }
            return
        }
        _state.update { it.copy(showTransferConfirmation = true, error = null) }
    }

    fun dismissTransferConfirmation() {
        _state.update { it.copy(showTransferConfirmation = false) }
    }

    fun confirmTransfer() {
        _state.update { it.copy(showTransferConfirmation = false) }
        startTransfer(checkSpotiFlacPreflight = true)
    }

    private fun startTransfer(checkSpotiFlacPreflight: Boolean) {
        if (transferJob?.isActive == true || _state.value.isTransferring) return
        transferJob = viewModelScope.launch {
            try {
                val starting = _state.value
                if (starting.source == DownloadSource.SPOTIFLAC &&
                    starting.selectedMissingCount > 0 &&
                    checkSpotiFlacPreflight && pauseForSpotiFlacVerificationIfNeeded()
                ) {
                    return@launch
                }

                val workKeys = _state.value.tracks
                    .filter { it.localSongId == null && it.selected }
                    .map { it.remote.stableKey }
                _state.update { current ->
                    current.copy(
                        isTransferring = true,
                        pausedForVerification = false,
                        verificationChallenge = null,
                        attemptedDownloads = workKeys.size,
                        completedDownloads = 0,
                        tracks = current.tracks.map { row ->
                            if (row.remote.stableKey in workKeys &&
                                row.status != PlaylistTransferTrackStatus.DOWNLOADED
                            ) {
                                row.copy(status = PlaylistTransferTrackStatus.READY, detail = null)
                            } else {
                                row
                            }
                        },
                        message = if (workKeys.isEmpty()) {
                            "Creating the Harmony playlist from local matches…"
                        } else {
                            "Starting ${workKeys.size} selected track${if (workKeys.size == 1) "" else "s"} with ${current.source.displayName}…"
                        },
                        error = null,
                    )
                }

                val completedSpotifyIds = _state.value.tracks
                    .mapNotNull { row ->
                        val spotifyId = row.remote.spotifyId
                        val localSongId = row.localSongId
                        if (spotifyId != null && localSongId != null) spotifyId to localSongId else null
                    }
                    .toMap()
                    .toMutableMap()
                for ((index, key) in workKeys.withIndex()) {
                    val row = _state.value.tracks.firstOrNull { it.remote.stableKey == key } ?: continue
                    if (row.localSongId != null) continue
                    val duplicateSongId = row.remote.spotifyId?.let(completedSpotifyIds::get)
                    if (duplicateSongId != null) {
                        updateTrack(
                            key = key,
                            status = PlaylistTransferTrackStatus.IN_LIBRARY,
                            localSongId = duplicateSongId,
                            detail = "Same track already resolved earlier in this playlist",
                            selected = false,
                        )
                        continue
                    }
                    updateTrack(
                        key = key,
                        status = PlaylistTransferTrackStatus.SEARCHING,
                        detail = "${index + 1}/${workKeys.size} · Preparing ${_state.value.source.displayName}",
                    )
                    _state.update {
                        it.copy(
                            currentTrackKey = key,
                            completedDownloads = index,
                            activeDetail = "Preparing ${row.remote.displayName}",
                            activeProgress = null,
                        )
                    }
                    publishActive(row.remote, "Preparing…", null)

                    try {
                        val song = when (_state.value.source) {
                            DownloadSource.SPOTIFLAC -> downloadWithSpotiFlac(row.remote)
                            DownloadSource.SOULSEEK -> downloadWithSoulseek(row.remote)
                        }
                        updateTrack(
                            key = key,
                            status = PlaylistTransferTrackStatus.DOWNLOADED,
                            localSongId = song.id,
                            detail = "Downloaded with ${_state.value.source.displayName}",
                            selected = false,
                        )
                        row.remote.spotifyId?.let { completedSpotifyIds[it] = song.id }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: SpotiFlacException) {
                        if (failure.errorType.equals("verification_required", ignoreCase = true)) {
                            updateTrack(
                                key = key,
                                status = PlaylistTransferTrackStatus.WAITING_FOR_VERIFICATION,
                                detail = "Provider verification required",
                            )
                            _state.update {
                                it.copy(
                                    isTransferring = false,
                                    pausedForVerification = true,
                                    verificationChallenge = failure.verificationChallenge
                                        ?: SpotiFlacVerificationChallenge(
                                            providerId = failure.provider ?: SPOTIFLAC_PREFLIGHT_PROVIDER,
                                            pending = true,
                                            verificationUrl = failure.verificationUrl,
                                        ),
                                    activeDetail = null,
                                    activeProgress = null,
                                    message = "Complete provider verification, then Harmony will resume this playlist.",
                                    error = null,
                                )
                            }
                            statusCenter.clear()
                            return@launch
                        }
                        markTrackFailed(key, failure)
                    } catch (failure: Throwable) {
                        markTrackFailed(key, failure)
                    }
                }

                saveHarmonyPlaylist()
            } catch (cancelled: CancellationException) {
                _state.update { current ->
                    current.copy(
                        isTransferring = false,
                        currentTrackKey = null,
                        activeDetail = null,
                        activeProgress = null,
                        tracks = current.tracks.map { row ->
                            if (row.status == PlaylistTransferTrackStatus.SEARCHING ||
                                row.status == PlaylistTransferTrackStatus.DOWNLOADING
                            ) row.copy(status = PlaylistTransferTrackStatus.READY, detail = "Cancelled") else row
                        },
                        message = "Playlist transfer paused. Completed tracks are kept for this session.",
                    )
                }
                statusCenter.clear()
                throw cancelled
            }
        }
    }

    private suspend fun pauseForSpotiFlacVerificationIfNeeded(): Boolean {
        _state.update { it.copy(isCheckingVerification = true, message = "Checking SpotiFLAC provider verification…") }
        return try {
            val challenge = spotiFlacEngine.getVerificationChallenge(SPOTIFLAC_PREFLIGHT_PROVIDER)
            val mustVerify = challenge.pending && !challenge.authenticated
            if (mustVerify) {
                _state.value.tracks
                    .firstOrNull { it.localSongId == null && it.selected }
                    ?.remote
                    ?.let { pending ->
                        spotiFlacEngine.rememberPendingVerificationTrackFor(
                            pending.toIdentifiedTrack(),
                            SpotiFlacRequestOwner.PLAYLIST_TRANSFER,
                        )
                    }
            }
            _state.update {
                it.copy(
                    isCheckingVerification = false,
                    pausedForVerification = mustVerify,
                    verificationChallenge = challenge.takeIf { mustVerify },
                    message = if (mustVerify) {
                        "Verify the provider once, then Harmony will start the selected playlist tracks."
                    } else {
                        "SpotiFLAC provider is ready."
                    },
                )
            }
            mustVerify
        } catch (failure: Throwable) {
            _state.update {
                it.copy(
                    isCheckingVerification = false,
                    error = failure.message ?: "Provider verification could not be checked.",
                )
            }
            true
        }
    }

    fun resumeAfterProviderVerification() {
        val current = _state.value
        if (!current.pausedForVerification || current.isCheckingVerification) return
        _state.update { it.copy(isCheckingVerification = true, error = null) }
        viewModelScope.launch {
            try {
                val provider = current.verificationChallenge?.providerId ?: SPOTIFLAC_PREFLIGHT_PROVIDER
                val challenge = spotiFlacEngine.getVerificationChallenge(provider)
                if (challenge.authenticated || !challenge.pending) {
                    _state.update { state ->
                        state.copy(
                            isCheckingVerification = false,
                            pausedForVerification = false,
                            verificationChallenge = null,
                            tracks = state.tracks.map { row ->
                                if (row.status == PlaylistTransferTrackStatus.WAITING_FOR_VERIFICATION) {
                                    row.copy(status = PlaylistTransferTrackStatus.READY, detail = null)
                                } else row
                            },
                            message = "Provider verified. Resuming Playlist Transfer…",
                        )
                    }
                    startTransfer(checkSpotiFlacPreflight = false)
                } else {
                    _state.update {
                        it.copy(
                            isCheckingVerification = false,
                            verificationChallenge = challenge,
                            message = "Provider verification is still pending.",
                        )
                    }
                }
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        isCheckingVerification = false,
                        error = failure.message ?: "Verification status could not be refreshed.",
                    )
                }
            }
        }
    }

    fun cancelTransfer() {
        when (_state.value.source) {
            DownloadSource.SPOTIFLAC -> spotiFlacEngine.cancelCurrentDownload()
            DownloadSource.SOULSEEK -> soulseekClient.cancelActiveDownload()
        }
        spotiFlacEngine.clearPendingVerificationTrackFor(SpotiFlacRequestOwner.PLAYLIST_TRANSFER)
        transferJob?.cancel()
    }

    private suspend fun downloadWithSpotiFlac(remote: SpotifyPlaylistTrack): Song {
        updateTrack(remote.stableKey, PlaylistTransferTrackStatus.DOWNLOADING, "Resolving lossless provider…")
        var staged: java.io.File? = null
        try {
            val downloaded = spotiFlacEngine.download(
                track = remote.toIdentifiedTrack(),
                outputFormat = _state.value.spotiFlacOutputFormat,
                requestOwner = SpotiFlacRequestOwner.PLAYLIST_TRANSFER,
            ) { progress ->
                val detail = progress.detail ?: progress.stage.label
                _state.update { it.copy(activeDetail = detail, activeProgress = progress.fraction) }
                publishActive(remote, detail, progress.fraction)
            }
            staged = downloaded.tempFile
            val validation = when (downloaded.outputFormat) {
                SpotiFlacOutputFormat.FLAC_LOSSLESS -> downloadRepository.validateStagedFlac(downloaded.tempFile)
                SpotiFlacOutputFormat.MP3_320 -> downloadRepository.validateStagedMp3(downloaded.tempFile)
            }
            val saved = when (downloaded.outputFormat) {
                SpotiFlacOutputFormat.FLAC_LOSSLESS ->
                    downloadRepository.publishStagedFlac(downloaded.tempFile, downloaded.suggestedFileName)
                SpotiFlacOutputFormat.MP3_320 ->
                    downloadRepository.publishStagedMp3(downloaded.tempFile, downloaded.suggestedFileName)
            }
            staged = null
            recordDownloadAndScan(
                uri = saved.uri.toString(),
                remote = remote,
                source = DownloadSource.SPOTIFLAC,
                provider = downloaded.provider,
                format = downloaded.outputFormat.historyLabel,
                bitDepth = if (downloaded.outputFormat.isLosslessOutput) {
                    downloaded.bitDepth ?: validation.bitDepth
                } else null,
                sampleRateHz = downloaded.sampleRateHz ?: validation.sampleRateHz,
                fileSizeBytes = saved.sizeBytes,
                originalTrackId = downloaded.originalTrackId ?: remote.spotifyId,
                isrc = downloaded.isrc ?: remote.isrc,
            )
            return findPublishedSong(saved.uri.toString(), remote)
        } finally {
            staged?.let { file -> runCatching { file.delete() } }
        }
    }

    private suspend fun downloadWithSoulseek(remote: SpotifyPlaylistTrack): Song {
        check(soulseekClient.connectionState.value.status == SoulseekConnectionStatus.CONNECTED) {
            "Soulseek disconnected before this track could start."
        }
        val preference = _state.value.soulseekFormatPreference
        updateTrack(remote.stableKey, PlaylistTransferTrackStatus.SEARCHING, "Searching Soulseek peers…")
        val results = soulseekClient.searchFlac(
            query = remote.displayName,
            mode = SoulseekSearchMode.BALANCED,
            onlyFreeSlots = false,
            formatPreference = preference,
        )
        val sources = orderedSoulseekSources(results).take(MAX_SOULSEEK_SOURCES_PER_TRACK)
        if (sources.isEmpty()) {
            throw SoulseekException("No matching ${preference.label} source was found.")
        }

        try {
            soulseekClient.racePeerConnections(sources.take(3), maxPeers = 3)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Preconnecting is only a latency optimization. The ordinary peer
            // request below remains authoritative when a warm-up fails.
        }
        var lastFailure: Throwable? = null
        val queuedSources = LinkedHashMap<String, Pair<SoulseekSearchCandidate, Int>>()
        for ((index, source) in sources.withIndex()) {
            try {
                return transferSoulseekSource(
                    remote = remote,
                    source = source,
                    patience = SoulseekDownloadPatience.FAST,
                    detail = "Source ${index + 1}/${sources.size} · ${source.username}",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PublishedFileIndexException) {
                throw failure
            } catch (failure: Throwable) {
                lastFailure = failure
                if (failure is SoulseekQueuedException) {
                    queuedSources[source.id] = source to failure.queuePlace
                }
                delay(50)
            }
        }

        // Every fast source was busy or unreachable. Preserve the existing
        // Downloads behavior by waiting once in the shallowest confirmed queue,
        // then giving that peer one immediate final request when the wait ends.
        val patientTarget = queuedSources.values.minByOrNull { (_, place) ->
            if (place > 0) place else UNKNOWN_QUEUE_PLACE_RANK
        }
        if (patientTarget != null) {
            val (source, place) = patientTarget
            val patientPlan = listOf(
                SoulseekDownloadPatience.PATIENT to if (place > 0) {
                    "Waiting in ${source.username}'s queue (#$place)…"
                } else {
                    "Waiting in ${source.username}'s queue…"
                },
                SoulseekDownloadPatience.FAST to "Queue wait ended · asking ${source.username} once more…",
            )
            for ((patience, detail) in patientPlan) {
                try {
                    return transferSoulseekSource(remote, source, patience, detail)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: PublishedFileIndexException) {
                    throw failure
                } catch (failure: Throwable) {
                    lastFailure = failure
                }
            }
        }
        throw lastFailure ?: SoulseekException("No reachable Soulseek source was found.")
    }

    private suspend fun transferSoulseekSource(
        remote: SpotifyPlaylistTrack,
        source: SoulseekSearchCandidate,
        patience: SoulseekDownloadPatience,
        detail: String,
    ): Song {
        var staged: java.io.File? = null
        var published = false
        try {
            updateTrack(remote.stableKey, PlaylistTransferTrackStatus.DOWNLOADING, detail)
            val downloaded = soulseekClient.download(source, patience = patience)
            staged = downloaded.tempFile
            val saved = if (source.extension.equals("mp3", ignoreCase = true)) {
                downloadRepository.validateStagedMp3(downloaded.tempFile)
                downloadRepository.publishStagedMp3(downloaded.tempFile, downloaded.suggestedFileName)
            } else {
                downloadRepository.validateStagedFlac(downloaded.tempFile)
                downloadRepository.publishStagedFlac(downloaded.tempFile, downloaded.suggestedFileName)
            }
            published = true
            staged = null
            recordDownloadAndScan(
                uri = saved.uri.toString(),
                remote = remote,
                source = DownloadSource.SOULSEEK,
                format = source.extension.uppercase(),
                bitDepth = source.bitDepth,
                sampleRateHz = source.sampleRate,
                fileSizeBytes = saved.sizeBytes,
                soulseekUsername = source.username,
                originalTrackId = remote.spotifyId,
                isrc = remote.isrc,
            )
            return findPublishedSong(saved.uri.toString(), remote)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (published) {
                throw PublishedFileIndexException(
                    "The Soulseek file was saved, but Harmony could not finish indexing it: " +
                        (failure.message ?: "library scan failed"),
                    failure,
                )
            }
            throw failure
        } finally {
            staged?.let { file -> runCatching { file.delete() } }
        }
    }

    private fun orderedSoulseekSources(results: List<SoulseekSearchCandidate>): List<SoulseekSearchCandidate> {
        if (results.isEmpty()) return emptyList()
        val bestMatch = results.maxOf { it.score }
        val safeMinimum = maxOf(65, bestMatch - 10)
        return results.asSequence()
            .filter { it.score >= safeMinimum }
            .distinctBy { it.username.lowercase() }
            .sortedWith(
                compareByDescending<SoulseekSearchCandidate> { it.freeUploadSlot }
                    .thenBy { it.queueLength.coerceAtLeast(0) }
                    .thenByDescending { it.averageSpeedBytesPerSecond }
                    .thenByDescending { it.score },
            )
            .toList()
    }

    private suspend fun recordDownloadAndScan(
        uri: String,
        remote: SpotifyPlaylistTrack,
        source: DownloadSource,
        provider: String? = null,
        format: String? = null,
        bitDepth: Int? = null,
        sampleRateHz: Int? = null,
        fileSizeBytes: Long,
        soulseekUsername: String? = null,
        originalTrackId: String? = null,
        isrc: String? = null,
    ) {
        downloadRecordDao.upsert(
            DownloadRecordEntity(
                uri = uri,
                title = remote.title,
                artist = remote.artists,
                source = source.name,
                provider = provider,
                format = format,
                bitDepth = bitDepth,
                sampleRateHz = sampleRateHz,
                fileSizeBytes = fileSizeBytes,
                downloadedAt = System.currentTimeMillis(),
                soulseekUsername = soulseekUsername,
                originalTrackId = originalTrackId,
                isrc = isrc,
            ),
        )
        scanLibrary(force = false).collect { }
    }

    private suspend fun findPublishedSong(uri: String, remote: SpotifyPlaylistTrack): Song {
        repeat(PUBLISHED_SONG_LOOKUP_ATTEMPTS) { attempt ->
            val library = libraryRepository.observeSongs().first()
            val saved = library.firstOrNull { it.uri == uri }
                ?: SpotifyPlaylistMatcher.match(remote, library)?.song
            if (saved != null) return saved
            if (attempt < PUBLISHED_SONG_LOOKUP_ATTEMPTS - 1) delay(PUBLISHED_SONG_LOOKUP_DELAY_MS)
        }
        throw IllegalStateException(
            "The file was saved, but the library scanner has not indexed it yet. Run the transfer again after the next scan.",
        )
    }

    private suspend fun saveHarmonyPlaylist() {
        val current = _state.value
        val playlist = current.selectedPlaylist ?: run {
            _state.update {
                it.copy(isTransferring = false, error = "The Spotify playlist is no longer selected.")
            }
            return
        }
        val orderedSongIds = current.tracks
            .sortedBy { it.remote.position }
            .mapNotNull { it.localSongId }
            .distinct()
        val playlistId = current.resultPlaylistId
            ?: playlistRepository.create(playlist.name.trim().take(120).ifBlank { "Spotify playlist" })
        playlistRepository.replaceSongs(playlistId, orderedSongIds)
        spotiFlacEngine.clearPendingVerificationTrackFor(SpotiFlacRequestOwner.PLAYLIST_TRANSFER)
        val failed = _state.value.failedCount
        _state.update {
            it.copy(
                isTransferring = false,
                currentTrackKey = null,
                activeDetail = null,
                activeProgress = null,
                completedDownloads = it.attemptedDownloads,
                resultPlaylistId = playlistId,
                message = buildString {
                    append("Created \"")
                    append(playlist.name)
                    append("\" in Harmony with ")
                    append(orderedSongIds.size)
                    append(if (orderedSongIds.size == 1) " song." else " songs.")
                    if (failed > 0) append(" $failed track${if (failed == 1) "" else "s"} can be retried.")
                },
                error = null,
            )
        }
        statusCenter.clear()
        statusCenter.publishOutcome(
            title = playlist.name,
            succeeded = failed == 0,
            message = if (failed == 0) "Spotify playlist transferred to Harmony." else "$failed track(s) need attention.",
        )
    }

    private fun markTrackFailed(key: String, failure: Throwable) {
        updateTrack(
            key = key,
            status = PlaylistTransferTrackStatus.FAILED,
            detail = failure.message?.take(180) ?: "Download failed",
        )
        _state.update { it.copy(error = null) }
    }

    private fun updateTrack(
        key: String,
        status: PlaylistTransferTrackStatus,
        detail: String?,
        localSongId: Long? = null,
        selected: Boolean? = null,
    ) {
        _state.update { current ->
            current.copy(
                tracks = current.tracks.map { row ->
                    if (row.remote.stableKey == key) {
                        row.copy(
                            localSongId = localSongId ?: row.localSongId,
                            status = status,
                            detail = detail,
                            selected = selected ?: row.selected,
                        )
                    } else row
                },
            )
        }
    }

    private fun publishActive(remote: SpotifyPlaylistTrack, detail: String, fraction: Float?) {
        statusCenter.publishActive(
            ActiveDownload(
                title = remote.displayName,
                source = _state.value.source.displayName,
                detail = detail,
                fraction = fraction,
            ),
        )
    }

    private fun SpotifyPlaylistTrack.toIdentifiedTrack(): IdentifiedTrack = IdentifiedTrack(
        artist = artists,
        title = title,
        sourceTitle = displayName,
        thumbnailUrl = artworkUrl,
        album = album,
        durationMs = durationMs,
        isrc = isrc,
        spotifyId = spotifyId,
    )

    override fun onCleared() {
        transferJob?.cancel()
        if (_state.value.isTransferring) {
            when (_state.value.source) {
                DownloadSource.SPOTIFLAC -> spotiFlacEngine.cancelCurrentDownload()
                DownloadSource.SOULSEEK -> soulseekClient.cancelActiveDownload()
            }
        }
        spotiFlacEngine.clearPendingVerificationTrackFor(SpotiFlacRequestOwner.PLAYLIST_TRANSFER)
        statusCenter.clear()
        super.onCleared()
    }

    private companion object {
        const val SPOTIFLAC_PREFLIGHT_PROVIDER = "tidal-web"
        const val MAX_SOULSEEK_SOURCES_PER_TRACK = 6
        const val UNKNOWN_QUEUE_PLACE_RANK = Int.MAX_VALUE / 2
        const val PUBLISHED_SONG_LOOKUP_ATTEMPTS = 6
        const val PUBLISHED_SONG_LOOKUP_DELAY_MS = 500L
        const val SPOTIFLAC_UI_PREFERENCES = "harmony_spotiflac_ui"
        const val KEY_SPOTIFLAC_OUTPUT_FORMAT = "spotiflac_output_format"
    }

    private class PublishedFileIndexException(message: String, cause: Throwable) :
        Exception(message, cause)
}
