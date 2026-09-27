package com.harmony.feature.downloads

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * The FFmpeg executable bundled in the APK, launched from Harmony's own
 * verified library set instead of through yt-dlp's FFmpeg setup, which fails
 * to initialize on some phones. SpotiFLAC prepares the same set in the same
 * folder, so it is extracted once and shared.
 */
internal class BundledFfmpeg(private val context: Context) {

    class Failure(message: String, val details: String, cause: Throwable? = null) : Exception(message, cause)

    @Volatile private var packagesDir: File? = null

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    /**
     * Runs FFmpeg with [arguments], trying each linker environment in turn until
     * one exits cleanly and [succeeded] accepts the result. Returns the profile
     * that worked; throws [Failure] with every attempt's log tail otherwise.
     */
    suspend fun run(
        arguments: List<String>,
        workDir: File,
        timeoutMs: Long,
        succeeded: () -> Boolean = { true },
    ): FfmpegEnvironmentProfile {
        val packages = try {
            prepare()
        } catch (t: Exception) {
            throw Failure("FFmpeg could not be prepared on this phone.", t.stackTraceToString(), t)
        }
        val executable = executable()
        val attempts = StringBuilder()
        for (profile in FfmpegEnvironmentProfile.entries) {
            currentCoroutineContext().ensureActive()
            val log = File(workDir, "ffmpeg-${profile.id}.log").apply { delete() }
            val process = try {
                ProcessBuilder(listOf(executable.absolutePath) + arguments)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .apply {
                        SpotiFlacFfmpegEnvironment.configure(environment(), packages, nativeDir, context.cacheDir, profile)
                    }
                    .start()
            } catch (t: Exception) {
                attempts.append("[${profile.id}] could not start: ${t.message ?: t.javaClass.simpleName}\n")
                continue
            }
            val finished = awaitDownloadProcess(process, timeoutMs)
            val exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
            val tail = runCatching { log.readText().takeLast(LOG_TAIL) }.getOrDefault("")
            log.delete()
            if (finished && exitCode == 0 && succeeded()) return profile
            attempts.append("[${profile.id}] ${if (finished) "exit $exitCode" else "timed out"}\n")
            if (tail.isNotBlank()) attempts.append(tail.trimEnd()).append('\n')
        }
        throw Failure("FFmpeg couldn't convert this audio on this phone.", attempts.toString())
    }

    @Synchronized
    private fun prepare(): File {
        packagesDir?.let { return it }
        check(File(nativeDir, "libc++_shared.so").isFile) {
            "The APK is missing libc++_shared.so required by FFmpeg. Rebuild with the shared NDK runtime."
        }
        return SpotiFlacFfmpegRuntime.prepare(nativeDir, File(context.noBackupFilesDir, RUNTIME_DIR))
            .also { packagesDir = it }
    }

    private fun executable(): File {
        val executable = listOf(File(nativeDir, "libffmpeg.so"), File(nativeDir, "libffmpeg.bin.so"))
            .firstOrNull { it.isFile && it.length() > 0L }
            ?: throw Failure("FFmpeg is missing from this installation.", "No FFmpeg executable in ${nativeDir.absolutePath}")
        if (!executable.canExecute()) runCatching { executable.setExecutable(true, false) }
        return executable
    }

    private companion object {
        /** Shared with SpotiFlacDownloadEngine's prepareFfmpegRuntime. */
        const val RUNTIME_DIR = "spotiflac/ffmpeg-libraries-v1"
        const val LOG_TAIL = 2_000
    }
}
