package com.harmony.feature.downloads

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpotiFlacFfmpegEnvironmentTest {
    @get:Rule val temp = TemporaryFolder()
    private fun configure(profile: FfmpegEnvironmentProfile, python: Boolean = true): Map<String, String> {
        val packages = File(temp.root, "packages").apply { mkdirs() }
        File(packages, "ffmpeg/usr/lib").mkdirs()
        if (python) File(packages, "python/usr/lib").mkdirs()
        File(packages, "aria2c/usr/lib").mkdirs()
        val native = File(temp.root, "native").apply { mkdirs() }
        val cache = File(temp.root, "cache").apply { mkdirs() }
        return mutableMapOf(
            "LD_LIBRARY_PATH" to "/legacy/lib:/legacy/lib", "PATH" to "/system/bin",
            "PYTHONHOME" to "/old/python", "PYTHONPATH" to "/old/modules", "LD_PRELOAD" to "/old/preload.so",
        ).also { SpotiFlacFfmpegEnvironment.configure(it, packages, native, cache, profile) }
    }

    @Test fun isolatedIsTheFirstAttempt() {
        assertEquals(FfmpegEnvironmentProfile.ISOLATED, FfmpegEnvironmentProfile.entries.first())
    }
    @Test fun isolatedUsesFfmpegFirstThenCommonDependenciesNotInheritedLibraries() {
        val env = configure(FfmpegEnvironmentProfile.ISOLATED)
        assertEquals(
            File(temp.root, "packages/ffmpeg/usr/lib").absolutePath + ":" +
                File(temp.root, "packages/python/usr/lib").absolutePath + ":" + File(temp.root, "native").absolutePath,
            env["LD_LIBRARY_PATH"],
        )
        for (key in listOf("LD_PRELOAD", "PYTHONHOME", "PYTHONPATH")) assertFalse(env.containsKey(key))
        assertEquals(File(temp.root, "cache").absolutePath, env["TMPDIR"])
    }
    @Test fun absentDirectoriesAreNotAddedToLibrarySearch() {
        val env = configure(FfmpegEnvironmentProfile.ISOLATED, python = false)
        assertTrue(env["LD_LIBRARY_PATH"]!!.startsWith(File(temp.root, "packages/ffmpeg/usr/lib").absolutePath))
        assertEquals(File(temp.root, "cache").absolutePath, env["HOME"])
    }
    @Test fun upstreamFallbackRetainsOriginalLibraryOrder() {
        val env = configure(FfmpegEnvironmentProfile.UPSTREAM)
        assertEquals(listOf("python", "ffmpeg", "aria2c").joinToString(":") {
            File(temp.root, "packages/$it/usr/lib").absolutePath
        }, env["LD_LIBRARY_PATH"])
    }
    @Test fun extendedFallbackRetainsVendorPathWithoutDuplicates() {
        val env = configure(FfmpegEnvironmentProfile.EXTENDED)
        assertTrue(env["LD_LIBRARY_PATH"]!!.endsWith(":" + File(temp.root, "native").absolutePath + ":/legacy/lib"))
        assertEquals("/system/bin:" + File(temp.root, "native").absolutePath, env["PATH"])
    }
    @Test fun emptyInstallPathIsNeverAddedToLinkerSearch() {
        val env = mutableMapOf<String, String>()
        SpotiFlacFfmpegEnvironment.configure(env, File(temp.root, "missing"), File(temp.root, "absent"), temp.root, FfmpegEnvironmentProfile.ISOLATED)
        assertEquals("", env["LD_LIBRARY_PATH"])
    }
}
