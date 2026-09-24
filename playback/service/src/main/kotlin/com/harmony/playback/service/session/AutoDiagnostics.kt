package com.harmony.playback.service.session

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small, persistent record of how car and other media clients reach the
 * playback service: when the process started, when the service was created,
 * who bound, connected and browsed, and how long each step took.
 *
 * Why a file and not only logcat: the problem shows up in the car, and the
 * logcat buffer has usually rolled over by the time the phone reaches a
 * computer. The file keeps the last few drives. It holds package names and
 * timings only, never song titles or account data.
 *
 * Read it with:
 *   adb pull /sdcard/Android/data/com.harmony.app/files/android-auto-log.txt
 */
object AutoDiagnostics {
    private const val TAG = "HarmonyAuto"
    private const val MAX_BYTES = 256 * 1024L
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "harmony-auto-log").apply { isDaemon = true } }
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "android-auto-log.txt")
        val sinceStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val users = context.getSystemService(UserManager::class.java)
        log("---- service created ${sinceStart} ms after process start · locked=${keyguard?.isDeviceLocked} " +
            "userUnlocked=${users?.isUserUnlocked} · Android ${Build.VERSION.RELEASE}")
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val target = file ?: return
        val line = "${clock.format(Date())} $message\n"
        writer.execute {
            runCatching {
                if (target.length() > MAX_BYTES) {
                    // Keep the newer half: the drive that just happened matters most.
                    val kept = target.readText().takeLast((MAX_BYTES / 2).toInt())
                    target.writeText(kept)
                }
                target.appendText(line)
            }
        }
    }

    /** Runs [block] and records how long it took, including when it fails. */
    inline fun <T> timed(what: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try {
            return block().also { log("$what · ${SystemClock.elapsedRealtime() - start} ms") }
        } catch (e: Throwable) {
            log("$what FAILED after ${SystemClock.elapsedRealtime() - start} ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}
