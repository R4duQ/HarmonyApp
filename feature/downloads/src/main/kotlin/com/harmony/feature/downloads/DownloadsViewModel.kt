package com.harmony.feature.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.data.analysis.inspect.SpectralInspector
import com.harmony.core.database.dao.DownloadRecordDao
import com.harmony.core.database.entity.DownloadRecordEntity
import com.harmony.domain.analysis.model.SpectralReport
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.harmony.core.datastore.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repository: MusicDownloadRepository,
    private val spectralInspector: SpectralInspector,
    private val soulseekClient: SoulseekClient,
    private val spotiFlacEngine: SpotiFlacDownloadEngine,
    private val downloadRecordDao: DownloadRecordDao,
    private val scanLibrary: ScanLibraryUseCase,
    private val settings: SettingsRepository,
    downloadStatusCenter: DownloadStatusCenter,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val statusCenter = downloadStatusCenter.forOwner()

    data class State(
        val query: String = "",
        val identifiedTrack: IdentifiedTrack? = null,
        val isIdentifying: Boolean = false,
        val isConverting: Boolean = false,
        val conversionProgress: Float = 0f,
        val conversionStatus: String = "",
        val directFlacUrl: String = "",
        val isValidating: Boolean = false,
        val download: HarmonyDownloadProgress? = null,
        val report: SpectralReport? = null,
        val error: String? = null,
        val errorDetails: String? = null,
        val showErrorDetails: Boolean = false,
        val canRetryYouTube: Boolean = false,
        val message: String? = null,
        val downloadFolderLabel: String = "Music/Harmony/Downloads",
        val usingCustomDownloadFolder: Boolean = false,
        val soulseekUsername: String = "",
        val soulseekPassword: String = "",
        val soulseekConnection: SoulseekConnectionState = SoulseekConnectionState(),
        val soulseekSharedFolder: SoulseekSharedFolder? = null,
        val isIndexingShare: Boolean = false,
        val soulseekQuery: String = "",
        val soulseekSearchMode: SoulseekSearchMode = SoulseekSearchMode.BALANCED,
        val soulseekFormatPreference: SoulseekFormatPreference = SoulseekFormatPreference.FLAC_ONLY,
        val soulseekOnlyFreeSlots: Boolean = false,
        val isSoulseekSearching: Boolean = false,
        val soulseekResults: List<SoulseekSearchCandidate> = emptyList(),
        val soulseekDiagnostics: SoulseekSearchDiagnostics = SoulseekSearchDiagnostics(),
        val showSoulseekDiagnostics: Boolean = false,
        val soulseekTransfer: SoulseekTransferProgress? = null,
        val soulseekDownloadingId: String? = null,
        val soulseekSourceAttempt: Int = 0,
        val soulseekSourceTotal: Int = 0,
        val spotiFlacTransfer: SpotiFlacTransferProgress? = null,
        val isSpotiFlacDownloading: Boolean = false,
        /** True only while the just-downloaded SpotiFLAC file is being quality-checked. */
        val isSpotiFlacQualityChecking: Boolean = false,
        val spotiFlacSearchResults: List<SpotiFlacSearchCandidate> = emptyList(),
        val selectedSpotiFlacMetadataId: String? = null,
        val spotiFlacOutputFormat: SpotiFlacOutputFormat = SpotiFlacOutputFormat.FLAC_LOSSLESS,
        val preferredDownloadSource: DownloadSource = DownloadSource.SPOTIFLAC,
        val selectedDownloadSource: DownloadSource = DownloadSource.SPOTIFLAC,
        val showDownloadSourceSelector: Boolean = false,
        val failedDownloadSource: DownloadSource? = null,
        val spotiFlacVerificationProvider: String? = null,
        val spotiFlacVerificationUrl: String? = null,
        val spotiFlacVerificationChallenge: SpotiFlacVerificationChallenge? = null,
        val isCheckingSpotiFlacVerification: Boolean = false,
        /** True when the primary SpotiFLAC provider can start a download without a browser challenge. */
        val spotiFlacPreflightReady: Boolean = false,
        val downloadHistory: List<DownloadHistoryItem> = emptyList(),
    )

    private val spotiFlacUiPrefs = context.getSharedPreferences("harmony_spotiflac_ui", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        State(
            downloadFolderLabel = repository.downloadFolderLabel(),
            usingCustomDownloadFolder = repository.hasCustomDownloadFolder(),
            soulseekUsername = soulseekClient.savedUsername(),
            soulseekConnection = soulseekClient.connectionState.value,
            soulseekSharedFolder = soulseekClient.sharedFolder.value,
            preferredDownloadSource = repository.lastDownloadSource(),
            selectedDownloadSource = repository.lastDownloadSource(),
            spotiFlacOutputFormat = SpotiFlacOutputFormat.fromName(
                spotiFlacUiPrefs.getString(KEY_SPOTIFLAC_OUTPUT_FORMAT, null),
            ),
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var soulseekDownloadJob: Job? = null
    private var spotiFlacDownloadJob: Job? = null
    private var lastSoulseekCandidate: SoulseekSearchCandidate? = null
    private var lastSpotiFlacTrack: IdentifiedTrack? = null
    private var pendingDownload: PendingDownload? = null
    private var verificationBrowserWasOpened = false

    private sealed interface PendingDownload {
        data class Identified(val track: IdentifiedTrack) : PendingDownload
        data class SoulseekCandidate(val candidate: com.harmony.feature.downloads.SoulseekSearchCandidate) : PendingDownload
    }

    init {
        // Mirror download activity into the app-scoped status centre so the
        // shell can show a banner on other screens. Derived from `state`
        // rather than hooked into each transfer call site: there are three
        // download routes (Soulseek, SpotiFLAC, direct file) and a dozen
        // places they update progress, and a single projection cannot drift
        // out of sync with the state the Downloads screen itself renders.
        viewModelScope.launch {
            var lastActiveTitle: String? = null
            var completionJob: Job? = null
            state.collect { current ->
                val active = current.toActiveDownload()
                // Progress is published immediately and undebounced, so the
                // bar stays live.
                statusCenter.publishActive(active)

                if (active != null) {
                    lastActiveTitle = active.title
                    // A download reappearing means the previous gap was the
                    // fallback moving between sources, not a finished job.
                    completionJob?.cancel()
                    completionJob = null
                    return@collect
                }

                val finished = lastActiveTitle
                if (finished == null || completionJob != null) return@collect

                // The Soulseek multi-source fallback clears its downloading id
                // between attempts, so "no active download" is not by itself
                // proof that anything finished. Wait for it to settle before
                // announcing an outcome; a retry cancels this job above.
                completionJob = viewModelScope.launch {
                    delay(COMPLETION_SETTLE_MS)
                    val settled = _state.value
                    if (settled.toActiveDownload() != null) return@launch
                    statusCenter.publishOutcome(
                        title = finished,
                        succeeded = settled.error == null,
                        message = settled.error,
                    )
                    lastActiveTitle = null
                    completionJob = null
                }
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
        viewModelScope.launch {
            soulseekClient.connectionState.collect { connection ->
                _state.update { it.copy(soulseekConnection = connection) }
            }
        }
        viewModelScope.launch {
            soulseekClient.sharedFolder.collect { folder ->
                _state.update { it.copy(soulseekSharedFolder = folder) }
            }
        }
        viewModelScope.launch {
            soulseekClient.indexingShare.collect { indexing ->
                _state.update { it.copy(isIndexingShare = indexing) }
            }
        }
        viewModelScope.launch {
            soulseekClient.searchResults.collect { results ->
                _state.update { it.copy(soulseekResults = results) }
            }
        }
        viewModelScope.launch {
            soulseekClient.searchDiagnostics.collect { diagnostics ->
                _state.update { it.copy(soulseekDiagnostics = diagnostics) }
            }
        }
        viewModelScope.launch {
            soulseekClient.transferProgress.collect { progress ->
                _state.update { it.copy(soulseekTransfer = progress) }
            }
        }
        viewModelScope.launch {
            downloadRecordDao.observeRecent(20).collect { records ->
                _state.update { current ->
                    current.copy(
                        downloadHistory = records.mapNotNull { record ->
                            val source = runCatching { DownloadSource.valueOf(record.source) }.getOrNull()
                                ?: return@mapNotNull null
                            DownloadHistoryItem(
                                title = record.title,
                                artist = record.artist,
                                source = source,
                                provider = record.provider,
                                format = record.format,
                                bitDepth = record.bitDepth,
                                sampleRateHz = record.sampleRateHz,
                                fileSizeBytes = record.fileSizeBytes,
                                downloadedAt = record.downloadedAt,
                                soulseekUsername = record.soulseekUsername,
                            )
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            spotiFlacEngine.verificationEvents.collect { result ->
                if (spotiFlacEngine.pendingVerificationOwner()?.let { it != SpotiFlacRequestOwner.DOWNLOADS } == true) return@collect
                handleSpotiFlacVerificationResult(result)
            }
        }
    }

    /** Open the required source selector for the currently identified track. */
    fun requestDownloadForIdentifiedTrack() {
        val track = _state.value.identifiedTrack
        if (track == null) {
            _state.update { it.copy(error = "Find a song first, then choose a download source.") }
            return
        }
        pendingDownload = PendingDownload.Identified(track)
        // No dialog. The engine was already chosen under Download method, so
        // asking again on every download is a confirmation of a decision the
        // user already made explicitly. Go straight to it.
        _state.update {
            it.copy(
                selectedDownloadSource = it.preferredDownloadSource,
                error = null,
                errorDetails = null,
            )
        }
        confirmDownloadSource()
    }

    fun selectPreferredDownloadSource(source: DownloadSource) {
        _state.update { current ->
            val sourceChanged = source != current.preferredDownloadSource
            current.copy(
                preferredDownloadSource = source,
                selectedDownloadSource = source,
                soulseekQuery = if (source == DownloadSource.SOULSEEK && current.query.isNotBlank()) {
                    current.query
                } else {
                    current.soulseekQuery
                },
                error = null,
                errorDetails = null,
                report = if (!sourceChanged) current.report else null,
                // Entering SpotiFLAC rechecks the provider session, but metadata
                // search remains independent from provider authentication.
                spotiFlacPreflightReady = if (source == DownloadSource.SPOTIFLAC && sourceChanged) false else current.spotiFlacPreflightReady,
                spotiFlacSearchResults = if (source == DownloadSource.SPOTIFLAC && sourceChanged) emptyList() else current.spotiFlacSearchResults,
                selectedSpotiFlacMetadataId = if (source == DownloadSource.SPOTIFLAC && sourceChanged) null else current.selectedSpotiFlacMetadataId,
                identifiedTrack = if (source == DownloadSource.SPOTIFLAC && sourceChanged) null else current.identifiedTrack,
            )
        }
    }

    fun selectPendingDownloadSource(source: DownloadSource) {
        _state.update { it.copy(selectedDownloadSource = source) }
    }

    fun dismissDownloadSourceSelector() {
        pendingDownload = null
        _state.update { it.copy(showDownloadSourceSelector = false) }
    }

    fun confirmDownloadSource() {
        val pending = pendingDownload ?: return
        val source = _state.value.selectedDownloadSource
        pendingDownload = null
        repository.rememberDownloadSource(source)
        _state.update {
            it.copy(
                showDownloadSourceSelector = false,
                preferredDownloadSource = source,
                failedDownloadSource = null,
                error = null,
                errorDetails = null,
            )
        }

        when (source) {
            DownloadSource.SPOTIFLAC -> {
                val track = when (pending) {
                    is PendingDownload.Identified -> pending.track
                    is PendingDownload.SoulseekCandidate -> identifiedTrackForCandidate(pending.candidate)
                }
                startSpotiFlacDownload(track)
            }
            DownloadSource.SOULSEEK -> when (pending) {
                is PendingDownload.Identified -> findSoulseekSourcesForTrack(pending.track)
                is PendingDownload.SoulseekCandidate -> startSoulseekDownload(pending.candidate)
            }
            // The converter has no source-resolution step: the URL in the
            // query box IS the source, so confirming goes straight to
            // conversion regardless of how `pending` was produced.
            DownloadSource.YTCONVERTER -> downloadYouTubeAsFlac()
        }
    }

    fun findSoulseekSourcesForIdentifiedTrack() {
        val track = _state.value.identifiedTrack
        if (track == null) {
            _state.update { it.copy(error = "Find a song first, then search Soulseek for a peer source.") }
            return
        }
        findSoulseekSourcesForTrack(track)
    }

    private fun findSoulseekSourcesForTrack(track: IdentifiedTrack) {
        val connected = _state.value.soulseekConnection.status == SoulseekConnectionStatus.CONNECTED
        _state.update {
            it.copy(
                soulseekQuery = track.displayName.ifBlank { it.soulseekQuery },
                error = null,
                errorDetails = null,
                failedDownloadSource = null,
                message = if (connected) {
                    "Soulseek selected. Searching the P2P network for peer sources…"
                } else {
                    "Soulseek selected. Connect your Soulseek account, then search for a peer source."
                },
            )
        }
        if (connected) searchSoulseek()
    }

    fun retryFailedEngineDownload() {
        when (_state.value.failedDownloadSource) {
            DownloadSource.SOULSEEK -> lastSoulseekCandidate?.let(::startSoulseekDownload)
            DownloadSource.SPOTIFLAC -> {
                val current = _state.value
                val verificationPending = current.spotiFlacVerificationChallenge?.let { challenge ->
                    challenge.pending && !challenge.authenticated
                } == true
                if (!current.spotiFlacPreflightReady || verificationPending) {
                    checkProviderVerificationAndRetry()
                } else {
                    lastSpotiFlacTrack?.let(::startSpotiFlacDownload)
                }
            }
            DownloadSource.YTCONVERTER -> downloadYouTubeAsFlac()
            null -> Unit
        }
    }

    /**
     * SpotiFLAC verification gate. The official v4.9.5
     * getExtensionPendingAuthJSON path bootstraps the provider signed-session
     * challenge when one is required. Metadata search is public and remains
     * available while this check runs; the provider session is required only
     * when the user starts a download.
     */
    fun prepareSpotiFlacVerification() {
        val current = _state.value
        if (current.preferredDownloadSource != DownloadSource.SPOTIFLAC) return
        if (current.isCheckingSpotiFlacVerification) return

        // Claim the check synchronously so screen recreation + callback routing
        // cannot start two signed-session preflights at the same time.
        _state.update { state ->
            state.copy(
                spotiFlacPreflightReady = false,
                isCheckingSpotiFlacVerification = true,
                spotiFlacVerificationProvider = SPOTIFLAC_PREFLIGHT_PROVIDER,
                error = null,
                errorDetails = null,
                showErrorDetails = false,
                message = "Checking ${providerDisplayName(SPOTIFLAC_PREFLIGHT_PROVIDER)} verification for downloading…",
            )
        }
        viewModelScope.launch {
            try {
                val challenge = spotiFlacEngine.getVerificationChallenge(SPOTIFLAC_PREFLIGHT_PROVIDER)
                applyVerificationChallenge(challenge)
                val ready = challenge.authenticated || !challenge.pending
                _state.update { state ->
                    state.copy(
                        spotiFlacPreflightReady = ready,
                        isCheckingSpotiFlacVerification = false,
                        error = null,
                        message = when {
                            challenge.authenticated -> "${providerDisplayName(challenge.providerId)} verification is complete. Downloads are ready."
                            challenge.pending && challenge.verificationUrl != null -> "${providerDisplayName(challenge.providerId)} verification is still pending for downloads. You can search metadata now."
                            challenge.pending -> "${providerDisplayName(challenge.providerId)} verification is required only before downloading. Metadata search remains available."
                            else -> "${providerDisplayName(challenge.providerId)} does not currently require interactive verification. Downloads are ready."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isCheckingSpotiFlacVerification = false) }
                throw cancelled
            } catch (t: Throwable) {
                _state.update { it.copy(isCheckingSpotiFlacVerification = false, spotiFlacPreflightReady = false) }
                fail(t, failedSource = DownloadSource.SPOTIFLAC)
            }
        }
    }

    fun providerVerificationOpened() {
        val provider = _state.value.spotiFlacVerificationProvider.orEmpty()
        verificationBrowserWasOpened = true
        _state.update {
            it.copy(
                message = if (provider.isBlank()) {
                    "Complete the provider verification. Android will return the signed callback to Harmony automatically."
                } else {
                    "Complete $provider verification. Android will return the signed callback to Harmony automatically."
                },
            )
        }
    }

    /**
     * Some browsers return to Harmony with Back instead of dispatching the
     * custom-scheme callback. Recheck once when the Downloads screen resumes;
     * the real signed callback still remains the authoritative completion path.
     */
    fun downloadsHostResumed() {
        if (!verificationBrowserWasOpened) return
        verificationBrowserWasOpened = false
        viewModelScope.launch {
            // Let the browser finish its Activity transition and let an
            // onNewIntent callback, when present, enter the engine first.
            delay(350)
            if (!_state.value.isCheckingSpotiFlacVerification &&
                _state.value.spotiFlacVerificationProvider != null
            ) {
                checkProviderVerificationAndRetry()
            }
        }
    }

    fun providerVerificationLaunchFailed() {
        _state.update {
            it.copy(message = "Harmony could not open the provider verification page. Refresh the challenge and try again.")
        }
    }

    private fun handleSpotiFlacVerificationResult(result: SpotiFlacVerificationCallbackResult) {
        val activeProvider = _state.value.spotiFlacVerificationProvider
        if (activeProvider != null && result.providerId.isNotBlank() &&
            !activeProvider.equals(result.providerId, ignoreCase = true)
        ) {
            return
        }

        if (!result.accepted) {
            _state.update { current ->
                current.copy(
                    isCheckingSpotiFlacVerification = false,
                    message = result.message,
                )
            }
            return
        }

        _state.update { current ->
            val provider = result.providerId.ifBlank { current.spotiFlacVerificationProvider.orEmpty() }
            current.copy(
                isCheckingSpotiFlacVerification = false,
                spotiFlacVerificationProvider = provider.ifBlank { current.spotiFlacVerificationProvider },
                spotiFlacPreflightReady = current.spotiFlacPreflightReady ||
                    provider.equals(SPOTIFLAC_PREFLIGHT_PROVIDER, ignoreCase = true),
                spotiFlacVerificationChallenge = current.spotiFlacVerificationChallenge?.let { challenge ->
                    if (challenge.providerId.equals(provider, ignoreCase = true)) {
                        challenge.copy(authenticated = true, pending = false)
                    } else {
                        challenge
                    }
                },
                message = result.message,
            )
        }

        // Only the workflow that initiated this provider challenge may resume
        // its track. Playlist Transfer has its own ordered queue and attaching
        // one of its files here would lose the playlist position.
        val track = spotiFlacEngine.pendingVerificationTrack(SpotiFlacRequestOwner.DOWNLOADS)
        if (track != null && !_state.value.isSpotiFlacDownloading) {
            lastSpotiFlacTrack = track
            _state.update { current ->
                current.copy(
                    identifiedTrack = current.identifiedTrack ?: track,
                    message = "Provider verification completed. Resuming the pending SpotiFLAC track…",
                )
            }
            startSpotiFlacDownload(track)
        } else if (track == null) {
            _state.update { current ->
                current.copy(
                    spotiFlacPreflightReady = true,
                    message = "Provider verification completed. Metadata search is now unlocked.",
                )
            }
        }
    }

    fun refreshProviderVerification() {
        val provider = _state.value.spotiFlacVerificationProvider ?: return
        if (_state.value.isCheckingSpotiFlacVerification) return
        viewModelScope.launch {
            _state.update { it.copy(isCheckingSpotiFlacVerification = true, message = "Refreshing provider verification state…") }
            try {
                val challenge = spotiFlacEngine.getVerificationChallenge(provider)
                applyVerificationChallenge(challenge)
                _state.update { current ->
                    val preflightProvider = provider.equals(SPOTIFLAC_PREFLIGHT_PROVIDER, ignoreCase = true)
                    val ready = challenge.authenticated || !challenge.pending
                    current.copy(
                        isCheckingSpotiFlacVerification = false,
                        spotiFlacPreflightReady = if (preflightProvider) ready else current.spotiFlacPreflightReady,
                        message = when {
                            challenge.authenticated && preflightProvider -> "${providerDisplayName(provider)} verification is complete. Downloads are ready."
                            challenge.authenticated -> "${providerDisplayName(provider)} verification is complete. Tap Check & continue."
                            challenge.verificationUrl != null -> "Verification challenge refreshed. Open the provider page and complete it."
                            challenge.pending -> "The provider still has a pending verification request."
                            preflightProvider -> "No interactive verification is required right now. Downloads are ready."
                            else -> "No pending verification request is currently exposed. You can continue the current SpotiFLAC flow."
                        },
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isCheckingSpotiFlacVerification = false, message = null) }
                fail(t, failedSource = DownloadSource.SPOTIFLAC)
            }
        }
    }

    fun checkProviderVerificationAndRetry() {
        val provider = _state.value.spotiFlacVerificationProvider ?: return
        if (_state.value.isCheckingSpotiFlacVerification) return
        viewModelScope.launch {
            _state.update { it.copy(isCheckingSpotiFlacVerification = true, message = "Checking ${providerDisplayName(provider)} verification…") }
            try {
                val challenge = spotiFlacEngine.getVerificationChallenge(provider)
                applyVerificationChallenge(challenge)
                _state.update { current -> current.copy(isCheckingSpotiFlacVerification = false) }
                if (challenge.authenticated || !challenge.pending) {
                    val preflightProvider = provider.equals(SPOTIFLAC_PREFLIGHT_PROVIDER, ignoreCase = true)
                    val track = spotiFlacEngine.pendingVerificationTrack(SpotiFlacRequestOwner.DOWNLOADS)
                    _state.update { current ->
                        current.copy(
                            spotiFlacPreflightReady = if (preflightProvider) true else current.spotiFlacPreflightReady,
                            message = if (track != null) {
                                "Provider verification is ready. Retrying SpotiFLAC…"
                            } else {
                                "Provider verification is ready. Downloads are unlocked."
                            },
                        )
                    }
                    track?.let {
                        lastSpotiFlacTrack = it
                        startSpotiFlacDownload(it)
                    }
                } else {
                    _state.update {
                        it.copy(
                            message = if (challenge.verificationUrl != null) {
                                "Verification is still pending. Reopen Verify ${providerDisplayName(provider)}; Android should return the signed callback to Harmony automatically when the provider finishes."
                            } else {
                                "Verification is still pending and no browser challenge is currently exposed. Refresh the challenge and try again."
                            },
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isCheckingSpotiFlacVerification = false) }
                fail(t, failedSource = DownloadSource.SPOTIFLAC)
            }
        }
    }

    private fun applyVerificationChallenge(challenge: SpotiFlacVerificationChallenge) {
        _state.update { current ->
            current.copy(
                spotiFlacVerificationProvider = challenge.providerId,
                spotiFlacVerificationUrl = challenge.verificationUrl,
                spotiFlacVerificationChallenge = challenge,
                spotiFlacPreflightReady = if (challenge.providerId.equals(SPOTIFLAC_PREFLIGHT_PROVIDER, ignoreCase = true)) {
                    challenge.authenticated || !challenge.pending
                } else {
                    current.spotiFlacPreflightReady
                },
                errorDetails = listOfNotNull(
                    current.errorDetails,
                    challenge.safeDetails?.let { "Pending auth summary:\n$it" },
                ).joinToString("\n\n").take(8_000),
            )
        }
    }

    private fun providerDisplayName(provider: String): String = provider
        .substringBefore('-')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    fun requestAlternativeDownloadSource() {
        val failed = _state.value.failedDownloadSource ?: return
        val track = _state.value.identifiedTrack ?: lastSpotiFlacTrack
        if (track == null) {
            _state.update { it.copy(error = "Find the song again before switching download source.") }
            return
        }
        pendingDownload = PendingDownload.Identified(track)
        val alternative = failed.alternative()
        _state.update {
            it.copy(
                showDownloadSourceSelector = true,
                selectedDownloadSource = alternative,
                error = null,
                errorDetails = null,
                spotiFlacVerificationProvider = null,
                spotiFlacVerificationUrl = null,
                spotiFlacVerificationChallenge = null,
                isCheckingSpotiFlacVerification = false,
            )
        }
    }

    fun selectDownloadFolder(uri: Uri) {
        runCatching { repository.selectDownloadFolder(uri) }
            .onSuccess {
                _state.update {
                    it.copy(
                        downloadFolderLabel = repository.downloadFolderLabel(),
                        usingCustomDownloadFolder = true,
                        message = "Download folder changed to ${repository.downloadFolderLabel()}.",
                        error = null,
                        errorDetails = null,
                    )
                }
            }
            .onFailure { fail(it) }
    }

    fun useDefaultDownloadFolder() {
        repository.useDefaultDownloadFolder()
        _state.update {
            it.copy(
                downloadFolderLabel = repository.downloadFolderLabel(),
                usingCustomDownloadFolder = false,
                message = "Downloads will use Music/Harmony/Downloads.",
                error = null,
                errorDetails = null,
            )
        }
    }

    fun setSoulseekUsername(value: String) {
        _state.update { it.copy(soulseekUsername = value, error = null, errorDetails = null) }
    }

    fun setSoulseekPassword(value: String) {
        _state.update { it.copy(soulseekPassword = value, error = null, errorDetails = null) }
    }

    fun setSoulseekQuery(value: String) {
        _state.update { it.copy(soulseekQuery = value, error = null, errorDetails = null) }
    }

    /**
     * FLAC is a lot of storage on a phone. This lets the person trade it away.
     * Persisted rather than per-search: someone who wants MP3 wants it for the
     * next download too, not just this one.
     */
    fun setSoulseekFormatPreference(preference: SoulseekFormatPreference) {
        _state.update { it.copy(soulseekFormatPreference = preference) }
        viewModelScope.launch { settings.setSoulseekFormatPreference(preference.name) }
    }

    fun setSpotiFlacOutputFormat(format: SpotiFlacOutputFormat) {
        if (_state.value.isSpotiFlacDownloading) return
        _state.update {
            it.copy(
                spotiFlacOutputFormat = format,
                report = null,
                error = null,
                errorDetails = null,
                message = when (format) {
                    SpotiFlacOutputFormat.FLAC_LOSSLESS -> "SpotiFLAC output set to FLAC up to 16-bit / 44.1 kHz."
                    SpotiFlacOutputFormat.FLAC_HI_RES_96 -> "SpotiFLAC will request the best available lossless quality, up to 24-bit / 96 kHz per track."
                    SpotiFlacOutputFormat.MP3_320 -> "SpotiFLAC output set to MP3 320 kbps. Harmony will encode it locally from the verified lossless source."
                },
            )
        }
        spotiFlacUiPrefs.edit().putString(KEY_SPOTIFLAC_OUTPUT_FORMAT, format.name).apply()
    }

    fun setSoulseekSearchMode(mode: SoulseekSearchMode) {
        _state.update {
            it.copy(
                soulseekSearchMode = mode,
                error = null,
                errorDetails = null,
            )
        }
    }

    fun setSoulseekOnlyFreeSlots(value: Boolean) {
        _state.update {
            it.copy(
                soulseekOnlyFreeSlots = value,
                error = null,
                errorDetails = null,
            )
        }
    }

    fun connectSoulseek() {
        val current = _state.value
        if (current.soulseekConnection.status == SoulseekConnectionStatus.CONNECTING) return
        if (current.soulseekUsername.isBlank() || current.soulseekPassword.isBlank()) {
            _state.update { it.copy(error = "Enter your Soulseek username and password first.") }
            return
        }
        viewModelScope.launch {
            try {
                soulseekClient.connect(current.soulseekUsername, current.soulseekPassword)
                _state.update {
                    it.copy(
                        message = "Connected to Soulseek as ${current.soulseekUsername}.",
                        error = null,
                        errorDetails = null,
                    )
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun disconnectSoulseek() {
        viewModelScope.launch {
            soulseekClient.disconnect()
            _state.update {
                it.copy(
                    soulseekPassword = "",
                    soulseekResults = emptyList(),
                    soulseekTransfer = null,
                    soulseekDownloadingId = null,
                    soulseekSourceAttempt = 0,
                    soulseekSourceTotal = 0,
                    message = "Disconnected from Soulseek.",
                )
            }
        }
    }

    /**
     * Called once the system folder picker returns a tree.
     *
     * The persistable grant must be taken here, before anything else touches
     * the URI: it is only valid to take from the exact Intent the picker
     * returned, and without it the grant dies with the Activity — the share
     * would stop working on the next app start with no visible cause.
     */
    fun setSharedFolder(treeUri: Uri) {
        viewModelScope.launch {
            val taken = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            if (taken.isFailure) {
                _state.update { it.copy(error = "Couldn't keep access to that folder. Try another one.") }
                return@launch
            }
            soulseekClient.setSharedFolder(treeUri, SoulseekShareIndexer.treeDisplayName(treeUri))
        }
    }

    fun refreshSharedFolder() {
        soulseekClient.refreshSharedFolder()
    }

    fun clearSharedFolder() {
        soulseekClient.clearSharedFolder()
    }

    fun searchSoulseek() {
        val current = _state.value
        if (current.isSoulseekSearching) return
        if (current.soulseekConnection.status != SoulseekConnectionStatus.CONNECTED) {
            // Prefer the real reason the session ended (relogged elsewhere,
            // dropped connection) over the generic prompt — otherwise a
            // taken-over account just looks like search being broken.
            val reason = current.soulseekConnection.message
                .takeIf {
                    current.soulseekConnection.status == SoulseekConnectionStatus.ERROR &&
                        it.isNotBlank()
                }
                ?: "Connect to Soulseek before searching the peer network."
            _state.update { it.copy(error = reason) }
            return
        }
        val searchText = current.soulseekQuery.trim().ifBlank {
            current.identifiedTrack?.displayName?.takeIf { it.isNotBlank() }
                ?: current.query.takeUnless(repository::isYouTubeUrl).orEmpty()
        }
        if (searchText.isBlank()) {
            _state.update { it.copy(error = "Enter an artist, album or song in Peer Search.") }
            return
        }
        _state.update {
            it.copy(
                soulseekQuery = searchText,
                isSoulseekSearching = true,
                soulseekResults = emptyList(),
                soulseekDiagnostics = SoulseekSearchDiagnostics(),
                showSoulseekDiagnostics = false,
                report = null,
                error = null,
                errorDetails = null,
                message = when (current.soulseekSearchMode) {
                    SoulseekSearchMode.QUICK -> "Quick Soulseek search…"
                    SoulseekSearchMode.BALANCED -> "Smart Soulseek search…"
                    SoulseekSearchMode.DEEP -> "Deep Soulseek search across query variants…"
                },
            )
        }
        viewModelScope.launch {
            try {
                val results = soulseekClient.searchFlac(
                    query = searchText,
                    mode = current.soulseekSearchMode,
                    onlyFreeSlots = current.soulseekOnlyFreeSlots,
                    formatPreference = current.soulseekFormatPreference,
                )
                _state.update {
                    it.copy(
                        isSoulseekSearching = false,
                        soulseekResults = results,
                        // An empty list is now explained rather than guessed
                        // at: the counters say whether nobody answered, or
                        // everyone answered with MP3.
                        showSoulseekDiagnostics = results.isEmpty(),
                        message = if (results.isEmpty()) {
                            val diagnostics = soulseekClient.searchDiagnostics.value
                            diagnostics.diagnosis
                                ?: "No matching ${current.soulseekFormatPreference.label} sources " +
                                    "arrived. Try Deep mode, a simpler artist - title query, or a " +
                                    "wider format filter."
                        } else {
                            "Harmony ranked the best ${results.size} FLAC source${if (results.size == 1) "" else "s"}."
                        },
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isSoulseekSearching = false) }
                fail(t)
            }
        }
    }

    fun toggleSoulseekDiagnostics() {
        _state.update { it.copy(showSoulseekDiagnostics = !it.showSoulseekDiagnostics) }
    }

    fun downloadBestSoulseek() {
        val results = _state.value.soulseekResults
        val best = chooseFastTransferSource(results)
        if (best == null) {
            _state.update {
                it.copy(error = "No Soulseek sources are loaded. Run a Peer Search first.")
            }
            return
        }
        downloadSoulseek(best)
    }

    /**
     * "Best" should not mean "highest sample rate at any cost". Search ranking
     * already protects title accuracy; for the actual transfer we take sources
     * close to the best match and strongly prefer a free slot, short queue and
     * the peer's advertised average upload speed. This usually starts sooner and
     * finishes faster without risking an unrelated track.
     */
    private fun chooseFastTransferSource(
        results: List<SoulseekSearchCandidate>,
    ): SoulseekSearchCandidate? = orderedFastTransferSources(results).firstOrNull()

    private fun orderedFastTransferSources(
        results: List<SoulseekSearchCandidate>,
    ): List<SoulseekSearchCandidate> {
        if (results.isEmpty()) return emptyList()
        val bestMatchScore = results.maxOf { it.score }
        val minimumSafeScore = maxOf(65, bestMatchScore - 10)
        val safePool = results.filter { it.score >= minimumSafeScore }.ifEmpty { results }

        return safePool.sortedWith(
            compareByDescending<SoulseekSearchCandidate> { it.freeUploadSlot }
                .thenBy { it.queueLength.coerceAtLeast(0) }
                .thenByDescending { it.averageSpeedBytesPerSecond }
                .thenByDescending { it.score }
        )
    }

    fun downloadSoulseek(candidate: SoulseekSearchCandidate) {
        lastSoulseekCandidate = candidate
        pendingDownload = PendingDownload.SoulseekCandidate(candidate)
        // Tapping a specific Soulseek peer is already an unambiguous choice of
        // engine; a dialog asking "SpotiFLAC or Soulseek?" at that point is
        // pure friction.
        _state.update {
            it.copy(
                selectedDownloadSource = DownloadSource.SOULSEEK,
                error = null,
                errorDetails = null,
                failedDownloadSource = null,
            )
        }
        // The dialog used to be what actually started the download, via its
        // confirm button. Removing it without this call would leave the peer
        // selected and nothing happening.
        confirmDownloadSource()
    }

    private fun startSoulseekDownload(candidate: SoulseekSearchCandidate) {
        lastSoulseekCandidate = candidate
        val current = _state.value
        // Both guards below used to return silently, so a stuck flag or an
        // empty result list made the button do literally nothing with no
        // explanation. Say why instead.
        if (current.soulseekDownloadingId != null) {
            _state.update {
                it.copy(error = "A Soulseek download is already running. Wait for it to finish first.")
            }
            return
        }

        val cellular = soulseekClient.isCellularNetwork()
        // Never let one slow/unresponsive peer hold Download Best hostage.
        // Keep the explicitly chosen source first, then walk through the other
        // high-confidence results (one per peer) until one actually starts.
        // This fallback is useful on Wi-Fi too; mobile data simply uses shorter
        // low-level peer timeouts inside SoulseekClient.
        val fallbackSources = buildList {
            add(candidate)
            orderedFastTransferSources(current.soulseekResults)
                .asSequence()
                .filterNot { it.id == candidate.id }
                .filterNot { other -> any { it.username.equals(other.username, ignoreCase = true) } }
                .take(MAX_FAST_FALLBACK_SOURCES - 1)
                .forEach(::add)
        }

        _state.update {
            it.copy(
                soulseekDownloadingId = candidate.id,
                soulseekSourceAttempt = 1,
                soulseekSourceTotal = fallbackSources.size,
                report = null,
                failedDownloadSource = null,
                error = null,
                errorDetails = null,
                message = if (cellular) {
                    "Mobile data: requesting ${candidate.fileNameOnly} from ${candidate.username}…"
                } else {
                    "Requesting ${candidate.fileNameOnly} from ${candidate.username}…"
                },
            )
        }
        soulseekDownloadJob = viewModelScope.launch {
            var lastError: Throwable? = null

            // Prepare the best few P-connections concurrently. We only send a
            // file request to one peer at a time, so this avoids duplicate
            // downloads while removing the long serial connection wait.
            val racePool = fallbackSources.take(MAX_PARALLEL_PRECONNECT_SOURCES)
            _state.update {
                it.copy(
                    message = if (racePool.size > 1) {
                        "Preparing ${racePool.size} Soulseek sources in parallel…"
                    } else {
                        it.message
                    },
                )
            }
            val raceWinner = runCatching {
                soulseekClient.racePeerConnections(
                    candidates = racePool,
                    maxPeers = MAX_PARALLEL_PRECONNECT_SOURCES,
                )
            }.getOrNull()

            // The tapped candidate is ALWAYS tried first.
            //
            // This used to promote raceWinner — whichever peer answered the
            // preconnect race fastest — to the front. That silently overrode an
            // explicit choice: tap the second result because it is a better
            // file, and the app would still start with someone else, because
            // the race is about latency and knows nothing about which file you
            // wanted.
            //
            // The race is still worth running: its winner goes second, so if
            // the chosen peer fails the next attempt is already connected. That
            // keeps the latency benefit without taking the decision away.
            val attemptSources = buildList {
                add(candidate)
                if (raceWinner != null && raceWinner.id != candidate.id) {
                    add(raceWinner)
                }
                fallbackSources
                    .filterNot { it.id == candidate.id }
                    .filterNot { raceWinner != null && it.id == raceWinner.id }
                    .forEach(::add)
            }

            // Peers that answered and queued us. These are alive and willing —
            // just busy — so they are the right candidates for a patient second
            // pass rather than sources to discard.
            val queuedSources = LinkedHashMap<String, Pair<SoulseekSearchCandidate, Int>>()

            for ((index, source) in attemptSources.withIndex()) {
                try {
                    _state.update {
                        it.copy(
                            soulseekDownloadingId = source.id,
                            soulseekSourceAttempt = index + 1,
                            soulseekSourceTotal = attemptSources.size,
                            // The first source used to inherit whatever message
                            // the preconnect race left behind — "Preparing N
                            // sources in parallel…" — and keep it for the whole
                            // attempt: connect, queue request, queue wait and
                            // transfer. So the one source most downloads
                            // actually use was the one with no status at all.
                            message = if (index == 0) {
                                android.util.Log.i(
                                    SoulseekClient.SLSK_TAG,
                                    "DL source 1/${attemptSources.size} user=${source.username}",
                                )
                                "Connecting to ${source.username}…"
                            } else {
                                "Source ${index + 1}/${attemptSources.size}: trying ${source.username}…"
                            },
                        )
                    }
                    val downloaded = soulseekClient.download(source)
                    _state.update { currentState ->
                        currentState.copy(
                            soulseekTransfer = currentState.soulseekTransfer?.let { transfer ->
                                transfer.copy(
                                    status = "Saving to library…",
                                    downloadedBytes = transfer.totalBytes,
                                )
                            },
                            message = "Transfer complete. Saving to ${currentState.downloadFolderLabel}…",
                        )
                    }
                    val saved = repository.publishSoulseekFlac(downloaded)
                    recordDownload(
                        uri = saved.uri.toString(),
                        title = _state.value.identifiedTrack?.title ?: source.fileNameOnly.substringBeforeLast('.'),
                        artist = _state.value.identifiedTrack?.artist.orEmpty(),
                        source = DownloadSource.SOULSEEK,
                        provider = null,
                        // The peer decides the format, not the app: a record
                        // that says FLAC over an MP3 file is worse than no
                        // record at all.
                        format = source.extension.uppercase(),
                        bitDepth = source.bitDepth,
                        sampleRateHz = source.sampleRate,
                        fileSizeBytes = saved.sizeBytes,
                        soulseekUsername = source.username,
                    )
                    _state.update { currentState ->
                        currentState.copy(
                            soulseekDownloadingId = null,
                            soulseekSourceAttempt = 0,
                            soulseekSourceTotal = 0,
                            soulseekTransfer = currentState.soulseekTransfer?.let { transfer ->
                                transfer.copy(
                                    status = "Checking FLAC spectrum…",
                                    downloadedBytes = transfer.totalBytes,
                                )
                            },
                            message = "Saved to ${currentState.downloadFolderLabel}. Checking the FLAC spectrum…",
                        )
                    }
                    inspectDownloadedFile(
                        uriString = saved.uri.toString(),
                        sizeBytes = saved.sizeBytes,
                        fileNameOverride = saved.displayName,
                        source = DownloadSource.SOULSEEK,
                    )
                    return@launch
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    if (t is SoulseekQueuedException) {
                        queuedSources[source.id] = source to t.queuePlace
                    }
                    if (index == attemptSources.lastIndex) break
                    _state.update {
                        it.copy(
                            message = if (t is SoulseekQueuedException) {
                                if (t.queuePlace > 0) {
                                    "${source.username} is queued at #${t.queuePlace}. Checking for a free source…"
                                } else {
                                    "${source.username} hasn't answered yet. Checking for a free source…"
                                }
                            } else {
                                "${source.username} did not start the transfer. Trying another recommended source…"
                            },
                            error = null,
                            errorDetails = null,
                        )
                    }
                    delay(50)
                }
            }

            // Second pass. No peer was immediately free, but some answered and
            // put us in their queue. Previously the download simply failed here,
            // which is why a popular track with only busy seeders never
            // completed. Return to the shallowest queue and actually wait.
            // Known positions rank ahead of unknown ones (place 0 means the
            // peer is reachable but has not reported a position yet), so a peer
            // that actually told us "#3" is preferred over pure silence.
            val patientTarget = queuedSources.values.minByOrNull { (_, place) ->
                if (place > 0) place else UNKNOWN_QUEUE_PLACE_RANK
            }
            // Two attempts on that peer, not one. When the patient wait ends,
            // the peer has very often just reached our turn and freed a slot --
            // which is why pressing Download again by hand started instantly.
            // The immediate second knock does that automatically.
            val patientPlan = patientTarget?.let { (source, place) ->
                listOf(
                    Triple(source, place, SoulseekDownloadPatience.PATIENT),
                    Triple(source, place, SoulseekDownloadPatience.FAST),
                )
            }.orEmpty()

            for ((planIndex, attempt) in patientPlan.withIndex()) {
                val (source, place, attemptPatience) = attempt
                try {
                    _state.update {
                        it.copy(
                            soulseekDownloadingId = source.id,
                            soulseekSourceAttempt = 0,
                            soulseekSourceTotal = 0,
                            message = when {
                                planIndex > 0 ->
                                    "Queue wait ended — asking ${source.username} once more…"
                                place > 0 ->
                                    "No free source right now. Waiting in ${source.username}'s queue (#$place)…"
                                else ->
                                    "No free source right now. Waiting in ${source.username}'s queue…"
                            },
                            error = null,
                            errorDetails = null,
                        )
                    }
                    val downloaded = soulseekClient.download(source, patience = attemptPatience)
                    _state.update { currentState ->
                        currentState.copy(
                            soulseekTransfer = currentState.soulseekTransfer?.let { transfer ->
                                transfer.copy(
                                    status = "Saving to library…",
                                    downloadedBytes = transfer.totalBytes,
                                )
                            },
                            message = "Transfer complete. Saving to ${currentState.downloadFolderLabel}…",
                        )
                    }
                    val saved = repository.publishSoulseekFlac(downloaded)
                    recordDownload(
                        uri = saved.uri.toString(),
                        title = _state.value.identifiedTrack?.title ?: source.fileNameOnly.substringBeforeLast('.'),
                        artist = _state.value.identifiedTrack?.artist.orEmpty(),
                        source = DownloadSource.SOULSEEK,
                        provider = null,
                        // The peer decides the format, not the app: a record
                        // that says FLAC over an MP3 file is worse than no
                        // record at all.
                        format = source.extension.uppercase(),
                        bitDepth = source.bitDepth,
                        sampleRateHz = source.sampleRate,
                        fileSizeBytes = saved.sizeBytes,
                        soulseekUsername = source.username,
                    )
                    _state.update { currentState ->
                        currentState.copy(
                            soulseekDownloadingId = null,
                            soulseekSourceAttempt = 0,
                            soulseekSourceTotal = 0,
                            soulseekTransfer = currentState.soulseekTransfer?.let { transfer ->
                                transfer.copy(
                                    status = "Checking FLAC spectrum…",
                                    downloadedBytes = transfer.totalBytes,
                                )
                            },
                            message = "Saved to ${currentState.downloadFolderLabel}. Checking the FLAC spectrum…",
                        )
                    }
                    inspectDownloadedFile(
                        uriString = saved.uri.toString(),
                        sizeBytes = saved.sizeBytes,
                        fileNameOverride = saved.displayName,
                        source = DownloadSource.SOULSEEK,
                    )
                    return@launch
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                }
            }

            _state.update { it.copy(soulseekDownloadingId = null, failedDownloadSource = DownloadSource.SOULSEEK) }
            fail(
                lastError ?: SoulseekException("No reachable Soulseek source was found."),
                failedSource = DownloadSource.SOULSEEK,
            )
        }
    }

    private fun identifiedTrackForCandidate(candidate: SoulseekSearchCandidate): IdentifiedTrack {
        _state.value.identifiedTrack?.let { return it }
        val title = candidate.fileNameOnly.substringBeforeLast('.').trim()
        return IdentifiedTrack(
            artist = "",
            title = title,
            sourceTitle = title,
        )
    }

    private fun startSpotiFlacDownload(track: IdentifiedTrack) {
        if (_state.value.isSpotiFlacDownloading) {
            _state.update { it.copy(error = "A SpotiFLAC download is already running.") }
            return
        }
        lastSpotiFlacTrack = track
        val requestedFormat = _state.value.spotiFlacOutputFormat
        _state.update {
            it.copy(
                isSpotiFlacDownloading = true,
                isSpotiFlacQualityChecking = false,
                spotiFlacTransfer = SpotiFlacTransferProgress(SpotiFlacStage.PREPARING),
                report = null,
                failedDownloadSource = null,
                error = null,
                errorDetails = null,
                spotiFlacVerificationProvider = null,
                spotiFlacVerificationUrl = null,
                spotiFlacVerificationChallenge = null,
                isCheckingSpotiFlacVerification = false,
                message = when (requestedFormat) {
                    SpotiFlacOutputFormat.FLAC_LOSSLESS, SpotiFlacOutputFormat.FLAC_HI_RES_96 -> "SpotiFLAC selected. Preparing ${requestedFormat.label}…"
                    SpotiFlacOutputFormat.MP3_320 -> "SpotiFLAC selected. Preparing a lossless source for local MP3 320 kbps encoding…"
                },
            )
        }
        spotiFlacDownloadJob = viewModelScope.launch {
            var stagingDir: java.io.File? = null
            try {
                val downloaded = spotiFlacEngine.download(track, requestedFormat) { progress ->
                    _state.update { current ->
                        current.copy(
                            spotiFlacTransfer = progress,
                            message = progress.detail ?: progress.stage.label,
                        )
                    }
                }
                stagingDir = downloaded.tempFile.parentFile
                _state.update {
                    it.copy(
                        spotiFlacTransfer = (it.spotiFlacTransfer ?: SpotiFlacTransferProgress(SpotiFlacStage.VALIDATING))
                            .copy(stage = SpotiFlacStage.VALIDATING, fraction = 1f, provider = downloaded.provider),
                        message = when (downloaded.outputFormat) {
                            SpotiFlacOutputFormat.FLAC_LOSSLESS, SpotiFlacOutputFormat.FLAC_HI_RES_96 -> "SpotiFLAC download complete. Validating the FLAC before import…"
                            SpotiFlacOutputFormat.MP3_320 -> "MP3 encoding complete. Validating the MPEG audio before import…"
                        },
                    )
                }
                val validation = when (downloaded.outputFormat) {
                    SpotiFlacOutputFormat.FLAC_LOSSLESS, SpotiFlacOutputFormat.FLAC_HI_RES_96 -> repository.validateStagedFlac(downloaded.tempFile)
                    SpotiFlacOutputFormat.MP3_320 -> repository.validateStagedMp3(downloaded.tempFile)
                }
                repository.validateSpotiFlacIdentity(
                    file = downloaded.tempFile,
                    expected = track,
                    validatedDurationMs = validation.durationMs,
                )
                _state.update {
                    it.copy(
                        spotiFlacTransfer = (it.spotiFlacTransfer ?: SpotiFlacTransferProgress(SpotiFlacStage.IMPORTING))
                            .copy(stage = SpotiFlacStage.IMPORTING, fraction = 1f, provider = downloaded.provider),
                        message = "Validated ${downloaded.outputFormat.label}. Importing into ${it.downloadFolderLabel}…",
                    )
                }
                val saved = when (downloaded.outputFormat) {
                    SpotiFlacOutputFormat.FLAC_LOSSLESS, SpotiFlacOutputFormat.FLAC_HI_RES_96 -> repository.publishStagedFlac(downloaded.tempFile, downloaded.suggestedFileName)
                    SpotiFlacOutputFormat.MP3_320 -> repository.publishStagedMp3(downloaded.tempFile, downloaded.suggestedFileName)
                }
                recordDownload(
                    uri = saved.uri.toString(),
                    title = track.title.ifBlank { track.sourceTitle },
                    artist = track.artist,
                    source = DownloadSource.SPOTIFLAC,
                    provider = downloaded.provider,
                    format = downloaded.outputFormat.historyLabel,
                    bitDepth = if (downloaded.outputFormat.isLosslessOutput) downloaded.bitDepth ?: validation.bitDepth else null,
                    sampleRateHz = downloaded.sampleRateHz ?: validation.sampleRateHz,
                    fileSizeBytes = saved.sizeBytes,
                    originalTrackId = downloaded.originalTrackId,
                    isrc = downloaded.isrc,
                )
                _state.update {
                    it.copy(
                        isSpotiFlacDownloading = false,
                        isSpotiFlacQualityChecking = true,
                        spotiFlacTransfer = it.spotiFlacTransfer?.copy(
                            stage = SpotiFlacStage.FINALIZING,
                            fraction = 1f,
                            detail = "Saved · starting ${downloaded.outputFormat.label} quality check",
                        ),
                        message = "SpotiFLAC file saved. Running Harmony's audio quality check…",
                    )
                }
                inspectDownloadedFile(
                    uriString = saved.uri.toString(),
                    sizeBytes = saved.sizeBytes,
                    fileNameOverride = saved.displayName,
                    source = DownloadSource.SPOTIFLAC,
                )
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        isSpotiFlacDownloading = false,
                        isSpotiFlacQualityChecking = false,
                        spotiFlacTransfer = null,
                        message = "SpotiFLAC download cancelled.",
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isSpotiFlacDownloading = false, isSpotiFlacQualityChecking = false, spotiFlacTransfer = null) }
                fail(t, failedSource = DownloadSource.SPOTIFLAC)
            } finally {
                runCatching { stagingDir?.deleteRecursively() }
            }
        }
    }

    fun cancelSpotiFlacDownload() {
        spotiFlacEngine.cancelCurrentDownload()
        spotiFlacDownloadJob?.cancel()
        _state.update {
            it.copy(
                isSpotiFlacDownloading = false,
                isSpotiFlacQualityChecking = false,
                spotiFlacTransfer = null,
                message = "SpotiFLAC download cancelled.",
            )
        }
    }

    fun cancelSoulseekDownload() {
        soulseekClient.cancelActiveDownload()
        soulseekDownloadJob?.cancel()
        _state.update {
            it.copy(
                soulseekDownloadingId = null,
                soulseekSourceAttempt = 0,
                soulseekSourceTotal = 0,
                soulseekTransfer = null,
                message = "Soulseek download cancelled.",
            )
        }
    }

    private fun searchSpotiFlac() {
        val current = _state.value
        if (current.isIdentifying) return
        _state.update {
            it.copy(
                isIdentifying = true,
                identifiedTrack = null,
                spotiFlacSearchResults = emptyList(),
                selectedSpotiFlacMetadataId = null,
                spotiFlacTransfer = null,
                isSpotiFlacQualityChecking = false,
                report = null,
                error = null,
                errorDetails = null,
                showErrorDetails = false,
                canRetryYouTube = false,
                failedDownloadSource = null,
                message = "Searching real track metadata…",
            )
        }
        viewModelScope.launch {
            try {
                val results = repository.searchSpotiFlacTracks(current.query)
                _state.update {
                    it.copy(
                        isIdentifying = false,
                        spotiFlacSearchResults = results,
                        identifiedTrack = null,
                        message = if (results.isEmpty()) {
                            "No close match on Tidal (SpotiFLAC), Deezer or Apple Music. Check the spelling, or try \"artist - song\" or just the song title."
                        } else {
                            "Found ${results.size} verified metadata match${if (results.size == 1) "" else "es"}. Select the correct track before downloading."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isIdentifying = false) }
                throw cancelled
            } catch (t: Throwable) {
                _state.update { it.copy(isIdentifying = false, spotiFlacSearchResults = emptyList()) }
                fail(t, identifying = true)
            }
        }
    }

    fun selectSpotiFlacSearchResult(candidate: SpotiFlacSearchCandidate) {
        if (_state.value.isIdentifying) return
        _state.update {
            it.copy(
                isIdentifying = true,
                selectedSpotiFlacMetadataId = candidate.metadataId,
                identifiedTrack = null,
                error = null,
                errorDetails = null,
                message = "Verifying ${candidate.displayName}…",
            )
        }
        viewModelScope.launch {
            try {
                val track = repository.resolveSpotiFlacTrack(candidate)
                _state.update {
                    it.copy(
                        isIdentifying = false,
                        identifiedTrack = track,
                        selectedSpotiFlacMetadataId = candidate.metadataId,
                        message = buildString {
                            append("Track verified")
                            track.isrc?.let { code -> append(" · ISRC $code") }
                            append(". Press Download below to start the lossless-provider download.")
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isIdentifying = false) }
                throw cancelled
            } catch (t: Throwable) {
                _state.update { it.copy(isIdentifying = false, identifiedTrack = null) }
                fail(t, identifying = true)
            }
        }
    }

    fun searchSelectedSource() {
        val current = _state.value
        val searchText = current.query.trim()
        if (searchText.isBlank()) {
            _state.update {
                it.copy(
                    error = when (current.preferredDownloadSource) {
                        DownloadSource.SPOTIFLAC ->
                            "Enter an artist and song first, for example: The Weeknd - Blinding Lights."
                        DownloadSource.YTCONVERTER ->
                            "Paste a YouTube link first."
                        DownloadSource.SOULSEEK ->
                            "Enter an artist, album or song first."
                    },
                )
            }
            return
        }

        when (current.preferredDownloadSource) {
            DownloadSource.SPOTIFLAC -> searchSpotiFlac()
            DownloadSource.SOULSEEK -> {
                _state.update {
                    it.copy(
                        soulseekQuery = searchText,
                        identifiedTrack = null,
                        error = null,
                        errorDetails = null,
                    )
                }
                searchSoulseek()
            }
            // "Searching" for the converter means resolving the pasted link
            // to a title/artist via oEmbed — there is no result list to
            // choose from, so identify() is the whole step.
            DownloadSource.YTCONVERTER -> {
                if (!repository.isYouTubeUrl(searchText)) {
                    _state.update {
                        it.copy(error = "That doesn't look like a YouTube link. Paste the full URL.")
                    }
                    return
                }
                identify()
            }
        }
    }

    fun setQuery(value: String) {
        _state.update {
            it.copy(
                query = value,
                soulseekQuery = if (it.preferredDownloadSource == DownloadSource.SOULSEEK) value else it.soulseekQuery,
                error = null,
                errorDetails = null,
                showErrorDetails = false,
                canRetryYouTube = false,
                failedDownloadSource = null,
                message = null,
                spotiFlacSearchResults = if (value != it.query) emptyList() else it.spotiFlacSearchResults,
                selectedSpotiFlacMetadataId = if (value != it.query) null else it.selectedSpotiFlacMetadataId,
                identifiedTrack = if (value != it.query) null else it.identifiedTrack,
                report = if (value != it.query) null else it.report,
            )
        }
    }

    fun identify() {
        if (_state.value.isIdentifying || _state.value.query.isBlank()) return
        _state.update {
            it.copy(
                isIdentifying = true,
                identifiedTrack = null,
                report = null,
                error = null,
                errorDetails = null,
                showErrorDetails = false,
                canRetryYouTube = false,
                message = null,
            )
        }
        viewModelScope.launch {
            runCatching { repository.identify(_state.value.query) }
                .onSuccess { track ->
                    _state.update {
                        it.copy(
                            isIdentifying = false,
                            identifiedTrack = track,
                            message = when (it.preferredDownloadSource) {
                                DownloadSource.SPOTIFLAC ->
                                    "Track ready. Tap Download with SpotiFLAC, confirm the source, and Harmony will resolve it through the enabled lossless providers."
                                DownloadSource.SOULSEEK ->
                                    "Track identified. Continue with Soulseek to search the peer network."
                                DownloadSource.YTCONVERTER ->
                                    "Link identified. Tap Convert and save to run yt-dlp and FFmpeg on your phone."
                            },
                        )
                    }
                }
                .onFailure { fail(it, identifying = true) }
        }
    }

    fun downloadYouTubeAsFlac() {
        val state = _state.value
        if (state.isConverting || state.query.isBlank()) return
        if (!repository.isYouTubeUrl(state.query)) {
            _state.update { it.copy(error = "The free converter needs a YouTube URL in the main search box.") }
            return
        }

        _state.update {
            it.copy(
                isConverting = true,
                conversionProgress = 0f,
                conversionStatus = "Starting yt-dlp + FFmpeg…",
                download = null,
                report = null,
                error = null,
                errorDetails = null,
                showErrorDetails = false,
                canRetryYouTube = false,
                message = null,
            )
        }

        viewModelScope.launch {
            try {
                val preferredName = _state.value.identifiedTrack?.displayName ?: "Harmony download"
                val result = repository.downloadYouTubeAsFlac(
                    youtubeUrl = _state.value.query,
                    preferredBaseName = preferredName,
                ) { progress, line ->
                    _state.update {
                        it.copy(
                            conversionProgress = progress,
                            conversionStatus = line.take(120),
                        )
                    }
                }

                _state.update {
                    it.copy(
                        isConverting = false,
                        conversionProgress = 100f,
                        conversionStatus = "Saved ${result.displayName}",
                        message = "Saved to ${_state.value.downloadFolderLabel}. Checking the spectrum now…",
                    )
                }
                inspectDownloadedFile(
                    uriString = result.uri.toString(),
                    sizeBytes = result.sizeBytes,
                    fileNameOverride = result.displayName,
                    convertedFromYouTube = true,
                    source = DownloadSource.YTCONVERTER,
                )
            } catch (t: Throwable) {
                val known = t as? HarmonyDownloadException
                _state.update {
                    it.copy(
                        isConverting = false,
                        conversionStatus = "Download failed",
                        error = known?.userMessage ?: "The free FLAC conversion failed.",
                        errorDetails = known?.technicalDetails
                            ?: t.stackTraceToString().take(8_000),
                        showErrorDetails = false,
                        canRetryYouTube = true,
                        message = null,
                    )
                }
            }
        }
    }

    fun setDirectFlacUrl(value: String) {
        _state.update {
            it.copy(
                directFlacUrl = value,
                error = null,
                errorDetails = null,
                showErrorDetails = false,
                canRetryYouTube = false,
                message = null,
            )
        }
    }

    fun downloadFlac() {
        val state = _state.value
        if (state.isValidating || state.directFlacUrl.isBlank()) return
        _state.update {
            it.copy(
                isValidating = true,
                download = null,
                report = null,
                error = null,
                errorDetails = null,
                showErrorDetails = false,
                canRetryYouTube = false,
                message = "Checking that the link really returns FLAC audio…",
            )
        }

        viewModelScope.launch {
            try {
                val validated = repository.validateFlacUrl(_state.value.directFlacUrl)
                val baseName = validated.suggestedFileName
                    ?.removeSuffix(".flac")
                    ?.removeSuffix(".FLAC")
                    ?.takeIf { it.isNotBlank() }
                    ?: _state.value.identifiedTrack?.displayName
                    ?: "Harmony download"

                _state.update {
                    it.copy(
                        isValidating = false,
                        isConverting = true,
                        conversionProgress = 0f,
                        conversionStatus = "Downloading FLAC…",
                        message = "Downloading direct FLAC to ${it.downloadFolderLabel}…",
                    )
                }

                val result = repository.downloadDirectFlac(
                    url = validated.finalUrl,
                    preferredBaseName = baseName,
                ) { progress, line ->
                    _state.update { current ->
                        current.copy(
                            conversionProgress = progress,
                            conversionStatus = line.take(120),
                        )
                    }
                }

                _state.update {
                    it.copy(
                        isConverting = false,
                        conversionProgress = 100f,
                        conversionStatus = "Saved ${result.displayName}",
                        message = "Saved to ${it.downloadFolderLabel}. Checking the spectrum now…",
                    )
                }
                inspectDownloadedFile(
                    uriString = result.uri.toString(),
                    sizeBytes = result.sizeBytes,
                    fileNameOverride = result.displayName,
                )
            } catch (t: Throwable) {
                fail(t, validating = true)
            }
        }
    }

    fun toggleErrorDetails() {
        _state.update { it.copy(showErrorDetails = !it.showErrorDetails) }
    }

    fun retryYouTubeDownload() {
        _state.update {
            it.copy(
                error = null,
                errorDetails = null,
                showErrorDetails = false,
                canRetryYouTube = false,
            )
        }
        downloadYouTubeAsFlac()
    }

    private suspend fun monitorDownload(id: Long) {
        while (true) {
            val progress = repository.queryDownload(id)
            if (progress == null) {
                _state.update { it.copy(error = "Android's download manager lost this download.") }
                return
            }
            _state.update { it.copy(download = progress) }

            when (progress.status) {
                HarmonyDownloadStatus.SUCCESSFUL -> {
                    val uri = progress.localUri
                    if (uri == null) {
                        _state.update {
                            it.copy(message = "Download complete. Harmony will pick it up from MediaStore.")
                        }
                        return
                    }
                    inspectDownloadedFile(uri.toString(), progress.totalBytes)
                    return
                }

                HarmonyDownloadStatus.FAILED -> {
                    _state.update {
                        it.copy(
                            error = "Download failed (Android reason ${progress.reason}). The link may have expired or require a browser login.",
                            message = null,
                        )
                    }
                    return
                }

                else -> delay(700)
            }
        }
    }

    private suspend fun inspectDownloadedFile(
        uriString: String,
        sizeBytes: Long,
        fileNameOverride: String? = null,
        convertedFromYouTube: Boolean = false,
        source: DownloadSource? = null,
    ) {
        _state.update {
            it.copy(message = "Running Harmony's spectral check…")
        }
        try {
            val uri = android.net.Uri.parse(uriString)
            val fileName = fileNameOverride
                ?: repository.displayNameForDownloadedUri(uri)
                ?: _state.value.identifiedTrack?.displayName?.plus(".flac")
                ?: "download.flac"
            val report = withContext(Dispatchers.Default) {
                spectralInspector.inspect(
                    uriString = uriString,
                    fileName = fileName,
                    fileSizeBytes = sizeBytes.coerceAtLeast(0L),
                    durationMs = 0L,
                )
            }

            // The converter's history entry is written HERE rather than at
            // its own call site, because this is the first point where the
            // file's real format, bit depth and sample rate are known —
            // yt-dlp/FFmpeg report none of that back, so recording earlier
            // would mean writing a row with the metadata line blank or,
            // worse, guessed. SpotiFLAC and Soulseek record at their own
            // call sites because their engines hand back those values
            // directly.
            if (source == DownloadSource.YTCONVERTER) {
                val identified = _state.value.identifiedTrack
                recordDownload(
                    uri = uriString,
                    // Prefer the oEmbed title when the link was identified;
                    // fall back to the saved file's name, minus extension.
                    title = identified?.title
                        ?.takeIf { it.isNotBlank() }
                        ?: fileName.substringBeforeLast('.'),
                    artist = identified?.artist.orEmpty(),
                    source = DownloadSource.YTCONVERTER,
                    format = fileName.substringAfterLast('.', "").uppercase()
                        .takeIf { it.isNotBlank() },
                    bitDepth = report.bitDepth.takeIf { it > 0 },
                    sampleRateHz = report.sampleRate.takeIf { it > 0 },
                    fileSizeBytes = sizeBytes.coerceAtLeast(0L),
                )
            }
            _state.update {
                it.copy(
                    report = report,
                    isSpotiFlacQualityChecking = if (source == DownloadSource.SPOTIFLAC) false else it.isSpotiFlacQualityChecking,
                    spotiFlacTransfer = if (source == DownloadSource.SPOTIFLAC) {
                        it.spotiFlacTransfer?.copy(
                            stage = SpotiFlacStage.COMPLETED,
                            fraction = 1f,
                            detail = "Complete · ${fileName.substringAfterLast('.', "audio").uppercase()} saved and checked",
                        )
                    } else {
                        it.spotiFlacTransfer
                    },
                    soulseekTransfer = if (source == DownloadSource.SOULSEEK) {
                        it.soulseekTransfer?.let { transfer ->
                            transfer.copy(
                                status = "Complete · ${
                                    fileName.substringAfterLast('.', "audio").uppercase()
                                } saved and checked",
                                downloadedBytes = transfer.totalBytes,
                            )
                        }
                    } else {
                        it.soulseekTransfer
                    },
                    message = when {
                        convertedFromYouTube -> "FLAC created successfully. Remember: the file is losslessly encoded, but YouTube's original stream is still a lossy source."
                        source == DownloadSource.SOULSEEK && !report.claimsLossless ->
                            // For MP3 the verdict is always LOSSY_AS_LABELLED,
                            // which is correct but says nothing about quality.
                            // The cutoff is the number that separates a real
                            // 320 from a 128 re-encoded up to 320, so surface
                            // it rather than leaving it buried in the report.
                            "Soulseek file saved to ${it.downloadFolderLabel}. Lossy as labelled, " +
                                "content up to ${report.cutoffHz / 1000} kHz — a genuine 320 kbps " +
                                "MP3 reaches about 20 kHz."
                        source == DownloadSource.SOULSEEK -> "Soulseek file validated and saved to ${it.downloadFolderLabel}. It is ready for the library scanner."
                        source == DownloadSource.SPOTIFLAC && !report.claimsLossless ->
                            "SpotiFLAC MP3 saved to ${it.downloadFolderLabel}. Lossy as selected; Harmony also checked the spectral content."
                        source == DownloadSource.SPOTIFLAC -> "SpotiFLAC FLAC validated and saved to ${it.downloadFolderLabel}. It is ready for the library scanner."
                        else -> "Saved to ${it.downloadFolderLabel} and ready for the library scanner."
                    },
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.update {
                it.copy(
                    isSpotiFlacQualityChecking = if (source == DownloadSource.SPOTIFLAC) false else it.isSpotiFlacQualityChecking,
                    spotiFlacTransfer = if (source == DownloadSource.SPOTIFLAC) {
                        it.spotiFlacTransfer?.copy(
                            stage = SpotiFlacStage.COMPLETED,
                            fraction = 1f,
                            detail = "Complete · saved (quality check unavailable)",
                        )
                    } else {
                        it.spotiFlacTransfer
                    },
                    soulseekTransfer = if (source == DownloadSource.SOULSEEK) {
                        it.soulseekTransfer?.let { transfer ->
                            transfer.copy(
                                status = "Complete · saved (spectrum check unavailable)",
                                downloadedBytes = transfer.totalBytes,
                            )
                        }
                    } else {
                        it.soulseekTransfer
                    },
                    message = "The file is saved; the automatic spectrum check couldn't run.",
                    error = t.message,
                )
            }
        }
    }

    private suspend fun recordDownload(
        uri: String,
        title: String,
        artist: String,
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
                title = title.ifBlank { "Unknown track" },
                artist = artist,
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
        scanNewDownloadIntoLibrary()
    }

    /**
     * Pull a freshly downloaded file into the library without waiting for a
     * manual rescan.
     *
     * Called from recordDownload because that is the one point both engines
     * pass through on success — hooking the Soulseek and SpotiFLAC paths
     * separately would mean two places to keep in step.
     *
     * force = false on purpose. The incremental scan compares file stats, so it
     * picks up the new file and leaves every unchanged song alone; forcing a
     * full re-extract after each track would re-read the entire library for one
     * addition. Combined with the dateAdded fix in SongDao.upsertBatch, this is
     * what makes "Recently Added" actually mean recently added.
     *
     * Launched on viewModelScope rather than awaited: the download is already
     * complete and its record is written, so a scan failure must not surface as
     * a download failure. Errors are swallowed for that reason — the next scan,
     * manual or from the following download, will pick the file up anyway.
     */
    private fun scanNewDownloadIntoLibrary() {
        viewModelScope.launch {
            runCatching { scanLibrary(force = false).collect { } }
        }
    }

    private fun fail(
        throwable: Throwable,
        identifying: Boolean = false,
        validating: Boolean = false,
        failedSource: DownloadSource? = null,
    ) {
        val spotiFlac = throwable as? SpotiFlacException
        val verificationRequired = spotiFlac?.errorType.equals("verification_required", ignoreCase = true)
        _state.update {
            it.copy(
                isIdentifying = if (identifying) false else it.isIdentifying,
                isValidating = if (validating) false else it.isValidating,
                isConverting = false,
                error = throwable.message ?: "Something went wrong.",
                errorDetails = (spotiFlac?.technicalDetails
                    ?: throwable.stackTraceToString()).take(8_000),
                showErrorDetails = false,
                canRetryYouTube = false,
                failedDownloadSource = failedSource ?: it.failedDownloadSource,
                spotiFlacVerificationProvider = if (verificationRequired) spotiFlac?.provider else null,
                spotiFlacVerificationUrl = if (verificationRequired) {
                    spotiFlac?.verificationChallenge?.verificationUrl ?: spotiFlac?.verificationUrl
                } else null,
                spotiFlacVerificationChallenge = if (verificationRequired) spotiFlac?.verificationChallenge else null,
                spotiFlacPreflightReady = if (verificationRequired &&
                    spotiFlac?.provider.equals(SPOTIFLAC_PREFLIGHT_PROVIDER, ignoreCase = true)
                ) false else it.spotiFlacPreflightReady,
                isCheckingSpotiFlacVerification = false,
                message = null,
            )
        }
    }

    override fun onCleared() {
        // viewModelScope is cancelled here, which kills any transfer in
        // flight. Leaving an "active download" banner up afterwards would be
        // advertising a download that no longer exists.
        statusCenter.clear()
        super.onCleared()
    }

    companion object {
        private const val KEY_SPOTIFLAC_OUTPUT_FORMAT = "spotiflac_output_format"
        /** Primary provider in the existing isolated SpotiFLAC route. */
        private const val SPOTIFLAC_PREFLIGHT_PROVIDER = "tidal-web"
        private const val MAX_FAST_FALLBACK_SOURCES = 10
        private const val UNKNOWN_QUEUE_PLACE_RANK = Int.MAX_VALUE / 2
        private const val MAX_PARALLEL_PRECONNECT_SOURCES = 3
        /** Grace period before a gap in activity counts as a finished download. */
        private const val COMPLETION_SETTLE_MS = 900L
    }
}

/**
 * Reduces the Downloads screen's full state to the one line a banner shows,
 * or null when nothing is running.
 *
 * Order matters: SpotiFLAC and Soulseek are checked before the plain file
 * download because a SpotiFLAC transfer also populates `download` during its
 * fetch stage, and the richer stage label is the more useful of the two.
 */
internal fun DownloadsViewModel.State.toActiveDownload(): ActiveDownload? {
    // displayName already falls back through artist/title/sourceTitle.
    val trackTitle = identifiedTrack?.displayName

    spotiFlacTransfer?.let { transfer ->
        if (!isSpotiFlacDownloading && transfer.stage == SpotiFlacStage.COMPLETED) return@let
        return ActiveDownload(
            title = trackTitle ?: "Track",
            source = "SpotiFLAC",
            detail = transfer.detail?.takeIf { it.isNotBlank() } ?: transfer.stage.label,
            fraction = transfer.fraction,
        )
    }

    // Soulseek deliberately KEEPS its transfer object after the download ends,
    // rewriting the status to "Complete · … saved and checked" so the Downloads
    // screen can still show the result. So a non-null transfer does not mean a
    // running download — reading it that way pinned the banner at 100% forever.
    // `soulseekDownloadingId` is the field the ViewModel actually clears when
    // the transfer finishes, so that is the signal to gate on.
    if (soulseekDownloadingId != null) {
        val transfer = soulseekTransfer
        if (transfer != null) {
            val queueDetail = transfer.queuePlace?.let { "Queue position $it" }
            return ActiveDownload(
                // The Soulseek filename is the honest label here; it is what
                // the peer is actually sending, which may differ from the
                // search text.
                title = transfer.filename.substringAfterLast('\\').substringAfterLast('/')
                    .ifBlank { trackTitle ?: "Track" },
                source = "Soulseek",
                detail = queueDetail ?: transfer.status.takeIf { it.isNotBlank() },
                fraction = transfer.fraction,
            )
        }
        // A peer has been chosen but no bytes have been reported yet. The
        // chosen candidate still names the file, which beats a generic label.
        val pending = soulseekResults.firstOrNull { it.id == soulseekDownloadingId }
        return ActiveDownload(
            title = pending?.filename?.substringAfterLast('\\')?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: trackTitle
                ?: "Track",
            source = "Soulseek",
            detail = "Connecting to peer…",
        )
    }

    download?.let { progress ->
        if (progress.status != HarmonyDownloadStatus.RUNNING &&
            progress.status != HarmonyDownloadStatus.PENDING
        ) {
            return null
        }
        return ActiveDownload(
            title = trackTitle ?: "Track",
            source = "Download",
            detail = if (progress.status == HarmonyDownloadStatus.PENDING) "Starting…" else null,
            fraction = progress.fraction,
        )
    }

    if (isConverting) {
        return ActiveDownload(
            title = trackTitle ?: "Track",
            source = "Download",
            detail = conversionStatus.takeIf { it.isNotBlank() } ?: "Converting…",
            fraction = conversionProgress.takeIf { it > 0f },
        )
    }
    return null
}
