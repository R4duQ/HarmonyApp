package com.harmony.core.common.crash

import java.io.File
import java.time.Instant

/**
 * A log the listener can open on the phone (Settings › Diagnostics log),
 * copy and share, since Logcat is out of reach without a computer:
 *
 *  - crashes: the stack trace, written as the app goes down ([install]);
 *  - errors Harmony caught and carried on from ([error]), with their stack;
 *  - short event lines ([line]): ids, counts and timings, never song titles,
 *    URLs or keys (callers keep to that, as for Logcat).
 *
 * One file, kept under [MAX_BYTES]: when it grows past that, the older half
 * goes. Writes are serialized; before [init] nothing is written.
 */
object DiagnosticsLog {
    const val FILE = "diagnostics.log"
    const val MAX_BYTES = 256 * 1024
    private const val MAX_ENTRY_CHARS = 32_000

    @Volatile private var file: File? = null
    private val lock = Any()

    fun init(dir: File) {
        dir.mkdirs()
        file = File(dir, FILE)
    }

    /** [init], and every uncaught crash written down before the previous handler ends the app. */
    fun install(dir: File, version: String) {
        init(dir)
        line("START", "Harmony $version")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write("CRASH", "uncaught on thread ${thread.name} (Harmony $version)", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun line(tag: String, message: String) = write(tag, message, null)

    fun error(tag: String, message: String, error: Throwable) = write(tag, message, error)

    fun read(): String = synchronized(lock) { file?.takeIf { it.isFile }?.readText().orEmpty() }

    fun clear() {
        synchronized(lock) { file?.delete() }
    }

    private fun write(tag: String, message: String, error: Throwable?) {
        val target = file ?: return
        val entry = buildString {
            append(Instant.now()).append(' ').append(tag).append(' ').append(message).append('\n')
            if (error != null) append(error.stackTraceToString().take(MAX_ENTRY_CHARS)).append('\n')
        }
        synchronized(lock) {
            runCatching {
                target.appendText(entry)
                if (target.length() > MAX_BYTES) {
                    val text = target.readText()
                    val keepFrom = text.indexOf('\n', text.length / 2).takeIf { it >= 0 } ?: (text.length / 2)
                    target.writeText("… older entries removed …\n" + text.substring(keepFrom + 1))
                }
            }
        }
    }
}
