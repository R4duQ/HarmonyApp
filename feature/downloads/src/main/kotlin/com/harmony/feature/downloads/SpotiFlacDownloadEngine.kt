package com.harmony.feature.downloads

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Harmony adapter for the native Go backend used by SpotiFLAC Mobile.
 *
 * v7.7 host goals:
 *  - keep the v7.0 signed-session completion and Android deep-link callback flow;
 *  - preserve successful provider results instead of masking host-side failures with later fallback errors;
 *  - complete Amazon's provider-supplied MOV descriptor with bundled FFmpeg before format probing;
 *  - keep other providers' descriptor handling unchanged and never expose keys in diagnostics;
 *  - reproduce the unencrypted lossless container finalization policy;
 *  - initialize the bundled FFmpeg independently of yt-dlp, and use a two-stage
 *    FLAC-in-MP4 finalizer (bit-exact remux first, official-style FLAC encode fallback);
 *  - accept native FLAC immediately, and convert M4A/MP4 to native FLAC only when
 *    the provider/backend codec probe proves the source audio is lossless FLAC/ALAC;
 *  - validate final fLaC bytes before import and never upscale AAC/Opus/other lossy
 *    codecs merely to make a file look lossless;
 *  - execute exactly one lossless provider per native request so lower-priority verification cannot interrupt Tidal;
 *  - route around provider-specific HTTP 429/not-found responses locally without unloading extensions;
 *  - honor backend retry_after_seconds for gateway-wide limits and perform at most one paced automatic retry;
 *  - prefer Tidal first and persist only the last successful provider ID across Android process recreation;
 *  - surface redacted codec/container/finalization diagnostics;
 *  - never bypass provider verification or silently fall back to Soulseek.
 */
