package com.harmony.feature.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.min

/**
 * Small, download-focused Soulseek client for Harmony.
 *
 * It deliberately implements only what Harmony needs:
 * server login, search, peer connections, queueing a remote upload and receiving
 * file-transfer connections. There is no chat, room, buddy or sharing UI.
 *
 * Protocol reference: Nicotine+ Soulseek protocol documentation (2026).
 * Soulseek traffic itself is not encrypted; never reuse an important password.
 */
@Singleton
class SoulseekClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverWriteMutex = Mutex()
    private val connectMutex = Mutex()
    private val networkHandoffMutex = Mutex()
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var serverNetworkHandle: Long? = null

    @Volatile
    private var currentPassword: String = ""

    @Volatile
    private var autoReconnectEnabled: Boolean = false

    /**
     * Why the last server session ended, when it ended for a reason the user
     * needs to act on. Surfaced through search diagnostics so an empty result
     * list can name a dead session instead of looking like a missing track.
     */
    @Volatile
    private var sessionEndReason: String? = null

    @Volatile
    private var handoffInProgress: Boolean = false

    private val activeFileTransferSockets = ConcurrentHashMap<Long, Socket>()

    private var serverSocket: Socket? = null
    private var serverInput: BufferedInputStream? = null
    private var serverOutput: BufferedOutputStream? = null
    private var serverReaderJob: Job? = null
    private var serverPingJob: Job? = null
    private var peerJanitorJob: Job? = null

    private var listener: ServerSocket? = null
    private var listenerJob: Job? = null

    @Volatile
    private var currentUsername: String = ""

    private val loginDeferredLock = Any()
    private var loginDeferred: CompletableDeferred<LoginResult>? = null

    private val nextToken = AtomicInteger((System.currentTimeMillis() and 0x3FFF_FFFF).toInt().coerceAtLeast(1))

    private val pendingAddresses = ConcurrentHashMap<String, CompletableDeferred<PeerAddress>>()
    private val pendingIndirect = ConcurrentHashMap<Long, PendingIndirectConnection>()
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val activeSearches = ConcurrentHashMap<Long, SearchSession>()
    private val pendingDownloads = ConcurrentHashMap<String, PendingDownload>()
    private val fileTransfersByToken = ConcurrentHashMap<Long, PendingFileTransfer>()

    // --- Sharing (uploading) -------------------------------------------------
    // Everything below this line is the reverse of the rest of this file: it
    // makes Harmony an uploader for exactly one user-chosen file, instead of a
    // download-only client. See docs/SOULSEEK-SHARING-v1.7.4.md.

    private val _sharedFolder = MutableStateFlow<SoulseekSharedFolder?>(null)
    val sharedFolder: StateFlow<SoulseekSharedFolder?> = _sharedFolder.asStateFlow()

    /** True while a folder is being walked, so the UI can say so on a big library. */
    private val _indexingShare = MutableStateFlow(false)
    val indexingShare: StateFlow<Boolean> = _indexingShare.asStateFlow()

    /** Tokens Harmony itself issued as the uploader, keyed the same way [fileTransfersByToken] is. */
    private val pendingUploads = ConcurrentHashMap<Long, PendingUpload>()

    // --- Reachability instrumentation ----------------------------------------
    // Zero search replies has several very different causes that all look
    // identical from the UI. These counters separate them: whether peers are
    // trying to reach us at all, whether they reach us directly or via the
    // server, and whether our outbound leg of an indirect connection works.
    private val inboundSocketsAccepted = AtomicInteger(0)
    private val connectToPeerRequests = AtomicInteger(0)
    private val indirectConnectFailures = AtomicInteger(0)

    /** ConnectToPeer requests dropped without dialling because Harmony does not speak that type. */
    private val distributedSolicitations = AtomicInteger(0)

    /**
     * Ceiling on simultaneous outbound call-backs to peers.
     *
     * Every failed call-back costs a full 4s connect timeout, and a search
     * produces hundreds of them — peers that matched but are themselves behind
     * NAT, so neither side can reach the other. Without a cap those doomed
     * dials consume the whole outbound budget and a download cannot get a
     * socket of its own. Measured symptom: the search never stopped, and the
     * download only began after the session died and was retried, because that
     * was the only thing that cleared the backlog.
     */
    private val indirectDialSlots = Semaphore(MAX_CONCURRENT_INDIRECT_DIALS)

    /** Call-backs dropped because the dial budget was already full. */
    private val indirectDialsShed = AtomicInteger(0)

    /** True while a search window is open; peer call-backs are only useful then. */
    @Volatile
    private var searchWindowOpen: Boolean = false

    /**
     * Single logging entry point for the whole Soulseek path.
     *
     * One tag, so `adb logcat -s HarmonySlsk` gives a clean transcript with no
     * filtering guesswork. Never logs the password. Never logs per-event for
     * the distributed solicitation flood — that would be thousands of lines per
     * search and would bury everything useful; only totals are reported.
     */
    private fun slsk(message: String) {
        Log.i(SLSK_TAG, message)
    }

    /**
     * One mutex per peer username. Without it, the preconnect race and the
     * actual download can both miss the [peerConnections] cache and each build
     * their own socket; the second [registerPeerConnection] then closed the
     * socket the first caller was about to send QueueUpload on. The peer never
     * saw the request, the accept timeout fired, and the attempt looked like a
     * dead peer when the connection had in fact been killed locally.
     */
    private val peerConnectMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Exactly one [PendingDownload] may write to a given content key at a time.
     *
     * The partial file is content-keyed (size + leaf filename) so any peer
     * offering the same file can resume it. That is only safe while a single
     * attempt owns the file. The lease makes that invariant explicit: an
     * abandoned attempt loses its lease, and every writer path re-checks
     * ownership before touching the partial.
     */
    private val transferLeases = ConcurrentHashMap<String, PendingDownload>()

    private val preferences by lazy {
        context.getSharedPreferences("harmony_soulseek", Context.MODE_PRIVATE)
    }

    private val _connectionState = MutableStateFlow(
        SoulseekConnectionState(
            username = preferences.getString(PREF_USERNAME, "").orEmpty(),
        )
    )
    val connectionState: StateFlow<SoulseekConnectionState> = _connectionState.asStateFlow()

    init {
        val savedUri = preferences.getString(PREF_SHARED_URI, null)
        if (savedUri != null) {
            // Re-index rather than trusting a stored file list: files get added,
            // renamed and deleted between sessions, and a stale index would make
            // Harmony advertise files it can no longer serve.
            //
            // This MUST be off the constructor's thread. SoulseekClient is an
            // @Singleton, so it is built on whatever thread first injects it —
            // typically the main thread — and walking a music folder there is
            // hundreds of content-provider queries on the UI thread.
            scope.launch { reindexSharedFolder(Uri.parse(savedUri), announce = false) }
        }
    }

    /**
     * Walk the shared tree and publish the result.
     *
     * Any failure clears the share instead of leaving a half-built index: a
     * revoked grant (SD card pulled, provider app uninstalled) must not leave
     * Harmony announcing files it cannot open.
     */
    private suspend fun reindexSharedFolder(treeUri: Uri, announce: Boolean) {
        _indexingShare.value = true
        try {
            val name = preferences.getString(PREF_SHARED_NAME, null)
                ?: SoulseekShareIndexer.treeDisplayName(treeUri)
            val indexed = withContext(Dispatchers.IO) {
                runCatching { SoulseekShareIndexer.index(context, treeUri, name) }.getOrNull()
            }
            if (indexed == null || indexed.entries.isEmpty()) {
                _sharedFolder.value = null
                if (indexed == null) {
                    preferences.edit().remove(PREF_SHARED_URI).remove(PREF_SHARED_NAME).apply()
                }
            } else {
                _sharedFolder.value = indexed
                preferences.edit()
                    .putString(PREF_SHARED_URI, indexed.treeUri)
                    .putString(PREF_SHARED_NAME, indexed.displayName)
                    .apply()
            }
        } finally {
            _indexingShare.value = false
        }
        if (announce) announceSharedCountIfConnected()
    }

    private val _searchResults = MutableStateFlow<List<SoulseekSearchCandidate>>(emptyList())
    val searchResults: StateFlow<List<SoulseekSearchCandidate>> = _searchResults.asStateFlow()

    private val _transferProgress = MutableStateFlow<SoulseekTransferProgress?>(null)
    val transferProgress: StateFlow<SoulseekTransferProgress?> = _transferProgress.asStateFlow()

    private val _searchDiagnostics = MutableStateFlow(SoulseekSearchDiagnostics())
    val searchDiagnostics: StateFlow<SoulseekSearchDiagnostics> = _searchDiagnostics.asStateFlow()

    /**
     * Responses whose token no longer maps to a live session, and peer replies
     * that failed to parse. Both were previously discarded without trace, which
     * is what made an empty result list impossible to interpret. Reset at the
     * start of each search.
     */
    private val unmatchedTokenResponses = AtomicInteger(0)
    private val searchParseFailures = AtomicInteger(0)

    /**
     * Track Android's default network for seamless Wi-Fi <-> cellular handoff.
     * A Socket created from Network.getSocketFactory() remains bound to that
     * Network, so old Soulseek sockets must be recreated when the default
     * Network changes.
     */
    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleDefaultNetworkCheck(network, NETWORK_HANDOFF_SETTLE_MS)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                scheduleDefaultNetworkCheck(network, 0L)
            }
        }

        override fun onLost(network: Network) {
            if (!autoReconnectEnabled || serverNetworkHandle != network.networkHandle) return
            _connectionState.value = SoulseekConnectionState(
                status = SoulseekConnectionStatus.CONNECTING,
                username = currentUsername.ifBlank { savedUsername() },
                message = "Network changed — waiting for the new connection…",
            )
            _transferProgress.value = _transferProgress.value?.copy(
                status = "Network changed — waiting to resume…",
            )
        }
    }

    init {
        runCatching { connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback) }
    }

    fun savedUsername(): String = preferences.getString(PREF_USERNAME, "").orEmpty()

    /**
     * Set the single file Harmony shares back to the network.
     *
     * [uri] must be a content:// URI already carrying a persistable read grant
     * — the caller (the file-picker launch site) is responsible for calling
     * `contentResolver.takePersistableUriPermission` on it first, since that
     * grant is only valid to take from the exact Intent the system picker
     * returned. This function only stores and announces it.
     */
    /**
     * Set the folder Harmony shares back to the network.
     *
     * [treeUri] must already carry a persisted read grant — the caller takes
     * `takePersistableUriPermission` on it, since that can only be done from
     * the exact Intent the system picker returned.
     */
    fun setSharedFolder(treeUri: Uri, displayName: String) {
        preferences.edit().putString(PREF_SHARED_NAME, displayName).apply()
        scope.launch { reindexSharedFolder(treeUri, announce = true) }
    }

    /** Re-walk the current folder, picking up files added or removed since. */
    fun refreshSharedFolder() {
        val current = _sharedFolder.value ?: return
        scope.launch { reindexSharedFolder(Uri.parse(current.treeUri), announce = true) }
    }

    fun clearSharedFolder() {
        if (_sharedFolder.value == null) return
        _sharedFolder.value = null
        preferences.edit().remove(PREF_SHARED_URI).remove(PREF_SHARED_NAME).apply()
        announceSharedCountIfConnected()
    }

    /**
     * Re-announce SharedFoldersFiles (server code 35) outside of login, so
     * toggling the shared file while already connected takes effect
     * immediately instead of waiting for the next reconnect.
     */
    private fun announceSharedCountIfConnected() {
        if (_connectionState.value.status != SoulseekConnectionStatus.CONNECTED) return
        val share = _sharedFolder.value
        val dirs = share?.folderCount?.toLong() ?: 0L
        val files = share?.fileCount?.toLong() ?: 0L
        scope.launch {
            // Server code 35: uint32 dirs, then uint32 files. Real counts —
            // whatever the index actually found.
            runCatching {
                sendServerMessage(SERVER_SHARED_FOLDERS_FILES, SlskWriter().u32(dirs).u32(files).bytes())
            }
        }
    }

    suspend fun connect(username: String, password: String) = withContext(Dispatchers.IO) {
        val user = username.trim()
        require(user.isNotBlank()) { "Enter your Soulseek username." }
        require(password.isNotBlank()) { "Enter your Soulseek password." }

        connectMutex.withLock {
            // isConnected() stays true forever once a socket has connected, and
            // isClosed() only reports *our* close — neither can see a
            // server-side disconnect. Requiring a live reader job is what
            // actually proves the session is still being served. Without it,
            // reconnecting under the same username short-circuited onto a dead
            // socket and every search silently returned nothing, while
            // switching usernames forced a real reconnect and appeared to work.
            if (
                _connectionState.value.status == SoulseekConnectionStatus.CONNECTED &&
                currentUsername == user &&
                serverSocket?.isConnected == true &&
                serverSocket?.isClosed == false &&
                serverReaderJob?.isActive == true
            ) {
                currentPassword = password
                autoReconnectEnabled = true
                return@withLock
            }

            sessionEndReason = null
            autoReconnectEnabled = false
            disconnectInternal("Reconnecting…")
            _connectionState.value = SoulseekConnectionState(
                status = SoulseekConnectionStatus.CONNECTING,
                username = user,
                message = "Connecting to Soulseek…",
            )

            try {
                establishServerSession(
                    user = user,
                    password = password,
                    network = requireActiveNetwork(),
                    connectedMessage = "Connected",
                )
                runCatching { sweepStalePartials() }
                currentPassword = password
                autoReconnectEnabled = true
            } catch (t: Throwable) {
                disconnectInternal("Connection failed")
                _connectionState.value = SoulseekConnectionState(
                    status = SoulseekConnectionStatus.ERROR,
                    username = user,
                    message = t.message ?: "Could not connect to Soulseek.",
                )
                throw if (t is SoulseekException) t else SoulseekException(
                    "Could not connect to Soulseek: ${t.message ?: t::class.java.simpleName}",
                    t,
                )
            }
        }
    }

    private suspend fun establishServerSession(
        user: String,
        password: String,
        network: Network,
        connectedMessage: String,
    ) {
        currentUsername = user
        slsk("LOGIN ok user=$user")

        val listenPort = startListener(network)
        val socket = network.socketFactory.createSocket().apply {
            keepAlive = true
            tcpNoDelay = true
            soTimeout = 0
            val serverAddress = network.getByName(SERVER_HOST)
            connect(InetSocketAddress(serverAddress, SERVER_PORT), SERVER_CONNECT_TIMEOUT_MS)
        }
        serverNetworkHandle = network.networkHandle

        serverSocket = socket
        serverInput = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        serverOutput = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

        val deferred = CompletableDeferred<LoginResult>()
        synchronized(loginDeferredLock) { loginDeferred = deferred }
        serverReaderJob = scope.launch { readServerLoop(socket) }

        val body = SlskWriter()
            .string(user)
            .string(password)
            .u32(EXPERIMENTAL_MAJOR_VERSION.toLong())
            .string(md5Hex(user + password))
            .u32(HARMONY_MINOR_VERSION.toLong())
            .bytes()
        sendServerMessage(SERVER_LOGIN, body)

        val login = withTimeout(LOGIN_TIMEOUT_MS) { deferred.await() }
        if (!login.success) {
            throw SoulseekException(
                when (login.reason) {
                    "INVALIDPASS" -> "Soulseek rejected the password for this username."
                    "INVALIDUSERNAME" -> "Soulseek rejected this username${login.detail?.let { ": $it" }.orEmpty()}."
                    else -> "Soulseek login failed${login.reason?.let { ": $it" }.orEmpty()}."
                }
            )
        }

        sendServerMessage(SERVER_SET_WAIT_PORT, SlskWriter().u32(listenPort.toLong()).bytes())
        sendServerMessage(SERVER_SET_STATUS, SlskWriter().u32(2).bytes())
        val share = _sharedFolder.value
        slsk("SESSION port=$listenPort shared=${share?.fileCount ?: 0}f/${share?.folderCount ?: 0}d")
        sendServerMessage(
            SERVER_SHARED_FOLDERS_FILES,
            SlskWriter()
                .u32(share?.folderCount?.toLong() ?: 0L)
                .u32(share?.fileCount?.toLong() ?: 0L)
                .bytes(),
        )
        startServerPingLoop(socket)
        startPeerConnectionJanitor()

        preferences.edit().putString(PREF_USERNAME, user).apply()
        _connectionState.value = SoulseekConnectionState(
            status = SoulseekConnectionStatus.CONNECTED,
            username = user,
            message = connectedMessage,
            listeningPort = listenPort,
        )
    }

    /**
     * Tear the session down. This is the user's escape hatch, so it must not be
     * blockable by Harmony's own internal state.
     *
     * The old version did everything inside connectMutex.withLock. That mutex
     * is also held by connect() and by performNetworkHandoff, and the handoff
     * runs establishServerSession inside it — blocking socket connects and
     * blocking writes. On a flaky network during a long download the handoff
     * can wedge there, and the Disconnect button then waited on the mutex
     * forever: nothing happened, the session stayed half-alive, and searches
     * kept being sent over a connection no peer could answer on.
     *
     * Now the lock is only attempted. If it cannot be taken quickly, the
     * teardown runs anyway — and closing the sockets is precisely what
     * unblocks whoever is stuck holding it, since blocking IO cannot be
     * cancelled but does throw when its socket closes.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        // Set before anything else, and outside the lock: whatever currently
        // holds the mutex must not be able to reconnect behind the teardown.
        autoReconnectEnabled = false
        currentPassword = ""

        val cleanly = withTimeoutOrNull(DISCONNECT_LOCK_TIMEOUT_MS) {
            connectMutex.withLock { disconnectInternal("Disconnected") }
            true
        }

        if (cleanly == null) {
            // Forced path. Close the sockets first — that is what breaks the
            // stuck holder out of its blocking call — then run the same
            // teardown, which is idempotent.
            runCatching { serverSocket?.close() }
            runCatching { listener?.close() }
            activeFileTransferSockets.values.forEach { runCatching { it.close() } }
            peerConnections.values.forEach { runCatching { it.close() } }
            disconnectInternal("Disconnected")
        }

        _connectionState.value = SoulseekConnectionState(
            status = SoulseekConnectionStatus.DISCONNECTED,
            username = savedUsername(),
            message = "Not connected",
        )
    }

    suspend fun searchFlac(
        query: String,
        mode: SoulseekSearchMode = SoulseekSearchMode.BALANCED,
        onlyFreeSlots: Boolean = false,
        formatPreference: SoulseekFormatPreference = SoulseekFormatPreference.FLAC_ONLY,
    ): List<SoulseekSearchCandidate> = withContext(Dispatchers.IO) {
        requireConnected()
        val clean = query.trim()
        require(clean.length >= 2) { "Enter an artist, album or song name." }

        val profile = searchProfile(mode)
        val variants = buildSearchVariants(clean, mode)
        val session = SearchSession(
            query = clean,
            mode = mode,
            onlyFreeSlots = onlyFreeSlots,
            // Captured per search rather than read live, so results arriving
            // after the person changes the filter are judged by the filter the
            // search was actually run with.
            formatPreference = formatPreference,
        )
        session.variants += variants
        _searchResults.value = emptyList()
        unmatchedTokenResponses.set(0)
        searchParseFailures.set(0)
        inboundSocketsAccepted.set(0)
        connectToPeerRequests.set(0)
        indirectConnectFailures.set(0)
        distributedSolicitations.set(0)
        indirectDialsShed.set(0)
        searchWindowOpen = true
        slsk("SEARCH start mode=$mode query=\"$query\"")
        _searchDiagnostics.value = SoulseekSearchDiagnostics(
            queriesSent = variants.size,
            variants = variants,
        )

        val tokens = variants.map { variant ->
            val token = nextProtocolToken()
            activeSearches[token] = session
            sendServerMessage(
                SERVER_FILE_SEARCH,
                SlskWriter().u32(token).string(variant).bytes(),
            )
            token
        }

        // Search responses arrive peer-to-peer and there is no protocol-level
        // "search finished" message. Keep listening until the selected search
        // profile reaches its maximum window, but stop early once we have a
        // healthy, diverse set of high-confidence sources.
        val startedAt = System.currentTimeMillis()
        while (true) {
            delay(SEARCH_POLL_INTERVAL_MS)
            _searchDiagnostics.value = snapshotDiagnostics(session)
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= profile.maxWindowMs) break
            if (elapsed >= profile.minWindowMs && hasSufficientResults(session)) break
        }

        session.acceptIntoUi = false
        val ranked = selectRecommendations(session)
        _searchResults.value = ranked
        _searchDiagnostics.value = snapshotDiagnostics(session)

        scope.launch {
            delay(LATE_SEARCH_RESULT_GRACE_MS)
            searchWindowOpen = false
            tokens.forEach { token -> activeSearches.remove(token, session) }
        }
        ranked
    }

    suspend fun download(
        candidate: SoulseekSearchCandidate,
        patience: SoulseekDownloadPatience = SoulseekDownloadPatience.FAST,
        albumTransfer: Boolean = false,
    ): SoulseekDownloadedFile = DownloadEngineGate.run(albumTransfer) { downloadExclusive(candidate, patience) }

    private suspend fun downloadExclusive(
        candidate: SoulseekSearchCandidate, patience: SoulseekDownloadPatience,
    ): SoulseekDownloadedFile = withContext(Dispatchers.IO) {
        requireConnected()
        // Gate on what Harmony can actually verify after the transfer, not on
        // what the search happened to accept. A peer can send anything, so a
        // format without a container check must never reach the download path.
        require(candidate.extension.lowercase(Locale.US) in SoulseekFormatPreference.SUPPORTED_EXTENSIONS) {
            "Harmony peer download accepts FLAC and MP3 results only."
        }
        require(candidate.sizeBytes > 0L) {
            "This peer did not report a valid file size. Try another recommended source."
        }

        val network = requireActiveNetwork()
        val isCellular = isCellularNetwork(network)
        val activeHandle = network.networkHandle
        val connectedHandle = serverNetworkHandle
        if (connectedHandle != null && connectedHandle != activeHandle && autoReconnectEnabled) {
            _transferProgress.value = SoulseekTransferProgress(
                username = candidate.username,
                filename = candidate.fileNameOnly,
                status = "Network changed — reconnecting automatically…",
                downloadedBytes = 0L,
                totalBytes = candidate.sizeBytes,
            )
            performNetworkHandoff(network)
            requireConnected()
        }

        val key = downloadKey(candidate.username, candidate.filename)
        if (pendingDownloads.containsKey(key)) {
            throw SoulseekException("This file is already queued from ${candidate.username}.")
        }

        val contentKey = contentKeyFor(candidate)
        val partialFile = partialFileFor(contentKey)
        val completion = CompletableDeferred<SoulseekDownloadedFile>()
        val pending = PendingDownload(
            candidate = candidate,
            contentKey = contentKey,
            partialFile = partialFile,
            patience = patience,
            completion = completion,
        )

        // The ViewModel walks sources strictly one at a time, so an existing
        // lease here means a previous attempt leaked a live writer. Tear it
        // down before this attempt touches the shared partial file.
        transferLeases.put(contentKey, pending)
            ?.takeIf { it !== pending }
            ?.let { superseded -> abortAttempt(superseded) }
        pendingDownloads[key] = pending

        _transferProgress.value = SoulseekTransferProgress(
            username = candidate.username,
            filename = candidate.fileNameOnly,
            status = if (isCellular) {
                "Mobile data: testing peer connection…"
            } else {
                "Connecting to peer…"
            },
            downloadedBytes = partialFile.length(),
            totalBytes = candidate.sizeBytes,
        )

        try {
            val peer = getOrCreatePeerConnection(candidate.username, isCellular)
            pending.peerConnection = peer
            slsk("DL queue-request user=${candidate.username} file=${candidate.filename.takeLast(60)}")
            peer.send(PEER_QUEUE_UPLOAD, SlskWriter().string(candidate.filename).bytes())
            peer.send(PEER_PLACE_IN_QUEUE_REQUEST, SlskWriter().string(candidate.filename).bytes())
            pending.queuePollJob = startQueuePolling(pending, peer)

            if (patience == SoulseekDownloadPatience.PATIENT) {
                // "Waiting for upload slot" maps to the Queued stage, so the
                // card shows the queue panel straight away rather than sitting
                // on Preparing until a position happens to arrive.
                _transferProgress.value = _transferProgress.value?.copy(
                    status = "Waiting for upload slot…",
                    queuePlace = pending.lastQueuePlace,
                    queueWaitStartedAtMs = System.currentTimeMillis(),
                )
            }

            val acceptTimeoutMs = requestAcceptTimeoutMs(candidate, isCellular, patience)
            _transferProgress.value = _transferProgress.value?.copy(
                status = if (candidate.freeUploadSlot || candidate.queueLength <= 0) {
                    "Request sent — waiting briefly for peer…"
                } else {
                    "Peer is queued — waiting briefly before trying another source…"
                },
            )

            try {
                if (patience == SoulseekDownloadPatience.PATIENT) {
                    awaitPatientAccept(pending, peer)
                } else {
                    withTimeout(acceptTimeoutMs) { pending.requestAccepted.await() }
                }
            } catch (_: TimeoutCancellationException) {
                // The peer connection was built and QueueUpload was delivered,
                // so this peer is reachable and simply has not answered yet --
                // which is what being in a queue looks like before the position
                // arrives. Reporting it as a dead source meant the patient pass
                // never ran and no queue position was ever obtained.
                throw if (pending.peerConnection != null) {
                    SoulseekQueuedException(
                        peerUsername = candidate.username,
                        queuePlace = pending.lastQueuePlace ?: 0,
                        message = "${candidate.username} has not answered the request yet.",
                    )
                } else {
                    SoulseekException(
                        "${candidate.username} did not accept the download quickly enough. Trying another source is recommended."
                    )
                }
            }

            _transferProgress.value = _transferProgress.value?.copy(
                status = "Peer accepted — opening file transfer…",
            )

            try {
                withTimeout(transferStartTimeoutMs(isCellular, patience)) { pending.transferStarted.await() }
            } catch (_: TimeoutCancellationException) {
                throw SoulseekException(
                    "${candidate.username} accepted the request but did not open the file transfer."
                )
            }

            // A peer can open the F connection and then never send a single byte.
            // Previously this looked like a permanent "Downloading…" state even
            // though the remote request had disappeared. Require real payload data
            // before considering the transfer healthy so the ViewModel can fall
            // back to another already-ranked source.
            _transferProgress.value = _transferProgress.value?.copy(
                status = "Transfer opened — waiting for audio data…",
                speedBytesPerSecond = 0L,
            )
            try {
                withTimeout(firstByteTimeoutMs(isCellular, patience)) { pending.firstByteReceived.await() }
            } catch (_: TimeoutCancellationException) {
                throw SoulseekException(
                    "${candidate.username} opened the transfer but sent no audio data. Trying another source."
                )
            }

            coroutineScope {
                val idleWatchdog = launch {
                    while (isActive && !completion.isCompleted) {
                        delay(DOWNLOAD_IDLE_WATCHDOG_POLL_MS)
                        if (pending.requeueJob?.isActive == true) continue

                        val idleForNs = System.nanoTime() - pending.lastProgressAt
                        if (idleForNs >= DOWNLOAD_IDLE_TIMEOUT_NS) {
                            completion.completeExceptionally(
                                SoulseekException(
                                    "No Soulseek download progress for ${DOWNLOAD_IDLE_TIMEOUT_NS / 1_000_000_000L} seconds."
                                )
                            )
                            break
                        }
                    }
                }
                try {
                    completion.await()
                } finally {
                    idleWatchdog.cancel()
                }
            }.also { releaseLease(pending) }
        } catch (t: Throwable) {
            // One teardown path for every failure mode. Previously the cleanup
            // here raced the resume scheduler: an F-socket read could fail,
            // observe `cancelled == false`, and queue a resume microseconds
            // before this block set the flag — leaving an abandoned attempt
            // still writing into the shared partial file.
            abortAttempt(pending)

            // Keep the content-keyed partial so the next matching source can resume it.
            _transferProgress.value = _transferProgress.value?.copy(
                status = "Download failed: ${t.message ?: "connection error"}",
                speedBytesPerSecond = 0L,
            )
            throw if (t is SoulseekException) t else SoulseekException(
                "Soulseek download failed: ${t.message ?: t::class.java.simpleName}",
                t,
            )
        }
    }

    /**
     * True only while [pending] is the single attempt allowed to write to its
     * content key. Every path that can append bytes or re-request an upload
     * checks this first, so a superseded attempt cannot resurrect itself.
     */
    /**
     * Peers answer PlaceInQueueRequest once and then usually go quiet, so a
     * queue position reported at second zero would sit frozen on screen for the
     * whole wait with no way to tell progress from a stall. Re-asking on a slow
     * cadence keeps the number honest.
     *
     * The interval is deliberately unhurried: this is a request to somebody
     * else's client, and hammering it is both rude and a good way to get
     * dropped. Stops as soon as the request is accepted or the attempt loses
     * its lease.
     */
    private fun startQueuePolling(pending: PendingDownload, peer: PeerConnection): Job =
        scope.launch {
            // Start fast, then back off. The opening request is answered at
            // the peer's leisure, and a fast pass only lives a few seconds --
            // waiting a full interval before re-asking meant a queue position
            // essentially never arrived in time to be shown or acted on.
            var waitMs = QUEUE_POLL_INITIAL_MS
            while (isActive) {
                delay(waitMs)
                if (pending.requestAccepted.isCompleted || !ownsTransferLease(pending)) return@launch
                if (!peer.isOpen) return@launch
                val sent = runCatching {
                    peer.send(
                        PEER_PLACE_IN_QUEUE_REQUEST,
                        SlskWriter().string(pending.candidate.filename).bytes(),
                    )
                }
                if (sent.isFailure) return@launch
                waitMs = (waitMs * 2).coerceAtMost(QUEUE_POLL_INTERVAL_MS)
            }
        }

    /**
     * Wait for a queued peer without a fixed deadline.
     *
     * A flat 90 s timeout produced the worst possible outcome: Harmony reported
     * failure at the exact moment the peer was about to reach our turn, and
     * pressing Download again then started instantly because the slot had just
     * come free. Waiting is therefore bounded by *lack of progress* rather than
     * by elapsed time.
     *
     * The periodic re-send of QueueUpload is the automated version of pressing
     * the button again: peers commonly free a slot without volunteering a new
     * position, and a fresh request lands on that free slot immediately.
     */
    private suspend fun awaitPatientAccept(pending: PendingDownload, peer: PeerConnection) {
        val startedAt = System.currentTimeMillis()
        var lastKnockAt = startedAt

        while (true) {
            val accepted = try {
                withTimeout(PATIENT_QUEUE_CHECK_INTERVAL_MS) {
                    pending.requestAccepted.await()
                    true
                }
            } catch (_: TimeoutCancellationException) {
                false
            }
            if (accepted) return

            val now = System.currentTimeMillis()
            val place = pending.lastQueuePlace ?: 0

            if (!ownsTransferLease(pending)) {
                throw SoulseekException("This source was superseded while queued.")
            }
            if (!peer.isOpen) {
                throw SoulseekQueuedException(
                    peerUsername = peer.username,
                    queuePlace = place,
                    message = "${peer.username} closed the connection while we were queued.",
                )
            }
            if (now - startedAt > PATIENT_MAX_WAIT_MS) {
                throw SoulseekQueuedException(
                    peerUsername = peer.username,
                    queuePlace = place,
                    message = "Still waiting in ${peer.username}'s queue after " +
                        "${(now - startedAt) / 60_000L} minutes.",
                )
            }
            // Only enforce the stall rule once the peer has actually reported a
            // position; otherwise silence would be misread as a stalled queue.
            if (pending.queueStartPlace != null &&
                now - pending.lastQueueProgressAt > PATIENT_QUEUE_STALL_MS
            ) {
                throw SoulseekQueuedException(
                    peerUsername = peer.username,
                    queuePlace = place,
                    message = "${peer.username}'s queue stopped moving.",
                )
            }

            if (now - lastKnockAt >= PATIENT_REKNOCK_INTERVAL_MS) {
                lastKnockAt = now
                runCatching {
                    peer.send(PEER_QUEUE_UPLOAD, SlskWriter().string(pending.candidate.filename).bytes())
                    peer.send(
                        PEER_PLACE_IN_QUEUE_REQUEST,
                        SlskWriter().string(pending.candidate.filename).bytes(),
                    )
                }
            }
        }
    }

    private fun ownsTransferLease(pending: PendingDownload): Boolean =
        !pending.cancelled && transferLeases[pending.contentKey] === pending

    /** Cancel all currently active Soulseek transfer attempts and remove partial files. */
    fun cancelActiveDownload() {
        if (DownloadEngineGate.albumOwnsTransfer) return // The album worker owns its cancellation/partial-file cleanup.
        val active = transferLeases.values.toList()
        active.forEach { pending ->
            abortAttempt(pending)
            if (!pending.completion.isCompleted) {
                pending.completion.completeExceptionally(
                    kotlinx.coroutines.CancellationException("Soulseek download cancelled by user."),
                )
            }
            runCatching { pending.partialFile.delete() }
            runCatching { partialOwnerFileFor(pending.contentKey).delete() }
        }
        _transferProgress.value = null
    }

    /**
     * Fully retire one download attempt: stop its resume scheduler, drop it
     * from the routing maps and close any file-transfer socket that still
     * belongs to it. Idempotent and safe to call from any thread.
     */
    private fun abortAttempt(pending: PendingDownload) {
        pending.cancelled = true
        pending.requeueJob?.cancel()
        pending.requeueJob = null
        pending.queuePollJob?.cancel()
        pending.queuePollJob = null
        pendingDownloads.remove(
            downloadKey(pending.candidate.username, pending.candidate.filename),
            pending,
        )
        transferLeases.remove(pending.contentKey, pending)

        // A late or stalled F socket left open would keep feeding bytes into
        // the shared partial file while the ViewModel is already on the next
        // source. Closing it here is what actually ends the ghost transfer.
        fileTransfersByToken.entries
            .filter { it.value.pending === pending }
            .map { it.key }
            .forEach { token ->
                fileTransfersByToken.remove(token)
                runCatching { activeFileTransferSockets.remove(token)?.close() }
            }
    }

    /**
     * Retire a download attempt that FINISHED, successfully or not.
     *
     * This used to clean less than abortAttempt does, and the gap leaked on
     * every completed download:
     *
     *  - pendingDownloads kept the entry forever. prunePeerConnections builds
     *    its "busy" set from that map, so every peer you ever finished a
     *    download from stayed permanently protected from eviction. Once twelve
     *    such entries accumulated, the MAX_CACHED_PEER_CONNECTIONS ceiling
     *    could evict nothing at all — it filters the busy set out first — and
     *    peerConnections grew without bound, each entry an open socket and a
     *    live reader coroutine.
     *
     *  - requeueJob was left running. abortAttempt cancels it; this did not.
     *    Its guards mean it retires itself on the next tick, but until then it
     *    can still re-send QueueUpload for a file already on disk.
     *
     * Both are now cleaned here, matching abortAttempt.
     */
    private fun releaseLease(pending: PendingDownload) {
        pending.queuePollJob?.cancel()
        pending.queuePollJob = null
        pending.requeueJob?.cancel()
        pending.requeueJob = null
        pendingDownloads.remove(
            downloadKey(pending.candidate.username, pending.candidate.filename),
            pending,
        )
        transferLeases.remove(pending.contentKey, pending)
        runCatching { partialOwnerFileFor(pending.contentKey).delete() }
        fileTransfersByToken.entries
            .filter { it.value.pending === pending }
            .map { it.key }
            .forEach { token ->
                fileTransfersByToken.remove(token)
                activeFileTransferSockets.remove(token)
            }
    }

    /**
     * Peer connections used to be cached for the lifetime of the session and
     * never given back. Every peer that answers a search opens one, so a long
     * stay on the search page accumulated sockets, coroutines and I/O buffers
     * until the process was killed.
     *
     * Connections belonging to an in-flight download are never touched.
     */
    private fun startPeerConnectionJanitor() {
        peerJanitorJob?.cancel()
        peerJanitorJob = scope.launch {
            while (isActive) {
                delay(PEER_JANITOR_INTERVAL_MS)
                runCatching { prunePeerConnections() }
            }
        }
    }

    private fun prunePeerConnections() {
        val busy = pendingDownloads.values.mapTo(HashSet()) {
            it.candidate.username.lowercase(Locale.US)
        }
        val now = System.currentTimeMillis()

        val evictable = ArrayList<Pair<String, PeerConnection>>()
        for ((username, connection) in peerConnections) {
            val protectedPeer = username.lowercase(Locale.US) in busy
            val dead = !connection.isOpen
            val idle = now - connection.lastUsedAt > PEER_CONNECTION_IDLE_TIMEOUT_MS
            if (dead || (!protectedPeer && idle)) evictable += username to connection
        }
        evictable.forEach { (username, connection) ->
            if (peerConnections.remove(username, connection)) {
                runCatching { connection.close() }
            }
        }

        // Hard ceiling as well as an idle rule: a burst of search replies can
        // open more connections than the idle timeout would retire in time.
        val excess = peerConnections.size - MAX_CACHED_PEER_CONNECTIONS
        if (excess > 0) {
            peerConnections.entries
                .filterNot { it.key.lowercase(Locale.US) in busy }
                .sortedBy { it.value.lastUsedAt }
                .take(excess)
                .forEach { (username, connection) ->
                    if (peerConnections.remove(username, connection)) {
                        runCatching { connection.close() }
                    }
                }
        }

        // Retire per-peer mutexes too, but only unlocked ones. Removing a held
        // mutex would let two builders run concurrently again; the worst case
        // if this races is a duplicate connection, which registerPeerConnection
        // already resolves in favour of the existing one.
        peerConnectMutexes.entries
            .filter { !it.value.isLocked && !peerConnections.containsKey(it.key) }
            .forEach { peerConnectMutexes.remove(it.key, it.value) }
    }

    /**
     * Open the socket peers connect back to, pinned to [network].
     *
     * Every outbound socket in this client is already created through
     * `network.socketFactory`, but the listener used a bare ServerSocket, which
     * binds the wildcard address on whatever Android considers default. With
     * Wi-Fi and cellular both up — precisely the state during a handoff — the
     * port advertised via SetWaitPort could belong to an interface other than
     * the one carrying the server session, and inbound peer connections landed
     * on the wrong network or none at all.
     *
     * There is no Network.bindSocket overload for ServerSocket, so the binding
     * is done explicitly against the network's own link address. If no usable
     * IPv4 link address is exposed, fall back to the wildcard: a listener on
     * the wrong interface is still better than no listener, since Soulseek can
     * fall back to the indirect ConnectToPeer path.
     */
    private suspend fun startListener(network: Network): Int {
        runCatching { listener?.close() }
        listenerJob?.cancelAndJoin()

        val bindAddress = runCatching {
            connectivityManager.getLinkProperties(network)
                ?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it is Inet4Address }
        }.getOrNull()

        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress ?: InetAddress.getByName("0.0.0.0"), 0), 64)
        }
        listener = server
        listenerJob = scope.launch {
            while (isActive && !server.isClosed) {
                try {
                    val socket = server.accept().also(::configurePeerSocket)
                    inboundSocketsAccepted.incrementAndGet()
                    launch { handleIncomingSocket(socket) }
                } catch (_: SocketException) {
                    break
                } catch (_: Throwable) {
                    // A malformed/hostile incoming peer must never stop the listener.
                }
            }
        }
        return server.localPort
    }

    private suspend fun readServerLoop(socket: Socket) {
        try {
            val input = serverInput ?: return
            while (scope.isActive && !socket.isClosed) {
                val frameLength = input.readU32LE().toIntChecked("server frame length")
                if (frameLength < 4 || frameLength > MAX_SERVER_FRAME_BYTES) {
                    throw SoulseekException("Soulseek sent an invalid server frame ($frameLength bytes).")
                }
                val code = input.readU32LE().toInt()
                val body = input.readExactly(frameLength - 4)
                handleServerMessage(code, body)
            }
        } catch (t: Throwable) {
            if (!socket.isClosed && serverSocket === socket) {
                val active = connectivityManager.activeNetwork
                val networkChanged = active != null && serverNetworkHandle != active.networkHandle
                val waitingForReplacement = _connectionState.value.status == SoulseekConnectionStatus.CONNECTING

                if (autoReconnectEnabled && (networkChanged || waitingForReplacement)) {
                    _connectionState.value = SoulseekConnectionState(
                        status = SoulseekConnectionStatus.CONNECTING,
                        username = currentUsername.ifBlank { savedUsername() },
                        message = "Network changed — reconnecting Soulseek automatically…",
                    )
                    _transferProgress.value = _transferProgress.value?.copy(
                        status = "Network changed — preserving download and reconnecting…",
                    )
                    active?.let { replacement ->
                        scope.launch { performNetworkHandoff(replacement) }
                    }
                } else {
                    val message = "Soulseek connection lost: ${t.message ?: "network error"}"
            slsk("SERVER lost: ${t.message ?: "network error"}")
                    if (sessionEndReason == null) {
                        sessionEndReason = "The Soulseek server connection dropped mid-session ($message)."
                    }
                    disconnectInternal(
                        message = message,
                        finalStatus = SoulseekConnectionStatus.ERROR,
                        transferFailureMessage = "Soulseek connection was lost while a transfer was active.",
                    )
                }
            }
        }
    }


    private fun startServerPingLoop(socket: Socket) {
        serverPingJob?.cancel()
        serverPingJob = scope.launch {
            while (isActive && !socket.isClosed && serverSocket === socket) {
                delay(SERVER_PING_INTERVAL_MS)
                if (socket.isClosed || serverSocket !== socket) break
                try {
                    // Protocol docs allow ServerPing at most once per minute.
                    // Mobile NATs often drop otherwise-idle TCP sessions sooner
                    // than desktop TCP keepalive defaults would notice.
                    sendServerMessage(SERVER_PING, ByteArray(0))
                } catch (_: Throwable) {
                    runCatching { socket.close() }
                    break
                }
            }
        }
    }

    private suspend fun handleServerMessage(code: Int, body: ByteArray) {
        when (code) {
            SERVER_LOGIN -> {
                val reader = SlskReader(body)
                val success = reader.bool()
                val result = if (success) {
                    val greet = reader.string()
                    if (reader.remaining >= 4) reader.u32() // own IP
                    if (reader.remaining >= 4) reader.string() // password hash
                    if (reader.remaining >= 1) reader.bool() // supporter
                    LoginResult(success = true, greet = greet)
                } else {
                    val reason = reader.stringOrNull()
                    val detail = if (reason == "INVALIDUSERNAME" && reader.remaining >= 4) {
                        reader.stringOrNull()
                    } else null
                    LoginResult(success = false, reason = reason, detail = detail)
                }
                synchronized(loginDeferredLock) {
                    loginDeferred?.takeIf { !it.isCompleted }?.complete(result)
                }
            }

            SERVER_GET_PEER_ADDRESS -> {
                val reader = SlskReader(body)
                val username = reader.string()
                val rawIp = reader.u32()
                val port = reader.u32().toInt()
                if (reader.remaining >= 4) reader.u32() // obfuscation type
                if (reader.remaining >= 2) reader.u16() // obfuscated port
                val address = PeerAddress(uint32ToIp(rawIp), port)
                pendingAddresses.remove(username)?.complete(address)
            }

            SERVER_CONNECT_TO_PEER -> {
                val reader = SlskReader(body)
                val username = reader.string()
                val type = reader.string()
                val rawIp = reader.u32()
                val port = reader.u32().toInt()
                val token = reader.u32()
                if (reader.remaining >= 1) reader.bool() // privileged
                if (reader.remaining >= 4) reader.u32() // obfuscation type
                if (reader.remaining >= 4) reader.u32() // obfuscated port
                connectToPeerRequests.incrementAndGet()
                scope.launch {
                    respondToIndirectConnection(
                        username = username,
                        type = type,
                        address = PeerAddress(uint32ToIp(rawIp), port),
                        token = token,
                    )
                }
            }

            SERVER_RELOGGED -> {
                // Soulseek permits exactly one session per account. Leaving
                // autoReconnectEnabled set here meant the next network callback
                // silently logged back in, which kicked the other client, which
                // reconnected and kicked Harmony — a ping-pong that looks from
                // the app like "search finds nothing when I use my username".
                autoReconnectEnabled = false
                sessionEndReason = SESSION_END_RELOGGED
                // Tear down on a sibling coroutine: disconnectInternal cancels
                // serverReaderJob, and we are currently running inside it.
                scope.launch {
                    disconnectInternal(
                        message = "This Soulseek account was signed in from another client. " +
                            "Soulseek allows one session per account — sign out there, then reconnect.",
                        finalStatus = SoulseekConnectionStatus.ERROR,
                        transferFailureMessage = "The Soulseek session was taken over by another client.",
                    )
                }
            }

            SERVER_CANT_CONNECT_TO_PEER -> {
                val reader = SlskReader(body)
                val token = if (reader.remaining >= 4) reader.u32() else return
                pendingIndirect.remove(token)?.deferred?.completeExceptionally(
                    SoulseekException("Peer could not establish a connection.")
                )
            }
        }
    }

    private suspend fun handleIncomingSocket(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream(), PEER_IO_BUFFER_BYTES)
            val output = BufferedOutputStream(socket.getOutputStream(), PEER_IO_BUFFER_BYTES)
            val length = input.readU32LE().toIntChecked("peer init length")
            if (length < 1 || length > MAX_PEER_INIT_BYTES) {
                throw SoulseekException("Invalid Soulseek peer-init frame.")
            }
            val initCode = input.read()
            if (initCode < 0) throw EOFException("Peer closed during init")
            val body = input.readExactly(length - 1)

            when (initCode) {
                PEER_INIT -> {
                    val r = SlskReader(body)
                    val username = r.string()
                    val type = r.string()
                    if (r.remaining >= 4) r.u32() // legacy token, always zero now
                    dispatchPeerSocket(username, type, socket, input, output)
                }

                PIERCE_FIREWALL -> {
                    val token = SlskReader(body).u32()
                    val pending = pendingIndirect.remove(token)
                    if (pending == null) {
                        socket.close()
                        return
                    }
                    val connection = ConnectedSocket(socket, input, output, pending.username, pending.type)
                    // The direct path may already have won; an uncompleted
                    // deferred means nobody will ever own this socket.
                    if (!pending.deferred.complete(connection)) {
                        runCatching { socket.close() }
                    }
                }

                else -> socket.close()
            }
        } catch (_: Throwable) {
            runCatching { socket.close() }
        }
    }

    private suspend fun respondToIndirectConnection(
        username: String,
        type: String,
        address: PeerAddress,
        token: Long,
    ) {
        // Check the type BEFORE dialling.
        //
        // The old order dialled out, opened both streams and wrote
        // PierceFirewall, and only then let dispatchPeerSocket look at the type
        // and close the socket for anything that is not "P" or "F". The Soulseek
        // server solicits every logged-in client to join the distributed search
        // network, relentlessly, with type "D" — instrumentation measured 12,445
        // relayed requests inside a single five-second search window.
        //
        // So Harmony was opening and immediately discarding thousands of TCP
        // connections per search. Those dials compete for the same sockets,
        // threads and dispatcher slots as the connections that actually matter,
        // which is a far better explanation for "the second download takes
        // forever to reach a peer" than the map leaks fixed in v1.8.3.
        //
        // Harmony does not join the distributed network, so the correct response
        // is to not dial at all. No CantConnectToPeer either: at this volume that
        // would just move the flood onto the server connection. The soliciting
        // peer times out by itself.
        val kind = type.uppercase(Locale.US)
        if (kind != "P" && kind != "F") {
            distributedSolicitations.incrementAndGet()
            return
        }

        // A "P" call-back is only worth anything while we are still listening
        // for search replies, or if it comes from a peer we are mid-download
        // with. Stragglers keep arriving for minutes after a search window
        // closes; answering them burns 4s per doomed connect and starves the
        // download path of outbound sockets.
        //
        // "F" is never dropped: that is an upload we asked for, and it is the
        // transfer itself.
        if (kind == "P" && !searchWindowOpen && !isPeerInUse(username)) {
            indirectDialsShed.incrementAndGet()
            return
        }

        // Hard ceiling regardless. Even inside a search window, hundreds of
        // simultaneous 4-second dials to unreachable peers is not useful work.
        if (!indirectDialSlots.tryAcquire()) {
            indirectDialsShed.incrementAndGet()
            return
        }
        // Single try/finally around everything after the acquire, so the permit
        // is returned on every exit path — including the port-check return
        // below, which would otherwise leak one permit per malformed address
        // until the pool was exhausted and nothing could dial at all.
        try {
            if (address.port <= 0) {
                sendCantConnectToPeer(token, username)
                return
            }
            try {
                val socket = connectSocket(address)
                val input = BufferedInputStream(socket.getInputStream(), PEER_IO_BUFFER_BYTES)
                val output = BufferedOutputStream(socket.getOutputStream(), PEER_IO_BUFFER_BYTES)
                sendPeerInitFrame(output, PIERCE_FIREWALL, SlskWriter().u32(token).bytes())
                dispatchPeerSocket(username, type, socket, input, output)
            } catch (t: Throwable) {
                indirectConnectFailures.incrementAndGet()
                slsk("PEER indirect-failed user=$username type=$type err=${t.message}")
                sendCantConnectToPeer(token, username)
            }
        } finally {
            indirectDialSlots.release()
        }
    }

    /** Are we mid-download (or mid-upload) with this peer? */
    private fun isPeerInUse(username: String): Boolean {
        val key = username.lowercase(Locale.US)
        return pendingDownloads.values.any { it.candidate.username.lowercase(Locale.US) == key } ||
            pendingUploads.values.any { it.peerUsername.lowercase(Locale.US) == key }
    }

    private suspend fun dispatchPeerSocket(
        username: String,
        type: String,
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ) {
        when (type.uppercase(Locale.US)) {
            "P" -> registerPeerConnection(username, socket, input, output)
            "F" -> handleFileConnection(username, socket, input, output)
            else -> socket.close() // Harmony does not join the distributed/chat network.
        }
    }

    private fun registerPeerConnection(
        username: String,
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ): PeerConnection {
        // Prefer an already-live connection over a newly arrived one. The old
        // behaviour unconditionally closed the previous socket, which could
        // kill a connection with an in-flight QueueUpload on it. Dead entries
        // are evicted by readPeerLoop's finally block, so this cannot pin a
        // stale connection for long.
        //
        // This must be ATOMIC, not check-then-put. peerConnectMutexes only
        // serialises the outbound builders; an inbound "P" socket arriving from
        // handleIncomingSocket reaches here without holding that gate, so a
        // read-then-put window let an inbound and an outbound registration both
        // miss, both construct, and the second put close the first out from
        // under a caller that was about to send QueueUpload — exactly the
        // failure the per-peer mutex was introduced to stop, on the one path
        // the mutex does not cover. ConcurrentHashMap.compute is atomic per key,
        // so the loser can be identified without a lock.
        val fresh = PeerConnection(username, socket, input, output)
        var displaced: PeerConnection? = null
        val winner = peerConnections.compute(username) { _, existing ->
            when {
                existing == null -> fresh
                existing.isOpen -> existing
                else -> {
                    // Record it; do not close it in here. The mapping function
                    // runs under a bin lock and must not do I/O.
                    displaced = existing
                    fresh
                }
            }
        } ?: fresh

        if (winner !== fresh) {
            // Someone else's live connection won. Drop ours; never touch theirs.
            runCatching { socket.close() }
            return winner
        }

        // A dead connection we displaced is safe to close: nobody can be mid-send
        // on a socket whose reader loop has already exited.
        displaced?.let { runCatching { it.close() } }
        // The launch also stays outside compute(), for the same reason.
        fresh.readerJob = scope.launch { readPeerLoop(fresh) }
        return fresh
    }

    private suspend fun readPeerLoop(peer: PeerConnection) {
        try {
            while (scope.isActive && !peer.socket.isClosed) {
                val frameLength = peer.input.readU32LE().toIntChecked("peer frame length")
                if (frameLength < 4 || frameLength > MAX_PEER_FRAME_BYTES) {
                    throw SoulseekException("Invalid peer frame from ${peer.username}.")
                }
                val code = peer.input.readU32LE().toInt()
                val payload = peer.input.readExactly(frameLength - 4)
                peer.lastUsedAt = System.currentTimeMillis()
                handlePeerMessage(peer, code, payload)
            }
        } catch (_: Throwable) {
            // Search peers often close P connections after replying; this is normal.
        } finally {
            peerConnections.remove(peer.username, peer)
            runCatching { peer.close() }
        }
    }

    private suspend fun handlePeerMessage(peer: PeerConnection, code: Int, payload: ByteArray) {
        when (code) {
            PEER_FILE_SEARCH_RESPONSE -> {
                // A malformed reply must not kill the peer loop, but it also
                // must not vanish: an all-failures search looks identical to an
                // all-MP3 search from the outside.
                try {
                    handleSearchResponse(payload)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    searchParseFailures.incrementAndGet()
                }
            }

            PEER_TRANSFER_REQUEST -> {
                val r = SlskReader(payload)
                val direction = r.u32().toInt()
                val token = r.u32()
                val filename = r.string()
                if (direction != 1) {
                    // Harmony is download-only. We do not accept requests to download from us.
                    peer.send(
                        PEER_TRANSFER_RESPONSE,
                        SlskWriter().u32(token).bool(false).string("Cancelled").bytes(),
                    )
                    return
                }
                val size = if (r.remaining >= 8) r.u64() else 0L
                val key = downloadKey(peer.username, filename)
                val pending = pendingDownloads[key]

                // Decline instead of going silent when this attempt is no
                // longer the owner. A silent drop left the peer holding an
                // upload slot for a transfer Harmony would never accept.
                if (pending == null || !ownsTransferLease(pending)) {
                    peer.send(
                        PEER_TRANSFER_RESPONSE,
                        SlskWriter().u32(token).bool(false).string("Cancelled").bytes(),
                    )
                    return
                }

                val expectedSize = size.takeIf { it > 0L } ?: pending.candidate.sizeBytes
                val transfer = PendingFileTransfer(token, expectedSize, pending)
                fileTransfersByToken[token] = transfer
                pending.expectedSize = expectedSize
                pending.requestAccepted.complete(Unit)
                peer.send(
                    PEER_TRANSFER_RESPONSE,
                    SlskWriter().u32(token).bool(true).bytes(),
                )
                _transferProgress.value = _transferProgress.value?.copy(
                    status = "Peer accepted. Starting transfer…",
                    totalBytes = expectedSize,
                )
            }

            PEER_PLACE_IN_QUEUE_RESPONSE -> {
                slsk("DL queue-position from ${peer.username}")
                val r = SlskReader(payload)
                val filename = r.string()
                val place = r.u32().toInt()
                pendingDownloads[downloadKey(peer.username, filename)]?.let { pending ->
                    val now = System.currentTimeMillis()
                    if (place > 0 && pending.queueStartPlace == null) {
                        pending.queueStartPlace = place
                        pending.queueWaitStartedAt = now
                    }
                    val previous = pending.lastQueuePlace
                    if (previous == null || place < previous) {
                        pending.lastQueueProgressAt = now
                    }
                    pending.lastQueuePlace = place
                    val start = pending.queueStartPlace
                    val advanced = if (start != null && place in 1 until start) start - place else 0

                    _transferProgress.value = _transferProgress.value?.copy(
                        status = when {
                            place <= 0 -> "Waiting for upload slot…"
                            advanced > 0 -> "Queued: #$place — moved up $advanced from #$start"
                            else -> "Queued: #$place"
                        },
                        queuePlace = place,
                        queueStartPlace = start,
                        queueWaitStartedAtMs = pending.queueWaitStartedAt.takeIf { it > 0L },
                        queueUpdatedAtMs = now,
                    )

                    // A queue place is a peer saying "yes, later" — it is not a
                    // failure. The fast pass still moves on quickly to look for
                    // an immediately free source, but it now reports the place
                    // so the ViewModel can come back and wait properly instead
                    // of discarding every queued peer and failing the download.
                    val limit = if (pending.patience == SoulseekDownloadPatience.PATIENT) {
                        PATIENT_QUEUE_PLACE_LIMIT
                    } else {
                        FAST_QUEUE_FALLBACK_THRESHOLD
                    }

                    if (
                        place > limit &&
                        !pending.requestAccepted.isCompleted &&
                        pending.partialFile.length() == 0L
                    ) {
                        val error = SoulseekQueuedException(
                            peerUsername = peer.username,
                            queuePlace = place,
                            message = "${peer.username} placed this request at queue #$place.",
                        )
                        pending.requestAccepted.completeExceptionally(error)
                        pending.transferStarted.completeExceptionally(error)
                    }
                }
            }

            PEER_UPLOAD_DENIED -> {
                slsk("DL denied by ${peer.username}")
                val r = SlskReader(payload)
                val filename = r.string()
                val reason = r.stringOrNull() ?: "Upload denied"
                val pending = pendingDownloads[downloadKey(peer.username, filename)]
                if (pending != null) {
                    val error = SoulseekException("${peer.username} denied the file: $reason")
                    // abortAttempt before completing so no resume can be
                    // scheduled in the window between the two.
                    abortAttempt(pending)
                    pending.requestAccepted.completeExceptionally(error)
                    pending.transferStarted.completeExceptionally(error)
                    pending.firstByteReceived.completeExceptionally(error)
                    pending.completion.completeExceptionally(error)
                }
            }

            PEER_UPLOAD_FAILED -> {
                val filename = SlskReader(payload).string()
                val key = downloadKey(peer.username, filename)
                val pending = pendingDownloads[key]
                if (pending != null && ownsTransferLease(pending)) {
                    if (!pending.requestAccepted.isCompleted && pending.partialFile.length() == 0L) {
                        val error = SoulseekException("${peer.username} could not start this upload.")
                        abortAttempt(pending)
                        pending.requestAccepted.completeExceptionally(error)
                        pending.transferStarted.completeExceptionally(error)
                        pending.firstByteReceived.completeExceptionally(error)
                        pending.completion.completeExceptionally(error)
                    } else {
                        _transferProgress.value = _transferProgress.value?.copy(
                            status = "Peer transfer interrupted; preparing resume…",
                        )
                        scheduleTransferResume(pending, networkHandoff = handoffInProgress)
                    }
                }
            }

            // --- Sharing: this and the next case are the only two places in
            // this file where Harmony behaves as an UPLOADER instead of a
            // downloader. Everything else in handlePeerMessage assumes
            // Harmony asked for something; these two assume a peer asked
            // Harmony for something.
            PEER_QUEUE_UPLOAD -> {
                val filename = SlskReader(payload).string()
                val entry = _sharedFolder.value?.find(filename)
                if (entry == null) {
                    // Spec's exact wire string for "we don't have this" —
                    // SoulseekQt uses "Banned" instead, but "File not shared."
                    // is the string clients render distinctly from an actual
                    // ban, which is what this genuinely is.
                    peer.send(PEER_UPLOAD_DENIED, SlskWriter().string(filename).string("File not shared.").bytes())
                    return
                }
                val token = nextProtocolToken()
                pendingUploads[token] = PendingUpload(
                    token = token,
                    peerUsername = peer.username,
                    entry = entry,
                )
                peer.send(
                    PEER_TRANSFER_REQUEST,
                    SlskWriter().u32(1L).u32(token).string(entry.virtualPath).u64(entry.sizeBytes).bytes(),
                )
            }

            PEER_SHARED_FILE_LIST_REQUEST -> {
                // Without this, a shared folder is invisible: peers can browse
                // you and see nothing, which reads as sharing nothing at all.
                // The v1.7.4 single-file share had this gap by design; with a
                // real folder it is worth closing.
                peer.send(PEER_SHARED_FILE_LIST_RESPONSE, buildSharedFileListPayload())
            }

            PEER_TRANSFER_RESPONSE -> {
                val r = SlskReader(payload)
                val token = r.u32()
                val allowed = r.bool()
                val upload = pendingUploads.remove(token) ?: return
                if (!allowed) {
                    // The downloader declined (already have it, cancelled,
                    // etc). Nothing was opened yet on our side; nothing to
                    // close.
                    return
                }
                // Serving the file is genuinely slow I/O (a full peer-to-peer
                // transfer), so it must not run on the shared peer read loop —
                // handlePeerMessage is called from readPeerLoop, and blocking
                // it here would stall every other message from this peer,
                // including their next unrelated request.
                scope.launch { serveSharedUpload(upload) }
            }
        }
    }

    private suspend fun handleSearchResponse(compressedPayload: ByteArray) {
        val bytes = inflateSearchPayload(compressedPayload)
        val r = SlskReader(bytes)
        val username = r.string()
        val token = r.u32()
        val session = activeSearches[token]
        if (session == null) {
            unmatchedTokenResponses.incrementAndGet()
            return
        }
        session.respondingPeers += username.lowercase(Locale.US)
        val resultCount = r.boundedCount(MAX_FILES_PER_SEARCH_RESPONSE, "search result count")
        val files = ArrayList<SearchFile>(min(resultCount, 256))
        repeat(resultCount) {
            files += r.searchFile()
        }
        val slotFree = r.bool()
        val averageSpeed = r.u32()
        val queueLength = r.u32().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (r.remaining >= 4) r.u32() // unknown, normally zero

        // Parse/consume private results as well, but don't recommend inaccessible files.
        val privateCount = if (r.remaining >= 4) {
            r.boundedCount(MAX_FILES_PER_SEARCH_RESPONSE, "private search result count")
        } else 0
        repeat(privateCount) { if (r.remaining > 0) r.searchFile() }

        session.filesSeen.addAndGet(files.size)

        for (file in files) {
            val extension = file.extension.ifBlank {
                file.filename.substringAfterLast('.', "")
            }.lowercase(Locale.US)
            if (!session.formatPreference.accepts(extension) || file.sizeBytes <= 0L) {
                session.rejectedByExtension
                    .computeIfAbsent(extension) { AtomicInteger(0) }
                    .incrementAndGet()
                continue
            }
            session.acceptedFiles.incrementAndGet()

            val id = sha1Hex("$username\u0000${file.filename}\u0000${file.sizeBytes}")
            val candidate = scoreCandidate(
                query = session.query,
                id = id,
                username = username,
                file = file,
                extension = extension,
                formatPreference = session.formatPreference,
                slotFree = slotFree,
                averageSpeed = averageSpeed,
                queueLength = queueLength,
            )
            session.candidates["$username\u0000${file.filename}"] = candidate
        }

        if (session.acceptIntoUi) {
            _searchResults.value = selectRecommendations(session)
        }
    }

    private fun snapshotDiagnostics(session: SearchSession): SoulseekSearchDiagnostics =
        SoulseekSearchDiagnostics(
            queriesSent = session.variants.size,
            variants = session.variants.toList(),
            peersResponded = session.respondingPeers.size,
            filesSeen = session.filesSeen.get(),
            acceptedFiles = session.acceptedFiles.get(),
            formatPreference = session.formatPreference,
            rejectedByExtension = session.rejectedByExtension.mapValues { it.value.get() },
            parseFailures = searchParseFailures.get(),
            unmatchedTokenResponses = unmatchedTokenResponses.get(),
            sessionEndReason = sessionEndReason,
            sharedFileCount = _sharedFolder.value?.fileCount ?: 0,
            listeningPort = _connectionState.value.listeningPort,
            inboundSocketsAccepted = inboundSocketsAccepted.get(),
            connectToPeerRequests = connectToPeerRequests.get(),
            indirectConnectFailures = indirectConnectFailures.get(),
            distributedSolicitations = distributedSolicitations.get(),
            indirectDialsShed = indirectDialsShed.get(),
        ).also {
            slsk(
                "SEARCH done peers=${it.peersResponded} files=${it.filesSeen} " +
                    "accepted=${it.acceptedFiles} format=${it.formatPreference.name} " +
                    "in=${it.inboundSocketsAccepted} relay=${it.relaysAttempted} " +
                    "failed=${it.indirectConnectFailures} shed=${it.indirectDialsShed} " +
                        "ignoredD=${it.distributedSolicitations}",
            )
        }

    private fun rankCandidates(session: SearchSession): List<SoulseekSearchCandidate> =
        session.candidates.values
            .asSequence()
            .filter { !session.onlyFreeSlots || it.freeUploadSlot }
            .sortedWith(
                compareByDescending<SoulseekSearchCandidate> { it.score }
                    .thenByDescending { it.freeUploadSlot }
                    .thenBy { it.queueLength }
                    .thenByDescending { it.averageSpeedBytesPerSecond }
            )
            .toList()

    /**
     * Keep the recommendation list useful instead of allowing a single large
     * share to occupy all ten positions. First pass prefers one result per
     * peer, then a second pass fills any remaining positions with the next
     * best candidates.
     */
    private fun selectRecommendations(session: SearchSession): List<SoulseekSearchCandidate> {
        val ranked = rankCandidates(session)
        if (ranked.size <= MAX_RECOMMENDATIONS) return ranked

        val chosen = ArrayList<SoulseekSearchCandidate>(MAX_RECOMMENDATIONS)
        val seenUsers = HashSet<String>()

        for (candidate in ranked) {
            val userKey = candidate.username.lowercase(Locale.US)
            if (userKey in seenUsers) continue
            chosen += candidate
            seenUsers += userKey
            if (chosen.size == MAX_RECOMMENDATIONS) return chosen
        }

        // If there are not enough distinct peers/files, fill the list with the
        // remaining best-ranked sources without duplicating the exact source.
        val chosenIds = chosen.mapTo(HashSet()) { it.id }
        for (candidate in ranked) {
            if (candidate.id in chosenIds) continue
            chosen += candidate
            chosenIds += candidate.id
            if (chosen.size == MAX_RECOMMENDATIONS) break
        }
        return chosen
    }

    private fun hasSufficientResults(session: SearchSession): Boolean {
        val recommendations = selectRecommendations(session)
        if (recommendations.size < MIN_RESULTS_FOR_EARLY_STOP) return false
        val distinctUsers = recommendations.map { it.username.lowercase(Locale.US) }.toSet().size
        val strongMatches = recommendations.count { it.score >= STRONG_MATCH_SCORE }
        return distinctUsers >= MIN_DISTINCT_USERS_FOR_EARLY_STOP &&
            strongMatches >= MIN_STRONG_MATCHES_FOR_EARLY_STOP
    }

    private fun scoreCandidate(
        query: String,
        id: String,
        username: String,
        file: SearchFile,
        extension: String,
        formatPreference: SoulseekFormatPreference,
        slotFree: Boolean,
        averageSpeed: Long,
        queueLength: Int,
    ): SoulseekSearchCandidate {
        val attributes = file.attributes
        val duration = attributes[1]?.takeIf { it in 1L..86_400L }?.toInt()
        val sampleRate = attributes[4]?.takeIf { it in 8_000L..768_000L }?.toInt()
        val bitDepth = attributes[5]?.takeIf { it in 8L..64L }?.toInt()
        val bitrate = attributes[0]?.takeIf { it in 1L..100_000L }?.toInt()

        val q = normalizeSearchText(query)
        val searchablePath = normalizeSearchText(file.filename)
        val rawLeaf = file.filename.substringAfterLast('\\').substringAfterLast('/')
        val leafName = normalizeSearchText(stripTrackNumber(rawLeaf))

        val qTokens = meaningfulTokens(q)
        val leafCoverage = weightedCoverage(qTokens, leafName)
        val pathCoverage = weightedCoverage(qTokens, searchablePath)

        val artistTitle = splitArtistTitle(query)
        val artistCoverage = artistTitle?.first
            ?.let(::normalizeSearchText)
            ?.let(::meaningfulTokens)
            ?.let { weightedCoverage(it, searchablePath) }
            ?: 0.0
        val titleCoverage = artistTitle?.second
            ?.let(::normalizeSearchText)
            ?.let(::meaningfulTokens)
            ?.let { weightedCoverage(it, leafName) }
            ?: leafCoverage

        // Matching is intentionally worth more than advertised hi-res quality.
        // A correct 16/44.1 track should rank above a wrong 24/192 track.
        val isLossless = SoulseekFormatPreference.isLossless(extension)
        // A result in the format the person asked for starts ahead of one in
        // the fallback format, but only by enough that a clearly better title
        // match still wins. Picking the wrong song in the right container is
        // not a good trade.
        var score = if (formatPreference.prefers(extension)) 18 else 8
        score += (leafCoverage * 34.0).toInt()
        score += (pathCoverage * 16.0).toInt()
        if (artistTitle != null) {
            score += (artistCoverage * 8.0).toInt()
            score += (titleCoverage * 14.0).toInt()
        }
        if (q.isNotBlank() && leafName.contains(q)) score += 12
        else if (q.isNotBlank() && searchablePath.contains(q)) score += 6

        if (slotFree) score += 11 else score -= 2
        if (isLossless) {
            score += when {
                bitDepth != null && bitDepth >= 24 -> 5
                bitDepth != null && bitDepth >= 16 -> 2
                else -> 0
            }
            score += when {
                sampleRate != null && sampleRate >= 96_000 -> 4
                sampleRate != null && sampleRate >= 48_000 -> 3
                sampleRate != null && sampleRate >= 44_100 -> 2
                else -> 0
            }
        } else {
            // For MP3 the only quality axis that matters is bitrate, and the
            // gap between 128 and 320 is audible in a way the FLAC bit-depth
            // ladder never is. Unreported bitrate is treated as unknown rather
            // than bad: plenty of peers send no attributes at all.
            score += when {
                bitrate == null -> 0
                bitrate >= 320 -> 6
                bitrate >= 256 -> 4
                bitrate >= 192 -> 2
                bitrate >= 160 -> 0
                bitrate >= 128 -> -4
                else -> -12
            }
        }
        if (averageSpeed > 0) {
            // Availability matters, but never enough to outrank a clearly
            // better title match on its own.
            score += (ln(1.0 + averageSpeed / 120_000.0) * 2.7).toInt().coerceIn(0, 9)
        }
        score -= min(12, queueLength.coerceAtLeast(0) * 2)

        val undesirable = listOf(
            "live", "remix", "instrumental", "karaoke", "cover",
            "sped up", "slowed", "nightcore", "reverb",
        )
        for (term in undesirable) {
            if (leafName.contains(term) && !q.contains(term)) score -= 10
        }

        // Common non-track extras that sometimes match an album/folder search.
        val nonTrackTerms = listOf("spectrogram", "log", "cue", "checksum", "artwork")
        for (term in nonTrackTerms) {
            if (leafName.contains(term)) score -= 18
        }

        // A file far too small for its advertised duration is unlikely to be a
        // normal music track. The floor has to follow the format: 35 kB/s is
        // about 280 kbps, which every FLAC clears and no MP3 ever does, so
        // applying the lossless floor to MP3 would penalise every single
        // result. 12 kB/s is roughly 96 kbps — below anything worth keeping.
        val minBytesPerSecond = if (isLossless) 35_000L else 12_000L
        if (duration != null && duration > 120 && file.sizeBytes in 1 until (duration * minBytesPerSecond)) {
            score -= 20
        }

        val reasons = buildList {
            if (titleCoverage >= 0.95 && (artistTitle == null || artistCoverage >= 0.75)) add("excellent match")
            else if (leafCoverage >= 0.75 || pathCoverage >= 0.85) add("good match")
            if (isLossless) {
                if (bitDepth != null && bitDepth >= 24) add("24-bit")
                if (sampleRate != null && sampleRate >= 96_000) add("hi-res")
            } else {
                if (bitrate != null && bitrate >= 320) add("320 kbps")
                else if (bitrate != null && bitrate <= 128) add("low bitrate")
            }
            if (slotFree) add("free slot")
            if (averageSpeed >= 2_000_000) add("fast peer")
            if (queueLength == 0) add("no queue")
        }

        return SoulseekSearchCandidate(
            id = id,
            username = username,
            filename = file.filename,
            sizeBytes = file.sizeBytes,
            extension = extension,
            durationSeconds = duration,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrate,
            freeUploadSlot = slotFree,
            averageSpeedBytesPerSecond = averageSpeed,
            queueLength = queueLength,
            score = score.coerceIn(0, 100),
            recommendation = reasons.joinToString(" · ")
                .ifBlank { "${extension.uppercase(Locale.US)} result" },
        )
    }

    private fun searchProfile(mode: SoulseekSearchMode): SearchProfile = when (mode) {
        SoulseekSearchMode.QUICK -> SearchProfile(minWindowMs = 3_200L, maxWindowMs = 5_200L)
        SoulseekSearchMode.BALANCED -> SearchProfile(minWindowMs = 5_000L, maxWindowMs = 8_800L)
        SoulseekSearchMode.DEEP -> SearchProfile(minWindowMs = 7_000L, maxWindowMs = 12_000L)
    }

    private fun buildSearchVariants(query: String, mode: SoulseekSearchMode): List<String> {
        // Keyed by lowercase: the server matches case-insensitively, so
        // "Bjork Joga" and "bjork joga" are the same request and must not
        // consume two of the limited variant slots.
        val variants = LinkedHashMap<String, String>()
        fun add(value: String) {
            val clean = value.replace(Regex("\\s+"), " ").trim()
            if (clean.length >= 2) variants.putIfAbsent(clean.lowercase(Locale.US), clean)
        }

        add(query)
        val cleaned = stripSearchNoise(query)
        add(cleaned)

        if (mode != SoulseekSearchMode.QUICK) {
            // Punctuation varies heavily between share names. A second request
            // containing the same words without separators improves recall.
            add(cleaned.replace(Regex("[\\-–—_:|]+"), " "))

            // A plain "artist title" query with no punctuation and no noise
            // words used to collapse into a single variant, because all three
            // rules above produced the identical string. Dropping stop words
            // yields a genuinely different, shorter query — and short queries
            // match more share layouts on Soulseek.
            //
            //   "the weeknd the hills" -> "weeknd hills"
            val compact = meaningfulTokens(normalizeSearchText(cleaned))
            if (compact.size >= 2) add(compact.joinToString(" "))
        }

        if (mode == SoulseekSearchMode.DEEP) {
            val split = splitArtistTitle(cleaned) ?: inferArtistTitle(cleaned)
            split?.let { (artist, title) ->
                add("$artist $title")
                // Broad fallback. Ranking still uses the original artist+title,
                // so unrelated title-only hits are pushed down.
                if (meaningfulTokens(normalizeSearchText(title)).size >= 2) add(title)
            }
            val ascii = removeDiacritics(cleaned)
            if (!ascii.equals(cleaned, ignoreCase = true)) add(ascii)
        }

        val limit = when (mode) {
            SoulseekSearchMode.QUICK -> 1
            SoulseekSearchMode.BALANCED -> 4
            SoulseekSearchMode.DEEP -> 6
        }
        return variants.values.take(limit)
    }

    /**
     * Best-effort artist/title split for queries typed without a separator.
     *
     * There is no reliable way to know where the artist ends, so this only
     * fires when a repeated leading stop word marks the boundary — the common
     * "the weeknd the hills" shape, where the second "the" starts the title.
     * Anything less clear is left alone; a wrong split would send a misleading
     * query, and ranking still scores against the full original text.
     */
    private fun inferArtistTitle(value: String): Pair<String, String>? {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 4) return null
        val lead = words.first().lowercase(Locale.US)
        if (lead !in SEARCH_STOP_WORDS) return null

        val boundary = words.drop(2).indexOfFirst { it.lowercase(Locale.US) == lead }
        if (boundary < 0) return null
        val cut = boundary + 2

        val artist = words.take(cut).joinToString(" ")
        val title = words.drop(cut).joinToString(" ")
        if (artist.length < 2 || title.length < 2) return null
        return artist to title
    }

    private fun stripSearchNoise(value: String): String = value
        .replace(Regex("(?i)\\[(?:official\\s+)?(?:audio|video|lyrics?|lyric\\s+video|music\\s+video|visuali[sz]er|4k|hd)\\]"), " ")
        .replace(Regex("(?i)\\((?:official\\s+)?(?:audio|video|lyrics?|lyric\\s+video|music\\s+video|visuali[sz]er|4k|hd)\\)"), " ")
        .replace(Regex("(?i)\\b(?:official\\s+audio|official\\s+video|lyric\\s+video|music\\s+video)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun splitArtistTitle(value: String): Pair<String, String>? {
        val match = Regex("^(.+?)\\s+[-–—]\\s+(.+)$").find(value.trim()) ?: return null
        val artist = match.groupValues[1].trim()
        val title = match.groupValues[2].trim()
        if (artist.length < 2 || title.length < 2) return null
        return artist to title
    }

    private fun meaningfulTokens(normalized: String): List<String> = normalized
        .split(' ')
        .filter { token -> token.length >= 2 && token !in SEARCH_STOP_WORDS }
        .distinct()

    private fun weightedCoverage(tokens: List<String>, haystack: String): Double {
        if (tokens.isEmpty()) return 0.0
        var totalWeight = 0.0
        var matchedWeight = 0.0
        for (token in tokens) {
            val weight = 1.0 + (token.length.coerceAtMost(10) / 10.0)
            totalWeight += weight
            if (haystack.contains(token)) matchedWeight += weight
        }
        return if (totalWeight <= 0.0) 0.0 else matchedWeight / totalWeight
    }

    private fun stripTrackNumber(value: String): String = value
        .replace(Regex("^\\s*(?:cd\\s*\\d+\\s*)?\\d{1,3}\\s*[-._)]\\s*", RegexOption.IGNORE_CASE), "")
        .substringBeforeLast('.', value)



    /**
     * Race a few high-confidence peers before sending the actual file request.
     *
     * Only the P (peer/control) connection is prepared here: no duplicate file
     * transfer requests are sent. The first reachable peer is returned at once,
     * while the other short-lived connection attempts are allowed to finish in
     * the background so they can act as warm fallbacks if the first upload is
     * denied or never starts.
     */
    suspend fun racePeerConnections(
        candidates: List<SoulseekSearchCandidate>,
        maxPeers: Int = 3,
    ): SoulseekSearchCandidate? = withContext(Dispatchers.IO) {
        requireConnected()
        val unique = candidates
            .distinctBy { it.username.lowercase(Locale.US) }
            .take(maxPeers.coerceIn(1, MAX_PRECONNECT_RACE_PEERS))
        if (unique.isEmpty()) return@withContext null

        val network = requireActiveNetwork()
        val cellular = isCellularNetwork(network)
        if (serverNetworkHandle != null && serverNetworkHandle != network.networkHandle && autoReconnectEnabled) {
            performNetworkHandoff(network)
            requireConnected()
        }

        val first = unique.first()
        _transferProgress.value = SoulseekTransferProgress(
            username = first.username,
            filename = first.fileNameOnly,
            status = "Preparing ${unique.size} fast sources in parallel…",
            downloadedBytes = 0L,
            totalBytes = first.sizeBytes,
        )

        // The racers must be CHILDREN of this call, not of the client's own
        // scope. Launched into `scope` they outlived both the race window and
        // the caller: every loser ran to completion and opened a full peer
        // connection nobody had asked for, and navigating away mid-search
        // cancelled the awaiting coroutine while leaving N builders running.
        // coroutineScope ties them to this frame; cancelling the losers the
        // moment a winner appears stops the rest of the work immediately.
        // NOT coroutineScope. That was the v1.7.1 fix for leaked racers, and it
        // introduced a worse bug: coroutineScope joins every child before it
        // returns, and cancel() cannot interrupt a coroutine blocked in socket
        // IO. So the race stopped finishing when a winner appeared and started
        // finishing when the SLOWEST racer gave up — tens of seconds, during
        // which the UI sat on "Preparing N sources in parallel…" with nothing
        // to show.
        //
        // Racers go back on `scope` so the race returns the moment it resolves.
        // The leak stays fixed because the losers are still cancelled here, and
        // buildPeerConnection's own finally closes whatever socket a straggler
        // eventually produces.
        val selected = run {
            val winner = CompletableDeferred<SoulseekSearchCandidate?>()
            val failures = AtomicInteger(0)
            val racers = unique.map { candidate ->
                scope.launch {
                    try {
                        withTimeout(preconnectPeerTimeoutMs(cellular)) {
                            getOrCreatePeerConnection(
                                username = candidate.username,
                                cellularHint = cellular,
                                announceStatus = false,
                            )
                        }
                        winner.complete(candidate)
                    } catch (_: TimeoutCancellationException) {
                        // This peer ran out of its own budget: a real failure,
                        // and it must be counted. Caught BEFORE CancellationException
                        // because TimeoutCancellationException is a subclass —
                        // ordering these the other way round swallows every
                        // per-peer timeout and the tally never reaches the
                        // all-failed threshold.
                        if (failures.incrementAndGet() >= unique.size) {
                            winner.complete(null)
                        }
                    } catch (e: CancellationException) {
                        // We lost the race and the parent cancelled us. Not a
                        // failure: counting it could resolve the race to null
                        // after a winner already existed. Rethrow so structured
                        // concurrency stays intact.
                        throw e
                    } catch (_: Throwable) {
                        if (failures.incrementAndGet() >= unique.size) {
                            winner.complete(null)
                        }
                    }
                }
            }

            val result = try {
                withTimeout(preconnectRaceWindowMs(cellular)) { winner.await() }
            } catch (_: TimeoutCancellationException) {
                null
            }
            racers.forEach { it.cancel() }
            result
        }

        if (selected != null) {
            _transferProgress.value = _transferProgress.value?.copy(
                username = selected.username,
                filename = selected.fileNameOnly,
                totalBytes = selected.sizeBytes,
                status = "Fast source ready — requesting ${selected.username}…",
            )
        } else {
            _transferProgress.value = _transferProgress.value?.copy(
                status = "No peer answered the quick race — trying ranked sources…",
            )
        }
        selected
    }

    private fun preconnectPeerTimeoutMs(cellular: Boolean): Long =
        if (cellular) PRECONNECT_PEER_CELLULAR_MS else PRECONNECT_PEER_WIFI_MS

    private fun preconnectRaceWindowMs(cellular: Boolean): Long =
        if (cellular) PRECONNECT_RACE_CELLULAR_MS else PRECONNECT_RACE_WIFI_MS

    private suspend fun getOrCreatePeerConnection(
        username: String,
        cellularHint: Boolean = isCellularNetwork(),
        announceStatus: Boolean = true,
    ): PeerConnection {
        peerConnections[username]?.takeIf { it.isOpen }?.let { return it }

        // Serialise per peer. The preconnect race and the download attempt
        // routinely ask for the same username within a few hundred ms; running
        // both builders concurrently produced two sockets, and registering the
        // second one closed the first out from under its caller.
        val gate = peerConnectMutexes.computeIfAbsent(username) { Mutex() }
        return gate.withLock {
            peerConnections[username]?.takeIf { it.isOpen }
                ?: buildPeerConnection(username, cellularHint, announceStatus)
        }
    }

    private suspend fun buildPeerConnection(
        username: String,
        cellularHint: Boolean,
        announceStatus: Boolean,
    ): PeerConnection {
        if (cellularHint && announceStatus) {
            _transferProgress.value = _transferProgress.value?.copy(
                status = "Mobile data: trying direct + reverse connection…"
            )
        }

        val indirectToken = nextProtocolToken()
        val indirectDeferred = CompletableDeferred<ConnectedSocket>()
        pendingIndirect[indirectToken] = PendingIndirectConnection(
            username = username,
            type = "P",
            deferred = indirectDeferred,
        )

        // Ask for both connection paths immediately. On mobile networks, a
        // reverse/indirect connection can arrive much faster than a direct
        // address route (or vice versa), so do not serially wait for one path
        // before trying the other. First successful path wins.
        sendServerMessage(
            SERVER_CONNECT_TO_PEER,
            SlskWriter().u32(indirectToken).string(username).string("P").bytes(),
        )

        val addressDeferred = CompletableDeferred<PeerAddress>()
        pendingAddresses[username]?.completeExceptionally(SoulseekException("Superseded by a new peer request."))
        pendingAddresses[username] = addressDeferred
        sendServerMessage(SERVER_GET_PEER_ADDRESS, SlskWriter().string(username).bytes())

        val winner = CompletableDeferred<ConnectedSocket>()
        // Sockets the race produced, and the one actually handed to a caller.
        // The finally block closes the difference. AtomicReference rather than
        // a plain var because both racers write it from other threads.
        val racedSocket = java.util.concurrent.atomic.AtomicReference<ConnectedSocket?>(null)
        var claimedSocket: ConnectedSocket? = null
        val failureCount = AtomicInteger(0)
        val errors = mutableListOf<Throwable>()

        fun reportFailure(t: Throwable) {
            synchronized(errors) { errors += t }
            if (failureCount.incrementAndGet() >= 2 && !winner.isCompleted) {
                val detail = synchronized(errors) {
                    errors.mapNotNull { it.message }.distinct().joinToString("; ")
                }
                winner.completeExceptionally(
                    SoulseekException(
                        "Could not connect to $username${if (detail.isBlank()) "" else " ($detail)"}.",
                        t,
                    )
                )
            }
        }

        val directJob = scope.launch {
            try {
                val address = withTimeout(PEER_ADDRESS_TIMEOUT_MS) { addressDeferred.await() }
                if (address.port <= 0) throw SoulseekException("Peer did not publish a usable direct port.")
                val socket = connectSocket(address)
                val input = BufferedInputStream(socket.getInputStream(), PEER_IO_BUFFER_BYTES)
                val output = BufferedOutputStream(socket.getOutputStream(), PEER_IO_BUFFER_BYTES)
                sendPeerInitFrame(
                    output,
                    PEER_INIT,
                    SlskWriter().string(currentUsername).string("P").u32(0).bytes(),
                )
                val connected = ConnectedSocket(socket, input, output, username, "P")
                racedSocket.set(connected)
                if (!winner.complete(connected)) runCatching { socket.close() }
            } catch (t: Throwable) {
                reportFailure(t)
            }
        }

        val indirectJob = scope.launch {
            try {
                val connected = withTimeout(if (cellularHint) INDIRECT_CONNECTION_TIMEOUT_CELLULAR_MS else INDIRECT_CONNECTION_TIMEOUT_MS) { indirectDeferred.await() }
                racedSocket.set(connected)
                if (!winner.complete(connected)) runCatching { connected.socket.close() }
            } catch (t: Throwable) {
                reportFailure(t)
            }
        }

        return try {
            val connected = withTimeout(if (cellularHint) PEER_CONNECTION_RACE_TIMEOUT_CELLULAR_MS else PEER_CONNECTION_RACE_TIMEOUT_MS) { winner.await() }
            claimedSocket = connected
            slsk("PEER connected user=$username via=${connected.type}")
            registerPeerConnection(username, connected.socket, connected.input, connected.output)
        } finally {
            directJob.cancel()
            indirectJob.cancel()
            pendingAddresses.remove(username, addressDeferred)
            pendingIndirect.remove(indirectToken)?.deferred?.cancel()

            // Close a socket the race produced but nobody claimed. If the
            // outer withTimeout fired just as a racer completed `winner`, that
            // socket was never returned to a caller and never registered, so
            // nothing else will ever close it. Flagged in v7.8 and left open
            // then; it leaks one socket and one peer-side connection per
            // late-losing race, which is exactly the kind of drip that makes
            // later connections progressively harder to establish.
            val produced = racedSocket.getAndSet(null)
            if (produced != null && produced !== claimedSocket) {
                runCatching { produced.socket.close() }
            }
        }
    }

    /**
     * Open an outbound F connection to [upload.peerUsername] and stream
     * [upload.entry] to them, starting from whatever offset they report.
     *
     * This mirrors [buildPeerConnection]'s direct/indirect race exactly, but
     * for connection type "F" instead of "P", and it does not go through
     * [registerPeerConnection] — file connections are one-shot per transfer,
     * never cached or reused, matching how Harmony already treats the
     * download-side F connections it receives.
     */
    /**
     * SharedFileListResponse (peer code 5) payload.
     *
     * Structure per the protocol reference: uint32 directory count, then per
     * directory a name, a file count, and per file a `1` marker, name, uint64
     * size, extension and attribute pairs. Then a uint32 `0` the official
     * clients always send, then a private-directory count of 0. The whole
     * thing is zlib-compressed — the message is uncompressed nowhere.
     *
     * Files are announced with their LEAF name only; the directory name
     * carries the path. Sending full paths as filenames makes browsers show
     * the path twice.
     */
    private fun buildSharedFileListPayload(): ByteArray {
        val share = _sharedFolder.value
        val byFolder = share?.entries?.groupBy { it.virtualFolder }.orEmpty()

        val w = SlskWriter().u32(byFolder.size.toLong())
        for ((folder, files) in byFolder) {
            w.string(folder).u32(files.size.toLong())
            for (f in files) {
                w.u8(1)
                    .string(f.virtualPath.substringAfterLast('\\'))
                    .u64(f.sizeBytes)
                    .string(f.extension)
                // Attribute count 0: Harmony does not read bitrate/duration off
                // shared files, and the spec allows attributes to be absent.
                    .u32(0L)
            }
        }
        w.u32(0L)  // "unknown", official clients always send 0
        w.u32(0L)  // private directory count

        val raw = w.bytes()
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private suspend fun serveSharedUpload(upload: PendingUpload) {
        val cellular = isCellularNetwork()
        val indirectToken = nextProtocolToken()
        val indirectDeferred = CompletableDeferred<ConnectedSocket>()
        pendingIndirect[indirectToken] = PendingIndirectConnection(
            username = upload.peerUsername,
            type = "F",
            deferred = indirectDeferred,
        )

        sendServerMessage(
            SERVER_CONNECT_TO_PEER,
            SlskWriter().u32(indirectToken).string(upload.peerUsername).string("F").bytes(),
        )

        val addressDeferred = CompletableDeferred<PeerAddress>()
        pendingAddresses[upload.peerUsername]?.completeExceptionally(
            SoulseekException("Superseded by a new peer request."),
        )
        pendingAddresses[upload.peerUsername] = addressDeferred
        sendServerMessage(SERVER_GET_PEER_ADDRESS, SlskWriter().string(upload.peerUsername).bytes())

        val winner = CompletableDeferred<ConnectedSocket>()

        val directJob = scope.launch {
            try {
                val address = withTimeout(PEER_ADDRESS_TIMEOUT_MS) { addressDeferred.await() }
                if (address.port <= 0) throw SoulseekException("Peer did not publish a usable direct port.")
                val socket = connectSocket(address)
                val input = BufferedInputStream(socket.getInputStream(), PEER_IO_BUFFER_BYTES)
                val output = BufferedOutputStream(socket.getOutputStream(), PEER_IO_BUFFER_BYTES)
                sendPeerInitFrame(
                    output,
                    PEER_INIT,
                    SlskWriter().string(currentUsername).string("F").u32(0).bytes(),
                )
                val connected = ConnectedSocket(socket, input, output, upload.peerUsername, "F")
                if (!winner.complete(connected)) runCatching { socket.close() }
            } catch (_: Throwable) {
                // The indirect path may still land; buildPeerConnection reports
                // failures back to the caller because a download has someone
                // waiting on the result. Nobody is waiting on a share upload
                // the same way, so there is nothing useful to surface here —
                // the overall timeout below is what actually gives up.
            }
        }

        val indirectJob = scope.launch {
            try {
                val connected = withTimeout(
                    if (cellular) INDIRECT_CONNECTION_TIMEOUT_CELLULAR_MS else INDIRECT_CONNECTION_TIMEOUT_MS,
                ) { indirectDeferred.await() }
                if (!winner.complete(connected)) runCatching { connected.socket.close() }
            } catch (_: Throwable) {
                // See directJob's comment.
            }
        }

        val connected = try {
            withTimeout(
                if (cellular) PEER_CONNECTION_RACE_TIMEOUT_CELLULAR_MS else PEER_CONNECTION_RACE_TIMEOUT_MS,
            ) { winner.await() }
        } catch (_: Throwable) {
            directJob.cancel()
            indirectJob.cancel()
            pendingAddresses.remove(upload.peerUsername, addressDeferred)
            pendingIndirect.remove(indirectToken)?.deferred?.cancel()
            return
        }
        directJob.cancel()
        indirectJob.cancel()
        pendingAddresses.remove(upload.peerUsername, addressDeferred)
        pendingIndirect.remove(indirectToken)?.deferred?.cancel()

        streamSharedFile(connected.socket, connected.input, connected.output, upload)
    }

    /**
     * The actual byte-pushing over an established F connection.
     *
     * Wire sequence (from the Nicotine+ Soulseek protocol reference): we write
     * FileTransferInit (a bare uint32 token, no message-code framing — F
     * connections carry no codes at all), the downloader replies with
     * FileOffset (a bare uint64), and from there it is a raw byte stream until
     * every expected byte has been sent.
     *
     * "It is always the downloader's responsibility to close the connection to
     * indicate a completed transfer. The uploader must not close it." Harmony
     * honors that — it does not close on success — but it will not wait
     * forever for an uncooperative peer either; UPLOAD_SERVE_CLOSE_GRACE_MS
     * below is a local safety net, not a protocol requirement.
     */
    private fun streamSharedFile(
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        upload: PendingUpload,
    ) {
        try {
            output.writeU32LE(upload.token)
            output.flush()
            val offset = input.readU64LE()
            if (offset < 0L || offset > upload.entry.sizeBytes) {
                throw SoulseekException("Peer requested an out-of-range resume offset.")
            }

            runCatching { socket.receiveBufferSize = FILE_TRANSFER_RECEIVE_BUFFER_BYTES }
            runCatching { socket.soTimeout = FILE_TRANSFER_STALL_TIMEOUT_MS.toInt() }

            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(upload.entry.documentUri), "r")
                ?: throw SoulseekException("Shared file is no longer accessible.")
            pfd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { fileIn ->
                    // Seek via the channel rather than InputStream.skip(): skip
                    // is only advisory and may return short on some SAF-backed
                    // providers, which would silently resume from the wrong
                    // byte. Positioning the channel is exact or throws.
                    fileIn.channel.position(offset)
                    val buffer = ByteArray(FILE_TRANSFER_BUFFER_BYTES)
                    var sent = offset
                    while (sent < upload.entry.sizeBytes) {
                        val toRead = min(buffer.size.toLong(), upload.entry.sizeBytes - sent).toInt()
                        val read = fileIn.read(buffer, 0, toRead)
                        if (read < 0) throw SoulseekException("Shared file ended before the advertised size.")
                        output.write(buffer, 0, read)
                        sent += read
                    }
                    output.flush()
                }
            }
            // Per protocol, wait for the downloader to close first. Bounded so
            // a peer that never closes cannot pin this socket (and its file
            // descriptor) open indefinitely.
            runCatching { socket.soTimeout = UPLOAD_SERVE_CLOSE_GRACE_MS }
            runCatching { input.read() }
        } catch (_: Throwable) {
            // Best-effort reciprocity: a failed share upload should not
            // surface as an error anywhere Harmony's own downloads do. If it
            // matters, the peer will simply re-request.
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun handleFileConnection(
        username: String,
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ) {
        var activeToken: Long? = null
        var activeTransfer: PendingFileTransfer? = null
        try {
            val token = input.readU32LE()
            activeToken = token
            var transfer = fileTransfersByToken[token]
            if (transfer == null) {
                // The F connection can beat processing of TransferRequest on a busy device.
                var waits = 0
                while (transfer == null && waits < FILE_TRANSFER_TOKEN_WAIT_STEPS) {
                    delay(50)
                    transfer = fileTransfersByToken[token]
                    waits += 1
                }
            }
            val actual = transfer ?: throw SoulseekException("Unknown Soulseek file-transfer token.")
            if (actual.pending.candidate.username != username) {
                // Do NOT claim activeTransfer/the socket map here: this socket is
                // not the one this token belongs to, and the catch block must not
                // be able to evict the genuine transfer on its way out.
                throw SoulseekException("Soulseek transfer peer did not match the queued source.")
            }
            activeTransfer = actual
            slsk("DL transfer opened user=$username bytes=${actual.sizeBytes}")
            // A peer that retries its F connection can open a second socket for
            // the same token. Replacing the map entry without closing the old
            // one leaked a socket that kept reading into the same partial.
            activeFileTransferSockets.put(token, socket)
                ?.takeIf { it !== socket }
                ?.let { previous -> runCatching { previous.close() } }

            // A peer that answers slowly can open its F socket after the
            // ViewModel has already given up on it. Admitting that socket meant
            // a second writer on a partial file another attempt now owns.
            if (!ownsTransferLease(actual.pending)) {
                throw SoulseekException("Soulseek transfer arrived after this source was abandoned.")
            }
            actual.pending.transferStarted.complete(Unit)

            // A dead peer should not leave a phone stuck forever on a socket read.
            // Timeout triggers the existing resume path from the exact byte offset.
            // This one really is a bulk socket, so give it the large window
            // that every peer connection used to get by default.
            runCatching { socket.receiveBufferSize = FILE_TRANSFER_RECEIVE_BUFFER_BYTES }
            runCatching {
                socket.soTimeout = if (isCellularNetwork()) {
                    FILE_TRANSFER_STALL_TIMEOUT_CELLULAR_MS
                } else {
                    FILE_TRANSFER_STALL_TIMEOUT_MS
                }
            }

            val partial = actual.pending.partialFile
            partial.parentFile?.mkdirs()
            if (partial.length() > actual.sizeBytes) {
                if (!partial.delete()) {
                    throw SoulseekException("Could not reset an oversized partial Soulseek download.")
                }
            }
            var offset = prepareResumePoint(actual.pending, username)
            output.writeU64LE(offset)
            output.flush()

            _transferProgress.value = SoulseekTransferProgress(
                username = username,
                filename = actual.pending.candidate.fileNameOnly,
                status = "Transfer opened — waiting for audio data…",
                downloadedBytes = offset,
                totalBytes = actual.sizeBytes,
            )

            // Larger sequential buffers reduce Java/native crossings and storage
            // writes on fast Wi-Fi/5G peers without changing Soulseek semantics.
            val buffer = ByteArray(FILE_TRANSFER_BUFFER_BYTES)
            var lastUiAt = System.nanoTime()
            var lastUiBytes = offset
            var speed = 0L
            var smoothedSpeed = 0.0
            // Positional writes instead of append mode. Append resolves to
            // "current end of file", which silently does the wrong thing if
            // anything else ever holds the same partial open; an explicit
            // offset cannot interleave. setLength also drops any bytes a
            // previous aborted writer left past our resume point.
            RandomAccessFile(partial, "rw").use { raf ->
                raf.setLength(offset)
                val channel = raf.channel
                while (offset < actual.sizeBytes) {
                    if (!ownsTransferLease(actual.pending)) {
                        throw SoulseekException("This source was superseded while transferring.")
                    }
                    val wanted = min(buffer.size.toLong(), actual.sizeBytes - offset).toInt()
                    val read = input.read(buffer, 0, wanted)
                    if (read < 0) throw EOFException("Peer disconnected at $offset/${actual.sizeBytes} bytes")
                    if (read == 0) continue
                    var written = 0
                    while (written < read) {
                        written += channel.write(
                            ByteBuffer.wrap(buffer, written, read - written),
                            offset + written,
                        )
                    }
                    offset += read
                    actual.pending.lastProgressAt = System.nanoTime()

                    if (!actual.pending.firstByteReceived.isCompleted) {
                        actual.pending.firstByteReceived.complete(Unit)
                        _transferProgress.value = SoulseekTransferProgress(
                            username = username,
                            filename = actual.pending.candidate.fileNameOnly,
                            status = if (offset > read.toLong()) "Resuming download…" else "Downloading…",
                            downloadedBytes = offset,
                            totalBytes = actual.sizeBytes,
                        )
                    }

                    val now = System.nanoTime()
                    val elapsedNs = now - lastUiAt
                    if (elapsedNs >= TRANSFER_UI_UPDATE_INTERVAL_NS || offset == actual.sizeBytes) {
                        val elapsedSec = elapsedNs / 1_000_000_000.0
                        if (elapsedSec > 0.0) {
                            val instantSpeed = ((offset - lastUiBytes) / elapsedSec).coerceAtLeast(0.0)
                            smoothedSpeed = if (smoothedSpeed <= 0.0) {
                                instantSpeed
                            } else {
                                (smoothedSpeed * 0.65) + (instantSpeed * 0.35)
                            }
                            speed = smoothedSpeed.toLong().coerceAtLeast(0L)
                        }
                        lastUiAt = now
                        lastUiBytes = offset
                        _transferProgress.value = SoulseekTransferProgress(
                            username = username,
                            filename = actual.pending.candidate.fileNameOnly,
                            status = "Downloading…",
                            downloadedBytes = offset,
                            totalBytes = actual.sizeBytes,
                            speedBytesPerSecond = speed,
                        )
                    }
                }
                runCatching { channel.force(false) }
            }

            // Downloader closes the F connection when the expected byte count is complete.
            runCatching { socket.close() }
            runCatching { partialOwnerFileFor(actual.pending.contentKey).delete() }

            val verificationError = verifyAudioContainer(
                file = partial,
                expectedSize = actual.pending.candidate.sizeBytes,
                extension = actual.pending.candidate.extension,
            )
            if (verificationError != null) {
                abortAttempt(actual.pending)
                // A partial that produced a bad container must not be offered
                // to the next source as a resume point.
                runCatching { partial.delete() }
                runCatching { partialOwnerFileFor(actual.pending.contentKey).delete() }

                val error = SoulseekException(
                    "Downloaded ${actual.pending.candidate.extension.uppercase(Locale.US)} " +
                        "failed verification: $verificationError"
                )
                _transferProgress.value = SoulseekTransferProgress(
                    username = username,
                    filename = actual.pending.candidate.fileNameOnly,
                    status = "Download rejected: $verificationError",
                    downloadedBytes = 0L,
                    totalBytes = actual.pending.candidate.sizeBytes,
                    speedBytesPerSecond = 0L,
                )
                actual.pending.completion.completeExceptionally(error)
                return
            }

            fileTransfersByToken.remove(token)
            activeFileTransferSockets.remove(token)
            actual.pending.requeueJob?.cancel()
            actual.pending.requeueJob = null
            pendingDownloads.remove(
                downloadKey(username, actual.pending.candidate.filename),
                actual.pending,
            )

            // The saved name has to carry the format the peer actually sent.
            // Naming an MP3 ".flac" would sail past the library scanner and
            // then surface as a corrupt lossless file much later.
            val extension = actual.pending.candidate.extension.lowercase(Locale.US)
            val finalName = sanitizeFileName(actual.pending.candidate.fileNameOnly).let {
                if (it.endsWith(".$extension", true)) it else "$it.$extension"
            }
            _transferProgress.value = SoulseekTransferProgress(
                username = username,
                filename = finalName,
                status = "Download complete",
                downloadedBytes = actual.sizeBytes,
                totalBytes = actual.sizeBytes,
                speedBytesPerSecond = speed,
            )
            actual.pending.completion.complete(
                SoulseekDownloadedFile(
                    tempFile = partial,
                    suggestedFileName = finalName,
                    sizeBytes = actual.sizeBytes,
                )
            )
        } catch (t: Throwable) {
            runCatching { socket.close() }
            // Identity-scoped teardown. The old code removed by key alone, so a
            // stray or retried F connection quoting a live token deregistered a
            // transfer that was still healthy: the real uploader then found no
            // token, waited out FILE_TRANSFER_TOKEN_WAIT_STEPS and failed as
            // "Unknown Soulseek file-transfer token", and cancelActiveDownload
            // lost its handle on the socket. Both maps are now only cleared when
            // the entry still belongs to the transfer this call actually owned.
            val owned = activeTransfer
            if (owned != null) {
                activeToken?.let { token ->
                    fileTransfersByToken.remove(token, owned)
                    if (activeFileTransferSockets[token] === socket) {
                        activeFileTransferSockets.remove(token, socket)
                    }
                }
            }
            val pending = activeTransfer?.pending
            if (pending != null && !pending.completion.isCompleted && !pending.cancelled) {
                _transferProgress.value = _transferProgress.value?.copy(
                    status = "Transfer interrupted; retrying from ${pending.partialFile.length()} bytes…",
                    speedBytesPerSecond = 0L,
                )
                scheduleTransferResume(pending)
            }
        }
    }

    private fun scheduleTransferResume(
        pending: PendingDownload,
        networkHandoff: Boolean = false,
    ) {
        if (pending.completion.isCompleted || pending.requeueJob?.isActive == true) return

        // The single most direct cause of a ghost download: an attempt the
        // ViewModel has already walked past re-queuing itself with its old
        // peer, then racing the current attempt for the same partial file.
        if (!ownsTransferLease(pending)) return
        pending.requeueJob = scope.launch {
            if (!networkHandoff) delay(TRANSFER_RETRY_DELAY_MS)
            if (pending.completion.isCompleted) {
                pending.requeueJob = null
                return@launch
            }

            // During Wi-Fi <-> cellular handoff the server session is rebuilt first.
            // Keep the partial file and wait instead of wasting peer retry attempts.
            while (
                isActive &&
                !pending.completion.isCompleted &&
                (handoffInProgress || (autoReconnectEnabled && _connectionState.value.status != SoulseekConnectionStatus.CONNECTED))
            ) {
                delay(NETWORK_HANDOFF_RESUME_POLL_MS)
            }
            if (pending.completion.isCompleted || !ownsTransferLease(pending)) {
                pending.requeueJob = null
                return@launch
            }
            if (_connectionState.value.status != SoulseekConnectionStatus.CONNECTED) {
                pending.requeueJob = null
                pendingDownloads.remove(
                    downloadKey(pending.candidate.username, pending.candidate.filename),
                    pending,
                )
                pending.completion.completeExceptionally(
                    SoulseekException("Soulseek disconnected before the transfer could resume.")
                )
                return@launch
            }

            if (!networkHandoff) {
                if (pending.retryAttempts >= MAX_TRANSFER_RESUME_ATTEMPTS) {
                    pending.requeueJob = null
                    pendingDownloads.remove(
                        downloadKey(pending.candidate.username, pending.candidate.filename),
                        pending,
                    )
                    pending.completion.completeExceptionally(
                        SoulseekException("The peer repeatedly interrupted this transfer. Try another recommended source.")
                    )
                    return@launch
                }
                pending.retryAttempts += 1
            }

            try {
                val peer = getOrCreatePeerConnection(pending.candidate.username)
                pending.peerConnection = peer

                // Every resume attempt needs fresh gates. The first attempt's deferreds
                // have already completed and cannot be reused as a health signal.
                pending.requestAccepted = CompletableDeferred()
                pending.transferStarted = CompletableDeferred()
                pending.firstByteReceived = CompletableDeferred()
                val transferStarted = pending.transferStarted
                val firstByteReceived = pending.firstByteReceived

                peer.send(PEER_QUEUE_UPLOAD, SlskWriter().string(pending.candidate.filename).bytes())
                peer.send(PEER_PLACE_IN_QUEUE_REQUEST, SlskWriter().string(pending.candidate.filename).bytes())
                _transferProgress.value = _transferProgress.value?.copy(
                    status = if (networkHandoff) {
                        "Network switched — resume requested from ${pending.partialFile.length()} bytes…"
                    } else {
                        "Resume requested (attempt ${pending.retryAttempts}/$MAX_TRANSFER_RESUME_ATTEMPTS)…"
                    },
                )

                val cellular = isCellularNetwork()
                withTimeout(
                    requestAcceptTimeoutMs(pending.candidate, cellular, pending.patience) +
                        transferStartTimeoutMs(cellular, pending.patience)
                ) {
                    transferStarted.await()
                }
                withTimeout(firstByteTimeoutMs(cellular, pending.patience)) {
                    firstByteReceived.await()
                }
                pending.requeueJob = null
            } catch (_: TimeoutCancellationException) {
                // Clear this exact job before recursion; otherwise the isActive guard
                // at the top would mistake the current watchdog for a pending retry.
                pending.requeueJob = null
                if (pending.completion.isCompleted) return@launch

                if (pending.retryAttempts >= MAX_TRANSFER_RESUME_ATTEMPTS) {
                    pendingDownloads.remove(
                        downloadKey(pending.candidate.username, pending.candidate.filename),
                        pending,
                    )
                    pending.completion.completeExceptionally(
                        SoulseekException("The peer did not restart the transfer in time. Trying another recommended source.")
                    )
                } else {
                    // A network-handoff resume never consumes the normal peer retry.
                    scheduleTransferResume(pending, networkHandoff = false)
                }
            } catch (cancelled: CancellationException) {
                pending.requeueJob = null
                throw cancelled
            } catch (_: Throwable) {
                pending.requeueJob = null
                if (!pending.completion.isCompleted) {
                    scheduleTransferResume(pending, networkHandoff = false)
                }
            }
        }
    }

    /**
     * A search result that advertises a free slot should start very quickly.
     * Do not make the user stare at a queued request for tens of seconds: if a
     * peer does not send TransferRequest within this window, the ViewModel can
     * immediately fall back to another ranked source.
     */
    private fun requestAcceptTimeoutMs(
        candidate: SoulseekSearchCandidate,
        cellular: Boolean,
        patience: SoulseekDownloadPatience,
    ): Long {
        if (patience == SoulseekDownloadPatience.PATIENT) return PATIENT_REQUEST_ACCEPT_MS
        return when {
            candidate.freeUploadSlot || candidate.queueLength <= 0 ->
                if (cellular) REQUEST_ACCEPT_FREE_CELLULAR_MS else REQUEST_ACCEPT_FREE_WIFI_MS
            candidate.queueLength <= 2 ->
                if (cellular) REQUEST_ACCEPT_SMALL_QUEUE_CELLULAR_MS else REQUEST_ACCEPT_SMALL_QUEUE_WIFI_MS
            else ->
                if (cellular) REQUEST_ACCEPT_QUEUED_CELLULAR_MS else REQUEST_ACCEPT_QUEUED_WIFI_MS
        }
    }

    private fun transferStartTimeoutMs(
        cellular: Boolean,
        patience: SoulseekDownloadPatience,
    ): Long = when {
        patience == SoulseekDownloadPatience.PATIENT -> PATIENT_TRANSFER_START_MS
        cellular -> FILE_TRANSFER_START_CELLULAR_MS
        else -> FILE_TRANSFER_START_WIFI_MS
    }

    private fun firstByteTimeoutMs(
        cellular: Boolean,
        patience: SoulseekDownloadPatience,
    ): Long = when {
        patience == SoulseekDownloadPatience.PATIENT -> PATIENT_FIRST_BYTE_MS
        cellular -> FIRST_AUDIO_BYTE_CELLULAR_MS
        else -> FIRST_AUDIO_BYTE_WIFI_MS
    }

    /**
     * Write one framed message to the server.
     *
     * Bounded, and that bound is load-bearing. Java sockets have no write
     * timeout: if the far end stops reading, `flush()` blocks forever, and
     * because the write happens under [serverWriteMutex] the stuck caller
     * takes every other server message down with it — including
     * SERVER_FILE_SEARCH. The visible symptom is a search that spins forever
     * without ever reaching its own polling loop, which is bounded and would
     * otherwise always terminate.
     *
     * The timeout works because `withLock` SUSPENDS while the mutex is held,
     * so waiters can be cancelled even though the holder is stuck in blocking
     * IO. Closing the socket on timeout is what frees the holder: the blocked
     * write throws, the mutex unwinds, and readServerLoop takes the normal
     * reconnect path.
     */
    private suspend fun sendServerMessage(code: Int, body: ByteArray) {
        val socket = serverSocket ?: throw SoulseekException("Not connected to Soulseek.")
        val output = serverOutput ?: throw SoulseekException("Not connected to Soulseek.")
        try {
            withTimeout(SERVER_WRITE_TIMEOUT_MS) {
                serverWriteMutex.withLock {
                    output.writeU32LE(4L + body.size)
                    output.writeU32LE(code.toLong())
                    output.write(body)
                    output.flush()
                }
            }
        } catch (_: TimeoutCancellationException) {
            runCatching { socket.close() }
            throw SoulseekException(
                "The Soulseek server stopped accepting data; the connection was closed so it can be re-established.",
            )
        }
    }

    private fun sendPeerInitFrame(output: BufferedOutputStream, code: Int, body: ByteArray) {
        output.writeU32LE(1L + body.size)
        output.write(code)
        output.write(body)
        output.flush()
    }

    private suspend fun sendCantConnectToPeer(token: Long, username: String) {
        runCatching {
            sendServerMessage(
                SERVER_CANT_CONNECT_TO_PEER,
                SlskWriter().u32(token).string(username).bytes(),
            )
        }
    }

    private fun scheduleDefaultNetworkCheck(network: Network, delayMs: Long) {
        if (!autoReconnectEnabled) return
        scope.launch {
            if (delayMs > 0L) delay(delayMs)
            val active = connectivityManager.activeNetwork ?: return@launch
            if (active.networkHandle != network.networkHandle) return@launch
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@launch
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@launch
            if (serverNetworkHandle == network.networkHandle && serverSocket?.isClosed == false) return@launch
            performNetworkHandoff(network)
        }
    }

    private suspend fun performNetworkHandoff(network: Network) {
        networkHandoffMutex.withLock {
            if (!autoReconnectEnabled) return@withLock
            if (serverNetworkHandle == network.networkHandle && serverSocket?.isClosed == false) return@withLock

            val user = currentUsername
            val password = currentPassword
            if (user.isBlank() || password.isBlank()) return@withLock

            val label = networkLabel(network)
            handoffInProgress = true
            try {
                connectMutex.withLock {
                    _connectionState.value = SoulseekConnectionState(
                        status = SoulseekConnectionStatus.CONNECTING,
                        username = user,
                        message = "Switching Soulseek to $label…",
                    )
                    _transferProgress.value = _transferProgress.value?.copy(
                        status = "Network changed — switching to $label…",
                    )

                    // Old Network-bound sockets cannot migrate. Close them, but keep
                    // partial files and CompletableDeferred download callers alive.
                    disconnectInternal(
                        message = "Switching to $label…",
                        finalStatus = SoulseekConnectionStatus.CONNECTING,
                        preserveTransfers = true,
                    )

                    var lastError: Throwable? = null
                    var connected = false
                    for (attempt in 1..NETWORK_HANDOFF_CONNECT_ATTEMPTS) {
                        if (attempt > 1) delay(NETWORK_HANDOFF_RETRY_DELAY_MS * attempt)
                        try {
                            establishServerSession(
                                user = user,
                                password = password,
                                network = network,
                                connectedMessage = "Connected via $label",
                            )
                            connected = true
                            break
                        } catch (t: Throwable) {
                            lastError = t
                            disconnectInternal(
                                message = "Retrying on $label…",
                                finalStatus = SoulseekConnectionStatus.CONNECTING,
                                preserveTransfers = true,
                            )
                        }
                    }

                    if (!connected) {
                        _connectionState.value = SoulseekConnectionState(
                            status = SoulseekConnectionStatus.ERROR,
                            username = user,
                            message = "Automatic network handoff failed: ${lastError?.message ?: "connection error"}",
                        )
                        _transferProgress.value = _transferProgress.value?.copy(
                            status = "Waiting for network recovery…",
                        )
                        scope.launch {
                            delay(NETWORK_HANDOFF_RECOVERY_RETRY_MS)
                            if (autoReconnectEnabled) scheduleDefaultNetworkCheck(network, 0L)
                        }
                        return@withLock
                    }

                    // Requeue every unfinished transfer. Soulseek's F connection
                    // resumes from partialFile.length(), so already-downloaded bytes
                    // are preserved across Wi-Fi <-> cellular changes.
                    pendingDownloads.values.forEach { pending ->
                        pending.peerConnection = null
                        pending.requeueJob?.cancel()
                        pending.requeueJob = null
                        scheduleTransferResume(pending, networkHandoff = true)
                    }
                }
            } finally {
                handoffInProgress = false
            }
        }
    }

    private fun networkLabel(network: Network): String {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "mobile data"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "the new network"
        }
    }

    private fun connectSocket(address: PeerAddress): Socket {
        val network = requireActiveNetwork()
        return network.socketFactory.createSocket().apply {
            configurePeerSocket(this)
            connect(InetSocketAddress(address.ip, address.port), PEER_CONNECT_TIMEOUT_MS)
        }
    }

    /**
     * Bind outbound Soulseek sockets to Android's current default network.
     * This matters when the phone is on cellular data or has just switched
     * away from Wi-Fi: a Socket created on an old Network does not migrate.
     */
    private fun requireActiveNetwork(): Network {
        val network = connectivityManager.activeNetwork
            ?: throw SoulseekException("No active internet network. Check mobile data or Wi-Fi.")
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: throw SoulseekException("Android could not read the active network state.")
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            throw SoulseekException("The active network has no internet capability.")
        }
        return network
    }

    fun isCellularNetwork(): Boolean = runCatching {
        isCellularNetwork(requireActiveNetwork())
    }.getOrDefault(false)

    private fun isCellularNetwork(network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** Best-effort TCP tuning for large P2P audio transfers. Android/the kernel may
     * clamp these values, which is fine; failures must never break connectivity. */
    private fun configurePeerSocket(socket: Socket) {
        runCatching { socket.keepAlive = true }
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.receiveBufferSize = PEER_SOCKET_RECEIVE_BUFFER_BYTES }
        runCatching { socket.sendBufferSize = PEER_SOCKET_SEND_BUFFER_BYTES }
    }

    private suspend fun disconnectInternal(
        message: String,
        finalStatus: SoulseekConnectionStatus = SoulseekConnectionStatus.DISCONNECTED,
        transferFailureMessage: String = "Soulseek disconnected while a transfer was active.",
        preserveTransfers: Boolean = false,
    ) {
        synchronized(loginDeferredLock) {
            loginDeferred?.takeIf { !it.isCompleted }?.cancel()
            loginDeferred = null
        }
        pendingAddresses.values.forEach { it.cancel() }
        pendingAddresses.clear()
        pendingIndirect.values.forEach { it.deferred.cancel() }
        pendingIndirect.clear()
        activeSearches.clear()
        if (!preserveTransfers) _searchResults.value = emptyList()

        activeFileTransferSockets.values.forEach { runCatching { it.close() } }
        activeFileTransferSockets.clear()
        fileTransfersByToken.clear()

        if (preserveTransfers) {
            pendingDownloads.values.forEach { pending ->
                pending.requeueJob?.cancel()
                pending.requeueJob = null
                pending.peerConnection = null
            }
            _transferProgress.value = _transferProgress.value?.copy(
                status = "Network changed — preserving partial download…",
            )
        } else {
            val disconnectError = SoulseekException(transferFailureMessage)
            pendingDownloads.values.forEach { pending ->
                pending.requeueJob?.cancel()
                pending.completion.takeIf { !it.isCompleted }?.completeExceptionally(disconnectError)
                runCatching { pending.partialFile.delete() }
                runCatching { partialOwnerFileFor(pending.contentKey).delete() }
            }
            pendingDownloads.clear()
            _transferProgress.value = null
        }

        peerConnections.values.forEach { runCatching { it.close() } }
        peerConnections.clear()
        peerConnectMutexes.clear()
        if (!preserveTransfers) transferLeases.clear()
        serverPingJob?.cancel()
        serverPingJob = null
        peerJanitorJob?.cancel()
        peerJanitorJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverNetworkHandle = null
        serverInput = null
        serverOutput = null
        serverReaderJob?.cancel()
        serverReaderJob = null
        runCatching { listener?.close() }
        listener = null
        listenerJob?.cancel()
        listenerJob = null
        _connectionState.value = SoulseekConnectionState(
            status = finalStatus,
            username = currentUsername.ifBlank { savedUsername() },
            message = message,
        )
    }

    /**
     * Guard for anything that needs a live server session.
     *
     * The socket checks alone are not enough, for the reason connect() already
     * documents: isConnected() stays true forever once a socket has connected,
     * and isClosed() only reports *our* own close — neither can see a
     * server-side disconnect. A session killed from the far end therefore
     * looked healthy here, so searches were sent into a dead socket and simply
     * returned nothing. From the UI that is indistinguishable from "no peer
     * wanted to answer", which sent people looking for a peer problem that did
     * not exist.
     *
     * Requiring a live reader job is what actually proves the session is still
     * being served, and turns a silent empty result into an honest error.
     */
    private fun requireConnected() {
        val socketUsable = serverSocket?.isClosed == false
        val readerAlive = serverReaderJob?.isActive == true
        if (
            _connectionState.value.status != SoulseekConnectionStatus.CONNECTED ||
            !socketUsable ||
            !readerAlive
        ) {
            throw SoulseekException(
                "The Soulseek session is no longer live. Reconnect and try again.",
            )
        }
    }

    private fun inflateSearchPayload(payload: ByteArray): ByteArray {
        return try {
            InflaterInputStream(ByteArrayInputStream(payload)).use { inflater ->
                val out = ByteArrayOutputStream(min(payload.size * 4, 1_048_576))
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val n = inflater.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_INFLATED_SEARCH_BYTES) {
                        throw SoulseekException("Peer sent an oversized search response.")
                    }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } catch (t: Throwable) {
            // Never turn our own size-limit rejection into a raw-payload fallback.
            if (t is SoulseekException) throw t

            // Very old/third-party clients have historically disagreed about
            // compression. A raw payload fallback improves interoperability.
            if (payload.size > MAX_INFLATED_SEARCH_BYTES) {
                throw SoulseekException("Peer sent an oversized search response.")
            }
            payload
        }
    }

    private fun nextProtocolToken(): Long {
        val next = nextToken.updateAndGet { previous ->
            if (previous >= Int.MAX_VALUE - 2) 1 else previous + 1
        }
        return next.toLong() and 0xFFFF_FFFFL
    }

    private fun uint32ToIp(value: Long): InetAddress {
        // Soulseek stores IPv4 addresses as a little-endian uint32, but the
        // socket API expects the four octets in network order. Nicotine+'s
        // reference parser reverses the four wire bytes before inet_ntoa().
        val bytes = byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
        return InetAddress.getByAddress(bytes)
    }

    private fun removeDiacritics(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")

    private fun normalizeSearchText(value: String): String = removeDiacritics(value)
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .take(180)
        // No extension here: the caller appends the one the peer actually sent.
        .ifBlank { "Soulseek download" }

    /**
     * Identity of the *file*, not of the peer. Two peers offering the same
     * size and leaf name are treated as the same content so a partial transfer
     * can survive a source switch.
     */
    private fun contentKeyFor(candidate: SoulseekSearchCandidate): String =
        sha1Hex("${candidate.sizeBytes}:${candidate.fileNameOnly.lowercase(Locale.ROOT)}")

    private fun partialFileFor(contentKey: String): File {
        val cacheDir = File(context.cacheDir, "harmony-soulseek").apply { mkdirs() }
        return File(cacheDir, "$contentKey.part")
    }

    private fun partialOwnerFileFor(contentKey: String): File {
        val cacheDir = File(context.cacheDir, "harmony-soulseek").apply { mkdirs() }
        return File(cacheDir, "$contentKey.owner")
    }

    /**
     * Byte-identical size and filename do not imply byte-identical content: two
     * peers can hold different rips or different tag padding under the same
     * name and size. Appending peer B's stream onto peer A's partial produces a
     * file of exactly the right length whose middle is spliced garbage — and
     * [verifyFlacContainer] only inspects the header, so it passes.
     *
     * Resume is therefore scoped to the peer that started the partial. A
     * different peer restarts from zero unless [ALLOW_CROSS_PEER_RESUME] is
     * turned back on.
     */
    private fun prepareResumePoint(pending: PendingDownload, username: String): Long {
        val partial = pending.partialFile
        val ownerFile = partialOwnerFileFor(pending.contentKey)
        if (!partial.isFile || partial.length() == 0L) {
            runCatching { ownerFile.writeText(username) }
            return 0L
        }

        val owner = runCatching { ownerFile.readText().trim() }.getOrNull().orEmpty()
        val sameOwner = owner.equals(username, ignoreCase = true)
        if (sameOwner || ALLOW_CROSS_PEER_RESUME) {
            if (!sameOwner) runCatching { ownerFile.writeText(username) }
            return partial.length()
        }

        runCatching { partial.delete() }
        runCatching { ownerFile.writeText(username) }
        return 0L
    }

    private fun sweepStalePartials() {
        val cacheDir = File(context.cacheDir, "harmony-soulseek")
        if (!cacheDir.isDirectory) return
        val cutoff = System.currentTimeMillis() - STALE_PARTIAL_MAX_AGE_MS
        cacheDir.listFiles()?.forEach { file ->
            val sweepable = file.name.endsWith(".part", ignoreCase = true) ||
                file.name.endsWith(".owner", ignoreCase = true)
            if (file.isFile && sweepable && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    /**
     * Post-transfer container check, by format.
     *
     * This exists because a peer can send any bytes it likes under any name.
     * Every format Harmony is willing to download needs a check here — adding
     * an extension to [SoulseekFormatPreference.SUPPORTED_EXTENSIONS] without
     * adding one here would silently reintroduce that hole.
     */
    private fun verifyAudioContainer(file: File, expectedSize: Long, extension: String): String? =
        if (SoulseekFormatPreference.isLossless(extension)) {
            verifyFlacContainer(file, expectedSize)
        } else {
            verifyMp3Container(file, expectedSize)
        }

    /**
     * Verifies the file really is MPEG audio.
     *
     * MP3 has no magic number at offset zero the way FLAC does. A file may
     * open with an ID3v2 tag of arbitrary size, and even without one the first
     * frame is not guaranteed to sit at byte zero — junk, or a stripped tag's
     * padding, can precede it. So: skip a declared ID3v2 tag if present, then
     * scan a bounded window for a frame header whose fields are all legal.
     *
     * Checking the sync word alone is not enough. 0xFFE is a common byte
     * pattern and appears in plenty of non-audio files, so the version, layer,
     * bitrate and sample-rate fields are validated too. Reserved values there
     * are what separate a real frame header from a coincidence.
     */
    private fun verifyMp3Container(file: File, expectedSize: Long): String? {
        if (expectedSize < MP3_MIN_CONTAINER_BYTES) return "expected size is too small for MP3"
        if (!file.isFile) return "temporary file is missing"
        if (file.length() != expectedSize) {
            return "size mismatch (${file.length()} != $expectedSize)"
        }
        if (file.length() < MP3_MIN_CONTAINER_BYTES) return "file is too small for MP3"

        val windowSize = minOf(file.length(), MP3_HEADER_SCAN_BYTES).toInt()
        val window = ByteArray(windowSize)
        try {
            RandomAccessFile(file, "r").use { raf -> raf.readFully(window) }
        } catch (_: Throwable) {
            return "could not read MP3 header"
        }

        var offset = 0
        // ID3v2: "ID3", two version bytes, one flag byte, then a four-byte
        // syncsafe length that excludes the ten-byte header itself.
        if (
            windowSize >= 10 &&
            window[0] == 'I'.code.toByte() &&
            window[1] == 'D'.code.toByte() &&
            window[2] == '3'.code.toByte()
        ) {
            val tagSize = ((window[6].toInt() and 0x7F) shl 21) or
                ((window[7].toInt() and 0x7F) shl 14) or
                ((window[8].toInt() and 0x7F) shl 7) or
                (window[9].toInt() and 0x7F)
            if (tagSize < 0) return "invalid ID3 tag length"
            val afterTag = 10L + tagSize
            if (afterTag >= file.length()) return "ID3 tag claims to be larger than the file"
            // The tag can easily be larger than the scan window (embedded
            // artwork), in which case the frame is beyond what was read and
            // the tag itself is the evidence this is an MP3.
            if (afterTag >= windowSize) return null
            offset = afterTag.toInt()
        }

        var scanned = 0
        while (offset + 4 <= windowSize && scanned < MP3_MAX_SYNC_SCAN) {
            if (isMpegFrameHeader(window, offset)) return null
            offset++
            scanned++
        }
        return "no MPEG audio frame found"
    }

    /** True when the four bytes at [offset] are a legal MPEG audio frame header. */
    private fun isMpegFrameHeader(bytes: ByteArray, offset: Int): Boolean {
        if (offset + 4 > bytes.size) return false
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        if (b0 != 0xFF) return false
        if (b1 and 0xE0 != 0xE0) return false          // 11 sync bits
        if (b1 and 0x18 == 0x08) return false          // reserved MPEG version
        if (b1 and 0x06 == 0x00) return false          // reserved layer
        val bitrateIndex = (b2 and 0xF0) ushr 4
        if (bitrateIndex == 0x00 || bitrateIndex == 0x0F) return false // free/bad
        if (b2 and 0x0C == 0x0C) return false          // reserved sample rate
        return true
    }

    private fun verifyFlacContainer(file: File, expectedSize: Long): String? {
        if (expectedSize < FLAC_MIN_CONTAINER_BYTES) return "expected size is too small for FLAC"
        if (!file.isFile) return "temporary file is missing"
        if (file.length() != expectedSize) {
            return "size mismatch (${file.length()} != $expectedSize)"
        }
        if (file.length() < FLAC_MIN_CONTAINER_BYTES) return "file is too small for FLAC"

        val header = ByteArray(FLAC_MIN_CONTAINER_BYTES.toInt())
        try {
            RandomAccessFile(file, "r").use { raf -> raf.readFully(header) }
        } catch (_: Throwable) {
            return "could not read FLAC header"
        }

        if (
            header[0] != 'f'.code.toByte() ||
            header[1] != 'L'.code.toByte() ||
            header[2] != 'a'.code.toByte() ||
            header[3] != 'C'.code.toByte()
        ) {
            return "missing fLaC signature"
        }

        val metadataType = header[4].toInt() and 0x7F
        val metadataLength =
            ((header[5].toInt() and 0xFF) shl 16) or
                ((header[6].toInt() and 0xFF) shl 8) or
                (header[7].toInt() and 0xFF)
        if (metadataType != 0) return "first metadata block is not STREAMINFO"
        if (metadataLength != 34) return "invalid STREAMINFO length"

        val streamInfo = 8
        fun u16be(index: Int): Int =
            ((header[index].toInt() and 0xFF) shl 8) or (header[index + 1].toInt() and 0xFF)
        fun u24be(index: Int): Int =
            ((header[index].toInt() and 0xFF) shl 16) or
                ((header[index + 1].toInt() and 0xFF) shl 8) or
                (header[index + 2].toInt() and 0xFF)

        val minBlockSize = u16be(streamInfo)
        val maxBlockSize = u16be(streamInfo + 2)
        val minFrameSize = u24be(streamInfo + 4)
        val maxFrameSize = u24be(streamInfo + 7)
        val packed12 = header[streamInfo + 12].toInt() and 0xFF
        val packed13 = header[streamInfo + 13].toInt() and 0xFF
        val sampleRate =
            ((header[streamInfo + 10].toInt() and 0xFF) shl 12) or
                ((header[streamInfo + 11].toInt() and 0xFF) shl 4) or
                ((packed12 and 0xF0) ushr 4)
        val channels = ((packed12 and 0x0E) ushr 1) + 1
        val bitsPerSample = (((packed12 and 0x01) shl 4) or ((packed13 and 0xF0) ushr 4)) + 1
        val totalSamples =
            ((packed13 and 0x0F).toLong() shl 32) or
                ((header[streamInfo + 14].toLong() and 0xFF) shl 24) or
                ((header[streamInfo + 15].toLong() and 0xFF) shl 16) or
                ((header[streamInfo + 16].toLong() and 0xFF) shl 8) or
                (header[streamInfo + 17].toLong() and 0xFF)

        if (minBlockSize !in 16..65_535 || maxBlockSize !in 16..65_535 || minBlockSize > maxBlockSize) {
            return "implausible STREAMINFO block size"
        }
        if (minFrameSize != 0 && maxFrameSize != 0 && minFrameSize > maxFrameSize) {
            return "implausible STREAMINFO frame size"
        }
        if (sampleRate !in 1..655_350) return "implausible sample rate: $sampleRate Hz"
        if (channels !in 1..8) return "implausible channel count: $channels"
        if (bitsPerSample !in 4..32) return "implausible bit depth: $bitsPerSample"
        if (totalSamples < 0L) return "invalid total sample count"

        return null
    }

    private fun downloadKey(username: String, filename: String): String =
        "${username.lowercase(Locale.US)}\u0000$filename"

    private fun md5Hex(value: String): String = digestHex("MD5", value)
    private fun sha1Hex(value: String): String = digestHex("SHA-1", value)

    private fun digestHex(algorithm: String, value: String): String =
        MessageDigest.getInstance(algorithm)
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class LoginResult(
        val success: Boolean,
        val greet: String? = null,
        val reason: String? = null,
        val detail: String? = null,
    )

    private data class PeerAddress(val ip: InetAddress, val port: Int)

    private data class ConnectedSocket(
        val socket: Socket,
        val input: BufferedInputStream,
        val output: BufferedOutputStream,
        val username: String,
        val type: String,
    )

    private data class PendingIndirectConnection(
        val username: String,
        val type: String,
        val deferred: CompletableDeferred<ConnectedSocket>,
    )

    private data class SearchFile(
        val filename: String,
        val sizeBytes: Long,
        val extension: String,
        val attributes: Map<Int, Long>,
    )

    private data class SearchProfile(
        val minWindowMs: Long,
        val maxWindowMs: Long,
    )

    private data class SearchSession(
        val query: String,
        val mode: SoulseekSearchMode,
        val onlyFreeSlots: Boolean,
        val formatPreference: SoulseekFormatPreference = SoulseekFormatPreference.FLAC_ONLY,
        val candidates: ConcurrentHashMap<String, SoulseekSearchCandidate> = ConcurrentHashMap(),
        @Volatile var acceptIntoUi: Boolean = true,
        val variants: MutableList<String> = java.util.Collections.synchronizedList(ArrayList()),
        val respondingPeers: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val filesSeen: AtomicInteger = AtomicInteger(0),
        val acceptedFiles: AtomicInteger = AtomicInteger(0),
        val parseFailures: AtomicInteger = AtomicInteger(0),
        val rejectedByExtension: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap(),
    )

    private data class PendingDownload(
        val candidate: SoulseekSearchCandidate,
        val contentKey: String,
        val partialFile: File,
        val patience: SoulseekDownloadPatience = SoulseekDownloadPatience.FAST,
        val completion: CompletableDeferred<SoulseekDownloadedFile>,
        @Volatile var requestAccepted: CompletableDeferred<Unit> = CompletableDeferred(),
        @Volatile var transferStarted: CompletableDeferred<Unit> = CompletableDeferred(),
        @Volatile var firstByteReceived: CompletableDeferred<Unit> = CompletableDeferred(),
        @Volatile var lastProgressAt: Long = System.nanoTime(),
        @Volatile var expectedSize: Long = candidate.sizeBytes,
        @Volatile var peerConnection: PeerConnection? = null,
        @Volatile var retryAttempts: Int = 0,
        @Volatile var requeueJob: Job? = null,
        @Volatile var queuePollJob: Job? = null,
        @Volatile var queueStartPlace: Int? = null,
        @Volatile var lastQueuePlace: Int? = null,
        @Volatile var lastQueueProgressAt: Long = System.currentTimeMillis(),
        @Volatile var queueWaitStartedAt: Long = 0L,
        @Volatile var cancelled: Boolean = false,
    )

    private data class PendingFileTransfer(
        val token: Long,
        val sizeBytes: Long,
        val pending: PendingDownload,
    )

    private data class PendingUpload(
        val token: Long,
        val peerUsername: String,
        val entry: SharedEntry,
    )

    private inner class PeerConnection(
        val username: String,
        val socket: Socket,
        val input: BufferedInputStream,
        val output: BufferedOutputStream,
    ) {
        val writeMutex = Mutex()
        var readerJob: Job? = null

        /** Last time this connection carried traffic, for idle eviction. */
        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()

        /**
         * socket.isConnected stays true forever once connected and
         * socket.isClosed only reports *our* close, so neither notices the peer
         * hanging up — which peers routinely do right after finishing an
         * upload. A cached connection then looked healthy, QueueUpload went
         * into a dead socket, and the next download to that peer timed out
         * waiting for a reply that could never come.
         *
         * The reader job dies when the peer closes, so requiring it to be
         * active is what actually proves the connection is usable.
         */
        val isOpen: Boolean
            get() = socket.isConnected && !socket.isClosed && readerJob?.isActive != false

        /**
         * Same bounding as sendServerMessage, for the same reason.
         *
         * This matters more since v1.8.0: answering a browse request writes the
         * whole share listing, which is far larger than any message Harmony
         * sent before. A peer that opens the connection and then stops reading
         * would otherwise block this connection's reader loop forever — and
         * that loop is what delivers search responses from that peer.
         */
        suspend fun send(code: Int, body: ByteArray) {
            lastUsedAt = System.currentTimeMillis()
            try {
                withTimeout(PEER_WRITE_TIMEOUT_MS) {
                    writeMutex.withLock {
                        output.writeU32LE(4L + body.size)
                        output.writeU32LE(code.toLong())
                        output.write(body)
                        output.flush()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                close()
                throw SoulseekException("$username stopped reading; peer connection closed.")
            }
        }

        fun close() {
            runCatching { socket.close() }
            readerJob?.cancel()
        }
    }

    private class SlskWriter {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int) = apply { out.write(value and 0xFF) }
        fun bool(value: Boolean) = u8(if (value) 1 else 0)
        fun u16(value: Int) = apply {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }
        fun u32(value: Int) = u32(value.toLong())
        fun u32(value: Long) = apply {
            val v = value and 0xFFFF_FFFFL
            repeat(4) { shift -> out.write(((v ushr (shift * 8)) and 0xFF).toInt()) }
        }
        fun u64(value: Long) = apply {
            repeat(8) { shift -> out.write(((value ushr (shift * 8)) and 0xFF).toInt()) }
        }
        fun string(value: String) = apply {
            val bytes = value.toByteArray(Charsets.UTF_8)
            u32(bytes.size)
            out.write(bytes)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class SlskReader(private val data: ByteArray) {
        private var offset = 0
        val remaining: Int get() = data.size - offset

        fun u8(): Int {
            ensure(1)
            return data[offset++].toInt() and 0xFF
        }
        fun bool(): Boolean = u8() != 0
        fun u16(): Int {
            ensure(2)
            val value = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return value
        }
        fun u32(): Long {
            ensure(4)
            var value = 0L
            repeat(4) { i -> value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i)) }
            offset += 4
            return value
        }
        fun u64(): Long {
            ensure(8)
            var value = 0L
            repeat(8) { i -> value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i)) }
            offset += 8
            return value
        }
        fun string(): String {
            val length = u32().toIntChecked("string length")
            if (length > MAX_STRING_BYTES) throw SoulseekException("Peer sent an oversized string.")
            ensure(length)
            val result = data.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
            offset += length
            return result
        }
        fun stringOrNull(): String? = if (remaining >= 4) runCatching { string() }.getOrNull() else null

        fun searchFile(): SearchFile {
            if (remaining < 1) throw EOFException("Truncated search result")
            u8() // result marker, normally 1
            val filename = string()
            val size = u64()
            val extension = string()
            val attrCount = boundedCount(MAX_ATTRIBUTES_PER_FILE, "file attribute count")
            val attrs = HashMap<Int, Long>(attrCount)
            repeat(attrCount) {
                attrs[u32().toInt()] = u32()
            }
            return SearchFile(filename, size, extension, attrs)
        }

        fun boundedCount(max: Int, label: String): Int {
            val value = u32()
            if (value > max.toLong()) {
                throw SoulseekException("Peer sent an invalid $label ($value).")
            }
            return value.toInt()
        }

        private fun ensure(count: Int) {
            if (count < 0 || offset + count > data.size) throw EOFException("Truncated Soulseek message")
        }
    }

    companion object {
        private const val SERVER_HOST = "server.slsknet.org"
        private const val SERVER_PORT = 2242

        // Experimental clients are explicitly assigned major version 177 by the
        // protocol documentation. Harmony uses its own minor value.
        private const val EXPERIMENTAL_MAJOR_VERSION = 177
        private const val HARMONY_MINOR_VERSION = 28

        private const val SERVER_LOGIN = 1
        private const val SERVER_SET_WAIT_PORT = 2
        private const val SERVER_GET_PEER_ADDRESS = 3
        private const val SERVER_CONNECT_TO_PEER = 18
        private const val SERVER_FILE_SEARCH = 26
        private const val SERVER_SET_STATUS = 28
        private const val SERVER_PING = 32
        private const val SERVER_SHARED_FOLDERS_FILES = 35
        private const val SERVER_RELOGGED = 41
        private const val SESSION_END_RELOGGED =
            "The Soulseek session ended because this account signed in from another client. " +
                "Soulseek allows one session per account, so searches stop returning results " +
                "until you reconnect."

        private const val SERVER_CANT_CONNECT_TO_PEER = 1001

        private const val PIERCE_FIREWALL = 0
        private const val PEER_INIT = 1
        private const val PEER_FILE_SEARCH_RESPONSE = 9
        private const val PEER_TRANSFER_REQUEST = 40
        private const val PEER_SHARED_FILE_LIST_REQUEST = 4
        private const val PEER_SHARED_FILE_LIST_RESPONSE = 5
        private const val PEER_TRANSFER_RESPONSE = 41
        private const val PEER_QUEUE_UPLOAD = 43
        private const val PEER_PLACE_IN_QUEUE_RESPONSE = 44
        private const val PEER_UPLOAD_FAILED = 46
        private const val PEER_UPLOAD_DENIED = 50
        private const val PEER_PLACE_IN_QUEUE_REQUEST = 51

        const val SLSK_TAG = "HarmonySlsk"

        private const val PREF_USERNAME = "username"

        /**
         * The shared folder, persisted across process restarts.
         *
         * Only the tree URI and its display name are stored — never the file
         * list. The folder is re-walked on startup, because files get added,
         * renamed and deleted between sessions and a cached index would make
         * Harmony advertise files it can no longer serve.
         *
         * Key names keep the v1.7.4 "shared_file_*" spelling deliberately: a
         * rename would silently orphan the previous release's persisted grant
         * and there is nothing to gain from it.
         */
        private const val PREF_SHARED_URI = "shared_file_uri"
        private const val PREF_SHARED_NAME = "shared_file_name"

        private const val MAX_RECOMMENDATIONS = 10
        private const val SEARCH_POLL_INTERVAL_MS = 350L
        private const val MIN_RESULTS_FOR_EARLY_STOP = 10
        private const val MIN_DISTINCT_USERS_FOR_EARLY_STOP = 7
        private const val MIN_STRONG_MATCHES_FOR_EARLY_STOP = 6
        private const val STRONG_MATCH_SCORE = 72

        private val SEARCH_STOP_WORDS = setOf(
            "the", "and", "feat", "ft", "featuring", "official", "audio", "video",
            "lyrics", "lyric", "music", "track",
        )
        private const val MAX_FILES_PER_SEARCH_RESPONSE = 10_000
        private const val MAX_ATTRIBUTES_PER_FILE = 32
        private const val MAX_STRING_BYTES = 2 * 1024 * 1024
        private const val MAX_SERVER_FRAME_BYTES = 16 * 1024 * 1024
        private const val MAX_PEER_FRAME_BYTES = 32 * 1024 * 1024
        private const val MAX_PEER_INIT_BYTES = 64 * 1024
        private const val MAX_INFLATED_SEARCH_BYTES = 64 * 1024 * 1024

        private const val SERVER_CONNECT_TIMEOUT_MS = 10_000
        private const val SERVER_PING_INTERVAL_MS = 60_000L
        private const val LOGIN_TIMEOUT_MS = 15_000L
        private const val PEER_CONNECT_TIMEOUT_MS = 4_000
        private const val PEER_ADDRESS_TIMEOUT_MS = 4_000L

        /**
         * The direct path costs an address lookup plus a TCP connect. The race
         * window must exceed that sum or the direct path is cancelled before it
         * can ever finish — which is what happened on cellular, where the
         * 6 s race window was shorter than the 7.5 s direct budget, so the only
         * path that actually works behind CGNAT was being killed every time.
         */
        private const val PEER_DIRECT_PATH_BUDGET_MS =
            PEER_ADDRESS_TIMEOUT_MS + PEER_CONNECT_TIMEOUT_MS

        private const val INDIRECT_CONNECTION_TIMEOUT_MS = 7_000L
        private const val PEER_CONNECTION_RACE_TIMEOUT_MS = PEER_DIRECT_PATH_BUDGET_MS + 1_500L

        // Cellular carriers commonly use CGNAT, so an inbound reverse
        // connection is unlikely to arrive at all. The indirect path stays
        // shorter there, but the race window does not: the direct outbound
        // path is the one that works on mobile data and it must be allowed to
        // run to completion.
        private const val INDIRECT_CONNECTION_TIMEOUT_CELLULAR_MS = 6_000L
        private const val PEER_CONNECTION_RACE_TIMEOUT_CELLULAR_MS = PEER_DIRECT_PATH_BUDGET_MS + 2_500L
        private const val DOWNLOAD_IDLE_TIMEOUT_NS = 90L * 1_000_000_000L
        private const val DOWNLOAD_IDLE_WATCHDOG_POLL_MS = 1_000L
        private const val STALE_PARTIAL_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val FLAC_MIN_CONTAINER_BYTES = 42L

        /** ID3v2 header plus one frame header; anything smaller cannot be MP3. */
        private const val MP3_MIN_CONTAINER_BYTES = 128L

        /** How much of the file to read when looking for the first frame. */
        private const val MP3_HEADER_SCAN_BYTES = 64L * 1024L

        /** Bounded so a pathological file cannot turn the check into a scan. */
        private const val MP3_MAX_SYNC_SCAN = 8_192
        // Cellular round-trips are slower than Wi-Fi, so every cellular budget
        // is now >= its Wi-Fi counterpart. They were previously inverted, which
        // gave mobile data less time to do strictly more work.
        private const val REQUEST_ACCEPT_FREE_CELLULAR_MS = 4_500L
        private const val REQUEST_ACCEPT_FREE_WIFI_MS = 3_500L
        private const val REQUEST_ACCEPT_SMALL_QUEUE_CELLULAR_MS = 4_000L
        private const val REQUEST_ACCEPT_SMALL_QUEUE_WIFI_MS = 3_000L
        private const val REQUEST_ACCEPT_QUEUED_CELLULAR_MS = 3_500L
        private const val REQUEST_ACCEPT_QUEUED_WIFI_MS = 2_500L
        private const val FILE_TRANSFER_START_CELLULAR_MS = 6_000L
        private const val FILE_TRANSFER_START_WIFI_MS = 4_500L
        private const val FIRST_AUDIO_BYTE_CELLULAR_MS = 6_500L
        private const val FIRST_AUDIO_BYTE_WIFI_MS = 5_000L

        /**
         * Second-pass budgets. The fast pass exists to find an instantly free
         * peer; when none exists, these let Harmony wait on a peer that has
         * already answered rather than failing the whole download.
         */
        private const val PATIENT_REQUEST_ACCEPT_MS = 90_000L
        private const val PATIENT_TRANSFER_START_MS = 30_000L
        private const val PATIENT_FIRST_BYTE_MS = 30_000L
        private const val PATIENT_QUEUE_PLACE_LIMIT = 30
        private const val PATIENT_QUEUE_CHECK_INTERVAL_MS = 5_000L
        /** Give up only after the queue has stopped advancing for this long. */
        private const val PATIENT_QUEUE_STALL_MS = 150_000L
        /** Absolute ceiling so a wait can never run forever. */
        private const val PATIENT_MAX_WAIT_MS = 6L * 60_000L
        /** Re-send QueueUpload this often: the automated "press it again". */
        private const val PATIENT_REKNOCK_INTERVAL_MS = 45_000L

        /**
         * How often to re-ask a peer for our queue position. Slow on purpose —
         * see startQueuePolling.
         */
        private const val QUEUE_POLL_INTERVAL_MS = 10_000L

        /** First re-ask comes quickly; the interval doubles up to the ceiling. */
        private const val QUEUE_POLL_INITIAL_MS = 1_200L
        private const val MAX_PRECONNECT_RACE_PEERS = 3
        // These are ceilings, not fixed waits: the race ends the moment one
        // peer connects. The old 3.5 s cellular ceiling was shorter than the
        // 8 s direct-path budget, so on mobile data the preconnect could never
        // warm anything and every download started cold.
        private const val PRECONNECT_PEER_CELLULAR_MS = PEER_DIRECT_PATH_BUDGET_MS + 1_000L
        private const val PRECONNECT_PEER_WIFI_MS = PEER_DIRECT_PATH_BUDGET_MS + 500L
        private const val PRECONNECT_RACE_CELLULAR_MS = PEER_DIRECT_PATH_BUDGET_MS + 1_500L
        private const val PRECONNECT_RACE_WIFI_MS = PEER_DIRECT_PATH_BUDGET_MS + 1_000L
        private const val FAST_QUEUE_FALLBACK_THRESHOLD = 1

        /**
         * Whether a partial started by one peer may be finished by a different
         * peer advertising the same size and filename. Off by default: the
         * saving is one re-download, the cost of getting it wrong is a
         * length-correct FLAC with spliced content that passes header checks.
         */
        private const val ALLOW_CROSS_PEER_RESUME = false

        // Transfer performance. These are deliberately conservative enough for
        // Android memory use while being much larger than the original 64/128 KiB
        // path used by the first Harmony Soulseek implementation.
        // Almost every peer connection carries control traffic only: search
        // responses and queue messages, never bulk audio. Sizing them all for a
        // file transfer cost 256 KiB of heap plus a 1 MiB kernel receive buffer
        // *per responding peer*, and a search draws replies from dozens. The
        // transfer path upgrades its own socket instead, in handleFileConnection.
        private const val PEER_IO_BUFFER_BYTES = 32 * 1024
        private const val PEER_SOCKET_RECEIVE_BUFFER_BYTES = 256 * 1024
        private const val PEER_SOCKET_SEND_BUFFER_BYTES = 64 * 1024
        private const val FILE_TRANSFER_RECEIVE_BUFFER_BYTES = 1024 * 1024

        // Peer connections were cached forever. Each one is a socket, a live
        // coroutine and its buffers, so a long session on the search page grew
        // without bound until the process died.
        private const val MAX_CACHED_PEER_CONNECTIONS = 12
        private const val PEER_CONNECTION_IDLE_TIMEOUT_MS = 90_000L
        private const val PEER_JANITOR_INTERVAL_MS = 20_000L
        private const val FILE_TRANSFER_BUFFER_BYTES = 512 * 1024
        private const val FILE_TRANSFER_STALL_TIMEOUT_MS = 20_000
        /**
         * How long Harmony keeps a finished share-upload socket open waiting
         * for the downloader's protocol-mandated close, before closing it
         * itself as a resource-safety fallback.
         */
        private const val UPLOAD_SERVE_CLOSE_GRACE_MS = 30_000

        /**
         * Ceilings on blocking writes. Java offers no SO_SNDTIMEO for Socket,
         * so these are enforced by cancelling the coroutine and closing the
         * socket, which is what actually unblocks a stuck write.
         */
        /**
         * How long Disconnect waits for connectMutex before tearing down
         * without it. Short on purpose: this is a button press, and three
         * seconds of nothing already reads as broken.
         */
        private const val DISCONNECT_LOCK_TIMEOUT_MS = 3_000L

        /**
         * How many outbound peer call-backs may be in flight at once. Eight is
         * enough to service a healthy search while leaving the download path
         * able to obtain a socket immediately.
         */
        private const val MAX_CONCURRENT_INDIRECT_DIALS = 8

        private const val SERVER_WRITE_TIMEOUT_MS = 15_000L
        private const val PEER_WRITE_TIMEOUT_MS = 20_000L
        // Mobile data stalls for longer during cell handovers and congestion
        // without the transfer actually being dead. Cutting at 15 s dropped
        // transfers that would have resumed on their own.
        private const val FILE_TRANSFER_STALL_TIMEOUT_CELLULAR_MS = 35_000
        private const val TRANSFER_UI_UPDATE_INTERVAL_NS = 600_000_000L
        private const val MAX_TRANSFER_RESUME_ATTEMPTS = 1
        private const val TRANSFER_RETRY_DELAY_MS = 1_500L
        private const val FILE_TRANSFER_TOKEN_WAIT_STEPS = 60 // 60 * 50 ms = 3 seconds
        private const val LATE_SEARCH_RESULT_GRACE_MS = 12_000L

        // Default-network handoff. Android may report onAvailable before the
        // replacement network is fully settled, so use a tiny debounce and a
        // few short reconnect attempts before surfacing an error.
        private const val NETWORK_HANDOFF_SETTLE_MS = 350L
        private const val NETWORK_HANDOFF_CONNECT_ATTEMPTS = 3
        private const val NETWORK_HANDOFF_RETRY_DELAY_MS = 900L
        private const val NETWORK_HANDOFF_RESUME_POLL_MS = 250L
        private const val NETWORK_HANDOFF_RECOVERY_RETRY_MS = 10_000L
    }
}

private fun InputStream.readExactly(count: Int): ByteArray {
    if (count < 0) throw EOFException("Negative read length")
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read < 0) throw EOFException("Connection closed")
        offset += read
    }
    return bytes
}

