package com.harmony.feature.downloads

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Private, versioned shared-library set for direct FFmpeg launches. The FFmpeg
 * AAR also needs libraries shipped in the Python AAR, but not Python itself or
 * yt-dlp. Never mix an old yt-dlp extraction with a new FFmpeg executable.
 */
internal object SpotiFlacFfmpegRuntime {
    private val libraryName = Regex("lib[A-Za-z0-9_+.-]+\\.so(?:\\.[A-Za-z0-9_.-]+)?")

    @Synchronized
    fun prepare(nativeDir: File, runtimeRoot: File): File {
        val archives = listOf("ffmpeg", "python").associateWith { name ->
            File(nativeDir, "lib$name.zip.so").also { check(it.isFile) { "Missing bundled ${it.name}" } }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        archives.values.forEach { file -> file.inputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } }
        val version = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(runtimeRoot.mkdirs() || runtimeRoot.isDirectory) { "Cannot create FFmpeg library directory" }
        val destination = File(runtimeRoot, version)
        // Check CRCs, not an archive-size preference. This repairs interrupted
        // extraction, stale same-size packages and missing aliases on next use.
        if (archives.all { (name, archive) -> validateLibraries(archive, File(destination, "$name/usr/lib")) }) {
            return destination
        }
        val staging = File(runtimeRoot, ".prepare-${UUID.randomUUID()}")
        try {
            archives.forEach { (name, archive) -> extractLibraries(archive, File(staging, "$name/usr/lib")) }
            if (destination.exists()) check(destination.deleteRecursively()) { "Cannot replace incomplete FFmpeg libraries" }
            check(staging.renameTo(destination)) { "Cannot publish FFmpeg libraries" }
            return destination
        } finally {
            staging.deleteRecursively()
        }
    }

    /** APK-owned archives only. Extract root-level shared libraries, not Python modules/executables. */
    internal fun extractLibraries(archive: File, destination: File) {
        check(destination.mkdirs() || destination.isDirectory)
        ZipFile(archive).use { zip ->
            val libraries = entries(zip)
            var total = 0L
            for ((name, entry) in libraries) {
                val actual = resolve(zip, libraries, entry, emptySet())
                total += actual.size
                check(total <= 512L * 1024 * 1024) { "FFmpeg library set exceeds extraction limit" }
                val output = File(destination, name)
                check(output.canonicalFile.parentFile == destination.canonicalFile) { "Library path escapes destination" }
                // Materialize aliases as regular files: no symlink support or
                // execution permission on writable app-data is required.
                zip.getInputStream(actual).use { input -> output.outputStream().use { input.copyTo(it) } }
                check(validFile(output, actual)) { "Invalid bundled library: $name" }
            }
        }
    }

    private fun validateLibraries(archive: File, destination: File): Boolean = runCatching {
        ZipFile(archive).use { zip ->
            val libraries = entries(zip)
            libraries.all { (name, entry) -> validFile(File(destination, name), resolve(zip, libraries, entry, emptySet())) }
        }
    }.getOrDefault(false)

    private fun entries(zip: ZipFile): Map<String, ZipEntry> {
        val result = linkedMapOf<String, ZipEntry>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.name.startsWith("usr/lib/")) continue
            val name = entry.name.removePrefix("usr/lib/")
            if (!libraryName.matches(name)) continue
            check(entry.size in 1..128L * 1024 * 1024) { "Invalid library size: $name" }
            check(result.put(name, entry) == null) { "Duplicate library: $name" }
            check(result.size <= 512) { "Too many bundled libraries" }
        }
        check(result.isNotEmpty()) { "No shared libraries in ${zip.name}" }
        return result
    }

    private fun resolve(zip: ZipFile, libraries: Map<String, ZipEntry>, entry: ZipEntry, seen: Set<String>): ZipEntry {
        check(entry.name !in seen && seen.size < 16) { "Cyclic bundled library alias" }
        val header = zip.getInputStream(entry).use { input -> ByteArray(4).also { input.read(it) } }
        if (header.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) return entry
        check(entry.size <= 256) { "Library is not ELF: ${entry.name}" }
        val alias = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(libraryName.matches(alias)) { "Unsafe bundled library alias" }
        val target = libraries[alias] ?: error("Missing library alias target: $alias")
        return resolve(zip, libraries, target, seen + entry.name)
    }

    private fun validFile(file: File, expected: ZipEntry): Boolean {
        if (!file.isFile || file.length() != expected.size) return false
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        return crc.value == expected.crc
    }
}
