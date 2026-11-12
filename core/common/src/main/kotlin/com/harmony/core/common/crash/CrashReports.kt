package com.harmony.core.common.crash

import java.io.File
import java.time.Instant

/**
 * The last crash, written to a file as the app goes down, so it can be read
 * and copied after the app is opened again (Settings › Last crash report).
 * Only the stack trace, the thread and the version: the code that failed,
 * not the listener's songs.
 */
object CrashReports {
    const val FILE = "last-crash.txt"
    private const val MAX_CHARS = 64_000

    /** Records uncaught crashes in [dir], then lets the previous handler end the app as before. */
    fun install(dir: File, version: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(dir, version, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun write(dir: File, version: String, thread: String, error: Throwable, at: Instant = Instant.now()) {
        dir.mkdirs()
        val text = buildString {
            appendLine("Harmony $version")
            appendLine("time $at")
            appendLine("thread $thread")
            append(error.stackTraceToString())
        }.take(MAX_CHARS)
        File(dir, FILE).writeText(text)
    }

    fun read(dir: File): String? = File(dir, FILE).takeIf { it.isFile }?.readText()

    fun clear(dir: File) {
        File(dir, FILE).delete()
    }
}
