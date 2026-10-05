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
) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tracks = MutableStateFlow(libraryCache.load())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    private val _scan = MutableStateFlow<ScanProgress?>(null)
    val scan: StateFlow<ScanProgress?> = _scan.asStateFlow()

    private val _settings = MutableStateFlow(settings.current)
    val settingsFlow: StateFlow<DesktopSettings> = _settings.asStateFlow()

    private var scanJob: Job? = null

    init {
        engine.volume = settings.current.volume
        engine.eq = settings.current.eq
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
        scope.cancel()
    }

    companion object {
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