private fun InputStream.readU32LE(): Long {
    val b0 = read(); val b1 = read(); val b2 = read(); val b3 = read()
    if ((b0 or b1 or b2 or b3) < 0) throw EOFException("Connection closed")
    return (b0.toLong() and 0xFF) or
        ((b1.toLong() and 0xFF) shl 8) or
        ((b2.toLong() and 0xFF) shl 16) or
        ((b3.toLong() and 0xFF) shl 24)
}

private fun InputStream.readU64LE(): Long {
    var value = 0L
    repeat(8) { shift ->
        val b = read()
        if (b < 0) throw EOFException("Connection closed")
        value = value or ((b.toLong() and 0xFF) shl (shift * 8))
    }
    return value
}

private fun OutputStream.writeU32LE(value: Long) {
    val v = value and 0xFFFF_FFFFL
    repeat(4) { shift -> write(((v ushr (shift * 8)) and 0xFF).toInt()) }
}

private fun OutputStream.writeU64LE(value: Long) {
    repeat(8) { shift -> write(((value ushr (shift * 8)) and 0xFF).toInt()) }
}

private fun Long.toIntChecked(label: String): Int {
    if (this < 0L || this > Int.MAX_VALUE.toLong()) throw SoulseekException("Invalid $label: $this")
    return toInt()
}
