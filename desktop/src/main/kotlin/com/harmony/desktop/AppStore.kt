package com.harmony.desktop

import com.harmony.core.model.ClaritySettings
import com.harmony.desktop.engine.EqConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.UUID

/** Where Harmony keeps its things: %APPDATA%\Harmony on Windows, ~/.harmony elsewhere. */
object AppDirs {
    val root: File by lazy {
        val appData = System.getenv("APPDATA")
        val dir = if (!appData.isNullOrBlank()) File(appData, "Harmony") else File(System.getProperty("user.home"), ".harmony")
        dir.apply { mkdirs() }
    }
    val library: File get() = File(root, "library.json")
    val covers: File get() = File(root, "covers")
    val settings: File get() = File(root, "settings.json")
}

/** Everything the desktop app remembers between runs. */
data class DesktopSettings(
    val folders: List<String> = defaultFolders(),
    val volume: Float = 0.8f,
    val eq: EqConfig = EqConfig(),
    val pcId: String = UUID.randomUUID().toString(),
    val pcName: String = defaultName(),
    /** Connect tokens: token -> phone name. */
    val phones: Map<String, String> = emptyMap(),
    val darkTheme: Boolean = true,
) {
    fun toJson(): String = JSONObject()
        .put("folders", JSONArray(folders))
        .put("volume", volume.toDouble())
        .put("eq", JSONObject()
            .put("enabled", eq.enabled)
            .put("winamp", JSONArray(eq.winampGainsDb.map { it.toDouble() }))
            .put("preamp", eq.winampPreampDb.toDouble())
            .put("clarity", eq.clarity)
            .put("recover", eq.claritySettings.recover.toDouble())
            .put("tame", eq.claritySettings.tame.toDouble())
            .put("bias", eq.claritySettings.bias.toDouble())
            .put("brighten", eq.claritySettings.brighten.toDouble())
            .put("boost", eq.claritySettings.boostDb.toDouble())
            .put("bass", eq.bassDb.toDouble())
            .put("treble", eq.trebleDb.toDouble())
            .put("width", eq.width.toDouble())
            .put("leveling", eq.leveling))
        .put("pcId", pcId)
        .put("pcName", pcName)
        .put("phones", JSONObject(phones))
        .put("dark", darkTheme)
        .toString(2)

    companion object {
        fun defaultFolders(): List<String> =
            listOf(File(System.getProperty("user.home"), "Music")).filter { it.isDirectory }.map { it.absolutePath }

        fun defaultName(): String = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "Harmony PC"

        fun fromJson(json: String): DesktopSettings = runCatching {
            val o = JSONObject(json)
            val d = DesktopSettings()
            val eq = o.optJSONObject("eq")
            val winamp = eq?.optJSONArray("winamp")
            val phones = o.optJSONObject("phones")
            DesktopSettings(
                folders = o.optJSONArray("folders")?.let { a -> List(a.length()) { a.getString(it) } } ?: d.folders,
                volume = o.optDouble("volume", d.volume.toDouble()).toFloat(),
                eq = if (eq == null) d.eq else EqConfig(
                    enabled = eq.optBoolean("enabled"),
                    winampGainsDb = if (winamp != null && winamp.length() == 10) List(10) { winamp.getDouble(it).toFloat() } else d.eq.winampGainsDb,
                    winampPreampDb = eq.optDouble("preamp", 0.0).toFloat(),
                    clarity = eq.optBoolean("clarity"),
                    claritySettings = ClaritySettings(
                        recover = eq.optDouble("recover", 0.5).toFloat(),
                        tame = eq.optDouble("tame", 0.5).toFloat(),
                        bias = eq.optDouble("bias", 0.0).toFloat(),
                        brighten = eq.optDouble("brighten", 0.0).toFloat(),
                        boostDb = eq.optDouble("boost", 0.0).toFloat(),
                    ).clamped(),
                    bassDb = eq.optDouble("bass", 0.0).toFloat(),
                    trebleDb = eq.optDouble("treble", 0.0).toFloat(),
                    width = eq.optDouble("width", 1.0).toFloat().coerceIn(0f, 2f),
                    leveling = eq.optBoolean("leveling"),
                ),
                pcId = o.optString("pcId").ifEmpty { d.pcId },
                pcName = o.optString("pcName").ifEmpty { d.pcName },
                phones = phones?.keys()?.asSequence()?.associateWith { phones.getString(it) } ?: emptyMap(),
                darkTheme = o.optBoolean("dark", true),
            )
        }.getOrDefault(DesktopSettings())
    }
}

/**
 * The settings file. Changes take effect at once in [current]; the file is
 * written in the background, a moment after the last change, so dragging a
 * slider never waits on the disk.
 */
class SettingsStore(private val file: File = AppDirs.settings) {
    @Volatile var current: DesktopSettings = load()
        private set

    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "harmony-settings").apply { isDaemon = true }
    }
    private var pendingWrite: java.util.concurrent.ScheduledFuture<*>? = null

    private fun load(): DesktopSettings {
        val s = if (file.isFile) DesktopSettings.fromJson(file.readText()) else DesktopSettings()
        if (!file.isFile) save(s)
        return s
    }

    @Synchronized
    fun update(change: (DesktopSettings) -> DesktopSettings): DesktopSettings {
        val next = change(current)
        current = next
        pendingWrite?.cancel(false)
        pendingWrite = writer.schedule({ save(current) }, SAVE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return next
    }

    /** Writes what's pending now (the app is closing). */
    @Synchronized
    fun flush() {
        if (pendingWrite?.cancel(false) == true) save(current)
    }

    private fun save(s: DesktopSettings) {
        runCatching {
            file.parentFile?.mkdirs()
            // Written beside it, then swapped in, so a crash mid-write never loses the settings.
            val tmp = File(file.path + ".tmp")
            tmp.writeText(s.toJson())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 400L
    }
}
