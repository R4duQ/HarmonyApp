package com.harmony.feature.downloads

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/** Installs only verified provider bytes; transport stays in the host engine. */
internal object SpotiFlacProviderInstaller {
    fun install(
        provider: ProviderPackage,
        extensionsDir: File,
        download: (ProviderPackage, File) -> Unit,
    ) {
        val destination = File(extensionsDir, "${provider.id}.sflx")
        if (destination.isFile && sha256(destination).equals(provider.sha256, ignoreCase = true)) return

        val partial = File(extensionsDir, "${provider.id}.sflx.part")
        partial.delete()
        try {
            download(provider, partial)
            val actualHash = sha256(partial)
            if (!actualHash.equals(provider.sha256, ignoreCase = true)) {
                throw SpotiFlacException(
                    "The ${provider.displayName} provider failed its SHA-256 integrity check.",
                    "provider_integrity",
                    technicalDetails = "expected=${provider.sha256}; actual=$actualHash; version=${provider.version}",
                )
            }

            // The existing package is untouched until its replacement is verified.
            if (destination.exists()) {
                val archiveDir = File(extensionsDir, "archive").apply { mkdirs() }
                val backup = File(archiveDir, "${provider.id}-${System.currentTimeMillis()}.sflx.bak")
                if (!destination.renameTo(backup)) {
                    destination.copyTo(backup, overwrite = true)
                    destination.delete()
                }
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
            }
        } finally {
            // Includes transport failures and interrupted copies, not just hash mismatches.
            partial.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}
