package com.harmony.desktop

import com.harmony.desktop.connect.ConnectHost
import com.harmony.desktop.engine.AudioEngine
import com.harmony.desktop.engine.EqConfig
import com.harmony.desktop.library.CoverCache
import com.harmony.desktop.library.LibraryCache
import com.harmony.desktop.library.LibraryScanner
import com.harmony.desktop.library.LocalTrack
import com.harmony.desktop.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Scan progress: songs looked at so far, out of how many. */
data class ScanProgress(val done: Int, val total: Int)

/**
 * Everything the desktop app is made of, wired together once: settings,
 * the library, the player and the Connect receiver.
 */
class DesktopApp(
    val settings: SettingsStore,
    val engine: AudioEngine,
    val player: PlayerController,
    val connect: ConnectHost,
    private val libraryCache: LibraryCache,
    private val scanner: LibraryScanner,
    val covers: CoverCache,
    val plays: PlayStats = PlayStats(),
    /** The sound outputs to choose from (Windows' own list; a fixed one in tests). */
    private val outputs: () -> List<String> = { com.harmony.desktop.engine.AudioOutputs.list() },
    /** The phone's songs kept here (Universal Queue cache); null in tests. */
    val cache: com.harmony.core.remote.cache.MediaCache? = null,
    /** This computer's songs for phones; null in tests. */
    val media: com.harmony.desktop.connect.PcMedia? = null,
    /** "Send to phone": the files offered to paired phones; null in tests. */
    val transfers: com.harmony.core.remote.transfer.TransferOutbox? = null,
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tracks = MutableStateFlow(libraryCache.load())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    private val _scan = MutableStateFlow<ScanProgress?>(null)
    val scan: StateFlow<ScanProgress?> = _scan.asStateFlow()

    private val _settings = MutableStateFlow(settings.current)
    val settingsFlow: StateFlow<DesktopSettings> = _settings.asStateFlow()

    private var scanJob: Job? = null

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _sends = MutableStateFlow(transfers?.entries() ?: emptyList())

    /** Everything sent to phones, as it stands (the phone's last word on each file). */
    val sends: StateFlow<List<com.harmony.core.remote.transfer.OutboxEntry>> = _sends.asStateFlow()

    init {
        transfers?.onChange = { _sends.value = transfers.entries() }
        engine.volume = settings.current.volume
        player.crossfadeSeconds = settings.current.crossfadeSeconds
        engine.eq = settings.current.eq
        countPlays()
    }

    /**
     * A song of the computer's own counts as played once it has played for
     * half a minute (or to its end, if shorter).
     */
    private fun countPlays() {
        scope.launch {
            var counted: String? = null
            while (true) {
                kotlinx.coroutines.delay(1_000)
                val np = player.nowPlaying.value
                if (np == null || np.fromPhone != null) continue
                if (np.key == counted) continue
                val playing = engine.state.value.status == com.harmony.desktop.engine.EngineStatus.PLAYING
                val enough = minOf(PLAYED_AFTER_MS, (np.durationMs - 1_000).coerceAtLeast(1_000))
                if (playing && engine.positionMs() >= enough) {
                    counted = np.key
                    plays.record(np.key)
                }
            }
        }
    }

    fun isFavorite(path: String): Boolean = path in _settings.value.favorites

    fun toggleFavorite(path: String) = update { s ->
        s.copy(favorites = if (path in s.favorites) s.favorites - path else s.favorites + path)
    }

    fun toggleMute() {
        val m = !_muted.value
        _muted.value = m
        player.setVolume(if (m) 0f else settings.current.volume)
    }

    fun rescan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch(Dispatchers.IO) {
            val folders = settings.current.folders.map(::File).filter { it.isDirectory }
            val known = _tracks.value.associateBy { it.path }
            val found = scanner.scan(folders, known) { done, total -> _scan.value = ScanProgress(done, total) }
            val sorted = found.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title.lowercase() }))
            _tracks.value = sorted
            libraryCache.save(sorted)
            _scan.value = null
        }
    }

    fun addFolder(folder: File) {
        update { s -> s.copy(folders = (s.folders + folder.absolutePath).distinct()) }
        rescan()
    }

    fun removeFolder(path: String) {
        update { s -> s.copy(folders = s.folders - path) }
        rescan()
    }

    fun setEq(eq: EqConfig) {
        engine.eq = eq
        update { it.copy(eq = eq) }
    }

    fun setVolume(v: Float) {
        _muted.value = false
        player.setVolume(v)
        update { it.copy(volume = v.coerceIn(0f, 1f)) }
    }

    fun setDarkTheme(dark: Boolean) = update { it.copy(darkTheme = dark) }

    /** How long songs fade into each other here (0: none); a phone playing here uses its own. */
    fun setCrossfade(seconds: Int) {
        val s = seconds.coerceIn(0, 12)
        player.crossfadeSeconds = s
        update { it.copy(crossfadeSeconds = s) }
    }

    /** How much disk the phone's songs kept here may take (GB). Applies to the next download. */
    fun setCacheLimit(gb: Int) = update { it.copy(cacheLimitGb = gb.coerceIn(1, 100)) }

    /** Universal Queue (beta). Off, the phone's link ends at its next request and the phone keeps its own queue. */
    fun setSharedQueue(on: Boolean) {
        update { it.copy(sharedQueue = on) }
        if (!on) player.sharedQueue(null)
    }

    /** The phones paired with this computer: token -> name. */
    fun pairedPhones(): Map<String, String> = connect.state.value.phones

    /**
     * Sends [tracks] to the paired phone of [token] for good ("Send to phone"):
     * the original files, which it saves in its library. Songs in a format the
     * phone can't play go only with [includeUnsupported]. Returns the send's
     * id, or null when nothing was left to send.
     */
    fun sendToPhone(token: String, tracks: List<LocalTrack>, includeUnsupported: Boolean): String? {
        val box = transfers ?: return null
        val phone = pairedPhones()[token] ?: return null
        val files = tracks.distinctBy { it.path }
            .filter { File(it.path).isFile }
            .filter { includeUnsupported || com.harmony.desktop.connect.PcMedia.phoneCanPlay(it) }
            .map {
                com.harmony.core.remote.transfer.OutgoingFile(
                    path = it.path, title = it.title, artist = it.artist, album = it.album, durationMs = it.durationMs,
                    mime = com.harmony.desktop.connect.PcMedia.contentTypeOf(File(it.path).extension),
                    playableOnPhone = com.harmony.desktop.connect.PcMedia.phoneCanPlay(it),
                )
            }
        if (files.isEmpty()) return null
        return box.create(token, phone, settings.current.pcId, settings.current.pcName, files)
    }

    fun cancelSend(id: String) = transfers?.cancel(id)
    fun retrySend(id: String) = transfers?.retry(id)
    fun removeSend(id: String) = transfers?.remove(id)

    /** The sound outputs there are now (asks Windows: call it off the UI thread). */
    fun outputDevices(): List<String> = outputs()

    /** Plays through [name] from now on (null: Windows' default); what plays carries on there. */
    fun setOutputDevice(name: String?) {
        if (settings.current.outputDevice == name) return
        update { it.copy(outputDevice = name) }
        engine.reopenOutput()
    }

    private fun update(change: (DesktopSettings) -> DesktopSettings) {
        _settings.value = settings.update(change)
    }

    override fun close() {
        connect.close()
        cache?.close()
        media?.close()
        transfers?.close()
        engine.shutdown()
        settings.flush()
        plays.flush()
        scope.cancel()
    }

    companion object {
        const val PLAYED_AFTER_MS = 30_000L

        fun create(): DesktopApp {
            val settings = SettingsStore()
            val engine = AudioEngine({ com.harmony.desktop.engine.JavaSoundSink { settings.current.outputDevice } })
            // The temporary folder the first Connect cache used: emptied for good.
            runCatching { java.io.File(System.getProperty("java.io.tmpdir"), "harmony-connect").deleteRecursively() }
            val cache = com.harmony.core.remote.cache.MediaCache(
                AppDirs.connectCache,
                limits = { com.harmony.core.remote.cache.CacheLimits(maxBytes = settings.current.cacheLimitGb * 1024L * 1024 * 1024) },
                log = { java.util.logging.Logger.getLogger("com.harmony.cache").info(it) },
            )
            // The library, once the app holds it (the songs offered to phones come from it).
            var library: () -> List<LocalTrack> = { emptyList() }
            val media = com.harmony.desktop.connect.PcMedia(
                tracks = { library() },
                converted = AppDirs.connectConverted,
                log = { java.util.logging.Logger.getLogger("com.harmony.connect").info(it) },
            )
            val player = PlayerController(engine, cache, pcId = settings.current.pcId, localFile = media::file)
            val transfers = com.harmony.core.remote.transfer.TransferOutbox(
                AppDirs.transfers,
                log = { java.util.logging.Logger.getLogger("com.harmony.transfer").info(it) },
            )
            // The shared queue lives as long as the app; it is offered to phones only while switched on.
            val sharedQueue = com.harmony.core.remote.queue.QueueService(onChange = { state, _ -> player.sharedQueue(state) })
            val connect = ConnectHost(
                pcId = settings.current.pcId,
                pcName = { settings.current.pcName },
                renderer = player,
                initialPhones = settings.current.phones,
                savePhones = { phones -> settings.update { it.copy(phones = phones) } },
                queue = { sharedQueue.takeIf { settings.current.sharedQueue } },
                // Offered with the shared queue: phones only ask for songs it has given them.
                media = { id -> if (settings.current.sharedQueue) media(id) else com.harmony.core.remote.ServedMedia.Missing },
                transfers = transfers,
            )
            // Edits made in the queue panel: the computer is the coordinator, so they go straight in.
            player.onSharedEdit = { op ->
                if (settings.current.sharedQueue) {
                    sharedQueue.submit(
                        com.harmony.core.remote.queue.QueueCommand(java.util.UUID.randomUUID().toString(), settings.current.pcId, op, sharedQueue.state.epoch),
                    )
                }
            }
            val app = DesktopApp(settings, engine, player, connect, LibraryCache(AppDirs.library), LibraryScanner(), CoverCache(AppDirs.covers), cache = cache, media = media, transfers = transfers)
            library = { app.tracks.value }
            connect.start()
            app.rescan()
            return app
        }
    }
}
