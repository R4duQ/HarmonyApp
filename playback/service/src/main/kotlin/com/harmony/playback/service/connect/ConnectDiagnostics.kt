package com.harmony.playback.service.connect

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What happened in Harmony Connect and the shared queue, kept in a small file
 * for testing on real devices (Universal Queue, stage 9): sessions starting and
 * ending (and why), handoffs with how long each step took and how long the
 * music was silent, the queue link's state, the phone's cache of the
 * computer's songs.
 *
 * Ids and timings only: never song titles, addresses with tokens, or tokens.
 * [save] copies it to Download/Harmony, to send from the Files app; with adb:
 *   adb pull /sdcard/Android/data/com.harmony.app/files/connect-log.txt
 */
object ConnectDiagnostics {
    private const val TAG = "HarmonyConnect"
    private const val MAX_BYTES = 512 * 1024L
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "harmony-connect-log").apply { isDaemon = true } }
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null
    @Volatile private var context: Context? = null

    fun init(context: Context) {
        if (file != null) return
        this.context = context.applicationContext
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "connect-log.txt")
        log("---- app started · Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})")
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val target = file ?: return
        val line = "${clock.format(Date())} $message\n"
        writer.execute {
            runCatching {
                if (target.length() > MAX_BYTES) target.writeText(target.readText().takeLast((MAX_BYTES / 2).toInt()))
                target.appendText(line)
            }
        }
    }

    /**
     * Copies this log (and the transfer and Android Auto ones, if any) into
     * Download/Harmony; returns where it went, or null if it couldn't.
     */
    fun save(): String? {
        val ctx = context ?: return null
        val source = file ?: return null
        // What's still being written goes first.
        runCatching { writer.submit { }.get() }
        val auto = File(source.parentFile, "android-auto-log.txt")
        val text = buildString {
            append("Harmony Connect log\n\n")
            append(runCatching { source.readText() }.getOrDefault("(empty)\n"))
            val transfers = File(source.parentFile, "transfer-log.txt")
            if (transfers.isFile) {
                append("\n\nSend to phone (transfers)\n\n")
                append(runCatching { transfers.readText() }.getOrDefault(""))
            }
            if (auto.isFile) {
                append("\n\nAndroid Auto log\n\n")
                append(runCatching { auto.readText() }.getOrDefault(""))
            }
        }
        val name = "harmony-connect-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Harmony")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no place")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("can't write")
            "Download/Harmony/$name"
        }.onFailure { Log.w(TAG, "couldn't save the log", it) }.getOrNull()
    }
}
