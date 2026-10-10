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

    init {
        engine.volume = settings.current.volume
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

    private fun update(change: (DesktopSettings) -> DesktopSettings) {
        _settings.value = settings.update(change)
    }

    override fun close() {
        connect.close()
        engine.shutdown()
        settings.flush()
        plays.flush()
        scope.cancel()
    }

    companion object {
        const val PLAYED_AFTER_MS = 30_000L

        fun create(): DesktopApp {
            val settings = SettingsStore()
            val engine = AudioEngine()
            val player = PlayerController(engine, com.harmony.desktop.connect.RemoteCache())
            val connect = ConnectHost(
                pcId = settings.current.pcId,
                pcName = { settings.current.pcName },
                renderer = player,
                initialPhones = settings.current.phones,
                savePhones = { phones -> settings.update { it.copy(phones = phones) } },
            )
            val app = DesktopApp(settings, engine, player, connect, LibraryCache(AppDirs.library), LibraryScanner(), CoverCache(AppDirs.covers))
            connect.start()
            app.rescan()
            return app
        }
    }
}
