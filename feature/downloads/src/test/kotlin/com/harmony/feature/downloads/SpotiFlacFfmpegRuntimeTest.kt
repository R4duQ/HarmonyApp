package com.harmony.feature.downloads

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpotiFlacFfmpegRuntimeTest {
    @get:Rule val temp = TemporaryFolder()
    private val elf = byteArrayOf(0x7f, 0x45, 0x4c, 0x46) + ByteArray(100) { it.toByte() }
    private fun archive(name: String, vararg entries: Pair<String, ByteArray>): File = File(temp.root, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        } }
    }
    @Test fun extractsLibrariesAndMaterializesAliasChainsWithoutSymlinks() {
        val zip = archive("libs.zip", "usr/lib/liba.so.1.2" to elf,
            "usr/lib/liba.so.1" to "liba.so.1.2".toByteArray(), "usr/lib/liba.so" to "liba.so.1".toByteArray())
        val out = temp.newFolder("out")
        SpotiFlacFfmpegRuntime.extractLibraries(zip, out)
        for (name in listOf("liba.so", "liba.so.1", "liba.so.1.2")) assertArrayEquals(elf, File(out, name).readBytes())
    }
    @Test fun doesNotExtractPythonModulesExecutablesOrTraversalPaths() {
        val zip = archive("libs.zip", "usr/lib/liba.so" to elf,
            "usr/lib/python3.12/test.py" to byteArrayOf(1), "usr/bin/python" to elf,
            "usr/lib/../../escape.so" to elf)
        val out = temp.newFolder("out")
        SpotiFlacFfmpegRuntime.extractLibraries(zip, out)
        assertEquals(listOf("liba.so"), out.listFiles()!!.map { it.name })
        assertFalse(File(temp.root, "escape.so").exists())
    }
    @Test fun rejectsAliasOutsideBundledLibrarySet() {
        val zip = archive("libs.zip", "usr/lib/liba.so" to "../../outside.so".toByteArray())
        assertThrows(IllegalStateException::class.java) { SpotiFlacFfmpegRuntime.extractLibraries(zip, temp.newFolder("out")) }
    }
    @Test fun rejectsCyclicAliases() {
        val zip = archive("libs.zip", "usr/lib/liba.so" to "libb.so".toByteArray(), "usr/lib/libb.so" to "liba.so".toByteArray())
        assertThrows(IllegalStateException::class.java) { SpotiFlacFfmpegRuntime.extractLibraries(zip, temp.newFolder("out")) }
    }
    @Test fun rejectsMissingAliasTargets() {
        val zip = archive("libs.zip", "usr/lib/liba.so" to "libmissing.so".toByteArray())
        assertThrows(IllegalStateException::class.java) { SpotiFlacFfmpegRuntime.extractLibraries(zip, temp.newFolder("out")) }
    }
    @Test fun preparesOnCleanInstallWithoutYtdlpAndRepairsSameSizeCorruption() {
        archive("libffmpeg.zip.so", "usr/lib/liba.so" to elf)
        archive("libpython.zip.so", "usr/lib/libshared.so" to elf)
        val runtimeRoot = temp.newFolder("runtime")
        val first = SpotiFlacFfmpegRuntime.prepare(temp.root, runtimeRoot)
        val library = File(first, "ffmpeg/usr/lib/liba.so")
        library.writeBytes(ByteArray(elf.size))
        val repaired = SpotiFlacFfmpegRuntime.prepare(temp.root, runtimeRoot)
        assertEquals(first, repaired)
        assertArrayEquals(elf, library.readBytes())
        assertArrayEquals(elf, File(repaired, "python/usr/lib/libshared.so").readBytes())
    }
    @Test fun missingLibraryIsRepairedAndValidCacheIsReused() {
        archive("libffmpeg.zip.so", "usr/lib/liba.so" to elf)
        archive("libpython.zip.so", "usr/lib/libshared.so" to elf)
        val runtimeRoot = temp.newFolder("runtime")
        val first = SpotiFlacFfmpegRuntime.prepare(temp.root, runtimeRoot)
        val library = File(first, "ffmpeg/usr/lib/liba.so")
        val previous = library.lastModified()
        assertEquals(first, SpotiFlacFfmpegRuntime.prepare(temp.root, runtimeRoot))
        assertEquals(previous, library.lastModified())
        assertTrue(library.delete())
        SpotiFlacFfmpegRuntime.prepare(temp.root, runtimeRoot)
        assertArrayEquals(elf, library.readBytes())
    }
    @Test fun changedArchiveUsesNewVersionDirectory() {
        archive("libffmpeg.zip.so", "usr/lib/liba.so" to elf)
        archive("libpython.zip.so", "usr/lib/libshared.so" to elf)
        val runtimeRoot = temp.newFolder("runtime")
        val first = SpotiFlacFfmpegRuntime.prepare(temp.root, runtimeRoot)
        archive("libffmpeg.zip.so", "usr/lib/liba.so" to elf.copyOf().apply { this[20] = 77 })
        val second = SpotiFlacFfmpegRuntime.prepare(temp.root, runtimeRoot)
        assertNotEquals(first, second)
        assertTrue(first.isDirectory)
    }
}