@Singleton
class SpotiFlacDownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val initMutex = Mutex()

    /** SpotiFLAC's Go runtime or Mornye's Rust one, depending on the build. */
    private val backend: SpotiFlacBackend = createSpotiFlacBackend()

    /** The most downloads this runtime can run at once. */
    private val runtimeParallel: Int = backend.parallelDownloads.coerceAtLeast(1)

    /** Whether this build's runtime could download two tracks at once (the experiment below). */
    val parallelDownloadsSupported: Boolean get() = runtimeParallel > 1

    /**
     * How many tracks an album downloads at once: one, unless the two-at-once
     * experiment was switched on ([setParallelDownloadsEnabled]) on a runtime
     * that supports it. It stays an experiment until it is shown on a phone to
     * shorten an album without costing playback or failing more often.
     */
    val parallelDownloads: Int
        get() = if (providerPrefs.getBoolean(KEY_PARALLEL_DOWNLOADS, false)) runtimeParallel else 1

    fun setParallelDownloadsEnabled(enabled: Boolean) {
        providerPrefs.edit().putBoolean(KEY_PARALLEL_DOWNLOADS, enabled && parallelDownloadsSupported).apply()
    }

    // A download plan holds one permit; sign-in and verification steps, which
    // change what the providers will answer, wait for the plans in flight and
    // hold them all (one such step at a time).
    private val planPermits = Semaphore(runtimeParallel)
    private val providerStateMutex = Mutex()

    private suspend fun <T> withProviderStateExclusive(block: suspend () -> T): T = providerStateMutex.withLock {
        var held = 0
        try {
            repeat(runtimeParallel) { planPermits.acquire(); held++ }
            block()
        } finally {
            repeat(held) { planPermits.release() }
        }
    }

    /** "Mornye": the engine behind album and track downloads. */
    val engineName: String get() = backend.displayName

    // MainActivity can receive the deep link before a workflow ViewModel is
    // recreated after browser verification. Replay preserves that completion,
    // while SharedFlow lets Downloads and Playlist Transfer observe the result;
    // request ownership below ensures only the initiator resumes a track.
    private val verificationEventChannel = MutableSharedFlow<SpotiFlacVerificationCallbackResult>(
        replay = 1,
        extraBufferCapacity = 3,
    )
    val verificationEvents: Flow<SpotiFlacVerificationCallbackResult> =
        verificationEventChannel.asSharedFlow()

    // A provider callback must outlive the Activity that receives it. Browsers
    // are free to recreate or finish the host Activity while returning through
    // a custom scheme; tying grant exchange to lifecycleScope can therefore
    // cancel a valid Tidal grant halfway through completion.
    private val verificationCallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Background lookups the caller stops waiting for at a deadline; see [awaitWithin]. */
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // downloadByStrategy can block in native/extension HTTP work. Keeping it on a
    // small dedicated executor makes the JNI boundary deterministic while a
    // separate control executor remains able to deliver cancellation/progress.
    private val nativeDownloadExecutor = Executors.newFixedThreadPool(backend.parallelDownloads.coerceAtLeast(1) + 2) { runnable ->
        Thread(runnable, "Harmony-SpotiFLAC-download").apply { isDaemon = true }
    }
    private val nativeControlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Harmony-SpotiFLAC-control").apply { isDaemon = true }
    }

    private val providerPrefs = context.getSharedPreferences(PROVIDER_STATE_PREFS, Context.MODE_PRIVATE)

    /** The last package accepted per provider, per runtime (the two builds accept different versions). */
    private val acceptedPackages = object : AcceptedPackages {
        private fun key(providerId: String) = "$KEY_ACCEPTED_PACKAGE_PREFIX${backend.nativeLibrary}_$providerId"

        override fun get(providerId: String): ProviderPackage? =
            providerPrefs.getString(key(providerId), null)?.let { raw ->
                runCatching {
                    val node = JSONObject(raw)
                    ProviderPackage(
                        id = providerId,
                        displayName = providerDisplay(providerId),
                        version = node.getString("version"),
                        url = node.getString("url"),
                        sha256 = node.getString("sha256"),
                    )
                }.getOrNull()
            }

        override fun put(accepted: ProviderPackage) {
            val node = JSONObject().put("version", accepted.version).put("url", accepted.url).put("sha256", accepted.sha256)
            providerPrefs.edit().putString(key(accepted.id), node.toString()).apply()
        }
    }

    @Volatile private var initialized = false
    /** One download in flight: its runtime id, its own staging folder, its clocks. */
    private class ActiveTransfer(
        val itemId: String,
        val lease: StagingLeases.Lease,
        val owner: SpotiFlacRequestOwner,
        val activity: TransferActivity,
        val timeline: DownloadTimeline,
    ) {
        val jobDir: File get() = lease.folder

        /** True while a provider call is running (the only time the runtime reports progress). */
        @Volatile var transferring = false

        /** Cancel was tapped: nothing of this job is kept. */
        @Volatile var cancelledByUser = false
    }

    private val activeTransfers = ConcurrentHashMap<String, ActiveTransfer>()

    private val providerSlots = ConcurrentHashMap<String, Semaphore>()

    private fun providerSlot(providerId: String): Semaphore =
        providerSlots.getOrPut(providerId) { Semaphore(SpotiFlacParallelPolicy.perProvider(providerId)) }

    /** Who holds which staging folder; a late native result can only land in its own job's. */
    private val stagingLeases = StagingLeases(File(context.cacheDir, "spotiflac/staging"))
    private val watchedCall = WatchedCall(WATCHDOG_TICK_MS, CANCEL_DELIVERY_TIMEOUT_MS)

    /** song.link answers by track URL, and a pause when it says it is asked too often. */
    private val songLinkMemory =
        LookupMemory<ProviderIdentifiers>(SONG_LINK_MEMORY, SONG_LINK_TTL_MS, SystemClock::elapsedRealtime)

    /** Requests Harmony itself makes (registry, packages, song.link, covers), for the timelines. */
    private val hostRequests = AtomicInteger()

    /** A newer provider set is in the store; the engine moves to it between downloads. */
    @Volatile private var pendingProviderUpdate = false
    private val providerRefreshRunning = AtomicBoolean(false)

    /** The timeline of each file handed out, until whoever imports it reports back. */
    private val handedOut = ConcurrentHashMap<String, Pair<String, DownloadTimeline>>()
    @Volatile private var ffmpegPackagesDir: File? = null
    @Volatile private var activeRegistrySummary: String = "provider registry not initialized"
    /** The providers whose packages were installed and loaded this run, in priority order. */
    @Volatile private var availableProviders: List<String> = PROVIDER_PRIORITY
    @Volatile private var providerQualityOptions: Map<String, List<SpotiFlacQualityOption>> = emptyMap()
    // Persist only the provider ID (never auth/session material) so an Android
    // process recreation during browser verification does not forget that Tidal
    // was the last healthy provider and suddenly lead with Deezer on the retry.
    @Volatile private var lastSuccessfulProviderId: String? =
        providerPrefs.getString(KEY_LAST_SUCCESSFUL_PROVIDER, null)
    @Volatile private var gatewayCooldownUntilElapsedMs: Long = 0L

    suspend fun download(
        track: IdentifiedTrack,
        outputFormat: SpotiFlacOutputFormat = SpotiFlacOutputFormat.FLAC_LOSSLESS,
        requestOwner: SpotiFlacRequestOwner = SpotiFlacRequestOwner.DOWNLOADS,
        onProgress: (SpotiFlacTransferProgress) -> Unit,
    ): SpotiFlacDownloadedFile {
        val albumWork = requestOwner in ALBUM_OWNERS
        if (requestOwner == SpotiFlacRequestOwner.ONLINE_MIX) {
            // In the background: only when the engine is free, and a download the user starts takes it over.
            return DownloadEngineGate.runInBackground {
                adoptProviderUpdate()
                downloadExclusive(track, outputFormat, requestOwner, onProgress)
            }
        }
        return DownloadEngineGate.run(albumWork, parallel = if (albumWork) parallelDownloads else 1) {
            adoptProviderUpdate()
            downloadExclusive(track, outputFormat, requestOwner, onProgress)
        }
    }

    /**
     * Moves to a newer provider set found in the background, but only between
     * downloads: with nothing in flight and no sign-in step running, the
     * runtime is started again on the new packages.
     */
    private suspend fun adoptProviderUpdate() {
        if (!pendingProviderUpdate || !backend.reinitializable || activeTransfers.isNotEmpty()) return
        withProviderStateExclusive {
            if (pendingProviderUpdate && activeTransfers.isEmpty()) {
                initMutex.withLock {
                    pendingProviderUpdate = false
                    initialized = false
                }
            }
        }
    }

    /**
     * Whoever imports a downloaded file says how long that took, which closes
     * the download's timeline (the last phase the engine cannot see).
     */
    fun reportImported(file: SpotiFlacDownloadedFile, importMs: Long) {
        val (jobId, timeline) = handedOut.remove(file.tempFile.path) ?: return
        timeline.add(DownloadTimeline.Phase.IMPORT, importMs)
        recordTimeline(timeline.summary(jobId, "imported"))
    }

    /** The last download timelines, newest last: ids, counts and timings only. */
    fun recentTimelines(): List<String> = synchronized(recentTimelineLines) { recentTimelineLines.toList() }

    private val recentTimelineLines = ArrayDeque<String>()

    private fun recordTimeline(line: String) {
        synchronized(recentTimelineLines) {
            recentTimelineLines.addLast(line)
            while (recentTimelineLines.size > TIMELINES_KEPT) recentTimelineLines.removeFirst()
        }
        runCatching { android.util.Log.i(TIMELINE_TAG, line) }
        // A small file next to the other logs, so a baseline taken on a phone can be shared.
        runCatching {
            val log = File(context.filesDir, TIMELINE_FILE)
            if (log.length() > TIMELINE_FILE_MAX_BYTES) log.writeText(log.readLines().takeLast(200).joinToString("\n", postfix = "\n"))
            log.appendText("${System.currentTimeMillis()} $line\n")
        }
    }

    private suspend fun downloadExclusive(
        track: IdentifiedTrack, outputFormat: SpotiFlacOutputFormat, requestOwner: SpotiFlacRequestOwner,
        onProgress: (SpotiFlacTransferProgress) -> Unit,
    ): SpotiFlacDownloadedFile = coroutineScope {
        require(track.title.isNotBlank() || track.sourceTitle.isNotBlank()) {
            "SpotiFLAC needs a track title before it can resolve a lossless source."
        }

        val timeline = DownloadTimeline(SystemClock::elapsedRealtime)
        timeline.coldStart = !initialized
        val requestsBefore = hostRequests.get()
        ensureNetworkAvailable()
        onProgress(SpotiFlacTransferProgress(SpotiFlacStage.PREPARING))
        timeline.measure(DownloadTimeline.Phase.INIT) { ensureReady(onProgress) }

        val itemId = "harmony-${UUID.randomUUID()}"
        // The folder is the track's, not the attempt's, so a later attempt can
        // continue a partial transfer (SpotiFlacStaging).
        val stagingKey = SpotiFlacStaging.key(
            listOf(
                track.isrc.orEmpty(), track.spotifyId.orEmpty(), track.metadataId.orEmpty(), track.artist,
                track.title.ifBlank { track.sourceTitle }, track.album, track.durationMs.toString(), outputFormat.name,
            ),
        )
        // A folder nobody else holds: the track's own when free (so a partial
        // transfer is continued), otherwise one only this job has (StagingLeases).
        val current = ActiveTransfer(
            itemId = itemId,
            lease = withContext(Dispatchers.IO) { stagingLeases.open(stagingKey, itemId) },
            owner = requestOwner,
            activity = TransferActivity(PROVIDER_DEADLINES, SystemClock::elapsedRealtime),
            timeline = timeline,
        ).also { activeTransfers[itemId] = it }
        val jobDir = current.jobDir
        var disposition = StagingLeases.Disposition.DELETE
        var outcome = "failed"

        val poller: Job = launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { if (current.transferring) readProgress(itemId, current) else null }
                    .getOrNull()
                    ?.let(onProgress)
                delay(PROGRESS_POLL_MS)
            }
        }

        try {
            onProgress(
                SpotiFlacTransferProgress(
                    stage = SpotiFlacStage.RESOLVING,
                    detail = "Resolving exact track identity for SpotiFLAC providers…",
                ),
            )
            nativeControl { backend.resetDownloadCancel(itemId) }

            // song.link only adds Tidal/Qobuz/Spotify ids; the track's own ids are enough to
            // start, so a slow song.link must not hold the download on "Resolving".
            val ownIds = basicProviderIdentifiers(track)
            val firstProvider = providerOrderForDownload().firstOrNull()
            val providerIds = timeline.measure(DownloadTimeline.Phase.RESOLVE) {
                if (firstProvider != null &&
                    SpotiFlacProviderMatch.hasExactId(firstProvider, ownIds.deezerId, ownIds.tidalId, ownIds.qobuzId)
                ) {
                    // The provider tried first is asked by its own exact id: nothing to wait
                    // for. The other providers' ids are looked up meanwhile and picked up
                    // by a later attempt if there is one.
                    prefetchProviderIdentifiers(listOf(track))
                    ownIds
                } else {
                    awaitWithin(SONG_LINK_DEADLINE_MS, lookupScope) { resolveProviderIdentifiers(track) } ?: ownIds
                }
            }
            onProgress(
                SpotiFlacTransferProgress(
                    stage = SpotiFlacStage.RESOLVING,
                    detail = providerIds.resolutionSummary,
                ),
            )

            val baseRequest = buildDownloadRequest(track, providerIds, jobDir, itemId)
            val response = planPermits.withPermit {
                executeProviderPlan(baseRequest, outputFormat, current, onProgress) {
                    songLinkIdentity(track)?.let(songLinkMemory::get)
                }
            }
            val finalizingSince = SystemClock.elapsedRealtime()
            // Native status may remain "preparing" after returning a file. It
            // must not overwrite the host's quality conversion/import stages.
            poller.cancel()

            onProgress(
                SpotiFlacTransferProgress(
                    stage = SpotiFlacStage.FINALIZING,
                    fraction = 1f,
                    provider = response.optString("service").takeIf(String::isNotBlank)
                        ?: response.optString("provider").takeIf(String::isNotBlank),
                ),
            )

            val file = resolveOutputFile(
                jobDir = jobDir,
                reportedPath = response.optString(HARMONY_VALIDATED_FILE_PATH).ifBlank {
                    response.optString("file_path").ifBlank { response.optString("file") }
                },
                reportedName = response.optString("file_name"),
            ) ?: throw SpotiFlacException(
                "SpotiFLAC reported success but Harmony could not find the downloaded audio file.",
                "missing_output",
                technicalDetails = response.toString(),
            )
            verifyFlacSignature(file)

            val provider = response.optString("service").takeIf(String::isNotBlank)
                ?: response.optString("provider").takeIf(String::isNotBlank)
            val qualityLimitedFile = if (outputFormat.isLosslessOutput) {
                enforceFlacQualityLimit(file, jobDir, outputFormat, provider, onProgress)
            } else {
                file
            }
            val resolvedAlbum = track.album.ifBlank {
                response.optString("album_name").ifBlank { response.optString("album") }
            }
            val resolvedArtworkUrl = track.thumbnailUrl?.takeIf(String::isNotBlank)
                ?: response.optString("cover_url").takeIf(String::isNotBlank)
                ?: response.optString("artwork_url").takeIf(String::isNotBlank)
                ?: response.optString("thumbnail_url").takeIf(String::isNotBlank)
            val taggedLosslessFile = enrichNativeFlacMetadata(
                source = qualityLimitedFile,
                track = track.copy(album = resolvedAlbum, thumbnailUrl = resolvedArtworkUrl),
                jobDir = jobDir,
                provider = provider,
                onProgress = onProgress,
            )
            verifyFlacSignature(taggedLosslessFile)

            val outputFile = when (outputFormat) {
                SpotiFlacOutputFormat.FLAC_LOSSLESS, SpotiFlacOutputFormat.FLAC_HI_RES_96 -> taggedLosslessFile
                SpotiFlacOutputFormat.MP3_320 -> {
                    onProgress(
                        SpotiFlacTransferProgress(
                            stage = SpotiFlacStage.FINALIZING,
                            fraction = null,
                            provider = provider,
                            detail = "Encoding MP3 320 kbps from the verified lossless source…",
                        ),
                    )
                    convertNativeFlacToMp3(taggedLosslessFile, jobDir)
                }
            }

            val title = response.optString("title").ifBlank { track.title.ifBlank { track.sourceTitle } }
            val artist = response.optString("artist").ifBlank { track.artist }
            val suggested = sanitizeFileName(
                listOf(artist, title).filter(String::isNotBlank).joinToString(" - ")
                    .ifBlank { outputFile.nameWithoutExtension },
            ) + ".${outputFormat.extension}"

            clearPendingVerificationTrack()
            val actualFlac = if (outputFormat.isLosslessOutput) SpotiFlacQualityPolicy.read(outputFile) else null
            timeline.add(DownloadTimeline.Phase.FINALIZE, SystemClock.elapsedRealtime() - finalizingSince)
            disposition = StagingLeases.Disposition.KEEP_OUTPUT
            outcome = "downloaded"
            if (handedOut.size > TIMELINES_KEPT) handedOut.clear()
            handedOut[outputFile.path] = itemId to timeline
            SpotiFlacDownloadedFile(
                tempFile = outputFile,
                suggestedFileName = suggested,
                provider = provider,
                outputFormat = outputFormat,
                bitrateKbps = if (outputFormat == SpotiFlacOutputFormat.MP3_320) 320 else null,
                bitDepth = actualFlac?.bitDepth,
                sampleRateHz = actualFlac?.sampleRateHz,
                codec = if (outputFormat == SpotiFlacOutputFormat.MP3_320) "mp3" else "flac",
                isrc = response.optString("isrc").takeIf(String::isNotBlank) ?: track.isrc,
                originalTrackId = response.optString("spotify_id").takeIf(String::isNotBlank)
                    ?: providerIds.spotifyId
                    ?: providerIds.deezerId,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { nativeControl { backend.cancelDownload(itemId) } }
            }
            // Pause, or the job was stopped: what was received stays for Resume.
            // Cancel by hand keeps nothing.
            disposition = if (current.cancelledByUser) StagingLeases.Disposition.DELETE else StagingLeases.Disposition.KEEP_RESUMABLE
            outcome = if (current.cancelledByUser) "cancelled" else "paused"
            throw cancelled
        } catch (t: Throwable) {
            // A dropped connection, a stall or a rate limit: the next attempt continues
            // from what was received. Anything else (not found, verification, a transfer
            // that no longer matches, the phone's own failure) starts clean.
            val kind = SpotiFlacFailures.kind((t as? SpotiFlacException)?.errorType, t.message)
            disposition = if (!current.cancelledByUser && kind.keepsPartial) {
                StagingLeases.Disposition.KEEP_RESUMABLE
            } else StagingLeases.Disposition.DELETE
            outcome = if (current.cancelledByUser) "cancelled" else kind.name.lowercase(Locale.ROOT)
            if (t is SpotiFlacException) {
                if (t.errorType.equals("verification_required", ignoreCase = true)) {
                    rememberPendingVerificationTrack(track, requestOwner)
                } else {
                    clearPendingVerificationTrack()
                }
                throw t
            }
            clearPendingVerificationTrack()
            throw SpotiFlacException(
                message = t.message ?: "SpotiFLAC could not complete the download.",
                cause = t,
                technicalDetails = "registry=$activeRegistrySummary\n${t.stackTraceToString()}",
            )
        } finally {
            poller.cancel()
            withContext(NonCancellable) {
                runCatching { nativeControl { backend.clearItemProgress(itemId) } }
            }
            current.transferring = false
            activeTransfers.remove(itemId)
            // Tidied now, or the moment the native call lets go of the folder: never
            // under a call that may still be writing into it.
            withContext(NonCancellable + Dispatchers.IO) { current.lease.close(disposition) }
            timeline.hostRequests(hostRequests.get() - requestsBefore)
            recordTimeline(timeline.summary(itemId, outcome))
        }
    }

    /**
     * Cancels the Downloads screen's own transfer. Album tracks belong to their
     * WorkManager job. The runtime is told to stop; the job's files go when it
     * has (see [StagingLeases]), not while it may still be writing them.
     */
    fun cancelCurrentDownload() {
        activeTransfers.values.filter { it.owner !in ALBUM_OWNERS && it.owner != SpotiFlacRequestOwner.ONLINE_MIX }.forEach { transfer ->
            transfer.cancelledByUser = true
            nativeControlExecutor.execute { runCatching { backend.cancelDownload(transfer.itemId) } }
        }
    }

    /**
     * What is known about [track] on each provider before any transfer: whether
     * the provider's own id for the recording is known, and whether it has it.
     * The runtimes Harmony ships check availability only inside a download
     * (Mornye 5.3.1 keeps `check_availability` to itself; its mobile API has
     * `download_by_strategy` and `download`), so the state is honestly
     * [SpotiFlacAvailabilityState.UNKNOWN] until a runtime offers the check.
     * Asks nobody: it uses the ids the track carries and what song.link
     * already answered.
     */
    internal fun checkAvailability(track: IdentifiedTrack): List<SpotiFlacAvailability> {
        val ids = songLinkIdentity(track)?.let(songLinkMemory::get) ?: basicProviderIdentifiers(track)
        return availableProviders.map { provider ->
            SpotiFlacAvailability(
                provider = provider,
                matched = SpotiFlacProviderMatch.hasExactId(provider, ids.deezerId, ids.tidalId, ids.qobuzId),
                state = SpotiFlacAvailabilityState.UNKNOWN,
                reason = "${backend.displayName} ${backend.version} confirms availability only as part of a download.",
            )
        }
    }

    /**
     * Looks up the provider ids of [tracks] ahead of their downloads (an
     * album's next tracks), so each download finds its answer waiting instead
     * of asking song.link while the track sits on "Resolving".
     */
    fun prefetchProviderIdentifiers(tracks: List<IdentifiedTrack>) {
        if (tracks.isEmpty()) return
        lookupScope.launch {
            for (track in tracks) {
                if (songLinkMemory.paused) break
                runCatching { resolveProviderIdentifiers(track) }
            }
        }
    }

    /**
     * Reads/preflights the provider-auth state that SpotiFLAC keeps separately
     * from the download response. The typed pending-auth object is read first;
     * the JSON compatibility path used by readVerificationChallengeNative() is
     * also SpotiFLAC's official signed-session bootstrap when no fresh pending
     * challenge exists. This lets the UI gate metadata before a download begins.
     */
    suspend fun getVerificationChallenge(providerId: String): SpotiFlacVerificationChallenge {
        val normalized = providerId.trim()
        require(normalized.isNotBlank()) { "Provider id is required for SpotiFLAC verification." }
        ensureReady { }
        return withProviderStateExclusive { readVerificationChallengeNative(normalized) }
    }

    /**
     * Android deep-link entry point used by MainActivity. This mirrors the
     * official SpotiFLAC Android host contract: spotiflac://session-grant (mornye://
     * in the Mornye build) uses
     * `state` as a one-time host nonce. The nonce is consumed to resolve the
     * owning extension before `grant` (or `code`) is staged. The grant is not
     * considered complete until the extension action `completeGrant` reports
     * success.
     */
    suspend fun acceptExternalVerificationCallback(
        callbackUrl: String,
    ): SpotiFlacVerificationCallbackResult {
        require(callbackUrl.isNotBlank()) { "Verification callback URL is empty." }

        val parsed = runCatching { Uri.parse(callbackUrl) }.getOrNull()
        val callbackState = parsed?.let {
            firstCallbackParameter(it, "state") ?: firstNestedCallbackParameter(it, "state")
        }.orEmpty().trim()
        val earlyFailure = when {
            parsed == null -> SpotiFlacVerificationCallbackResult(
                accepted = false,
                providerId = "",
                message = "Harmony could not parse the SpotiFLAC verification callback.",
            )
            !parsed.scheme.orEmpty().equals(backend.callbackScheme, ignoreCase = true) -> SpotiFlacVerificationCallbackResult(
                accepted = false,
                providerId = "",
                message = "Harmony ignored a verification callback with an unexpected URL scheme.",
            )
            parsed.host.orEmpty().lowercase(Locale.ROOT) !in SPOTIFLAC_CALLBACK_HOSTS -> SpotiFlacVerificationCallbackResult(
                accepted = false,
                providerId = "",
                message = "Harmony ignored a verification callback with an unexpected host.",
            )
            callbackState.isBlank() -> SpotiFlacVerificationCallbackResult(
                accepted = false,
                providerId = "",
                message = "The SpotiFLAC verification callback did not contain the required one-time state.",
            )
            else -> null
        }
        if (earlyFailure != null) {
            verificationEventChannel.tryEmit(earlyFailure)
            return earlyFailure
        }

        try {
            ensureReady { }
        } catch (t: Throwable) {
            val result = SpotiFlacVerificationCallbackResult(
                accepted = false,
                providerId = "",
                message = "Harmony received the verification callback, but the SpotiFLAC runtime could not initialize: ${t.message?.take(160) ?: "initialization failed"}",
            )
            verificationEventChannel.tryEmit(result)
            return result
        }

        // SpotiFLAC does not return the extension id in `state`. It returns a
        // one-time nonce which must first be consumed by the Go runtime. The old
        // Harmony bridge used that nonce as an extension id, so completeGrant
        // ran against a nonexistent provider and Tidal remained pending.
        //
        // Android may kill the app while the browser challenge is open. The Go
        // pending-state map is process-local, so retain only a SHA-256 digest of
        // the nonce in SharedPreferences and use it as a narrowly-scoped fallback
        // after process recreation. The raw nonce and signed grant are never
        // persisted by Harmony.
        val consumedProviderId = runCatching {
            nativeControl { backend.consumeCallbackState(callbackState) }
                .orEmpty()
                .trim()
        }.getOrNull()
        val providerId = consumedProviderId
            ?.takeIf(String::isNotBlank)
            ?: resolvePersistedCallbackProvider(callbackState)
        if (providerId.isNullOrBlank()) {
            val result = SpotiFlacVerificationCallbackResult(
                accepted = false,
                providerId = "",
                message = "The Tidal verification return was received, but its one-time state was expired or no longer belongs to this challenge. Tap Refresh, then Verify Tidal again.",
            )
            verificationEventChannel.tryEmit(result)
            return result
        }

        val result = withProviderStateExclusive {
            completeVerificationCallbackLocked(
                expectedProviderId = providerId,
                callbackUrl = callbackUrl,
            )
        }
        if (result.accepted) clearPersistedCallbackState(providerId)
        verificationEventChannel.tryEmit(result)
        return result
    }

    /**
     * Starts callback completion in application-lifetime scope. This is called
     * by MainActivity after it has copied and cleared the credential-bearing URI.
     */
    fun enqueueExternalVerificationCallback(callbackUrl: String) {
        if (callbackUrl.isBlank()) return
        verificationCallbackScope.launch {
            runCatching { acceptExternalVerificationCallback(callbackUrl) }
                .onFailure { failure ->
                    verificationEventChannel.tryEmit(
                        SpotiFlacVerificationCallbackResult(
                            accepted = false,
                            providerId = "",
                            message = "Harmony received the Tidal verification return, but could not finish it: ${failure.message?.take(160) ?: "callback completion failed"}",
                        ),
                    )
                }
        }
    }

    private suspend fun completeVerificationCallbackLocked(
        expectedProviderId: String,
        callbackUrl: String,
    ): SpotiFlacVerificationCallbackResult {
        val normalized = expectedProviderId.trim()
        val uri = runCatching { Uri.parse(callbackUrl) }.getOrNull()
            ?: return SpotiFlacVerificationCallbackResult(
                accepted = false,
                providerId = normalized,
                message = "Harmony could not parse the provider verification callback.",
            )

        val callbackState = firstCallbackParameter(uri, "state") ?: firstNestedCallbackParameter(uri, "state")
        if (callbackState.isNullOrBlank()) {
            return SpotiFlacVerificationCallbackResult(
                accepted = false,
                providerId = normalized,
                message = "The verification callback is missing the required extension state.",
            )
        }

        val providerError = firstCallbackParameter(uri, "error", "error_description", "error_message")
        if (!providerError.isNullOrBlank()) {
            return SpotiFlacVerificationCallbackResult(
                accepted = false,
                providerId = normalized,
                message = "Provider verification returned an error: ${providerError.take(180)}",
            )
        }

        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val isSignedSessionCallback = host == SPOTIFLAC_SESSION_GRANT_HOST

        if (isSignedSessionCallback) {
            // SpotiFLAC v4.9.5 uses `grant`, with `code` accepted as a fallback.
            // Do not accept broad token/result names as a one-time session grant.
            val sessionGrant = firstCallbackParameter(uri, "grant", "code")
                ?: firstNestedCallbackParameter(uri, "grant", "code")
            if (sessionGrant.isNullOrBlank()) {
                return SpotiFlacVerificationCallbackResult(
                    accepted = false,
                    providerId = normalized,
                    message = "The signed-session callback did not contain the required verification grant.",
                )
            }

            val completionRaw = try {
                nativeControl { backend.setSessionGrant(normalized, sessionGrant) }
                nativeControl {
                    backend.invokeAction(normalized, SPOTIFLAC_COMPLETE_GRANT_ACTION)
                }.orEmpty().trim()
            } catch (t: Throwable) {
                return SpotiFlacVerificationCallbackResult(
                    accepted = false,
                    providerId = normalized,
                    credentialKind = "session_grant",
                    message = "SpotiFLAC could not complete the signed-session verification: ${t.message?.take(160) ?: "native completion failed"}",
                )
            }
            val completion = runCatching { JSONObject(completionRaw) }.getOrNull()
            val completed = completion?.optBoolean("success", false) == true
            if (!completed) {
                val safeMessage = completion
                    ?.optString("error")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.take(180)
                return SpotiFlacVerificationCallbackResult(
                    accepted = false,
                    providerId = normalized,
                    credentialKind = "session_grant",
                    message = safeMessage?.let { "SpotiFLAC could not complete provider verification: $it" }
                        ?: "SpotiFLAC received the signed-session grant, but completeGrant did not report success.",
                )
            }

            return SpotiFlacVerificationCallbackResult(
                accepted = true,
                providerId = normalized,
                credentialKind = "session_grant",
                message = "Provider verification completed. Retrying SpotiFLAC…",
            )
        }

        // Preserve the standard extension OAuth path. It is intentionally kept
        // separate from signed-session completion: an OAuth code is staged using
        // the backend's auth-code API and then consumed by the provider flow.
        val authCode = firstCallbackParameter(uri, "code", "auth_code", "authorization_code")
        if (!authCode.isNullOrBlank()) {
            nativeControl { backend.setAuthCode(normalized, authCode) }
            return SpotiFlacVerificationCallbackResult(
                accepted = true,
                providerId = normalized,
                credentialKind = "auth_code",
                message = "Authorization callback captured. Retrying SpotiFLAC…",
            )
        }

        return SpotiFlacVerificationCallbackResult(
            accepted = false,
            providerId = normalized,
            message = "The provider callback did not contain a supported authorization result.",
        )
    }

    suspend fun clearVerificationChallenge(providerId: String) {
        val normalized = providerId.trim()
        if (normalized.isBlank()) return
        ensureReady { }
        withProviderStateExclusive {
            runCatching { nativeControl { backend.clearPendingAuth(normalized) } }
            clearPersistedCallbackState(normalized)
        }
    }

    private fun ensureNetworkAvailable() {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw SpotiFlacException("Android network service is unavailable.", "network_unavailable")
        val network = connectivity.activeNetwork
            ?: throw SpotiFlacException("No active internet connection is available.", "network_unavailable")
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: throw SpotiFlacException("The active network has no usable capabilities.", "network_unavailable")
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            throw SpotiFlacException("The active network does not provide internet access.", "network_unavailable")
        }
    }

    private suspend fun ensureReady(onProgress: (SpotiFlacTransferProgress) -> Unit) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return@withLock

            // The phone APK carries an ARM-only native runtime. Android's
            // emulator translation layer can sometimes install and launch it
            // on an x86_64 image, but the first native call may terminate the
            // process instead of returning a catchable UnsatisfiedLinkError.
            // Validate the actual extracted ELF before touching the generated
            // bindings. A
            // mismatched build now leaves the Downloads screen alive and
            // explains which APK the user needs.
            SpotiFlacNativeRuntime.requireCompatible(context, backend.nativeLibrary)

            val runtimeBase = File(context.filesDir, backend.runtimeDirectory)
            val store = SpotiFlacPackageStore(
                File(runtimeBase, "packages"), acceptedPackages, SpotiFlacProviderRegistry.fallbackPackages,
            )
            val startup = SpotiFlacProviderStartup(store, PROVIDER_PRIORITY)

            // Providers this phone has already run are used at once; the registry is
            // asked off to the side and a newer set waits for a moment between
            // downloads. Only a first run has to wait for the registry.
            val cached = withContext(Dispatchers.IO) { startup.cached() }
            val set: ProviderSet
            val source: String
            if (cached.packages.isNotEmpty()) {
                set = cached
                source = "last accepted packages (registry checked in the background)"
                refreshProvidersInBackground(startup, cached.fingerprint)
            } else {
                onProgress(
                    SpotiFlacTransferProgress(
                        stage = SpotiFlacStage.PROVIDERS,
                        detail = "Checking the official SpotiFLAC extension registry…",
                    ),
                )
                val registry = withContext(Dispatchers.IO) { resolveProviderRegistry() }
                onProgress(
                    SpotiFlacTransferProgress(
                        stage = SpotiFlacStage.PROVIDERS,
                        detail = "Verifying current SpotiFLAC provider packages…",
                    ),
                )
                // Each provider's package, verified and reviewed (SpotiFlacPackageStore). A
                // provider whose package cannot be had is left out; it does not stop the others.
                set = withContext(Dispatchers.IO) { startup.fromRegistry(registry, ::downloadProviderPackage) }
                source = registry.source
            }
            val resolved = set.packages
            val packageNotes = set.notes
            if (resolved.isEmpty()) {
                throw SpotiFlacException(
                    "No download provider could be installed. Check the connection and try again.",
                    "provider_install",
                    technicalDetails = "source=$source\n${packageNotes.joinToString("\n")}",
                )
            }
            activeRegistrySummary = "$source; fingerprint=${set.fingerprint}; " +
                resolved.joinToString { "${it.accepted.id}@${it.accepted.version}" } +
                if (packageNotes.isEmpty()) "" else "; " + packageNotes.joinToString("; ")

            // The runtime fingerprint changes whenever one of the installed package
            // hashes changes. This prevents a stale restored extension VM from
            // shadowing a newer .sflx file after an update.
            val runtimeRoot = File(runtimeBase, set.fingerprint)
            val extensionsDir = File(runtimeRoot, "extensions")
            val dataDir = File(runtimeRoot, "data")
            val previous = rememberedRuntime()

            withContext(Dispatchers.IO) {
                // A new set starts in a new folder. The providers whose package did not
                // change keep their settings and signed session (SpotiFlacRuntimeLayout).
                if (previous != null && previous.fingerprint != set.fingerprint && !dataDir.exists()) {
                    val unchanged = resolved.map { it.accepted }
                        .filter { previous.hashes[it.id].equals(it.sha256, ignoreCase = true) }.map { it.id }
                    SpotiFlacRuntimeLayout.carryOver(File(runtimeBase, "${previous.fingerprint}/data"), dataDir, unchanged)
                }
                extensionsDir.mkdirs()
                dataDir.mkdirs()
                resolved.forEach { item ->
                    SpotiFlacProviderInstaller.install(item.accepted, extensionsDir) { _, partial ->
                        item.file.copyTo(partial, overwrite = true)
                    }
                }
                // Only these packages may be in the directory the runtime loads.
                val wanted = resolved.map { "${it.accepted.id}.sflx" }.toSet()
                extensionsDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".sflx") && file.name !in wanted) file.delete()
                }
                store.prune(resolved.map { it.accepted })
            }

            // SpotiFLAC 4.9+ encrypts extension settings, session material and
            // credentials. Install a stable Android-Keystore-backed 32-byte key
            // before the Go runtime validates or loads any provider package.
            val extensionStorageMasterKey = withContext(Dispatchers.IO) {
                SpotiFlacExtensionStorageKey.loadOrCreateEncoded()
            }

            // Every download is staged under here; the Rust runtime refuses writes anywhere else.
            val stagingRoot = File(context.cacheDir, "spotiflac/staging").apply { mkdirs() }
            // Partial transfers nobody came back for.
            withContext(Dispatchers.IO) {
                SpotiFlacStaging.sweep(
                    stagingRoot, STAGING_MAX_AGE_MS, System.currentTimeMillis(),
                    keep = stagingLeases.heldNames, budgetBytes = STAGING_BUDGET_BYTES,
                )
            }
            nativeControl {
                backend.initialize(
                    extensionsDir.absolutePath,
                    dataDir.absolutePath,
                    extensionStorageMasterKey,
                    stagingRoot.absolutePath,
                )
                val loadResult = JSONObject(backend.loadExtensionsFromDir(extensionsDir.absolutePath))
                val errors = loadResult.optJSONArray("errors")
                val realErrors = (0 until (errors?.length() ?: 0))
                    .mapNotNull { errors?.optString(it)?.takeIf(String::isNotBlank) }
                    .filterNot(::isBenignDuplicateExtensionMessage)
                // One provider that does not load must not take the others down with it:
                // the download plan simply runs on the ones that did.
                val loaded = loadedProviderIds()
                val usable = PROVIDER_PRIORITY.filter { id -> id in loaded && resolved.any { it.accepted.id == id } }
                if (usable.isEmpty()) {
                    throw SpotiFlacException(
                        "SpotiFLAC provider initialization failed: ${realErrors.firstOrNull() ?: "no provider loaded"}",
                        "provider_init",
                        technicalDetails = "registry=$activeRegistrySummary\n${loadResult}",
                    )
                }
                if (realErrors.isNotEmpty()) {
                    activeRegistrySummary += "; load errors: " + realErrors.joinToString("; ") { it.take(200) }
                }
                availableProviders = usable
                providerQualityOptions = readInstalledProviderQualityOptions()
                restoreProviderConfiguration()
            }
            // The set that works is remembered with the one before it (to carry sessions
            // over next time, and to go back to); older runtime folders go.
            if (previous?.fingerprint != set.fingerprint) {
                rememberRuntime(set, previousFingerprint = previous?.fingerprint)
            }
            withContext(Dispatchers.IO) {
                SpotiFlacRuntimeLayout.prune(runtimeBase, setOfNotNull(set.fingerprint, rememberedRuntime()?.previous))
            }
            initialized = true
        }
    }

    /**
     * Execute one lossless extension provider at a time. SpotiFLAC's unified
     * DownloadByStrategy router can stop an aggregate fallback route on an
     * interactive verification challenge from a later provider. That made v7.6
     * ask for Deezer verification even when Tidal was the intended/healthy first
     * choice. v7.7 constrains each native call to exactly one provider, then
     * rotates locally on provider-scoped miss/rate-limit/format failures.
     *
     * We still never disable/unload extensions while a download is running; only
     * the router priority/fallback lists are narrowed to the current provider.
     */
    private suspend fun executeProviderPlan(
        baseRequest: JSONObject,
        outputFormat: SpotiFlacOutputFormat,
        current: ActiveTransfer,
        onProgress: (SpotiFlacTransferProgress) -> Unit,
        latestIds: () -> ProviderIdentifiers? = { null },
    ): JSONObject {
        val itemId = current.itemId
        val jobDir = current.jobDir
        // Naming the provider in the request is what lets two downloads run side
        // by side; with one at a time the runtime-wide route stays, as before.
        val routePerRequest = backend.routesPerRequest && parallelDownloads > 1
        val diagnostics = JSONArray()
        val blockedForThisDownload = linkedSetOf<String>()
        var lastFailure: JSONObject? = null
        var lastConversionFailure: SpotiFlacException? = null
        // A provider that asks for verification is set aside while the others are
        // tried; its challenge is surfaced only if none of them delivers the song.
        var deferredVerification: SpotiFlacException? = null
        var gatewayRateLimitRetries = 0

        try {
            awaitGatewayCooldownIfNeeded(onProgress)
            // IMPORTANT: never disable/unload extensions while a download plan is
            // running. In an earlier build Harmony used setExtensionEnabledByID(false/true)
            // between attempts. SpotiFLAC's provider registry is runtime-backed;
            // mutating extension lifecycle state here can leave the router with
            // zero registered download providers even though the packages are
            // present on disk. Instead we keep every extension loaded and only
            // change the provider-priority/fallback lists.
            var remaining = providerOrderForDownload().toMutableList()
            var attemptIndex = 0

            while (remaining.isNotEmpty() && attemptIndex <= PROVIDER_PRIORITY.size) {
                ensureNetworkAvailable()
                val currentProvider = remaining.first()
                val providerQuality = SpotiFlacProviderQuality.select(
                    currentProvider, outputFormat, providerQualityOptions[currentProvider],
                )
                if (providerQuality == null) {
                    lastFailure = JSONObject().apply {
                        put("success", false)
                        put("error_type", "unsupported_lossless_quality")
                        put("provider", currentProvider)
                        put("error", "${providerDisplay(currentProvider)} does not advertise a supported lossless quality.")
                    }
                    diagnostics.put(diagnosticNode("Lossless quality selection", lastFailure))
                    remaining.remove(currentProvider)
                    attemptIndex++
                    continue
                }
                nativeControl {
                    // The Go runtime routes by a runtime-wide order; Mornye's takes
                    // the provider from the request below, so downloads running
                    // side by side never change each other's route.
                    if (!routePerRequest) configureProviderRoute(listOf(currentProvider))
                    backend.resetDownloadCancel(itemId)
                }

                val label = "SpotiFLAC isolated ${providerDisplay(currentProvider)} attempt"

                onProgress(
                    SpotiFlacTransferProgress(
                        SpotiFlacStage.PROVIDERS,
                        provider = currentProvider,
                        detail = "Requesting ${outputFormat.label} from ${providerDisplay(currentProvider)}…",
                    ),
                )

                val request = JSONObject(baseRequest.toString()).apply {
                    put("quality", providerQuality)
                    // Keep the unified extension entrypoint, but prevent the Go
                    // router from moving on to another provider behind Harmony's
                    // back. This keeps verification and 429 attribution exact.
                    put("service", if (routePerRequest) currentProvider else "")
                    // Ids song.link answered since the request was built (a later provider's).
                    latestIds()?.let { applyIdentifiers(this, it) }
                    put("use_extensions", true)
                    put("use_fallback", false)
                    put("allow_fallback", false)
                    put("provider_priority", JSONArray(listOf(currentProvider)))
                    put("extension_fallback_provider_ids", JSONArray(listOf(currentProvider)))
                }

                // No more calls into one provider at once than its package allows.
                val response = providerSlot(currentProvider).withPermit {
                    try {
                        current.timeline.callStarted(withContext(Dispatchers.IO) { SpotiFlacStaging.resumableBytes(jobDir) })
                        current.transferring = true
                        callDownloadWithWatchdog(request = request, current = current, label = label)
                    } finally {
                        current.transferring = false
                    }
                }
                // One last look, so a fast transfer between two polls is still counted.
                runCatching { readProgress(itemId, current) }
                current.timeline.callEnded(response.optInt("attempts", 0))
                // With an isolated route, an unlabelled backend failure still
                // belongs to the provider Harmony explicitly selected.
                val reportedProvider = verificationProviderId(response) ?: currentProvider
                val attemptDiagnostic = diagnosticNode(label, response).apply {
                    put("route_mode", "isolated_provider")
                    put("selected_provider", currentProvider)
                    put("provider_quality", providerQuality)
                    put("requested_output_format", outputFormat.name)
                    put("eligible_providers", JSONArray(listOf(currentProvider)))
                }

                if (response.optBoolean("success", false)) {
                    // SpotiFLAC's official host has a post-download finalization
                    // step. A successful extension may intentionally return an
                    // M4A/MP4 container whose audio codec is FLAC or ALAC. v7.1
                    // incorrectly rejected that success before host finalization.
                    // Reproduce the lossless-only portion here because Harmony
                    // invokes the Go backend directly rather than the Flutter host.
                    var file = resolveOutputFile(
                        jobDir = jobDir,
                        reportedPath = response.optString("file_path").ifBlank { response.optString("file") },
                        reportedName = response.optString("file_name"),
                    )

                    // Amazon returns a successful provider result before the host's MOV step.
                    // Only Amazon uses this finalizer; all other providers keep their existing guard.
                    val decryptionInfo = inspectDownloadDecryption(response)
                    if (decryptionInfo != null && reportedProvider == "amazon") {
                        onProgress(SpotiFlacTransferProgress(
                            SpotiFlacStage.FINALIZING, provider = "amazon",
                            detail = "Finishing Amazon Music audio…",
                        ))
                        try {
                            val source = file ?: throw AmazonDownloadFinalizer.Failure(
                                "source", "Amazon's downloaded audio file is missing.",
                            )
                            val completed = current.timeline.measure(DownloadTimeline.Phase.FINALIZE) {
                                finishAmazonDownload(response, source, jobDir)
                            }
                            file = completed.file
                            response.apply {
                                put("file_path", completed.file.absolutePath)
                                put("file", completed.file.absolutePath)
                                put("file_name", completed.file.name)
                                put("audio_codec", completed.codec)
                                put("actual_extension", ".m4a")
                                put("actual_container", "mp4")
                                put("requires_container_conversion", true)
                                remove("decryption")
                                remove("decryption_key")
                            }
                            attemptDiagnostic.put("host_stage", "amazon_audio_ready")
                            attemptDiagnostic.put("decryption", decryptionInfo.toSafeJson())
                        } catch (failure: AmazonDownloadFinalizer.Failure) {
                            attemptDiagnostic.put("host_stage", "amazon_finalization_failed")
                            attemptDiagnostic.put("amazon_stage", failure.stage)
                            attemptDiagnostic.put("decryption", decryptionInfo.toSafeJson())
                            diagnostics.put(attemptDiagnostic)
                            throw SpotiFlacException(
                                message = failure.message ?: "Amazon audio finalization failed.",
                                errorType = "amazon_finalization_failed",
                                provider = "amazon",
                                technicalDetails = JSONObject().apply {
                                    put("registry", activeRegistrySummary)
                                    put("provider", "amazon")
                                    put("provider_success", true)
                                    put("host_stage", "amazon_finalization_failed")
                                    put("amazon_stage", failure.stage)
                                    put("decryption", decryptionInfo.toSafeJson())
                                    put("attempts", diagnostics)
                                }.toString(2),
                            )
                        }
                    } else if (decryptionInfo != null) {
                        attemptDiagnostic.apply {
                            put("output_found", file != null)
                            put("host_stage", "decryption_required")
                            put("decryption", decryptionInfo.toSafeJson())
                        }
                        diagnostics.put(attemptDiagnostic)
                        throw SpotiFlacException(
                            message = "${providerDisplay(reportedProvider)} returned an encrypted provider stream that requires host-side decryption. Harmony preserved the provider success but will not bypass protected-stream encryption.",
                            errorType = "protected_stream_decryption_required",
                            provider = reportedProvider,
                            technicalDetails = JSONObject().apply {
                                put("registry", activeRegistrySummary)
                                put("provider", reportedProvider ?: "unknown")
                                put("provider_success", true)
                                put("host_stage", "decryption_required")
                                put("decryption", decryptionInfo.toSafeJson())
                                put("attempts", diagnostics)
                            }.toString(2),
                        )
                    }

                    var probe = file?.let { probeAudioOutput(it, response) }
                    var usableFile = file
                    var qualityFailure: SpotiFlacException? = null
                    attemptDiagnostic.apply {
                        put("output_found", file != null)
                        probe?.let { putAudioProbeDiagnostic(this, it) }
                    }

                    if (file != null && probe != null && !probe.isNativeFlac && probe.canFinalizeLosslessly) {
                        onProgress(
                            SpotiFlacTransferProgress(
                                SpotiFlacStage.FINALIZING,
                                provider = reportedProvider,
                                detail = "Finalizing lossless ${probe.displayName} to native FLAC…",
                            ),
                        )
                        val finalization = current.timeline.measure(DownloadTimeline.Phase.FINALIZE) {
                            finalizeLosslessContainerToFlac(file, jobDir, probe)
                        }
                        attemptDiagnostic.put("container_finalization", finalization.diagnostic)
                        if (finalization.outputFile != null) {
                            val finalizedFile = finalization.outputFile
                            val finalizedProbe = probeAudioOutput(finalizedFile, JSONObject(response.toString()).apply {
                                put("audio_codec", "flac")
                                put("actual_extension", ".flac")
                                put("actual_container", "flac")
                                put("requires_container_conversion", false)
                            })
                            probe = finalizedProbe
                            attemptDiagnostic.put("final_detected_format", finalizedProbe.detectedFormat)
                            attemptDiagnostic.put("final_magic", finalizedProbe.magicHex)
                            if (finalizedProbe.isNativeFlac) {
                                response.put("file_path", finalizedFile.absolutePath)
                                response.put("file", finalizedFile.absolutePath)
                                response.put("file_name", finalizedFile.name)
                                response.put("audio_codec", "flac")
                                response.put("actual_extension", ".flac")
                                response.put("actual_container", "flac")
                                response.put("requires_container_conversion", false)
                                usableFile = finalizedFile
                            }
                        }
                    }

                    if (usableFile != null && probe?.isNativeFlac == true) {
                        try {
                            // Apply the ceiling inside the provider plan: if this
                            // device cannot convert a 192 kHz source, another
                            // provider may offer native FLAC within the ceiling.
                            val accepted = if (outputFormat.isLosslessOutput) {
                                current.timeline.measure(DownloadTimeline.Phase.FINALIZE) {
                                    enforceFlacQualityLimit(usableFile, jobDir, outputFormat, reportedProvider, onProgress)
                                }
                            } else usableFile
                            response.put(HARMONY_VALIDATED_FILE_PATH, accepted.absolutePath)
                            diagnostics.put(attemptDiagnostic)
                            rememberSuccessfulProvider(reportedProvider)
                            return response
                        } catch (failure: SpotiFlacException) {
                            if (failure.errorType != "flac_quality_limit_failed") throw failure
                            qualityFailure = failure
                            attemptDiagnostic.put("quality_limit_error", failure.technicalDetails ?: failure.message)
                        }
                    }
                    diagnostics.put(attemptDiagnostic)

                    val failureType = qualityFailure?.errorType ?: if (file == null) "missing_output" else {
                        if (probe?.isLosslessSource == true) "lossless_finalization_failed" else "not_lossless_flac"
                    }
                    val formatLabel = probe?.displayName ?: "no usable audio file"
                    val failureReason = when {
                        qualityFailure != null -> qualityFailure.message
                        file == null -> "Provider reported success but produced no usable audio file."
                        probe?.isLosslessSource == true -> "Provider returned lossless $formatLabel, but Harmony could not finalize it to native FLAC."
                        else -> "Provider returned $formatLabel; its codec is not proven lossless FLAC/ALAC."
                    }
                    val syntheticFailure = JSONObject().apply {
                        put("success", false)
                        put("error_type", failureType)
                        put("provider", reportedProvider ?: response.optString("service").ifBlank { response.optString("provider") })
                        put("error", failureReason)
                        probe?.let { putAudioProbeDiagnostic(this, it) }
                    }
                    lastFailure = syntheticFailure

                    if (failureType == "lossless_finalization_failed" || qualityFailure != null) {
                        lastConversionFailure = SpotiFlacException(
                            message = qualityFailure?.message ?: "The lossless download completed, but this phone could not convert it to a FLAC file.",
                            errorType = failureType,
                            provider = reportedProvider,
                            technicalDetails = buildAudioFailureDetails(reportedProvider, probe, diagnostics, activeRegistrySummary),
                        )
                    }
                    val providerFallbackIsUseful = SpotiFlacCompatibilityPolicy.canTryAnotherSource(failureType)
                    if (providerFallbackIsUseful && remaining.size > 1) {
                        blockedForThisDownload += currentProvider
                        remaining.remove(currentProvider)
                        cleanupStagingForRetry(jobDir)
                        attemptIndex++
                        onProgress(
                            SpotiFlacTransferProgress(
                                SpotiFlacStage.PROVIDERS,
                                provider = reportedProvider,
                                detail = if (lastConversionFailure != null) {
                                    "Local FLAC conversion failed. Trying another lossless source within ${outputFormat.label}…"
                                } else "${providerDisplay(reportedProvider)} returned no usable lossless FLAC. Trying the next provider…",
                            ),
                        )
                        continue
                    }

                    throw SpotiFlacException(
                        message = when {
                            qualityFailure != null -> qualityFailure.message ?: "The selected FLAC quality limit could not be applied."
                            file == null -> "SpotiFLAC reported success but no usable audio file was produced."
                            probe?.isLosslessSource == true -> "${providerDisplay(reportedProvider)} returned lossless $formatLabel, but Harmony could not finalize it to native FLAC."
                            else -> "${providerDisplay(reportedProvider)} returned $formatLabel. Harmony refused to transcode a lossy or unverified codec to FLAC."
                        },
                        errorType = failureType,
                        provider = reportedProvider,
                        technicalDetails = buildAudioFailureDetails(
                            providerId = reportedProvider,
                            probe = probe,
                            diagnostics = diagnostics,
                            registrySummary = activeRegistrySummary,
                        ),
                    )
                }

                diagnostics.put(attemptDiagnostic)
                lastFailure = response

                val errorType = response.optString("error_type").lowercase(Locale.ROOT)

                if (errorType == "rate_limit" || errorType == "too_many_requests") {
                    val limitedProvider = inferProviderFromRateLimit(response) ?: currentProvider
                    if (remaining.size > 1) {
                        blockedForThisDownload += currentProvider
                        remaining.remove(currentProvider)
                        cleanupStagingForRetry(jobDir)
                        attemptIndex++
                        onProgress(
                            SpotiFlacTransferProgress(
                                SpotiFlacStage.PROVIDERS,
                                provider = limitedProvider,
                                detail = "${providerDisplay(limitedProvider)} is temporarily rate-limited. Trying ${remaining.joinToString { providerDisplay(it) }} without hammering the limited provider…",
                            ),
                        )
                        continue
                    }

                    if (gatewayRateLimitRetries < MAX_GATEWAY_RATE_LIMIT_AUTO_RETRIES) {
                        val waitSeconds = registerGatewayCooldown(response, gatewayRateLimitRetries)
                        gatewayRateLimitRetries++
                        cleanupStagingForRetry(jobDir)
                        onProgress(
                            SpotiFlacTransferProgress(
                                SpotiFlacStage.PROVIDERS,
                                provider = limitedProvider,
                                detail = "SpotiFLAC gateway rate limit reached. Waiting ${waitSeconds}s before one paced retry…",
                            ),
                        )
                        awaitGatewayCooldownIfNeeded(onProgress)
                        continue
                    }
                }

                if (errorType == "verification_required") {
                    // Verification state lives in SpotiFLAC's pending-auth store,
                    // not necessarily in DownloadByStrategy's JSON response. Read
                    // the exported pending-auth API before surfacing the challenge.
                    val providerId = currentProvider
                    val pendingAuth = providerId?.let {
                        runCatching { readVerificationChallengeNative(it) }.getOrNull()
                    }
                    val verificationUrl = pendingAuth?.verificationUrl ?: extractSafeVerificationUrl(response)
                    val effectiveChallenge = pendingAuth?.copy(verificationUrl = verificationUrl)
                        ?: providerId?.let {
                            SpotiFlacVerificationChallenge(
                                providerId = it,
                                pending = true,
                                verificationUrl = verificationUrl,
                                safeDetails = JSONObject().apply {
                                    put("provider", it)
                                    put("pending_auth_available", false)
                                    put("download_response_has_verification_url", verificationUrl != null)
                                }.toString(2),
                            )
                        }
                    val providerName = providerDisplay(providerId)
                    val technical = JSONObject().apply {
                        put("registry", activeRegistrySummary)
                        put("verification_response", response)
                        put("attempts", diagnostics)
                        effectiveChallenge?.safeDetails?.let { details ->
                            runCatching { put("pending_auth_summary", JSONObject(details)) }
                        }
                        if (verificationUrl != null) put("verification_url_available", true)
                    }.toString(2)
                    val verificationFailure = SpotiFlacException(
                        message = if (verificationUrl != null) {
                            "$providerName requires provider verification. Open the provider challenge, finish it, return to Harmony, then use Check & retry."
                        } else {
                            "$providerName requires provider verification, but SpotiFLAC did not expose a safe browser authorization URL for the pending request."
                        },
                        errorType = "verification_required",
                        technicalDetails = technical,
                        provider = providerId,
                        verificationUrl = verificationUrl,
                        verificationChallenge = effectiveChallenge,
                    )
                    // Since SpotiFLAC 4.9.6 every provider's signed session is checked
                    // before it is tried, so one provider's challenge (often one with no
                    // browser page to open) used to end the whole download while the
                    // remaining providers were never asked. Try them first.
                    if (remaining.size > 1) {
                        deferredVerification = deferredVerification ?: verificationFailure
                        blockedForThisDownload += currentProvider
                        remaining.remove(currentProvider)
                        cleanupStagingForRetry(jobDir)
                        attemptIndex++
                        onProgress(
                            SpotiFlacTransferProgress(
                                SpotiFlacStage.PROVIDERS,
                                provider = currentProvider,
                                detail = "$providerName asks for verification. Trying ${providerDisplay(remaining.first())} first…",
                            ),
                        )
                        continue
                    }
                    throw actionableVerification(deferredVerification) ?: verificationFailure
                }

                // Some extension errors include the provider that rejected the
                // request. If the error is recoverable, rotate that provider out
                // of this download only, without disabling/unloading it globally.
                if (canRetryPerProvider(response) && remaining.size > 1) {
                    blockedForThisDownload += currentProvider
                    remaining.remove(currentProvider)
                    cleanupStagingForRetry(jobDir)
                    attemptIndex++
                    continue
                }

                // This provider was the final isolated candidate, so surface its
                // concrete failure instead of replaying the same request, unless an
                // earlier provider asked for a verification the user can complete.
                throw actionableVerification(deferredVerification) ?: friendlyBackendFailure(response, diagnostics)
            }
        } catch (failure: SpotiFlacException) {
            val conversion = lastConversionFailure
            if (conversion != null && SpotiFlacCompatibilityPolicy.preserveConversionFailure(failure.errorType)) {
                throw SpotiFlacException(
                    message = "This phone could not finish FLAC conversion, and the other sources did not provide a compatible file. Open Details for the converter error.",
                    errorType = conversion.errorType,
                    provider = conversion.provider,
                    technicalDetails = JSONObject().apply {
                        put("conversion_failure", conversion.technicalDetails)
                        put("last_provider_failure", failure.message)
                        put("attempts", diagnostics)
                    }.toString(2),
                )
            }
            throw failure
        } finally {
            if (!routePerRequest) {
                withContext(NonCancellable) {
                    runCatching { nativeControl { restoreProviderConfiguration() } }
                }
            }
        }

        actionableVerification(deferredVerification)?.let { throw it }
        val failure = lastFailure ?: JSONObject().apply {
            put("success", false)
            put("error_type", "not_found")
            put("error", "No SpotiFLAC provider returned a file.")
        }
        throw friendlyBackendFailure(failure, diagnostics)
    }

    /** A set-aside verification worth showing: one with a browser page the user can open. */
    private fun actionableVerification(deferred: SpotiFlacException?): SpotiFlacException? =
        deferred?.takeIf { it.verificationUrl != null }

    /**
     * One provider call, stopped when it is going nowhere rather than when it
     * has run for a fixed time (see [TransferActivity] and [WatchedCall]): a
     * large file on a slow connection goes on for as long as bytes keep
     * arriving. A call stopped or cancelled is told so; whatever it answers
     * afterwards is not used, and its folder is tidied only once it returned.
     */
    private suspend fun callDownloadWithWatchdog(
        request: JSONObject,
        current: ActiveTransfer,
        label: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val itemId = current.itemId
        val startedAt = System.nanoTime()
        current.lease.nativeStarted()
        val future = try {
            nativeDownloadExecutor.submit<String> {
                try {
                    backend.downloadByStrategy(request.toString())
                } finally {
                    current.lease.nativeFinished()
                }
            }
        } catch (rejected: Throwable) {
            current.lease.nativeFinished()
            throw rejected
        }
        val result = try {
            watchedCall.await(
                call = future,
                activity = current.activity,
                isCancelled = { !isActive },
                stopNative = {
                    nativeControlExecutor.submit { backend.cancelDownload(itemId) }
                        .get(CANCEL_DELIVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Throwable) {
            throw SpotiFlacException(
                "$label failed at the native Go boundary: ${cause.message ?: cause.javaClass.simpleName}",
                "native_bridge",
                cause = cause,
                technicalDetails = "registry=$activeRegistrySummary",
            )
        }
        when (result) {
            is WatchedCall.Result.Returned -> parseBackendResponse(result.raw, label, elapsedMs(startedAt))
            is WatchedCall.Result.Stopped -> JSONObject().apply {
                put("success", false)
                put("error_type", "provider_timeout")
                put("error", "$label ${result.reason} (${current.activity.receivedBytes / (1024 * 1024)} MB received).")
                put("elapsed_ms", elapsedMs(startedAt))
            }
            WatchedCall.Result.Cancelled -> throw CancellationException("Download cancelled")
        }
    }

    private fun parseBackendResponse(raw: String, label: String, elapsedMs: Long): JSONObject {
        if (raw.isBlank()) {
            return JSONObject().apply {
                put("success", false)
                put("error_type", "empty_response")
                put("error", "$label returned an empty backend response.")
                put("elapsed_ms", elapsedMs)
            }
        }
        return runCatching { JSONObject(raw) }.getOrElse {
            JSONObject().apply {
                put("success", false)
                put("error_type", "invalid_backend_response")
                put("error", "$label returned non-JSON data.")
                put("raw", raw.take(4000))
                put("elapsed_ms", elapsedMs)
            }
        }.apply {
            if (!has("elapsed_ms")) put("elapsed_ms", elapsedMs)
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun diagnosticNode(label: String, response: JSONObject): JSONObject = JSONObject().apply {
        put("attempt", label)
        put("success", response.optBoolean("success", false))
        put("provider", response.optString("service").ifBlank { response.optString("provider") })
        put("error_type", response.optString("error_type"))
        put("error", response.optString("error").ifBlank { response.optString("message") })
        put("elapsed_ms", response.optLong("elapsed_ms", -1L))
    }

    private fun providerOrderForDownload(): List<String> {
        val usable = availableProviders
        val preferred = lastSuccessfulProviderId
        if (preferred.isNullOrBlank() || preferred !in usable) return usable
        return buildList(usable.size) {
            add(preferred)
            usable.filterTo(this) { it != preferred }
        }
    }

    private fun rememberSuccessfulProvider(providerId: String?) {
        if (!providerId.isNullOrBlank() && providerId in PROVIDER_PRIORITY) {
            lastSuccessfulProviderId = providerId
            providerPrefs.edit().putString(KEY_LAST_SUCCESSFUL_PROVIDER, providerId).apply()
        }
    }

    private fun rememberPendingVerificationTrack(
        track: IdentifiedTrack,
        owner: SpotiFlacRequestOwner,
    ) {
        // This is only resume metadata for Android process recreation. Never put
        // auth URLs, grants, cookies, tokens, or provider secrets in this JSON.
        val json = JSONObject().apply {
            put("artist", track.artist)
            put("title", track.title)
            put("source_title", track.sourceTitle)
            put("thumbnail_url", track.thumbnailUrl ?: JSONObject.NULL)
            put("album", track.album)
            put("duration_ms", track.durationMs)
            put("isrc", track.isrc ?: JSONObject.NULL)
            put("spotify_id", track.spotifyId ?: JSONObject.NULL)
            put("metadata_id", track.metadataId ?: JSONObject.NULL)
            put("request_owner", owner.name)
        }.toString()
        providerPrefs.edit().putString(KEY_PENDING_VERIFICATION_TRACK, json).apply()
    }

    /** Claims a preflight challenge for a workflow before native download work starts. */
    fun rememberPendingVerificationTrackFor(
        track: IdentifiedTrack,
        owner: SpotiFlacRequestOwner,
    ) = rememberPendingVerificationTrack(track, owner)

    fun pendingVerificationTrack(
        owner: SpotiFlacRequestOwner = SpotiFlacRequestOwner.DOWNLOADS,
    ): IdentifiedTrack? {
        val raw = providerPrefs.getString(KEY_PENDING_VERIFICATION_TRACK, null)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            val node = JSONObject(raw)
            val storedOwner = node.optString("request_owner")
                .takeIf(String::isNotBlank)
                ?.let { SpotiFlacRequestOwner.valueOf(it) }
                ?: SpotiFlacRequestOwner.DOWNLOADS
            if (storedOwner != owner) return@runCatching null
            IdentifiedTrack(
                artist = node.optString("artist"),
                title = node.optString("title"),
                sourceTitle = node.optString("source_title"),
                thumbnailUrl = node.optString("thumbnail_url").takeIf { it.isNotBlank() && it != "null" },
                album = node.optString("album"),
                durationMs = node.optLong("duration_ms", 0L),
                isrc = node.optString("isrc").takeIf { it.isNotBlank() && it != "null" },
                spotifyId = node.optString("spotify_id").takeIf { it.isNotBlank() && it != "null" },
                metadataId = node.optString("metadata_id").takeIf { it.isNotBlank() && it != "null" },
            )
        }.getOrNull()
    }

    fun pendingVerificationOwner(): SpotiFlacRequestOwner? {
        val raw = providerPrefs.getString(KEY_PENDING_VERIFICATION_TRACK, null)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            JSONObject(raw).optString("request_owner")
                .takeIf(String::isNotBlank)
                ?.let(SpotiFlacRequestOwner::valueOf)
                ?: SpotiFlacRequestOwner.DOWNLOADS
        }.getOrNull()
    }

    fun clearPendingVerificationTrackFor(owner: SpotiFlacRequestOwner) {
        if (pendingVerificationOwner() == owner) clearPendingVerificationTrack()
    }

    private fun clearPendingVerificationTrack() {
        providerPrefs.edit().remove(KEY_PENDING_VERIFICATION_TRACK).apply()
    }

    private fun inferProviderFromRateLimit(response: JSONObject): String? {
        val haystack = buildString {
            append(response.optString("error"))
            append(' ')
            append(response.optString("message"))
        }.lowercase(Locale.ROOT)
        return when {
            "tidal" in haystack -> "tidal-web"
            "qobuz" in haystack -> "qobuz-web"
            "deezer" in haystack || "/dl/dzr" in haystack -> "deezer"
            "amazon" in haystack || "amzn" in haystack -> "amazon"
            else -> null
        }
    }

    /** How long to leave the provider alone: its own Retry-After when it gave one (SpotiFlacFailures). */
    private fun retryAfterSeconds(response: JSONObject, retryOrdinal: Int): Long = SpotiFlacFailures.retryAfterSeconds(
        advertised = response.optLong("retry_after_seconds", 0L),
        retryOrdinal = retryOrdinal,
        fallbackSeconds = DEFAULT_GATEWAY_COOLDOWN_SECONDS,
        minSeconds = MIN_GATEWAY_COOLDOWN_SECONDS,
        maxSeconds = MAX_GATEWAY_COOLDOWN_SECONDS,
    )

    private fun registerGatewayCooldown(response: JSONObject, retryOrdinal: Int): Long {
        val seconds = retryAfterSeconds(response, retryOrdinal)
        val proposed = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(seconds)
        gatewayCooldownUntilElapsedMs = maxOf(gatewayCooldownUntilElapsedMs, proposed)
        return seconds
    }

    private suspend fun awaitGatewayCooldownIfNeeded(
        onProgress: (SpotiFlacTransferProgress) -> Unit,
    ) {
        while (true) {
            val remainingMs = gatewayCooldownUntilElapsedMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return
            val remainingSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
            onProgress(
                SpotiFlacTransferProgress(
                    SpotiFlacStage.PROVIDERS,
                    detail = "Respecting SpotiFLAC rate limit: retrying in ${remainingSeconds}s…",
                ),
            )
            delay(minOf(1_000L, remainingMs))
        }
    }

    private fun canRetryPerProvider(response: JSONObject): Boolean {
        if (response.optBoolean("cancelled", false)) return false
        return when (response.optString("error_type").lowercase(Locale.ROOT)) {
            "cancelled", "network_unavailable", "storage", "storage_error",
            "permission", "invalid_request", "rate_limit", "too_many_requests" -> false
            // Provider-scoped bad/missing audio is recoverable: rotate only that
            // provider out of this download and let another SpotiFLAC lossless
            // provider try.
            else -> true
        }
    }

    private fun buildDownloadRequest(
        track: IdentifiedTrack,
        ids: ProviderIdentifiers,
        jobDir: File,
        itemId: String,
    ): JSONObject {
        val title = track.title.ifBlank { track.sourceTitle }
        val artist = track.artist
        val sourceUrl = ids.spotifyUrl ?: ids.deezerUrl.orEmpty()
        return JSONObject().apply {
            // Canonical unified-request fields used by SpotiFLAC's router.
            // These match the v4.9.5 DownloadRequestPayload host contract.
            put("contract_version", 1)
            put("storage_mode", "app")
            put("service", "")
            put("query", listOf(artist, title).filter(String::isNotBlank).joinToString(" - "))
            put("track_name", title)
            put("artist_name", artist)
            put("album_name", track.album)
            put("album_artist", track.albumTags?.albumArtist?.takeIf(String::isNotBlank) ?: artist)
            put("cover_url", track.thumbnailUrl.orEmpty())
            // An album's own numbering and date, embedded with the rest (embed_metadata).
            track.albumTags?.let { tags ->
                put("track_number", tags.trackNumber.coerceAtLeast(0))
                put("disc_number", tags.discNumber.coerceAtLeast(0))
                put("total_tracks", tags.totalTracks.coerceAtLeast(0))
                put("total_discs", tags.totalDiscs.coerceAtLeast(0))
                put("release_date", tags.releaseDate)
            }
            put("output_dir", jobDir.absolutePath)
            put("audio_format", "LOSSLESS")
            // Quality is selected from the installed provider's capabilities
            // immediately before each isolated attempt, never as a global token.
            put("filename_format", "{artist} - {title}")
            put("item_id", itemId)
            put("duration", (track.durationMs.coerceAtLeast(0L) / 1000L).toInt())
            put("duration_ms", track.durationMs.coerceAtLeast(0L))
            put("isrc", track.isrc.orEmpty())
            put("spotify_id", ids.spotifyId.orEmpty())
            put("deezer_id", ids.deezerId.orEmpty())
            put("qobuz_id", ids.qobuzId.orEmpty())
            put("tidal_id", ids.tidalId.orEmpty())
            put("source_url", sourceUrl)
            put("url", sourceUrl)
            put("embed_metadata", true)
            put("embed_lyrics", false)
            put("embed_max_quality_cover", false)
            put("use_extensions", true)
            put("use_fallback", true)
            put("allow_fallback", true)
            // Match the official payload default for strict lossless requests.
            // v7.0 set this true, which allowed a provider/router branch to accept
            // an alternate quality and later report success with non-FLAC bytes.
            put("allow_quality_variant", false)
            put("quality_variant", "")
            put("quality_variant_collision_only", false)

            // The official DownloadRequestPayload exposes output_ext. Harmony is
            // FLAC-only on this path, so make that contract explicit rather than
            // relying on an extension's native filename/container.
            put("output_ext", ".flac")
            // The Go result can still expose a native M4A/MP4 container. Setting
            // this flag tells compatible providers/hosts that Harmony wants the
            // post-download container finalized. Harmony also performs the same
            // lossless-only finalization locally as a direct-Go-host fallback.
            put("requires_container_conversion", true)

            // The mobile backend has used an availability object for direct
            // SongLink-derived provider IDs. Keep it alongside the canonical
            // scalar IDs so older/newer compatible backend revisions can use it.
            put("availability", JSONObject().apply {
                put("deezer_id", ids.deezerId.orEmpty())
                put("spotify_id", ids.spotifyId.orEmpty())
                put("qobuz_id", ids.qobuzId.orEmpty())
                put("tidal_id", ids.tidalId.orEmpty())
            })
        }
    }

    private suspend fun readProgress(itemId: String, current: ActiveTransfer? = null): SpotiFlacTransferProgress? {
        val raw = nativeControl { backend.getAllDownloadProgress() }
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val node = findProgressNode(root, itemId) ?: return null

        val total = firstLong(node, "total_bytes", "totalBytes", "total")
        val downloaded = firstLong(node, "downloaded_bytes", "downloadedBytes", "downloaded", "bytes_downloaded")
        val percentage = firstDouble(node, "progress", "percent", "percentage", "progress_percent")
        // What the watchdog goes by: bytes that arrived since it last looked.
        current?.activity?.note(downloaded, total)
        current?.timeline?.progress(downloaded)
        val fraction = when {
            total > 0L && downloaded >= 0L ->
                (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
            percentage in 0.0..1.0 -> percentage.toFloat()
            percentage in 1.0..100.0 -> (percentage / 100.0).toFloat()
            else -> null
        }
        val stageText = listOf("stage", "status", "phase")
            .firstNotNullOfOrNull { key -> node.optString(key).takeIf(String::isNotBlank) }
            .orEmpty()
        // Preserve the host's provider/quality explanation while native code
        // still reports an empty preparation entry with no transferred audio.
        if (downloaded <= 0L && stageText.contains("prepar", ignoreCase = true)) return null
        val stage = when {
            stageText.contains("prepare", true) || stageText.contains("check", true) -> SpotiFlacStage.RESOLVING
            stageText.contains("final", true) || stageText.contains("metadata", true) -> SpotiFlacStage.FINALIZING
            else -> SpotiFlacStage.DOWNLOADING
        }
        return SpotiFlacTransferProgress(
            stage = stage,
            fraction = fraction,
            downloadedBytes = downloaded.coerceAtLeast(0L),
            totalBytes = total.coerceAtLeast(0L),
            provider = node.optString("provider").takeIf(String::isNotBlank)
                ?: node.optString("service").takeIf(String::isNotBlank),
            detail = stageText.takeIf(String::isNotBlank),
        )
    }

    private fun findProgressNode(value: Any?, itemId: String): JSONObject? {
        return when (value) {
            is JSONObject -> {
                val explicitId = sequenceOf("item_id", "itemId", "id")
                    .map { value.optString(it) }
                    .firstOrNull { it == itemId }
                if (explicitId != null) {
                    value
                } else {
                    val direct = if (value.has(itemId) && value.opt(itemId) is JSONObject) {
                        value.optJSONObject(itemId)
                    } else {
                        null
                    }
                    if (direct != null) {
                        direct
                    } else {
                        val keys = value.keys()
                        var found: JSONObject? = null
                        while (keys.hasNext() && found == null) {
                            found = findProgressNode(value.opt(keys.next()), itemId)
                        }
                        found
                    }
                }
            }
            is JSONArray -> {
                var found: JSONObject? = null
                for (index in 0 until value.length()) {
                    found = findProgressNode(value.opt(index), itemId)
                    if (found != null) break
                }
                found
            }
            else -> null
        }
    }

    private fun firstLong(json: JSONObject, vararg keys: String): Long {
        for (key in keys) if (json.has(key)) {
            when (val value = json.opt(key)) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun firstDouble(json: JSONObject, vararg keys: String): Double {
        for (key in keys) if (json.has(key)) {
            when (val value = json.opt(key)) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return -1.0
    }

    /**
     * Track search across SpotiFLAC's own metadata providers (Tidal, Qobuz,
     * Deezer, Amazon: whichever installed extensions offer search), in the
     * engine's priority order. Returns the engine's JSON array of tracks.
     * A provider that needs verification is skipped; the error is returned
     * only when no provider found anything.
     */
    suspend fun searchProviderTracks(query: String, limit: Int): String {
        ensureReady { }
        return nativeControl { backend.searchTracksWithMetadataProviders(query, limit.toLong(), true) }
    }

    private suspend fun <T> nativeControl(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            nativeControlExecutor.submit<T> { block() }.get()
        } catch (execution: ExecutionException) {
            throw execution.cause ?: execution
        }
    }

    private fun restoreProviderConfiguration() {
        // Enabling is idempotent and done only as part of initialization/restore.
        // We never disable providers during a download attempt because unloading
        // an extension can unregister its provider from the Go runtime.
        availableProviders.forEach { backend.setExtensionEnabled(it, true) }
        configureProviderRoute(availableProviders)
    }

    private class RememberedRuntime(val fingerprint: String, val previous: String?, val hashes: Map<String, String>)

    private fun rememberedRuntime(): RememberedRuntime? =
        providerPrefs.getString("$KEY_RUNTIME_SET_PREFIX${backend.nativeLibrary}", null)?.let { raw ->
            runCatching {
                val node = JSONObject(raw)
                val packages = node.getJSONObject("packages")
                RememberedRuntime(
                    fingerprint = node.getString("fingerprint"),
                    previous = node.optString("previous").takeIf(String::isNotBlank),
                    hashes = packages.keys().asSequence().associateWith { packages.getString(it) },
                )
            }.getOrNull()
        }

    private fun rememberRuntime(set: ProviderSet, previousFingerprint: String?) {
        val packages = JSONObject()
        set.packages.forEach { packages.put(it.accepted.id, it.accepted.sha256) }
        val node = JSONObject().put("fingerprint", set.fingerprint).put("previous", previousFingerprint.orEmpty())
            .put("packages", packages)
        providerPrefs.edit().putString("$KEY_RUNTIME_SET_PREFIX${backend.nativeLibrary}", node.toString()).apply()
    }

    /**
     * Asks the registry while downloads go on with the providers at hand. A
     * different set is verified into the store and waits ([adoptProviderUpdate]);
     * nothing the running downloads use is touched.
     */
    private fun refreshProvidersInBackground(startup: SpotiFlacProviderStartup, runningFingerprint: String) {
        if (!providerRefreshRunning.compareAndSet(false, true)) return
        lookupScope.launch {
            try {
                val next = startup.fromRegistry(resolveProviderRegistry(), ::downloadProviderPackage)
                if (next.packages.isNotEmpty() && next.fingerprint != runningFingerprint) pendingProviderUpdate = true
            } catch (_: Throwable) {
                // Offline or a broken registry: the providers at hand keep working.
            } finally {
                providerRefreshRunning.set(false)
            }
        }
    }

    /** The ids of the extensions the runtime has loaded. */
    private fun loadedProviderIds(): Set<String> = runCatching {
        val installed = JSONArray(backend.getInstalledExtensions())
        (0 until installed.length()).mapNotNull { installed.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }.toSet()
    }.getOrDefault(emptySet())

    private fun readInstalledProviderQualityOptions(): Map<String, List<SpotiFlacQualityOption>> {
        val installed = JSONArray(backend.getInstalledExtensions())
        return buildMap {
            for (index in 0 until installed.length()) {
                val extension = installed.optJSONObject(index) ?: continue
                val id = extension.optString("id")
                if (id !in PROVIDER_PRIORITY) continue
                val options = extension.optJSONArray("quality_options") ?: continue
                put(id, buildList {
                    for (optionIndex in 0 until options.length()) {
                        val option = options.optJSONObject(optionIndex) ?: continue
                        val qualityId = option.optString("id").trim()
                        if (qualityId.isNotEmpty()) add(SpotiFlacQualityOption(qualityId, option.optString("kind")))
                    }
                })
            }
        }
    }

    private fun configureProviderRoute(providerIds: List<String>) {
        val route = JSONArray(providerIds).toString()
        backend.setProviderPriority(route)
        backend.setFallbackProviders(route)
    }

    private fun isBenignDuplicateExtensionMessage(message: String): Boolean {
        val normalized = message.lowercase(Locale.ROOT)
        return normalized.contains("already installed") || normalized.contains("already loaded")
    }

    /** The registry, asked for at most every [REGISTRY_MAX_AGE_MS]; see [SpotiFlacProviderRegistry.resolve]. */
    private fun resolveProviderRegistry(): RegistrySnapshot = SpotiFlacProviderRegistry.resolve(
        appVersion = backend.version,
        cache = SpotiFlacRegistryCache(
            File(context.filesDir, "${backend.runtimeDirectory}/registry.json"), REGISTRY_MAX_AGE_MS,
        ),
    ) { fetchOfficialProviderRegistry() }

    private fun fetchOfficialProviderRegistry(): String {
        hostRequests.incrementAndGet()
        val connection = (URL(SpotiFlacProviderRegistry.OFFICIAL_REGISTRY_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = REGISTRY_CONNECT_TIMEOUT_MS
            readTimeout = REGISTRY_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Harmony/1.0 Android SpotiFLAC-Bridge")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("official extension registry returned HTTP $code")
            return connection.inputStream.use { input ->
                SpotiFlacProviderGate.readBounded(input, SpotiFlacRegistryCache.MAX_BYTES).toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** One provider package to [partial], no larger than a package can be; the store verifies it. */
    private fun downloadProviderPackage(requested: ProviderPackage, partial: File) {
        hostRequests.incrementAndGet()
        val connection = (URL(requested.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = PROVIDER_CONNECT_TIMEOUT_MS
            readTimeout = PROVIDER_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "Harmony/1.0 Android SpotiFLAC-Bridge")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw SpotiFlacException(
                    "Could not download the ${requested.displayName} SpotiFLAC provider (HTTP $code).",
                    "provider_download",
                )
            }
            connection.inputStream.use { input ->
                SpotiFlacProviderGate.copyBounded(input, partial, SpotiFlacProviderGate.MAX_PACKAGE_BYTES)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** The ids the track already carries, without asking song.link. */
    private fun basicProviderIdentifiers(track: IdentifiedTrack): ProviderIdentifiers {
        val deezerId = track.metadataId?.trim()?.takeIf { it.all(Char::isDigit) }
        val spotifyId = track.spotifyId?.trim()?.takeIf { it.matches(SPOTIFY_TRACK_ID) }
        return ProviderIdentifiers(
            deezerId = deezerId,
            deezerUrl = deezerId?.let { "https://www.deezer.com/track/$it" },
            spotifyId = spotifyId,
            spotifyUrl = spotifyId?.let { "https://open.spotify.com/track/$it" },
        )
    }

    /** The URL song.link is asked about: the track's Spotify page, else its Deezer one. */
    private fun songLinkIdentity(track: IdentifiedTrack): String? {
        val own = basicProviderIdentifiers(track)
        return own.spotifyUrl ?: own.deezerUrl
    }

    /**
     * The track's ids on every provider. A confirmed answer is reused for a
     * day, callers asking about the same track at once share one request, and
     * after song.link says it is asked too often the track's own ids do until
     * the pause ends.
     */
    private fun resolveProviderIdentifiers(track: IdentifiedTrack): ProviderIdentifiers {
        val own = basicProviderIdentifiers(track)
        val identityUrl = own.spotifyUrl ?: own.deezerUrl
            ?: return ProviderIdentifiers(deezerId = null, deezerUrl = null)
        return songLinkMemory.getOrAsk(identityUrl) { askSongLink(identityUrl, own) } ?: own
    }

    /** One request to song.link; null when it did not answer with links (nothing is remembered then). */
    private fun askSongLink(identityUrl: String, own: ProviderIdentifiers): ProviderIdentifiers? = runCatching {
        val endpoint = "https://api.song.link/v1-alpha.1/links?url=" +
            URLEncoder.encode(identityUrl, StandardCharsets.UTF_8.toString())
        hostRequests.incrementAndGet()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = SONG_LINK_TIMEOUT_MS
            readTimeout = SONG_LINK_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Harmony/1.0 Android SpotiFLAC-Resolver")
        }
        try {
            val status = connection.responseCode
            if (status == 429) {
                val wait = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: SONG_LINK_PAUSE_SECONDS
                songLinkMemory.pauseFor(wait.coerceIn(10L, 300L) * 1_000L)
            }
            if (status !in 200..299) return@runCatching null
            val root = JSONObject(
                connection.inputStream.use { SpotiFlacProviderGate.readBounded(it, SONG_LINK_MAX_BYTES) }
                    .toString(Charsets.UTF_8),
            )
            val links = root.optJSONObject("linksByPlatform")
            val spotify = links?.optJSONObject("spotify")
            val qobuz = links?.optJSONObject("qobuz")
            val tidal = links?.optJSONObject("tidal")
            ProviderIdentifiers(
                deezerId = platformTrackId(links?.optJSONObject("deezer")) ?: own.deezerId,
                deezerUrl = links?.optJSONObject("deezer")?.optString("url")
                    ?.takeIf(String::isNotBlank) ?: own.deezerUrl,
                spotifyId = platformTrackId(spotify) ?: own.spotifyId,
                spotifyUrl = spotify?.optString("url")?.takeIf(String::isNotBlank) ?: own.spotifyUrl,
                qobuzId = platformTrackId(qobuz),
                qobuzUrl = qobuz?.optString("url")?.takeIf(String::isNotBlank),
                tidalId = platformTrackId(tidal),
                tidalUrl = tidal?.optString("url")?.takeIf(String::isNotBlank),
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Puts [ids] into a request built before they were known. */
    private fun applyIdentifiers(request: JSONObject, ids: ProviderIdentifiers) {
        val sourceUrl = ids.spotifyUrl ?: ids.deezerUrl.orEmpty()
        request.put("spotify_id", ids.spotifyId.orEmpty())
        request.put("deezer_id", ids.deezerId.orEmpty())
        request.put("qobuz_id", ids.qobuzId.orEmpty())
        request.put("tidal_id", ids.tidalId.orEmpty())
        request.put("source_url", sourceUrl)
        request.put("url", sourceUrl)
        request.put("availability", JSONObject().apply {
            put("deezer_id", ids.deezerId.orEmpty())
            put("spotify_id", ids.spotifyId.orEmpty())
            put("qobuz_id", ids.qobuzId.orEmpty())
            put("tidal_id", ids.tidalId.orEmpty())
        })
    }

    private fun platformTrackId(node: JSONObject?): String? {
        if (node == null) return null
        node.optString("entityUniqueId")
            .substringAfterLast("::")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        val rawUrl = node.optString("url").trim()
        if (rawUrl.isBlank()) return null
        return runCatching {
            val path = URL(rawUrl).path.trimEnd('/')
            path.substringAfterLast('/').substringBefore('?').trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun readVerificationChallengeNative(providerId: String): SpotiFlacVerificationChallenge {
        val authenticated = runCatching {
            nativeControl { backend.isAuthenticated(providerId) }
        }.getOrDefault(false)

        // v4.9.5 exposes a typed PendingAuthRequest. Its javap contract contains
        // exactly ExtensionID + AuthURL + CallbackURL; there is no user-entered
        // verification-code field in this object. Prefer this typed API over
        // guessing a flow type from arbitrary JSON keys.
        val typedPending = runCatching {
            nativeControl { backend.pendingAuth(providerId) }
        }.getOrNull()
        val typedExtensionId = typedPending?.extensionId?.trim().orEmpty()
        val typedAuthUrlRaw = typedPending?.authUrl?.trim().orEmpty()
        val typedCallbackUrl = typedPending?.callbackUrl?.trim().orEmpty()
            .takeIf(String::isNotBlank)
        val typedAuthUrl = typedAuthUrlRaw.takeIf(::isSafeExternalHttpsUrl)
        val typedPendingMeaningful = typedExtensionId.isNotBlank() || typedAuthUrlRaw.isNotBlank() || typedCallbackUrl != null

        // Keep the JSON calls only as a compatibility fallback for builds where
        // the typed proxy is temporarily empty. Never infer an auth-code/session-
        // grant input field from this payload.
        val directRaw = runCatching {
            nativeControl { backend.pendingAuthJson(providerId) }
        }.getOrNull().orEmpty().trim()
        val directPayload = parseJsonValue(directRaw)
        val allRaw = if (hasMeaningfulJson(directPayload)) {
            ""
        } else {
            runCatching { nativeControl { backend.allPendingAuthJson() } }
                .getOrNull().orEmpty().trim()
        }
        val allPayload = parseJsonValue(allRaw)
        val payload = when {
            hasMeaningfulJson(directPayload) -> directPayload
            hasMeaningfulJson(allPayload) -> findProviderAuthNode(allPayload, providerId) ?: allPayload
            else -> null
        }

        val verificationUrl = typedAuthUrl ?: extractSafeVerificationUrl(payload)
        val callbackUrl = typedCallbackUrl ?: firstTextByKeys(
            payload,
            setOf("callback_url", "callbackurl", "redirect_uri", "redirect_url"),
        )?.take(2048)
        val instructions = firstTextByKeys(
            payload,
            setOf("instructions", "instruction", "prompt", "help", "description", "verification_message", "message"),
        )?.take(500)
        val pending = !authenticated && (typedPendingMeaningful || hasMeaningfulJson(payload))

        if (pending) {
            rememberPendingCallbackState(
                providerId = providerId,
                callbackUrl = callbackUrl,
                verificationUrl = verificationUrl,
            )
        } else {
            clearPersistedCallbackState(providerId)
        }

        // Deliberately expose only a non-secret summary. Auth/callback URLs may
        // contain state, PKCE or one-time query parameters and therefore are not
        // copied into Details/Room/logs.
        val safeDetails = JSONObject().apply {
            put("provider", providerId)
            put("authenticated", authenticated)
            put("pending", pending)
            put("typed_pending_auth_present", typedPendingMeaningful)
            put("typed_extension_matches", typedExtensionId.isBlank() || typedExtensionId.equals(providerId, ignoreCase = true))
            put("has_auth_url", verificationUrl != null)
            put("has_callback_url", callbackUrl != null)
            callbackUrl?.let { raw ->
                runCatching { URI(raw) }.getOrNull()?.let { uri ->
                    if (!uri.scheme.isNullOrBlank()) put("callback_scheme", uri.scheme.lowercase(Locale.ROOT))
                    if (!uri.host.isNullOrBlank()) put("callback_host", uri.host.lowercase(Locale.ROOT))
                }
            }
            put("direct_pending_auth_present", hasMeaningfulJson(directPayload))
            put("all_pending_auth_fallback_used", !hasMeaningfulJson(directPayload) && hasMeaningfulJson(allPayload))
        }.toString(2)

        return SpotiFlacVerificationChallenge(
            providerId = providerId,
            authenticated = authenticated,
            pending = pending,
            verificationUrl = verificationUrl,
            callbackUrl = callbackUrl,
            instructions = instructions,
            safeDetails = safeDetails,
        )
    }

    private fun rememberPendingCallbackState(
        providerId: String,
        callbackUrl: String?,
        verificationUrl: String?,
    ) {
        val callbackState = sequenceOf(callbackUrl, verificationUrl)
            .filterNotNull()
            .mapNotNull { raw ->
                runCatching { Uri.parse(raw) }.getOrNull()?.let { uri ->
                    firstCallbackParameter(uri, "state") ?: firstNestedCallbackParameter(uri, "state")
                }
            }
            .firstOrNull()
            ?.trim()
            .orEmpty()
        if (callbackState.isBlank()) return

        val digest = callbackStateDigest(callbackState)
        val existingProvider = providerPrefs.getString(KEY_PENDING_CALLBACK_PROVIDER, null)
        val existingDigest = providerPrefs.getString(KEY_PENDING_CALLBACK_STATE_DIGEST, null)
        if (existingProvider.equals(providerId, ignoreCase = true) && existingDigest == digest) return

        providerPrefs.edit()
            .putString(KEY_PENDING_CALLBACK_PROVIDER, providerId)
            .putString(KEY_PENDING_CALLBACK_STATE_DIGEST, digest)
            .putLong(KEY_PENDING_CALLBACK_CREATED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    private fun resolvePersistedCallbackProvider(callbackState: String): String? {
        val providerId = providerPrefs.getString(KEY_PENDING_CALLBACK_PROVIDER, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val expectedDigest = providerPrefs.getString(KEY_PENDING_CALLBACK_STATE_DIGEST, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val createdAtMs = providerPrefs.getLong(KEY_PENDING_CALLBACK_CREATED_AT_MS, 0L)
        val ageMs = System.currentTimeMillis() - createdAtMs
        if (createdAtMs <= 0L || ageMs < 0L || ageMs >= PENDING_CALLBACK_STATE_TTL_MS) {
            clearPersistedCallbackState(providerId)
            return null
        }

        val actualDigest = callbackStateDigest(callbackState)
        val matches = MessageDigest.isEqual(
            expectedDigest.toByteArray(StandardCharsets.US_ASCII),
            actualDigest.toByteArray(StandardCharsets.US_ASCII),
        )
        return providerId.takeIf { matches }
    }

    private fun clearPersistedCallbackState(providerId: String? = null) {
        val storedProvider = providerPrefs.getString(KEY_PENDING_CALLBACK_PROVIDER, null)
        if (!providerId.isNullOrBlank() &&
            !storedProvider.isNullOrBlank() &&
            !storedProvider.equals(providerId, ignoreCase = true)
        ) {
            return
        }
        providerPrefs.edit()
            .remove(KEY_PENDING_CALLBACK_PROVIDER)
            .remove(KEY_PENDING_CALLBACK_STATE_DIGEST)
            .remove(KEY_PENDING_CALLBACK_CREATED_AT_MS)
            .apply()
    }

    private fun callbackStateDigest(callbackState: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(callbackState.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private fun firstCallbackParameter(uri: Uri, vararg keys: String): String? {
        for (key in keys) {
            runCatching { uri.getQueryParameter(key) }
                .getOrNull()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
        }

        val fragment = uri.fragment?.trim().orEmpty()
        if (fragment.isNotBlank()) {
            val fragmentUri = runCatching { Uri.parse("harmony://callback?$fragment") }.getOrNull()
            if (fragmentUri != null) {
                for (key in keys) {
                    runCatching { fragmentUri.getQueryParameter(key) }
                        .getOrNull()
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let { return it }
                }
            }
        }
        return null
    }

    private fun firstNestedCallbackParameter(uri: Uri, vararg keys: String): String? {
        val nested = firstCallbackParameter(uri, "cb") ?: return null
        val nestedUri = runCatching { Uri.parse(nested) }.getOrNull() ?: return null
        return firstCallbackParameter(nestedUri, *keys)
    }

    private fun parseJsonValue(raw: String): Any? {
        if (raw.isBlank() || raw.equals("null", ignoreCase = true)) return null
        return runCatching {
            when {
                raw.startsWith("{") -> JSONObject(raw)
                raw.startsWith("[") -> JSONArray(raw)
                else -> null
            }
        }.getOrNull()
    }

    private fun hasMeaningfulJson(value: Any?): Boolean = when (value) {
        is JSONObject -> value.length() > 0
        is JSONArray -> value.length() > 0
        else -> false
    }

    private fun findProviderAuthNode(node: Any?, providerId: String, depth: Int = 0): Any? {
        if (node == null || depth > 8) return null
        val target = providerId.lowercase(Locale.ROOT)
        return when (node) {
            is JSONObject -> {
                val identity = sequenceOf("extension_id", "extensionId", "provider", "provider_id", "service", "id")
                    .map { node.optString(it).trim().lowercase(Locale.ROOT) }
                    .firstOrNull { it.isNotBlank() && (it == target || it.startsWith(target) || target.startsWith(it)) }
                if (identity != null) {
                    node
                } else {
                    val keys = node.keys()
                    var found: Any? = null
                    while (keys.hasNext() && found == null) {
                        val key = keys.next()
                        val value = node.opt(key)
                        if (key.lowercase(Locale.ROOT) == target) {
                            found = value
                        } else if (value is JSONObject || value is JSONArray) {
                            found = findProviderAuthNode(value, providerId, depth + 1)
                        }
                    }
                    found
                }
            }
            is JSONArray -> {
                var found: Any? = null
                for (index in 0 until node.length()) {
                    found = findProviderAuthNode(node.opt(index), providerId, depth + 1)
                    if (found != null) break
                }
                found
            }
            else -> null
        }
    }

    private fun firstTextByKeys(node: Any?, wanted: Set<String>, depth: Int = 0): String? {
        if (node == null || depth > 8) return null
        return when (node) {
            is JSONObject -> {
                val keys = node.keys()
                var nested: String? = null
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (key.lowercase(Locale.ROOT) in wanted && value is String && value.isNotBlank()) {
                        return value.trim()
                    }
                    if (nested == null && (value is JSONObject || value is JSONArray)) {
                        nested = firstTextByKeys(value, wanted, depth + 1)
                    }
                }
                nested
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    firstTextByKeys(node.opt(index), wanted, depth + 1)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun collectTextForKeys(
        node: Any?,
        wanted: Set<String>,
        out: MutableList<String>,
        depth: Int,
    ) {
        if (node == null || depth > 8) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (key.lowercase(Locale.ROOT) in wanted && value is String && value.isNotBlank()) {
                        out += value.trim()
                    }
                    if (value is JSONObject || value is JSONArray) {
                        collectTextForKeys(value, wanted, out, depth + 1)
                    }
                }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                collectTextForKeys(node.opt(index), wanted, out, depth + 1)
            }
        }
    }

    private fun verificationProviderId(response: JSONObject): String? {
        val raw = response.optString("service")
            .ifBlank { response.optString("provider") }
            .trim()
        if (raw.isBlank()) return null
        val normalized = raw.lowercase(Locale.US)
        return PROVIDER_PRIORITY.firstOrNull { provider ->
            normalized == provider.lowercase(Locale.US) ||
                normalized.startsWith(provider.lowercase(Locale.US)) ||
                providerDisplay(provider).lowercase(Locale.US) in normalized
        }
    }

    /**
     * Providers may surface browser/signed-session challenges in different JSON
     * shapes. Harmony only exposes an URL if it is HTTPS, contains no embedded
     * credentials and is not a localhost/private-network target. This mirrors
     * the upstream security model instead of blindly opening arbitrary strings.
     */
    private fun extractSafeVerificationUrl(response: JSONObject): String? =
        extractSafeVerificationUrl(response as Any?)

    private fun extractSafeVerificationUrl(node: Any?): String? {
        val candidates = LinkedHashSet<String>()
        collectVerificationUrls(node, candidates, depth = 0)

        val urlRegex = Regex("https://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        collectLikelyVerificationMessages(node).forEach { text ->
            urlRegex.findAll(text).forEach { match ->
                candidates += match.value.trimEnd('.', ',', ';', ')', ']')
            }
        }
        return candidates.firstOrNull(::isSafeExternalHttpsUrl)
    }

    private fun collectLikelyVerificationMessages(node: Any?, depth: Int = 0): List<String> {
        if (node == null || depth > 6) return emptyList()
        val out = mutableListOf<String>()
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (value is String && key.lowercase(Locale.ROOT) in setOf(
                            "error", "message", "instructions", "instruction", "help", "description"
                        )
                    ) {
                        val lower = value.lowercase(Locale.ROOT)
                        if ("verification" in lower || "challenge" in lower || "auth" in lower || "signed session" in lower) {
                            out += value
                        }
                    }
                    if (value is JSONObject || value is JSONArray) {
                        out += collectLikelyVerificationMessages(value, depth + 1)
                    }
                }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                out += collectLikelyVerificationMessages(node.opt(index), depth + 1)
            }
        }
        return out
    }

    private fun collectVerificationUrls(node: Any?, out: MutableSet<String>, depth: Int) {
        if (node == null || depth > 6) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (value is String) {
                        val normalizedKey = key.lowercase(Locale.ROOT)
                        if (("verification" in normalizedKey || "challenge" in normalizedKey || "auth" in normalizedKey || normalizedKey == "url") && value.startsWith("https://", ignoreCase = true)) {
                            out += value
                        }
                    }
                    if (value is JSONObject || value is JSONArray) {
                        collectVerificationUrls(value, out, depth + 1)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val value = node.opt(i)
                if (value is JSONObject || value is JSONArray) collectVerificationUrls(value, out, depth + 1)
            }
        }
    }

    private fun isSafeExternalHttpsUrl(raw: String): Boolean = SpotiFlacUrlSafety.isPublicHttpsUrl(raw)

    private fun friendlyBackendFailure(response: JSONObject, diagnostics: JSONArray? = null): SpotiFlacException {
        val type = response.optString("error_type").takeIf(String::isNotBlank)
        val provider = response.optString("service").takeIf(String::isNotBlank)
            ?: response.optString("provider").takeIf(String::isNotBlank)
        val raw = response.optString("error").ifBlank { response.optString("message") }
        val message = when (type?.lowercase(Locale.ROOT)) {
            "verification_required" -> "${providerDisplay(provider)} requires interactive provider verification. Harmony did not bypass it or switch to Soulseek."
            "isp_blocked" -> "The selected SpotiFLAC provider appears to be blocked by the current network or region."
            "provider_timeout" -> "SpotiFLAC providers timed out before returning a complete lossless file."
            "rate_limit", "too_many_requests" -> {
                val wait = retryAfterSeconds(response, MAX_GATEWAY_RATE_LIMIT_AUTO_RETRIES)
                "The SpotiFLAC provider gateway is still rate-limited (HTTP 429) after Harmony's paced retry. Wait about ${wait}s before trying again."
            }
            "cancelled" -> "SpotiFLAC download cancelled."
            "not_found", "song_not_found" -> "SpotiFLAC found the track metadata, but the enabled lossless providers did not return a matching downloadable file."
            else -> raw.ifBlank { "SpotiFLAC could not download this track from the enabled lossless providers." }
        }
        val technical = JSONObject().apply {
            put("registry", activeRegistrySummary)
            put("last_response", response)
            diagnostics?.let { put("attempts", it) }
        }.toString(2)
        return SpotiFlacException(
            message = message,
            errorType = type,
            technicalDetails = technical,
            provider = provider,
            verificationUrl = if (type.equals("verification_required", ignoreCase = true)) extractSafeVerificationUrl(response) else null,
        )
    }

    private data class AudioOutputProbe(
        val isNativeFlac: Boolean,
        val detectedFormat: String,
        val displayName: String,
        val reportedCodec: String,
        val effectiveCodec: String,
        val reportedExtension: String,
        val actualContainer: String,
        val requiresContainerConversion: Boolean,
        val isLosslessSource: Boolean,
        val canFinalizeLosslessly: Boolean,
        val fileSize: Long,
        val magicHex: String,
    )

    private data class LosslessFinalizationResult(
        val outputFile: File?,
        val diagnostic: JSONObject,
    )

    /** Uses the existing verified FFmpeg runtime, timeout and cancellation for Amazon only. */
    private suspend fun finishAmazonDownload(
        response: JSONObject, source: File, jobDir: File,
    ): AmazonDownloadFinalizer.Completed = withContext(Dispatchers.IO) {
        try {
            prepareFfmpegRuntime()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AmazonDownloadFinalizer.Failure("runtime", "FFmpeg could not be prepared for Amazon Music on this phone.")
        }
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val executable = listOf(File(nativeDir, "libffmpeg.so"), File(nativeDir, "libffmpeg.bin.so"))
            .firstOrNull { it.isFile && it.length() > 0L }
            ?: throw AmazonDownloadFinalizer.Failure("runtime", "FFmpeg is missing from this installation.")
        if (!executable.canExecute()) runCatching { executable.setExecutable(true, false) }
        AmazonDownloadFinalizer.finish("amazon", response, source, jobDir) { arguments ->
            var result = AmazonDownloadFinalizer.Execution(false, "")
            for (profile in FfmpegEnvironmentProfile.entries) {
                val log = File(jobDir, "harmony-amazon-${UUID.randomUUID()}.log")
                val attempt = try {
                    runFfmpegProcess(
                        listOf(executable.absolutePath) + arguments, log, nativeDir,
                        LOSSLESS_FINALIZER_TIMEOUT_SECONDS, profile,
                    )
                } finally {
                    // A failed FFmpeg invocation can echo input options. Never keep its log.
                    log.delete()
                }
                result = AmazonDownloadFinalizer.Execution(attempt.success, attempt.logTail)
                if (attempt.success) break
            }
            result
        }
    }

    private data class DownloadDecryptionInfo(
        val normalizedStrategy: String,
        val keyLength: Int,
        val inputFormat: String?,
        val outputExtension: String?,
        val source: String,
    ) {
        fun toSafeJson(): JSONObject = JSONObject().apply {
            put("strategy", normalizedStrategy)
            put("key_present", keyLength > 0)
            put("key_length", keyLength)
            inputFormat?.let { put("input_format", it) }
            outputExtension?.let { put("output_extension", it) }
            put("descriptor_source", source)
            put("key_redacted", true)
        }
    }

    /**
     * Mirrors only the descriptor *detection* portion of SpotiFLAC Mobile's
     * DownloadDecryptionDescriptor.fromDownloadResult(). No key material is
     * returned to diagnostics. Execution is restricted to the Amazon path above.
     */
    private fun inspectDownloadDecryption(response: JSONObject): DownloadDecryptionInfo? {
        val descriptor = response.optJSONObject("decryption")
        if (descriptor != null) {
            val rawStrategy = descriptor.optString("strategy").trim()
            val key = descriptor.optString("key").trim()
            val normalizedStrategy = normalizeDecryptionStrategy(rawStrategy)
            if (key.isNotEmpty()) {
                return DownloadDecryptionInfo(
                    normalizedStrategy = normalizedStrategy,
                    keyLength = key.length,
                    inputFormat = descriptor.optString("input_format").trim().ifBlank { null },
                    outputExtension = descriptor.optString("output_extension")
                        .trim().ifBlank { response.optString("output_extension").trim() }
                        .ifBlank { null },
                    source = "decryption",
                )
            }
        }

        val legacyKey = response.optString("decryption_key").trim()
        if (legacyKey.isNotEmpty()) {
            return DownloadDecryptionInfo(
                normalizedStrategy = "ffmpeg.mov_key",
                keyLength = legacyKey.length,
                inputFormat = "mov",
                outputExtension = response.optString("output_extension").trim().ifBlank { null },
                source = "decryption_key",
            )
        }
        return null
    }

    private fun normalizeDecryptionStrategy(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
        "", "ffmpeg.mov_key", "ffmpeg_mov_key", "mov_decryption_key",
        "mp4_decryption_key", "ffmpeg.mp4_decryption_key" -> "ffmpeg.mov_key"
        else -> raw.trim()
    }

    private fun resolveOutputFile(
        jobDir: File,
        reportedPath: String,
        reportedName: String = "",
    ): File? {
        // Prefer the exact path returned by the backend when it is a local file,
        // but only inside this download's own staging folder: a path in a
        // provider's answer must never make Harmony read, convert or move
        // another file.
        if (reportedPath.isNotBlank() && !reportedPath.startsWith("content://", ignoreCase = true)) {
            SpotiFlacJobFiles.owned(File(reportedPath), jobDir)?.let { return it }
        }

        // The same rule for whatever is found by looking: regular files of this
        // job, links resolved, nothing reached through a link out of the folder.
        val candidates = SpotiFlacJobFiles.all(jobDir).asSequence()
            .filterNot { file ->
                val lower = file.name.lowercase(Locale.ROOT)
                lower.contains(".partial") || lower.endsWith(".part") ||
                    lower.endsWith(".tmp") || lower.endsWith(".lrc") ||
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") || lower.endsWith(".webp") ||
                    lower.endsWith(".json") || lower.endsWith(".txt") ||
                    lower.endsWith(".log")
            }
            .toList()

        if (candidates.isEmpty()) return null

        // If a native FLAC exists anywhere in this per-download staging folder,
        // select it even when a larger cover/sidecar/container also exists.
        candidates.firstOrNull { hasNativeFlacSignature(it) }?.let { return it }

        val safeReportedName = reportedName.takeIf(String::isNotBlank)?.let { File(it).name }
        if (safeReportedName != null) {
            candidates.firstOrNull { it.name == safeReportedName }?.let { return it }
        }

        val knownAudio = candidates.filter { it.extension.lowercase(Locale.ROOT) in KNOWN_AUDIO_EXTENSIONS }
        return (knownAudio.ifEmpty { candidates })
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.length() })
            .firstOrNull()
    }

    private fun probeAudioOutput(file: File, response: JSONObject): AudioOutputProbe {
        val prefix = ByteArray(512 * 1024)
        val read = runCatching { FileInputStream(file).use { it.read(prefix) } }.getOrDefault(-1)
        val bytes = if (read > 0) prefix.copyOf(read) else byteArrayOf()
        val reportedCodec = normalizeAudioCodec(response.optString("audio_codec"))
        val actualExt = response.optString("actual_extension")
            .ifBlank { response.optString("output_ext") }
            .ifBlank { file.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty() }
            .trim()
            .lowercase(Locale.ROOT)
        val actualContainer = response.optString("actual_container")
            .trim()
            .lowercase(Locale.ROOT)
        val requiresConversion = response.optBoolean("requires_container_conversion", false)

        val nativeFlac = bytes.size >= 4 &&
            bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()

        val detected = when {
            nativeFlac -> "flac"
            bytes.size >= 12 && bytes.copyOfRange(4, 8).toString(StandardCharsets.US_ASCII) == "ftyp" -> "mp4/m4a"
            bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "OggS" -> "ogg"
            bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" -> "wav/riff"
            bytes.size >= 3 && bytes.copyOfRange(0, 3).toString(StandardCharsets.US_ASCII) == "ID3" -> "mp3/id3"
            bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
                bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte() -> "webm/matroska"
            bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && ((bytes[1].toInt() and 0xF6) == 0xF0) -> "aac/adts"
            looksLikeHtml(bytes) -> "html"
            looksLikeJson(bytes) -> "json"
            else -> actualExt.removePrefix(".").ifBlank { reportedCodec.ifBlank { "unknown" } }
        }

        val containerCodec = if (detected == "mp4/m4a") detectMp4CodecHint(bytes) else ""
        val effectiveCodec = when {
            nativeFlac -> "flac"
            reportedCodec in GENERIC_CONTAINER_CODEC_LABELS && containerCodec.isNotBlank() -> containerCodec
            reportedCodec.isNotBlank() -> reportedCodec
            containerCodec.isNotBlank() -> containerCodec
            else -> ""
        }
        val lossless = nativeFlac || effectiveCodec in LOSSLESS_SOURCE_CODECS
        val canFinalize = !nativeFlac && detected == "mp4/m4a" && effectiveCodec in LOSSLESS_SOURCE_CODECS

        val display = when (detected) {
            "flac" -> "native FLAC"
            "mp4/m4a" -> if (effectiveCodec.isNotBlank()) "M4A/MP4 ($effectiveCodec)" else "M4A/MP4"
            "ogg" -> if (effectiveCodec.isNotBlank()) "Ogg ($effectiveCodec)" else "Ogg/Opus"
            "wav/riff" -> "WAV"
            "mp3/id3" -> "MP3"
            "webm/matroska" -> if (effectiveCodec.isNotBlank()) "WebM/Matroska ($effectiveCodec)" else "WebM/Matroska"
            "aac/adts" -> "AAC"
            "html" -> "an HTML error page"
            "json" -> "a JSON response"
            else -> buildString {
                append(if (actualExt.isNotBlank()) actualExt.uppercase(Locale.ROOT).removePrefix(".") else detected.uppercase(Locale.ROOT))
                if (effectiveCodec.isNotBlank() && effectiveCodec !in detected) append(" ($effectiveCodec)")
            }
        }

        return AudioOutputProbe(
            isNativeFlac = nativeFlac,
            detectedFormat = detected,
            displayName = display,
            reportedCodec = reportedCodec,
            effectiveCodec = effectiveCodec,
            reportedExtension = actualExt,
            actualContainer = actualContainer,
            requiresContainerConversion = requiresConversion,
            isLosslessSource = lossless,
            canFinalizeLosslessly = canFinalize,
            fileSize = file.length(),
            magicHex = bytes.take(16).joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 0xFF) },
        )
    }

    private fun normalizeAudioCodec(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        if (value.isBlank()) return ""
        return when {
            value == "flac" || value.contains("flac") -> "flac"
            value == "alac" || value.contains("alac") -> "alac"
            value == "aac" || value.contains("mp4a") || value.contains("aac") -> "aac"
            value == "opus" || value.contains("opus") -> "opus"
            value == "vorbis" || value.contains("vorbis") -> "vorbis"
            value == "eac3" || value == "e-ac-3" || value.contains("ec-3") -> "eac3"
            value == "ac3" || value.contains("ac-3") -> "ac3"
            value == "ac4" || value.contains("ac-4") -> "ac4"
            value == "mp3" || value.contains("mpeg layer 3") -> "mp3"
            else -> value
        }
    }

    private fun detectMp4CodecHint(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val ascii = bytes.toString(StandardCharsets.ISO_8859_1)
        return when {
            ascii.contains("fLaC") -> "flac"
            ascii.contains("alac") -> "alac"
            ascii.contains("mp4a") -> "aac"
            ascii.contains("Opus") || ascii.contains("dOps") -> "opus"
            ascii.contains("ec-3") -> "eac3"
            ascii.contains("ac-3") -> "ac3"
            ascii.contains("ac-4") -> "ac4"
            else -> ""
        }
    }

    private fun putAudioProbeDiagnostic(target: JSONObject, probe: AudioOutputProbe) {
        target.put("detected_format", probe.detectedFormat)
        target.put("reported_codec", probe.reportedCodec)
        target.put("effective_codec", probe.effectiveCodec)
        target.put("reported_extension", probe.reportedExtension)
        target.put("actual_container", probe.actualContainer)
        target.put("requires_container_conversion", probe.requiresContainerConversion)
        target.put("lossless_source", probe.isLosslessSource)
        target.put("can_finalize_losslessly", probe.canFinalizeLosslessly)
        target.put("file_size", probe.fileSize)
        target.put("magic", probe.magicHex)
    }

    private suspend fun finalizeLosslessContainerToFlac(
        source: File,
        jobDir: File,
        probe: AudioOutputProbe,
    ): LosslessFinalizationResult = withContext(Dispatchers.IO) {
        val diagnostic = JSONObject().apply {
            put("requested", true)
            put("source_format", probe.detectedFormat)
            put("source_codec", probe.effectiveCodec)
            put("lossless_source", probe.isLosslessSource)
            put("source_size", source.length())
            put("host_finalizer", "v1.1.0-isolated-ffmpeg-source-fallback")
            put("device_manufacturer", Build.MANUFACTURER)
            put("device_model", Build.MODEL)
            put("android_sdk", Build.VERSION.SDK_INT)
            put("android_release", Build.VERSION.RELEASE ?: "")
            put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        }
        if (!probe.canFinalizeLosslessly) {
            diagnostic.put("success", false)
            diagnostic.put("reason", "source codec/container is not eligible for lossless M4A/MP4 to FLAC finalization")
            return@withContext LosslessFinalizationResult(null, diagnostic)
        }

        // Direct FFmpeg needs shared libraries from both bundled archives,
        // but not Python/yt-dlp initialization. Use a verified private layout.
        val initFailure = runCatching {
            prepareFfmpegRuntime()
        }.exceptionOrNull()
        if (initFailure != null) {
            diagnostic.put("success", false)
            diagnostic.put("reason", "FFmpeg initialization failed")
            diagnostic.put("error", initFailure.message ?: initFailure.javaClass.simpleName)
            return@withContext LosslessFinalizationResult(null, diagnostic)
        }

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val executable = listOf(
            File(nativeDir, "libffmpeg.so"),
            File(nativeDir, "libffmpeg.bin.so"),
        ).firstOrNull { it.isFile }
        if (executable == null) {
            diagnostic.put("success", false)
            diagnostic.put("reason", "bundled FFmpeg executable not found")
            diagnostic.put("native_library_dir", nativeDir.absolutePath)
            return@withContext LosslessFinalizationResult(null, diagnostic)
        }

        diagnostic.put("ffmpeg_binary", executable.name)
        diagnostic.put("ffmpeg_binary_size", executable.length())
        diagnostic.put("ffmpeg_binary_can_execute_before", executable.canExecute())
        if (!executable.canExecute()) {
            runCatching { executable.setExecutable(true, false) }
        }
        diagnostic.put("ffmpeg_binary_can_execute", executable.canExecute())
        diagnostic.put("native_library_dir", nativeDir.absolutePath)

        val attempts = JSONArray()
        diagnostic.put("attempts", attempts)

        // Prefer FFmpeg's own libraries. Keep the wrapper's historical linker
        // environments as bounded fallbacks, not as phone-model assumptions.
        val selfTests = JSONArray()
        diagnostic.put("ffmpeg_self_tests", selfTests)
        var selectedEnvironment = FfmpegEnvironmentProfile.ISOLATED
        var selfTest: FfmpegProcessResult? = null
        for (profile in FfmpegEnvironmentProfile.entries) {
            val selfTestLog = File(jobDir, "harmony-ffmpeg-selftest-${profile.id}.log").also { it.delete() }
            val candidate = runFfmpegProcess(
                command = listOf(executable.absolutePath, "-hide_banner", "-version"),
                log = selfTestLog,
                nativeDir = nativeDir,
                timeoutSeconds = 20L,
                environmentProfile = profile,
            )
            selfTests.put(candidate.toJson("self-test").apply { put("environment_profile", profile.id) })
            runCatching { selfTestLog.delete() }
            if (candidate.success) {
                selectedEnvironment = profile
                selfTest = candidate
                break
            }
        }
        diagnostic.put("ffmpeg_environment_profile", selectedEnvironment.id)
        if (selfTest?.success != true) {
            diagnostic.put("success", false)
            diagnostic.put("reason", "bundled FFmpeg failed its launch self-test on this device")
            return@withContext LosslessFinalizationResult(null, diagnostic)
        }

        fun freshOutput(): File = File(jobDir, "harmony-finalized-${UUID.randomUUID()}.flac").also { it.delete() }

        // A FLAC elementary stream wrapped by MP4 does not need audio transcoding.
        // First try a bit-exact remux so the FLAC frames are preserved exactly.
        if (probe.effectiveCodec == "flac") {
            val output = freshOutput()
            val log = File(jobDir, "harmony-ffmpeg-remux.log").also { it.delete() }
            val command = listOf(
                executable.absolutePath,
                "-v", "error", "-xerror", "-nostdin", "-hide_banner", "-y",
                "-i", source.absolutePath,
                "-map", "0:a:0", "-vn", "-sn", "-dn",
                "-c:a", "copy",
                "-f", "flac",
                output.absolutePath,
            )
            val attempt = runFfmpegProcess(
                command, log, nativeDir, LOSSLESS_FINALIZER_TIMEOUT_SECONDS, selectedEnvironment,
            )
            val attemptJson = attempt.toJson("flac-stream-copy-remux").apply {
                put("environment_profile", selectedEnvironment.id)
                put("command_policy", "bit-exact FLAC stream copy from MP4/M4A to native FLAC")
                put("output_exists", output.isFile)
                put("output_size", if (output.isFile) output.length() else 0L)
                put("native_flac", output.isFile && hasNativeFlacSignature(output))
            }
            attempts.put(attemptJson)
            runCatching { log.delete() }
            if (attempt.success && output.isFile && output.length() >= 42L && hasNativeFlacSignature(output)) {
                diagnostic.put("success", true)
                diagnostic.put("method", "flac-stream-copy-remux")
                diagnostic.put("output_size", output.length())
                diagnostic.put("final_magic", "66 4C 61 43")
                if (source.absolutePath != output.absolutePath) runCatching { source.delete() }
                return@withContext LosslessFinalizationResult(output, diagnostic)
            }
            runCatching { output.delete() }
        }

        // Match SpotiFLAC Mobile's documented local M4A->FLAC path as closely as
        // practical: decode only the audio stream and encode native FLAC at level 8.
        // This remains lossless because we enter this branch only after the source
        // codec has been classified as FLAC or ALAC.
        val output = freshOutput()
        val log = File(jobDir, "harmony-ffmpeg-flac-encode.log").also { it.delete() }
        val command = listOf(
            executable.absolutePath,
            "-v", "error", "-xerror", "-nostdin", "-hide_banner", "-y",
            "-i", source.absolutePath,
            "-map", "0:a:0", "-vn", "-sn", "-dn",
            "-c:a", "flac", "-compression_level", "8",
            "-f", "flac",
            output.absolutePath,
        )
        var attempt = runFfmpegProcess(
            command, log, nativeDir, LOSSLESS_FINALIZER_TIMEOUT_SECONDS, selectedEnvironment,
        )
        var usedEnvironment = selectedEnvironment
        for (alternate in FfmpegEnvironmentProfile.entries.filter { it != selectedEnvironment }) {
            if (attempt.success) break
            val retryLog = File(jobDir, "harmony-ffmpeg-flac-encode-${alternate.id}.log").also { it.delete() }
            val retry = runFfmpegProcess(
                command, retryLog, nativeDir, LOSSLESS_FINALIZER_TIMEOUT_SECONDS, alternate,
            )
            attempts.put(retry.toJson("lossless-flac-encode-retry").apply {
                put("environment_profile", alternate.id)
                put("command_policy", "same lossless FLAC encode retried with alternate Android linker environment")
            })
            runCatching { retryLog.delete() }
            if (retry.success) {
                attempt = retry
                usedEnvironment = alternate
            }
        }
        val attemptJson = attempt.toJson("lossless-flac-encode").apply {
            put("environment_profile", usedEnvironment.id)
            put("command_policy", "lossless decode/re-encode to native FLAC; no lossy source codecs allowed")
            put("output_exists", output.isFile)
            put("output_size", if (output.isFile) output.length() else 0L)
            put("native_flac", output.isFile && hasNativeFlacSignature(output))
        }
        attempts.put(attemptJson)
        runCatching { log.delete() }

        if (!attempt.success || !output.isFile || output.length() < 42L || !hasNativeFlacSignature(output)) {
            runCatching { output.delete() }
            diagnostic.put("success", false)
            diagnostic.put(
                "reason",
                when {
                    attempt.timedOut -> "FFmpeg finalization timed out"
                    attempt.startError != null -> "could not start bundled FFmpeg"
                    attempt.exitCode != 0 -> "FFmpeg returned a non-zero exit code"
                    else -> "final output did not contain native fLaC bytes"
                },
            )
            return@withContext LosslessFinalizationResult(null, diagnostic)
        }

        diagnostic.put("success", true)
        diagnostic.put("method", "lossless-flac-encode")
        diagnostic.put("output_size", output.length())
        diagnostic.put("final_magic", "66 4C 61 43")
        if (source.absolutePath != output.absolutePath) runCatching { source.delete() }
        LosslessFinalizationResult(output, diagnostic)
    }

    /** Enforce the output ceiling even when an extension ignores the requested quality. */
    private suspend fun enforceFlacQualityLimit(
        source: File,
        jobDir: File,
        format: SpotiFlacOutputFormat,
        provider: String?,
        onProgress: (SpotiFlacTransferProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val sourceSpec = SpotiFlacQualityPolicy.read(source)
        val target = SpotiFlacQualityPolicy.target(sourceSpec, format)
        if (target.matches(sourceSpec)) return@withContext source

        onProgress(SpotiFlacTransferProgress(
            stage = SpotiFlacStage.FINALIZING,
            provider = provider,
            detail = "Reducing ${sourceSpec.label} to ${target.bitDepth}-bit / ${target.sampleRateHz / 1000.0} kHz…",
        ))
        try {
            prepareFfmpegRuntime()
        } catch (failure: Exception) {
            throw SpotiFlacException(
                "FFmpeg could not initialize to apply the selected quality limit.",
                "flac_quality_limit_failed", cause = failure, provider = provider,
                technicalDetails = failure.stackTraceToString(),
            )
        }
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val executable = listOf(File(nativeDir, "libffmpeg.so"), File(nativeDir, "libffmpeg.bin.so"))
            .firstOrNull { it.isFile && it.length() > 0L }
            ?: throw SpotiFlacException("FFmpeg is missing; the selected FLAC quality limit could not be applied.", "flac_quality_limit_failed")
        if (!executable.canExecute()) executable.setExecutable(true, false)

        val output = File(jobDir, "harmony-quality-${UUID.randomUUID()}.flac")
        val diagnostics = JSONArray()
        // Keep provider text tags and any attached cover before Harmony enriches them.
        val command = listOf(
            executable.absolutePath, "-v", "error", "-xerror", "-nostdin", "-hide_banner", "-y",
            "-i", source.absolutePath,
            "-map", "0:a:0", "-map", "0:v?", "-map_metadata", "0", "-c:v", "copy",
        ) + target.encoderArguments() + listOf("-f", "flac", output.absolutePath)
        for (environment in FfmpegEnvironmentProfile.entries) {
            val log = File(jobDir, "harmony-quality-${environment.id}.log")
            val attempt = runFfmpegProcess(command, log, nativeDir, LOSSLESS_FINALIZER_TIMEOUT_SECONDS, environment)
            val validation = if (attempt.success) runCatching {
                verifyFlacSignature(output)
                SpotiFlacQualityPolicy.validateConversion(sourceSpec, SpotiFlacQualityPolicy.read(output), target)
            } else null
            diagnostics.put(attempt.toJson("flac-quality-limit").apply {
                put("environment", environment.id)
                put("validation_error", validation?.exceptionOrNull()?.message.orEmpty())
            })
            log.delete()
            if (validation?.isSuccess == true) {
                source.delete()
                return@withContext output
            }
            output.delete()
        }
        throw SpotiFlacException(
            message = "The lossless source downloaded, but Harmony could not apply ${format.label}. The file was not imported.",
            errorType = "flac_quality_limit_failed",
            provider = provider,
            technicalDetails = diagnostics.toString(2),
        )
    }

    private data class FfmpegProcessResult(
        val exitCode: Int,
        val timedOut: Boolean,
        val logTail: String,
        val startError: String? = null,
    ) {
        val success: Boolean get() = startError == null && !timedOut && exitCode == 0

        fun toJson(stage: String): JSONObject = JSONObject().apply {
            put("stage", stage)
            put("success", success)
            put("exit_code", exitCode)
            put("timed_out", timedOut)
            if (!startError.isNullOrBlank()) put("start_error", startError)
            if (logTail.isNotBlank()) put("ffmpeg_log_tail", logTail)
        }
    }

    private fun prepareFfmpegRuntime() {
        if (ffmpegPackagesDir != null) return
        check(File(context.applicationInfo.nativeLibraryDir, "libc++_shared.so").isFile) {
            "The APK is missing libc++_shared.so required by FFmpeg. Rebuild with the shared NDK runtime."
        }
        ffmpegPackagesDir = SpotiFlacFfmpegRuntime.prepare(
            File(context.applicationInfo.nativeLibraryDir),
            File(context.noBackupFilesDir, "spotiflac/ffmpeg-libraries-v1"),
        )
    }

    private suspend fun runFfmpegProcess(
        command: List<String>,
        log: File,
        nativeDir: File,
        timeoutSeconds: Long,
        environmentProfile: FfmpegEnvironmentProfile = FfmpegEnvironmentProfile.ISOLATED,
    ): FfmpegProcessResult {
        val process = runCatching {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .apply {
                    SpotiFlacFfmpegEnvironment.configure(
                        environment(),
                        checkNotNull(ffmpegPackagesDir) { "FFmpeg libraries were not prepared" },
                        nativeDir, context.cacheDir, environmentProfile,
                    )
                }
                .start()
        }.getOrElse { failure ->
            return FfmpegProcessResult(
                exitCode = -1,
                timedOut = false,
                logTail = runCatching { log.readText().takeLast(4000) }.getOrDefault(""),
                startError = failure.message ?: failure.javaClass.simpleName,
            )
        }

        val finished = awaitDownloadProcess(process, timeoutSeconds * 1_000L)
        if (!finished) {
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            return FfmpegProcessResult(
                exitCode = -1,
                timedOut = true,
                logTail = runCatching { log.readText().takeLast(4000) }.getOrDefault(""),
            )
        }

        return FfmpegProcessResult(
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
            timedOut = false,
            logTail = runCatching { log.readText().takeLast(4000) }.getOrDefault(""),
        )
    }

    /**
     * Makes SpotiFLAC's selected metadata authoritative inside the final native
     * FLAC itself. This is intentionally a container-metadata rewrite, not an
     * audio transcode: the original FLAC frame bytes are copied verbatim after
     * the rebuilt metadata chain.
     *
     * Provider output is allowed to contain its own tags, but Harmony replaces
     * the identity fields that came from the explicit metadata selection and,
     * when available, embeds the selected album artwork as a FLAC PICTURE block.
     * Unknown/provider tags (date, genre, track/disc number, ReplayGain, etc.) are
     * preserved. If artwork retrieval fails, text metadata is still written and
     * any provider-supplied cover is preserved.
     */
    private suspend fun enrichNativeFlacMetadata(
        source: File,
        track: IdentifiedTrack,
        jobDir: File,
        provider: String?,
        onProgress: (SpotiFlacTransferProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        verifyFlacSignature(source)

        val albumTags = track.albumTags
        val tags = FlacMetadataWriter.Tags(
            title = track.title.ifBlank { track.sourceTitle }.trim(),
            artist = track.artist.trim(),
            album = track.album.trim(),
            albumArtist = albumTags?.albumArtist?.trim().orEmpty(),
            isrc = track.isrc?.trim().orEmpty(),
            trackNumber = albumTags?.trackNumber ?: 0,
            totalTracks = albumTags?.totalTracks ?: 0,
            discNumber = albumTags?.discNumber ?: 0,
            totalDiscs = albumTags?.totalDiscs ?: 0,
            date = albumTags?.releaseDate?.trim().orEmpty(),
        )
        val artworkUrl = track.thumbnailUrl?.trim().orEmpty()

        if (tags.isEmpty && artworkUrl.isBlank()) {
            return@withContext source
        }

        onProgress(
            SpotiFlacTransferProgress(
                stage = SpotiFlacStage.FINALIZING,
                fraction = null,
                provider = provider,
                detail = if (artworkUrl.isNotBlank()) {
                    "Embedding album name, track tags and artwork into FLAC…"
                } else {
                    "Embedding album name and track tags into FLAC…"
                },
            ),
        )

        val artwork = if (artworkUrl.isNotBlank()) {
            runCatching { downloadArtworkForFlac(artworkUrl) }.getOrNull()
        } else {
            null
        }

        val output = File(jobDir, "harmony-tagged-${UUID.randomUUID()}.flac").also { it.delete() }
        // The one writer of the final tags (FlacMetadataWriter).
        val rewritten = runCatching { FlacMetadataWriter.rewrite(source, output, tags, artwork) }.getOrDefault(false)

        if (!rewritten || !output.isFile || output.length() < 42L || !hasNativeFlacSignature(output)) {
            runCatching { output.delete() }
            // Metadata enrichment must never destroy an otherwise valid lossless
            // download. The provider file remains the safe fallback.
            return@withContext source
        }

        if (source.absolutePath != output.absolutePath) runCatching { source.delete() }
        output
    }

    /** A cover, fetched safely and once per album (SpotiFlacArtworkFetcher), ready to embed. */
    private val artworkFetcher = SpotiFlacArtworkFetcher(
        maxBytes = MAX_EMBEDDED_ARTWORK_BYTES,
        maxRedirects = MAX_ARTWORK_REDIRECTS,
        cacheEntries = ARTWORK_CACHE_ENTRIES,
    ) { url ->
        hostRequests.incrementAndGet()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = ARTWORK_CONNECT_TIMEOUT_MS
        connection.readTimeout = ARTWORK_READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        // Each redirect is checked before anything connects to it; the client follows none.
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "image/jpeg,image/png;q=0.9,*/*;q=0.2")
        connection.setRequestProperty("User-Agent", "Harmony/1.0 Android ArtworkEmbed")
        object : SpotiFlacArtworkFetcher.Exchange {
            override val status: Int get() = connection.responseCode
            override fun header(name: String): String? = connection.getHeaderField(name)
            override fun body() = connection.inputStream
            override fun close() = connection.disconnect()
        }
    }

    private fun downloadArtworkForFlac(url: String): FlacMetadataWriter.Artwork? {
        val fetched = artworkFetcher.fetch(url) ?: return null
        val bytes = fetched.bytes
        val mime = detectArtworkMime(bytes, fetched.contentType) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val depth = if (mime == "image/png") 32 else 24
        return FlacMetadataWriter.Artwork(bytes, mime, options.outWidth.coerceAtLeast(0), options.outHeight.coerceAtLeast(0), depth)
    }

    private fun detectArtworkMime(bytes: ByteArray, contentType: String?): String? {
        val normalized = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (normalized == "image/jpeg" || normalized == "image/jpg") return "image/jpeg"
        if (normalized == "image/png") return "image/png"
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF
        ) return "image/jpeg"
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            )
        ) return "image/png"
        return null
    }

    /**
     * SpotiFLAC Mobile's MP3 quality option is a lossless-source -> MP3 conversion.
     * Harmony follows the same policy: provider routing is still strict LOSSLESS,
     * native FLAC validation happens first, and only then may the user-selected
     * MP3 320 kbps output be encoded locally. This prevents a lossy provider file
     * from being silently transcoded and presented as a high-quality source.
     */
    private suspend fun convertNativeFlacToMp3(
        source: File,
        jobDir: File,
    ): File = withContext(Dispatchers.IO) {
        verifyFlacSignature(source)

        val initFailure = runCatching {
            prepareFfmpegRuntime()
        }.exceptionOrNull()
        if (initFailure != null) {
            throw SpotiFlacException(
                message = "Harmony could not initialize the bundled FFmpeg encoder for MP3 320 kbps.",
                errorType = "mp3_encoder_unavailable",
                cause = initFailure,
                technicalDetails = initFailure.stackTraceToString(),
            )
        }

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val executable = listOf(
            File(nativeDir, "libffmpeg.so"),
            File(nativeDir, "libffmpeg.bin.so"),
        ).firstOrNull { it.isFile }
            ?: throw SpotiFlacException(
                message = "Harmony could not find the bundled FFmpeg encoder required for MP3 output.",
                errorType = "mp3_encoder_unavailable",
                technicalDetails = "native_library_dir=${nativeDir.absolutePath}",
            )

        fun freshOutput(): File = File(jobDir, "harmony-spotiflac-${UUID.randomUUID()}.mp3").also { it.delete() }
        val diagnostics = JSONArray()

        // First preserve text metadata and an attached cover stream when the
        // source FLAC exposes one. The optional video map means tracks without
        // embedded art use the same command successfully.
        val withArtwork = freshOutput()
        val artworkLog = File(jobDir, "harmony-ffmpeg-mp3-artwork.log").also { it.delete() }
        val artworkAttempt = runFfmpegProcess(
            command = listOf(
                executable.absolutePath,
                "-v", "error", "-xerror", "-nostdin", "-hide_banner", "-y",
                "-i", source.absolutePath,
                "-map", "0:a:0", "-map", "0:v?",
                "-map_metadata", "0",
                "-c:a", "libmp3lame", "-b:a", "320k",
                "-c:v", "copy",
                "-id3v2_version", "3", "-write_id3v1", "1",
                "-f", "mp3",
                withArtwork.absolutePath,
            ),
            log = artworkLog,
            nativeDir = nativeDir,
            timeoutSeconds = MP3_ENCODER_TIMEOUT_SECONDS,
        )
        diagnostics.put(artworkAttempt.toJson("mp3-320-with-artwork"))
        runCatching { artworkLog.delete() }
        if (artworkAttempt.success && verifyMp3Container(withArtwork) == null) {
            runCatching { source.delete() }
            return@withContext withArtwork
        }
        runCatching { withArtwork.delete() }

        // Some FFmpeg/device builds reject an attached picture in the MP3 muxer.
        // Retry audio + text metadata only rather than failing the whole download.
        val audioOnly = freshOutput()
        val audioLog = File(jobDir, "harmony-ffmpeg-mp3-audio.log").also { it.delete() }
        val audioAttempt = runFfmpegProcess(
            command = listOf(
                executable.absolutePath,
                "-v", "error", "-xerror", "-nostdin", "-hide_banner", "-y",
                "-i", source.absolutePath,
                "-map", "0:a:0", "-vn", "-sn", "-dn",
                "-map_metadata", "0",
                "-c:a", "libmp3lame", "-b:a", "320k",
                "-id3v2_version", "3", "-write_id3v1", "1",
                "-f", "mp3",
                audioOnly.absolutePath,
            ),
            log = audioLog,
            nativeDir = nativeDir,
            timeoutSeconds = MP3_ENCODER_TIMEOUT_SECONDS,
        )
        diagnostics.put(audioAttempt.toJson("mp3-320-audio-only"))
        runCatching { audioLog.delete() }

        val validationError = if (audioAttempt.success) verifyMp3Container(audioOnly) else "FFmpeg did not complete successfully"
        if (audioAttempt.success && validationError == null) {
            runCatching { source.delete() }
            return@withContext audioOnly
        }
        runCatching { audioOnly.delete() }

        throw SpotiFlacException(
            message = "The lossless source downloaded correctly, but Harmony could not create a valid MP3 320 kbps file.",
            errorType = "mp3_encode_failed",
            technicalDetails = JSONObject().apply {
                put("source_validated_as", "native FLAC")
                put("target", "MP3 320 kbps")
                put("validation_error", validationError ?: "unknown")
                put("attempts", diagnostics)
            }.toString(2),
        )
    }

    /** Bounded MP3 container validation: ID3v2-aware and legal MPEG frame fields. */
    private fun verifyMp3Container(file: File): String? {
        if (!file.isFile || file.length() < MP3_MIN_CONTAINER_BYTES) return "file is too small for MP3"
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val prefix = ByteArray(minOf(10L, file.length()).toInt())
                val prefixRead = raf.read(prefix)
                if (prefixRead <= 0) return@use "could not read MP3 header"

                var audioStart = 0L
                if (
                    prefixRead >= 10 && prefix[0] == 'I'.code.toByte() &&
                    prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte()
                ) {
                    // Synchsafe 28-bit ID3v2 size excludes the ten-byte header.
                    val tagSize = ((prefix[6].toInt() and 0x7F) shl 21) or
                        ((prefix[7].toInt() and 0x7F) shl 14) or
                        ((prefix[8].toInt() and 0x7F) shl 7) or
                        (prefix[9].toInt() and 0x7F)
                    audioStart = 10L + tagSize.toLong()
                    if (audioStart >= file.length()) return@use "ID3 tag claims to be larger than the file"
                }

                raf.seek(audioStart)
                val probeSize = minOf(file.length() - audioStart, MP3_HEADER_SCAN_BYTES).toInt()
                if (probeSize < 4) return@use "MP3 audio payload is too small"
                val bytes = ByteArray(probeSize)
                val read = raf.read(bytes)
                if (read <= 0) return@use "could not read MP3 audio payload"

                var offset = 0
                var scanned = 0
                while (offset + 4 <= read && scanned < MP3_MAX_SYNC_SCAN) {
                    if (isMpegAudioFrameHeader(bytes, offset)) return@use null
                    offset++
                    scanned++
                }
                "no legal MPEG audio frame was found"
            }
        }.getOrElse { "could not validate MP3 container: ${it.message ?: it::class.java.simpleName}" }
    }

    private fun isMpegAudioFrameHeader(bytes: ByteArray, offset: Int): Boolean {
        if (offset + 4 > bytes.size) return false
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return false
        if (b1 and 0x18 == 0x08) return false
        if (b1 and 0x06 == 0x00) return false
        val bitrateIndex = (b2 and 0xF0) ushr 4
        if (bitrateIndex == 0x00 || bitrateIndex == 0x0F) return false
        if (b2 and 0x0C == 0x0C) return false
        return true
    }

    private fun hasNativeFlacSignature(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        val header = ByteArray(4)
        return runCatching {
            FileInputStream(file).use { input ->
                input.read(header) == 4 &&
                    header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val text = bytes.toString(StandardCharsets.UTF_8).trimStart().lowercase(Locale.ROOT)
        return text.startsWith("<!doctype html") || text.startsWith("<html")
    }

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val text = bytes.toString(StandardCharsets.UTF_8).trimStart()
        return text.startsWith("{") || text.startsWith("[")
    }

    private fun cleanupStagingForRetry(jobDir: File) {
        jobDir.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }
    }

    private fun buildAudioFailureDetails(
        providerId: String?,
        probe: AudioOutputProbe?,
        diagnostics: JSONArray,
        registrySummary: String,
    ): String = JSONObject().apply {
        put("registry", registrySummary)
        put("provider", providerId ?: "unknown")
        put("attempts", diagnostics)
        if (probe == null) {
            put("output_found", false)
        } else {
            put("output_found", true)
            put("detected_format", probe.detectedFormat)
            put("reported_codec", probe.reportedCodec)
            put("effective_codec", probe.effectiveCodec)
            put("reported_extension", probe.reportedExtension)
            put("actual_container", probe.actualContainer)
            put("requires_container_conversion", probe.requiresContainerConversion)
            put("lossless_source", probe.isLosslessSource)
            put("can_finalize_losslessly", probe.canFinalizeLosslessly)
            put("file_size", probe.fileSize)
            put("magic", probe.magicHex)
        }
    }.toString(2)

    private fun verifyFlacSignature(file: File) {
        if (!file.isFile || file.length() < 42L) {
            throw SpotiFlacException("SpotiFLAC produced an empty or incomplete file.", "invalid_audio")
        }
        if (!hasNativeFlacSignature(file)) {
            throw SpotiFlacException(
                "SpotiFLAC returned a non-FLAC file after provider validation. Harmony refused to import it.",
                "not_flac",
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }


    private fun providerDisplay(provider: String?): String = when (provider) {
        "qobuz-web" -> "Qobuz"
        "tidal-web" -> "Tidal"
        "deezer" -> "Deezer"
        "amazon" -> "Amazon Music"
        null, "" -> "A SpotiFLAC provider"
        else -> provider
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(180)
        .ifBlank { "Harmony SpotiFLAC" }

    private data class ProviderIdentifiers(
        val deezerId: String?,
        val deezerUrl: String?,
        val spotifyId: String? = null,
        val spotifyUrl: String? = null,
        val qobuzId: String? = null,
        val qobuzUrl: String? = null,
        val tidalId: String? = null,
        val tidalUrl: String? = null,
    ) {
        val resolutionSummary: String
            get() {
                val resolved = buildList {
                    deezerId?.let { add("Deezer") }
                    spotifyId?.let { add("Spotify") }
                    qobuzId?.let { add("Qobuz") }
                    tidalId?.let { add("Tidal") }
                }
                return if (resolved.isEmpty()) {
                    "Track metadata verified. Searching lossless providers…"
                } else {
                    "Resolved ${resolved.joinToString()} identity. Searching lossless providers…"
                }
            }

        fun urlForProvider(providerId: String): String? = when (providerId) {
            "deezer" -> deezerUrl
            "qobuz-web" -> qobuzUrl
            "tidal-web" -> tidalUrl
            else -> spotifyUrl ?: deezerUrl
        }
    }

    companion object {
        private const val HARMONY_VALIDATED_FILE_PATH = "_harmony_validated_file_path"
        private val SPOTIFY_TRACK_ID = Regex("[A-Za-z0-9]{10,64}")
        private const val SPOTIFLAC_SESSION_GRANT_HOST = "session-grant"
        private const val SPOTIFLAC_COMPLETE_GRANT_ACTION = "completeGrant"
        private val SPOTIFLAC_CALLBACK_HOSTS = setOf("callback", "spotify-callback", SPOTIFLAC_SESSION_GRANT_HOST)
        private const val PROGRESS_POLL_MS = 450L
        private const val PROVIDER_CONNECT_TIMEOUT_MS = 20_000
        private const val PROVIDER_READ_TIMEOUT_MS = 60_000
        private const val REGISTRY_CONNECT_TIMEOUT_MS = 12_000
        private const val REGISTRY_READ_TIMEOUT_MS = 12_000
        private const val SONG_LINK_TIMEOUT_MS = 12_000
        /** Longest the song.link lookup may hold a download on "Resolving". */
        private const val SONG_LINK_DEADLINE_MS = 15_000L
        private const val ROUTER_WATCHDOG_MS = 210_000L
        /**
         * What a provider call gets (TransferActivity): 95 s to deliver a first
         * byte, as the whole call used to get; then for as long as bytes keep
         * coming, stopped after 95 s without one (the runtime's own read timeout
         * and retry fit inside that); 3 minutes to finish after the last byte;
         * and 45 minutes at most.
         */
        private val PROVIDER_DEADLINES = TransferDeadlines(
            firstByteMs = 95_000L, stalledMs = 95_000L, finalizeMs = 180_000L, longestMs = 45L * 60L * 1_000L,
        )
        private const val WATCHDOG_TICK_MS = 1_000L
        /** A partial transfer nobody resumed is removed after a day, sooner when they add up to more than this. */
        private const val STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val STAGING_BUDGET_BYTES = 1024L * 1024L * 1024L
        private const val SONG_LINK_MEMORY = 512
        /** A confirmed match is reused for a day. */
        private const val SONG_LINK_TTL_MS = 24L * 60L * 60L * 1_000L
        private const val KEY_PARALLEL_DOWNLOADS = "experimental_parallel_downloads"
        private const val KEY_RUNTIME_SET_PREFIX = "runtime_set_"
        private const val TIMELINE_TAG = "HarmonyDownload"
        private const val TIMELINE_FILE = "download-timings.txt"
        private const val TIMELINE_FILE_MAX_BYTES = 128L * 1024L
        private const val TIMELINES_KEPT = 40
        private const val SONG_LINK_PAUSE_SECONDS = 60L
        private const val SONG_LINK_MAX_BYTES = 1L * 1024 * 1024
        private val ALBUM_OWNERS = setOf(SpotiFlacRequestOwner.ALBUM_DOWNLOAD, SpotiFlacRequestOwner.DISCOVERY_DOWNLOAD)
        private const val CANCEL_DELIVERY_TIMEOUT_MS = 8_000L
        private const val LOSSLESS_FINALIZER_TIMEOUT_SECONDS = 180L
        private const val MP3_ENCODER_TIMEOUT_SECONDS = 240L
        private const val MP3_MIN_CONTAINER_BYTES = 128L
        private const val MP3_HEADER_SCAN_BYTES = 64L * 1024L
        private const val MP3_MAX_SYNC_SCAN = 8_192
        private const val MAX_EMBEDDED_ARTWORK_BYTES = 8L * 1024L * 1024L
        private const val MAX_ARTWORK_REDIRECTS = 3
        /** Covers kept for the next tracks of the same album (each at most [MAX_EMBEDDED_ARTWORK_BYTES]). */
        private const val ARTWORK_CACHE_ENTRIES = 3
        /** The registry is asked for at most this often; a new provider version is picked up within it. */
        private const val REGISTRY_MAX_AGE_MS = 6L * 60L * 60L * 1_000L
        private const val KEY_ACCEPTED_PACKAGE_PREFIX = "accepted_package_"
        private const val ARTWORK_CONNECT_TIMEOUT_MS = 12_000
        private const val ARTWORK_READ_TIMEOUT_MS = 20_000
        private const val MAX_GATEWAY_RATE_LIMIT_AUTO_RETRIES = 1
        private const val DEFAULT_GATEWAY_COOLDOWN_SECONDS = 60L
        private const val MIN_GATEWAY_COOLDOWN_SECONDS = 5L
        private const val MAX_GATEWAY_COOLDOWN_SECONDS = 600L
        private const val PROVIDER_STATE_PREFS = "spotiflac_provider_state"
        private const val KEY_LAST_SUCCESSFUL_PROVIDER = "last_successful_lossless_provider"
        private const val KEY_PENDING_VERIFICATION_TRACK = "pending_verification_track"
        private const val KEY_PENDING_CALLBACK_PROVIDER = "pending_callback_provider"
        private const val KEY_PENDING_CALLBACK_STATE_DIGEST = "pending_callback_state_digest"
        private const val KEY_PENDING_CALLBACK_CREATED_AT_MS = "pending_callback_created_at_ms"
        // Matches SpotiFLAC's pendingAuthRequestTTL. A callback that arrives
        // later must start a fresh challenge instead of reusing stale state.
        private const val PENDING_CALLBACK_STATE_TTL_MS = 5L * 60L * 1_000L
        private val PROVIDER_PRIORITY = SpotiFlacProviderRegistry.priority
        private val LOSSLESS_SOURCE_CODECS = setOf("flac", "alac")
        private val GENERIC_CONTAINER_CODEC_LABELS = setOf("m4a", "mp4", "mp4/m4a", "lossless", "unknown")
        private val KNOWN_AUDIO_EXTENSIONS = setOf(
            "flac", "m4a", "mp4", "mp3", "ogg", "opus", "wav", "aiff", "aif",
            "aac", "webm", "mka", "ac4",
        )

    }
}
