package com.harmony.feature.downloads

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * What a provider package may ask for.
 *
 * Provider packages update from the official registry without a signature:
 * the registry's SHA-256 only proves the bytes are the ones the registry
 * names. So an update is also checked against what Harmony has reviewed for
 * that provider: the package must be the provider it claims to be, in the
 * version the registry names, and may reach only hosts this build knows the
 * provider uses. A package that asks for more is not installed; the provider
 * stays on the last accepted package until a Harmony update reviews the new
 * access. (The runtime enforces the manifest's own list; this bounds what the
 * manifest may say.)
 */
internal object SpotiFlacProviderGate {
    /** A package is at most this large; the four providers are a few hundred KB. */
    const val MAX_PACKAGE_BYTES = 16L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private val allowedPermissions = setOf("network", "storage", "file")

    /** One reviewed host: exact, any host under a suffix, or a pattern of exact hosts. */
    internal sealed interface Reviewed {
        fun covers(entry: String): Boolean

        data class Host(val host: String) : Reviewed {
            override fun covers(entry: String) = entry == host
        }

        /** "*.suffix" in a manifest, or any host (or narrower wildcard) under it. */
        data class Under(val suffix: String) : Reviewed {
            override fun covers(entry: String): Boolean {
                val host = entry.removePrefix("*.")
                return host.endsWith(".$suffix") || entry == "*.$suffix"
            }
        }

        /** Exact hosts of one shape (regional sites); never a wildcard entry. */
        data class Shape(val pattern: Regex) : Reviewed {
            override fun covers(entry: String) = '*' !in entry && pattern.matches(entry)
        }
    }

    private fun hosts(vararg names: String) = names.map { Reviewed.Host(it) }

    /** The hosts each provider is known to use (Mornye-Extension, reviewed for this build). */
    internal val reviewed: Map<String, List<Reviewed>> = mapOf(
        "tidal-web" to hosts("tidal.com", "api.zarz.moe") + Reviewed.Under("tidal.com"),
        "qobuz-web" to hosts("qobuz.com", "api.zarz.moe", "streaming-qobuz-std.akamaized.net") +
            Reviewed.Under("qobuz.com"),
        "deezer" to hosts("api.zarz.moe", "dl.musicdl.me", "deezer.page.link") +
            Reviewed.Under("deezer.com") + Reviewed.Under("dzcdn.net"),
        "amazon" to hosts(
            "m.media-amazon.com", "api.zarz.moe", "song.link", "album.link", "artist.link",
            "odesli.co", "www.odesli.co", "odesli.io", "api.deezer.com", "open.spotify.com", "songstats.com",
        ) + Reviewed.Under("skill.music.a2z.com") + Reviewed.Under("cloudfront.net") +
            Reviewed.Shape(Regex("music\\.amazon\\.[a-z]{2,3}(\\.[a-z]{2})?")),
    )

    /** Null when [file] may be installed as [expected]; otherwise why not. */
    fun refusal(file: File, expected: ProviderPackage): String? = runCatching {
        if (file.length() > MAX_PACKAGE_BYTES) return@runCatching "package is larger than ${MAX_PACKAGE_BYTES / (1024 * 1024)} MB"
        val manifest = readManifest(file) ?: return@runCatching "package has no readable manifest.json"
        val name = manifest.optString("name").trim()
        if (name != expected.id) return@runCatching "package is '$name', not '${expected.id}'"
        val version = manifest.optString("version").trim()
        if (version != expected.version) return@runCatching "package is version $version, the registry says ${expected.version}"
        val permissions = manifest.optJSONObject("permissions") ?: JSONObject()
        val unknown = permissions.keys().asSequence().filter { it !in allowedPermissions }.toList()
        if (unknown.isNotEmpty()) return@runCatching "package asks for new permissions: ${unknown.sorted().joinToString()}"
        val known = reviewed[expected.id] ?: return@runCatching "no reviewed access for '${expected.id}'"
        val network = permissions.optJSONArray("network")
        val asked = (0 until (network?.length() ?: 0)).map { network!!.optString(it).trim().lowercase(Locale.ROOT) }
        val wider = asked.filter { entry ->
            // A wildcard is only ever a leading "*." label.
            entry.isEmpty() || '*' in entry.removePrefix("*.") || known.none { it.covers(entry) }
        }
        if (wider.isNotEmpty()) "package asks to reach hosts this build has not reviewed: ${wider.joinToString()}" else null
    }.getOrElse { "package could not be inspected: ${it.message ?: it.javaClass.simpleName}" }

    private fun readManifest(file: File): JSONObject? = ZipFile(file).use { zip ->
        val entry = zip.getEntry("manifest.json") ?: return null
        if (entry.isDirectory || entry.size > MAX_MANIFEST_BYTES) return null
        val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_MANIFEST_BYTES.toLong()) }
        JSONObject(String(bytes, Charsets.UTF_8))
    }

    /** Reads all of [input], refusing more than [maxBytes] (a response's size is the server's to choose). */
    fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw java.io.IOException("response is larger than $maxBytes bytes")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /** Copies [input] to [target], refusing more than [maxBytes]. */
    fun copyBounded(input: InputStream, target: File, maxBytes: Long) {
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw java.io.IOException("download is larger than $maxBytes bytes")
                out.write(buffer, 0, count)
            }
        }
    }

    /**
     * Dotted versions, numerically: 1.2.10 is newer than 1.2.9. Missing parts
     * count as 0; anything that is not a number sorts as 0.
     */
    fun compareVersions(a: String, b: String): Int {
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(i) { 0L }.compareTo(right.getOrElse(i) { 0L })
            if (difference != 0) return difference
        }
        return 0
    }

    private fun parts(version: String): List<Long> =
        version.trim().removePrefix("v").split('.', '-', '+').map { part ->
            part.takeWhile(Char::isDigit).take(12).toLongOrNull() ?: 0L
        }
}
