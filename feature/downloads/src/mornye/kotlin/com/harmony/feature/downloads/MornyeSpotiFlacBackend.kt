package com.harmony.feature.downloads

import com.mornye.backend.ExtensionEnvironment
import com.mornye.backend.ExtensionManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Mornye build: Mornye 5's Rust runtime, through its UniFFI bindings.
 *
 * Mornye is what SpotiFLAC became upstream; its Rust runtime replaced the Go
 * one and keeps the Go runtime's wire formats as a compatibility contract, so
 * requests, responses and extensions are the same. What changed is the shape
 * of the API: one [ExtensionManager] object owns the runtime instead of global
 * functions. The mapping follows Mornye's own Android host
 * (android/app/src/rust/.../RustCoreBackendApplication.kt in Mornye 5.2.5).
 */
internal fun createSpotiFlacBackend(): SpotiFlacBackend = MornyeSpotiFlacBackend

private object MornyeSpotiFlacBackend : SpotiFlacBackend {
    override val displayName = "Mornye"
    override val version = "5.2.5"
    override val callbackScheme = "mornye"
    override val nativeLibrary = "mornye_mobile"

    // Its own directory: the Go runtime's encrypted extension storage is not
    // this runtime's to rewrite, and the stable build may be installed again.
    override val runtimeDirectory = "mornye/runtime-v1"

    /** Extension calls give up after this long unless the extension sets its own limit. */
    private const val CALL_TIMEOUT_MS = 30_000uL

    @Volatile private var manager: ExtensionManager? = null
    private var storage: Pair<String, String>? = null

    private fun owner(): ExtensionManager = checkNotNull(manager) { "Mornye backend is not initialized" }

    private inline fun <T> environment(block: (ExtensionEnvironment) -> T): T = owner().environment().use(block)

    @Synchronized
    override fun initialize(extensionsDir: String, dataDir: String, masterKey: String, downloadRoot: String) {
        val sources = File(extensionsDir).canonicalPath
        val data = File(dataDir).canonicalPath
        // Downloads may only be written inside these; both spellings, as Mornye
        // compares paths before and after symlinks are resolved.
        val root = File(downloadRoot)
        val allowed = listOf(root.canonicalPath, root.absolutePath).distinct()
        val current = manager
        if (current != null) {
            if (storage == sources to data) {
                current.environment().use { it.setAllowedDownloadDirectories(allowed) }
                return
            }
            // New provider packages live in a new runtime directory: start over there.
            shutdown(current)
        }
        val created = ExtensionManager(sources, data, masterKey, version, CALL_TIMEOUT_MS)
        try {
            created.environment().use { it.setAllowedDownloadDirectories(allowed) }
        } catch (error: Exception) {
            shutdown(created)
            throw error
        }
        manager = created
        storage = sources to data
    }

    private fun shutdown(owner: ExtensionManager) {
        manager = null
        storage = null
        runCatching { owner.shutdown() }
        owner.close()
    }

    // Mornye loads from the directory it was created with, which is this one.
    override fun loadExtensionsFromDir(dirPath: String): String = owner().loadAll()

    override fun getInstalledExtensions(): String = owner().installed()

    override fun setExtensionEnabled(extensionId: String, enabled: Boolean) = owner().setEnabled(extensionId, enabled)

    override fun setProviderPriority(idsJson: String) = owner().setProviderPriority("download", ids(idsJson))

    override fun setFallbackProviders(idsJson: String) {
        val raw = idsJson.trim()
        owner().setFallbackProviders(if (raw.isEmpty() || raw == "null") null else ids(raw))
    }

    override fun searchTracksWithMetadataProviders(query: String, limit: Long, includeExtensions: Boolean): String =
        owner().searchMetadataProviders(query, limit, includeExtensions, "", CALL_TIMEOUT_MS)

    override fun downloadByStrategy(requestJson: String): String = owner().downloadByStrategy(requestJson)

    override fun cancelDownload(itemId: String) = downloads { it.cancelDownload(itemId) }

    override fun resetDownloadCancel(itemId: String) = downloads { it.resetDownloadCancel(itemId) }

    override fun clearItemProgress(itemId: String) = downloads { it.clearItemProgress(itemId) }

    override fun getAllDownloadProgress(): String = downloads { it.allProgress() }

    private inline fun <T> downloads(block: (com.mornye.backend.DownloadState) -> T): T =
        environment { environment -> environment.downloadState().use(block) }

    override fun consumeCallbackState(state: String): String = environment { it.consumeCallbackState(state) }

    override fun setSessionGrant(extensionId: String, grant: String) = environment { it.setSessionGrant(extensionId, grant) }

    override fun setAuthCode(extensionId: String, code: String) = environment { it.setAuthCode(extensionId, code) }

    override fun invokeAction(extensionId: String, action: String): String = owner().invokeAction(extensionId, action)

    // Mornye has no separate "signed in" query; a pending challenge is the signal.
    override fun isAuthenticated(extensionId: String): Boolean = false

    override fun pendingAuth(extensionId: String): SpotiFlacPendingAuth? {
        val raw = pendingAuthJson(extensionId).trim()
        if (raw.isEmpty() || raw == "null") return null
        val node = JSONObject(raw)
        return SpotiFlacPendingAuth(
            extensionId = node.optString("extension_id"),
            authUrl = node.optString("auth_url"),
            callbackUrl = node.optString("callback_url"),
        ).takeIf { it.extensionId.isNotBlank() || it.authUrl.isNotBlank() || it.callbackUrl.isNotBlank() }
    }

    override fun pendingAuthJson(extensionId: String): String = owner().getExtensionPendingAuthJson(extensionId)

    // Mornye only answers per extension; the engine asks per extension first.
    override fun allPendingAuthJson(): String = ""

    // Mornye drops a challenge when its callback is consumed or it expires.
    override fun clearPendingAuth(extensionId: String) = Unit

    private fun ids(json: String): List<String> {
        val values = JSONArray(json)
        return (0 until values.length()).map { values.getString(it) }
    }
}
