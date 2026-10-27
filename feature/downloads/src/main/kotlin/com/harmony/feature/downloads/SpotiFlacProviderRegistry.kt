package com.harmony.feature.downloads

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

internal data class ProviderPackage(
    val id: String,
    val displayName: String,
    val version: String,
    val url: String,
    val sha256: String,
)

internal data class RegistrySnapshot(
    val packages: List<ProviderPackage>,
    val updatedAt: String,
    val source: String,
) {
    val fingerprint: String
        get() {
            val value = packages.joinToString("|") { "${it.id}:${it.version}:${it.sha256}" }
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }.take(16)
        }
    val summary: String
        get() = "$source; updated=$updatedAt; fingerprint=$fingerprint; " +
            packages.joinToString { "${it.id}@${it.version}" }
}

/** Official registry discovery, with an immutable snapshot for network/registry failures. */
internal object SpotiFlacProviderRegistry {
    const val OFFICIAL_REGISTRY_URL =
        "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/registry.json"
    const val FALLBACK_COMMIT = "93d55eee1cc52f088c311459e015b168c4798aa5"
    val priority = listOf("tidal-web", "qobuz-web", "deezer", "amazon")
    private val names = mapOf("tidal-web" to "Tidal", "qobuz-web" to "Qobuz", "deezer" to "Deezer", "amazon" to "Amazon Music")
    private val filenames = mapOf("tidal-web" to "tidal-web.sflx", "qobuz-web" to "qobuz-web.sflx", "deezer" to "deezer.sflx", "amazon" to "amzn.sflx")
    private val officialRepositories = setOf(
        "mornyeapp/mornye-extension", "zarzet/spotiflac-extension", "spotiflacapp/spotiflac-extension",
    )

    // Versions and SHA-256 values match registry.json and the package bytes at
    // FALLBACK_COMMIT. A fixed hash must never be paired with a mutable /main URL.
    val fallbackPackages: List<ProviderPackage> = listOf(
        pinned("tidal-web", "1.2.9", "69ce52eabdd9df6464a30b793b802d3d7be32b331082c02a9bf2b25d1d49114d"),
        pinned("qobuz-web", "1.2.23", "054c1d2c40058b49f5ade17a64442014ddf05944663a12c790a17d63ee99985f"),
        pinned("deezer", "1.3.7", "39868171eee142f21de323b38c2ee5690e44659496f8e70e01554564b464f292"),
        pinned("amazon", "2.3.14", "d45e105b4286d4c7ddc062b73b8190c93099e7f6e4d4586b420e0b31dabcb967"),
    )

    private fun pinned(id: String, version: String, hash: String) = ProviderPackage(
        id = id,
        displayName = names.getValue(id),
        version = version,
        url = "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/$FALLBACK_COMMIT/extensions/${filenames.getValue(id)}",
        sha256 = hash,
    )

    fun resolve(fetch: () -> String): RegistrySnapshot = runCatching { parse(fetch()) }.getOrElse { failure ->
        RegistrySnapshot(
            packages = fallbackPackages,
            updatedAt = "bundled-fallback",
            source = "bundled fallback after registry error: ${failure.message ?: failure.javaClass.simpleName}",
        )
    }

    fun parse(raw: String): RegistrySnapshot {
        val root = JSONObject(raw)
        val extensions = root.optJSONArray("extensions") ?: error("registry has no extensions array")
        val byId = mutableMapOf<String, ProviderPackage>()
        for (index in 0 until extensions.length()) {
            val node = extensions.optJSONObject(index) ?: continue
            val id = node.optString("id").trim()
            if (id !in priority || !node.optString("category").equals("download", ignoreCase = true)) continue
            val url = node.optString("download_url").trim()
            val hash = node.optString("sha256").trim().lowercase(Locale.ROOT)
            val version = node.optString("version").trim()
            if (!isTrustedProviderUrl(url, id) || !hash.matches(Regex("[0-9a-f]{64}")) || version.isBlank()) continue
            byId[id] = ProviderPackage(id, names.getValue(id), version, url, hash)
        }
        return RegistrySnapshot(
            packages = priority.map { id -> byId[id] ?: fallbackPackages.first { it.id == id } },
            updatedAt = root.optString("updated_at").ifBlank { "unknown" },
            source = "official Mornye extension registry",
        )
    }

    fun isTrustedProviderUrl(raw: String, id: String): Boolean = runCatching {
        val uri = URI(raw)
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("raw.githubusercontent.com", ignoreCase = true) ||
            uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath != uri.path) return@runCatching false
        val parts = uri.path.removePrefix("/").split("/")
        parts.size == 5 &&
            "${parts[0]}/${parts[1]}".lowercase(Locale.ROOT) in officialRepositories &&
            parts[2].matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")) &&
            parts[3] == "extensions" && parts[4] == filenames[id]
    }.getOrDefault(false)
}
