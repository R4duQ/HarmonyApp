package com.harmony.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** How often a song was played, and when last. */
data class PlayStat(val count: Int, val lastPlayed: Long)

/**
 * What gets played on this computer, for "Most played" and "Recently
 * played". Kept in plays.json next to the settings, written in the
 * background a moment after each change.
 */
class PlayStats(private val file: File = AppDirs.plays, private val clock: () -> Long = System::currentTimeMillis) {
    private val _stats = MutableStateFlow(load())
    val stats: StateFlow<Map<String, PlayStat>> = _stats.asStateFlow()

    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "harmony-plays").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    /** One more play of the song at [path]. */
    @Synchronized
    fun record(path: String) {
        val now = clock()
        val old = _stats.value[path]
        _stats.value = _stats.value + (path to PlayStat((old?.count ?: 0) + 1, now))
        pending?.cancel(false)
        pending = writer.schedule({ save() }, 1, TimeUnit.SECONDS)
    }

    /** Writes what's pending now (the app is closing). */
    @Synchronized
    fun flush() {
        if (pending?.cancel(false) == true) save()
    }

    private fun load(): Map<String, PlayStat> = runCatching {
        if (!file.isFile) return emptyMap()
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { k ->
            val s = o.getJSONObject(k)
            PlayStat(s.optInt("count"), s.optLong("last"))
        }
    }.getOrDefault(emptyMap())

    private fun save() {
        runCatching {
            val o = JSONObject()
            for ((path, s) in _stats.value) o.put(path, JSONObject().put("count", s.count).put("last", s.lastPlayed))
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
