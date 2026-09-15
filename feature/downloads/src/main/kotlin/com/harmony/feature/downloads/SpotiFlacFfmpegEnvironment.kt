package com.harmony.feature.downloads

import java.io.File

/** Direct FFmpeg launches do not require Python or the phone's media encoders. */
internal enum class FfmpegEnvironmentProfile(val id: String) {
    ISOLATED("ffmpeg-only"),
    UPSTREAM("upstream"),
    EXTENDED("extended"),
}

internal object SpotiFlacFfmpegEnvironment {
    fun configure(
        env: MutableMap<String, String>,
        packagesDir: File,
        nativeDir: File,
        cacheDir: File,
        profile: FfmpegEnvironmentProfile,
    ) {
        val ffmpeg = File(packagesDir, "ffmpeg/usr/lib")
        val python = File(packagesDir, "python/usr/lib")
        val aria = File(packagesDir, "aria2c/usr/lib")
        val inheritedLd = env["LD_LIBRARY_PATH"].orEmpty()
        val libraries = when (profile) {
            // Never load a same-named Python/aria2 library ahead of FFmpeg's
            // own ABI-matched dependencies in the preferred launch profile.
            // The Python AAR supplies common native dependencies (OpenSSL,
            // expat, Android support), without needing the Python runtime.
            FfmpegEnvironmentProfile.ISOLATED -> listOf(ffmpeg, python)
            else -> listOf(python, ffmpeg, aria)
        }.filter(File::isDirectory).map(File::getAbsolutePath).toMutableList()
        if (profile != FfmpegEnvironmentProfile.UPSTREAM && nativeDir.isDirectory) {
            libraries += nativeDir.absolutePath
        }
        if (profile == FfmpegEnvironmentProfile.EXTENDED) {
            libraries += inheritedLd.split(':').filter(String::isNotBlank)
        }
        env["LD_LIBRARY_PATH"] = libraries.distinct().joinToString(":")
        env["PATH"] = listOf(env["PATH"].orEmpty(), nativeDir.absolutePath)
            .filter(String::isNotBlank).joinToString(":")
        env["TMPDIR"] = cacheDir.absolutePath
        if (profile == FfmpegEnvironmentProfile.ISOLATED) {
            env.remove("LD_PRELOAD")
            env.remove("PYTHONHOME")
            env.remove("PYTHONPATH")
            env["HOME"] = cacheDir.absolutePath
        } else {
            val pythonHome = File(packagesDir, "python/usr")
            if (pythonHome.isDirectory) {
                env["PYTHONHOME"] = pythonHome.absolutePath
                env["HOME"] = pythonHome.absolutePath
                val cert = File(pythonHome, "etc/tls/cert.pem")
                if (cert.isFile) env["SSL_CERT_FILE"] = cert.absolutePath
            }
        }
    }
}
