package com.harmony.feature.downloads

import java.net.URI

internal object SpotifyApiUrlPolicy {
    fun isAllowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("api.spotify.com", ignoreCase = true) &&
            uri.port in listOf(-1, 443) && uri.userInfo == null && uri.fragment == null &&
            uri.path.startsWith("/v1/") && uri.normalize().path == uri.path
    }.getOrDefault(false)
}
