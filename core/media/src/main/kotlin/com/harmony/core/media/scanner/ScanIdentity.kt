package com.harmony.core.media.scanner

import com.harmony.core.media.model.MediaCandidate
import java.util.Locale

/** A name and byte count are not a file identity: separate albums often share both. */
object ScanIdentity {
    fun mediaPath(volume: String, directory: String?, name: String): String? =
        directory?.let { "${normalizedVolume(volume)}/${it.trim('/').let { path -> if (path.isEmpty()) name else "$path/$name" }}" }

    fun documentPath(authority: String?, documentId: String): String? {
        if (authority != "com.android.externalstorage.documents" || ':' !in documentId) return null
        val volume = documentId.substringBefore(':')
        val path = documentId.substringAfter(':').trimStart('/')
        return if (volume.isBlank() || path.isBlank()) null else "${normalizedVolume(volume)}/$path"
    }

    private fun normalizedVolume(value: String) = when (value.lowercase(Locale.ROOT)) {
        "primary", "external_primary" -> "external_primary"
        else -> value.lowercase(Locale.ROOT)
    }

    fun deduplicate(candidates: List<MediaCandidate>, knownUris: Set<String>): List<MediaCandidate> =
        candidates.distinctBy { it.uri }.groupBy { it.physicalPath ?: it.uri }.values.flatMap { copies ->
            // Retain an existing URI when a second provider starts exposing the same file.
            // Keep multiple legacy rows until an explicit merge can preserve their playlists.
            copies.filter { it.uri in knownUris }.ifEmpty { listOf(copies.first()) }
        }

    fun missing(known: Set<String>, found: Set<String>, scannedPrefixes: Set<String>): List<String> =
        known.filter { uri -> uri !in found && scannedPrefixes.any(uri::startsWith) }
}
