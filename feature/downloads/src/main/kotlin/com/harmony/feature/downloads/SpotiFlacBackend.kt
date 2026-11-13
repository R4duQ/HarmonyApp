package com.harmony.feature.downloads

/**
 * The native runtime that runs the download extensions.
 *
 * Harmony uses Mornye 5's Rust runtime (MornyeSpotiFlacBackend.kt), which
 * replaced SpotiFLAC's Go runtime upstream and keeps its request and response
 * JSON. The engine talks to it through this contract, so tests can use fakes;
 * [createSpotiFlacBackend] provides the real one.
 *
 * The calls mirror SpotiFLAC's Go exports, which the engine was written
 * against. Calls a runtime does not have answer as "nothing there" (an empty
 * string, false, null) rather than failing; the engine already treats those as
 * absent.
 *
 * All calls block and may do network work: call them off the main thread.
 */
internal interface SpotiFlacBackend {
    /** What the user sees: "SpotiFLAC" or "Mornye". */
    val displayName: String

    /** The upstream version; extensions use it for compatibility gates. */
    val version: String

    /** The native library the runtime lives in, without "lib" and ".so". */
    val nativeLibrary: String

    /** The URL scheme extensions send sign-in and verification results back on. */
    val callbackScheme: String

    /** Where this runtime keeps installed extensions and their data, under the app's files. */
    val runtimeDirectory: String

    /**
     * Whether a download request names its own provider. The Go runtime routes
     * by a runtime-wide provider order, so only one download may run; a
     * runtime that routes per request can run several.
     */
    val routesPerRequest: Boolean get() = false

    /** How many downloads the runtime can run at once (1 unless [routesPerRequest]). */
    val parallelDownloads: Int get() = 1

    /**
     * Whether [initialize] may be called again while the app runs, to move to
     * a newer set of provider packages between downloads. A runtime that
     * cannot takes the new set the next time the app starts.
     */
    val reinitializable: Boolean get() = false

    /**
     * Starts the runtime: extension sources in [extensionsDir], their encrypted
     * data in [dataDir] under [masterKey]. Extensions may only write downloads
     * under [downloadRoot].
     */
    fun initialize(extensionsDir: String, dataDir: String, masterKey: String, downloadRoot: String)

    /** Loads every package in [dirPath]; a JSON object whose "errors" lists what failed. */
    fun loadExtensionsFromDir(dirPath: String): String

    /** The installed extensions, a JSON array. */
    fun getInstalledExtensions(): String

    fun setExtensionEnabled(extensionId: String, enabled: Boolean)

    /** [idsJson] is a JSON array of extension IDs, first tried first. */
    fun setProviderPriority(idsJson: String)

    fun setFallbackProviders(idsJson: String)

    fun searchTracksWithMetadataProviders(query: String, limit: Long, includeExtensions: Boolean): String

    /** Runs one download; the response JSON says whether it worked. Throws when the runtime itself fails. */
    fun downloadByStrategy(requestJson: String): String

    fun cancelDownload(itemId: String)

    fun resetDownloadCancel(itemId: String)

    fun clearItemProgress(itemId: String)

    fun getAllDownloadProgress(): String

    /** The extension a one-time browser callback state belongs to, consuming the state. */
    fun consumeCallbackState(state: String): String

    fun setSessionGrant(extensionId: String, grant: String)

    fun setAuthCode(extensionId: String, code: String)

    fun invokeAction(extensionId: String, action: String): String

    fun isAuthenticated(extensionId: String): Boolean

    /** A sign-in or verification the extension is waiting on, or null. */
    fun pendingAuth(extensionId: String): SpotiFlacPendingAuth?

    fun pendingAuthJson(extensionId: String): String

    fun allPendingAuthJson(): String

    fun clearPendingAuth(extensionId: String)
}

/** A sign-in or verification page an extension is waiting on. */
internal data class SpotiFlacPendingAuth(
    val extensionId: String,
    val authUrl: String,
    val callbackUrl: String,
)
