package com.harmony.feature.downloads

import gobackend.Gobackend

/** The stable build: SpotiFLAC 4.9.6's Go runtime, through its GoMobile bindings. */
internal fun createSpotiFlacBackend(): SpotiFlacBackend = GoSpotiFlacBackend

private object GoSpotiFlacBackend : SpotiFlacBackend {
    override val displayName = "SpotiFLAC"
    override val version = "4.9.6"
    override val callbackScheme = "spotiflac"
    override val nativeLibrary = "gojni"
    override val runtimeDirectory = "spotiflac/runtime-v3"

    override fun initialize(extensionsDir: String, dataDir: String, masterKey: String, downloadRoot: String) {
        // Match the official SpotiFLAC host contract. Extensions use this
        // value for compatibility gates, utilities and signed-session request
        // identity. The Go runtime does not confine where downloads go.
        Gobackend.setAppVersion(version)
        Gobackend.setExtensionStorageMasterKey(masterKey)
        Gobackend.initExtensionSystem(extensionsDir, dataDir)
    }

    override fun loadExtensionsFromDir(dirPath: String): String = Gobackend.loadExtensionsFromDir(dirPath)
    override fun getInstalledExtensions(): String = Gobackend.getInstalledExtensions()
    override fun setExtensionEnabled(extensionId: String, enabled: Boolean) {
        Gobackend.setExtensionEnabledByID(extensionId, enabled)
    }
    override fun setProviderPriority(idsJson: String) = Gobackend.setProviderPriorityJSON(idsJson)
    override fun setFallbackProviders(idsJson: String) = Gobackend.setExtensionFallbackProviderIDsJSON(idsJson)

    override fun searchTracksWithMetadataProviders(query: String, limit: Long, includeExtensions: Boolean): String =
        Gobackend.searchTracksWithMetadataProvidersJSON(query, limit, includeExtensions)

    override fun downloadByStrategy(requestJson: String): String = Gobackend.downloadByStrategy(requestJson)
    override fun cancelDownload(itemId: String) = Gobackend.cancelDownload(itemId)
    override fun resetDownloadCancel(itemId: String) = Gobackend.resetDownloadCancel(itemId)
    override fun clearItemProgress(itemId: String) = Gobackend.clearItemProgress(itemId)
    override fun getAllDownloadProgress(): String = Gobackend.getAllDownloadProgress()

    override fun consumeCallbackState(state: String): String = Gobackend.consumeExtensionCallbackState(state).orEmpty()
    override fun setSessionGrant(extensionId: String, grant: String) =
        Gobackend.setExtensionSessionGrantByID(extensionId, grant)
    override fun setAuthCode(extensionId: String, code: String) = Gobackend.setExtensionAuthCodeByID(extensionId, code)
    override fun invokeAction(extensionId: String, action: String): String =
        Gobackend.invokeExtensionActionJSON(extensionId, action).orEmpty()
    override fun isAuthenticated(extensionId: String): Boolean = Gobackend.isExtensionAuthenticatedByID(extensionId)

    override fun pendingAuth(extensionId: String): SpotiFlacPendingAuth? =
        Gobackend.getPendingAuthRequest(extensionId)?.let {
            SpotiFlacPendingAuth(it.getExtensionID().orEmpty(), it.getAuthURL().orEmpty(), it.getCallbackURL().orEmpty())
        }

    override fun pendingAuthJson(extensionId: String): String =
        Gobackend.getExtensionPendingAuthJSON(extensionId).orEmpty()
    override fun allPendingAuthJson(): String = Gobackend.getAllPendingAuthRequestsJSON().orEmpty()
    override fun clearPendingAuth(extensionId: String) = Gobackend.clearExtensionPendingAuthByID(extensionId)
}
