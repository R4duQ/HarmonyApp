package com.harmony.feature.downloads

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify Web API adapter for Playlist Transfer.
 *
 * Authentication uses Authorization Code with PKCE, so Harmony stores no
 * client secret. Spotify supplies playlist structure and metadata only; audio
 * continues through Harmony's independently selected download engine.
 */
@Singleton
class SpotifyPlaylistClient @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenMutex = Mutex()
    private val authEventChannel = Channel<SpotifyAuthorizationEvent>(Channel.BUFFERED)
    private val authorizationLock = Any()
    private var exchangingState: String? = null
    private var tokenGeneration = 0L

    val authorizationEvents: Flow<SpotifyAuthorizationEvent> = authEventChannel.receiveAsFlow()

    fun savedClientId(): String = preferences.getString(KEY_CLIENT_ID, "").orEmpty()

    fun hasSession(): Boolean =
        preferences.getString(KEY_ACCESS_TOKEN, null) != null ||
            preferences.getString(KEY_REFRESH_TOKEN, null) != null

    fun saveClientId(value: String) = synchronized(authorizationLock) {
        val normalized = value.trim()
        SpotifyAuthorization.clientIdError(normalized)?.let { throw IllegalArgumentException(it) }
        if (savedClientId() != normalized) {
            clearTokens()
            clearPendingAuthorization()
        }
        preferences.edit().putString(KEY_CLIENT_ID, normalized).apply()
    }

    fun createAuthorizationUri(clientId: String): Uri = synchronized(authorizationLock) {
        saveClientId(clientId)
        val verifier = SpotifyAuthorization.newVerifier()
        val state = SpotifyAuthorization.newState()
        val challenge = SpotifyAuthorization.challenge(verifier)
        val saved = preferences.edit()
            .putString(KEY_PKCE_VERIFIER, verifier)
            .putString(KEY_OAUTH_STATE, state)
            .putString(KEY_OAUTH_CLIENT_ID, savedClientId())
            .putLong(KEY_OAUTH_STARTED_AT, System.currentTimeMillis())
            .commit()
        check(saved) { "Harmony could not save the Spotify connection. Try again." }

        Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", savedClientId())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", REQUIRED_SCOPES)
            .build()
    }

    fun enqueueAuthorizationCallback(callbackUrl: String) {
        if (callbackUrl.isBlank()) return
        callbackScope.launch {
            try {
                acceptAuthorizationCallback(callbackUrl)
            } catch (failure: Exception) {
                authEventChannel.trySend(
                    SpotifyAuthorizationEvent.Failed(
                        failure.message ?: "Spotify authorization could not be completed.",
                    ),
                )
            }
        }
    }

    private fun acceptAuthorizationCallback(callbackUrl: String) {
        SpotifyAuthorization.callbackLocationError(callbackUrl)?.let { reason ->
            throw IllegalArgumentException(
                "Harmony could not accept the Spotify return ($reason). Tap Connect to try again.",
            )
        }
        // Never read code, error or state from the fragment. Some returns have
        // a fragment suffix, but only query parameters belong to our PKCE flow.
        val uri = Uri.parse(callbackUrl)
        val pending = synchronized(authorizationLock) {
            val expectedState = preferences.getString(KEY_OAUTH_STATE, null)
            val returnedState = uri.getQueryParameter("state")
            // Browsers can deliver the same Intent more than once. A response
            // to a finished/cancelled request must not start another exchange.
            if (expectedState == null) return
            SpotifyAuthorization.validateState(
                expectedState,
                returnedState,
                preferences.getLong(KEY_OAUTH_STARTED_AT, 0L),
                System.currentTimeMillis(),
            )
            if (exchangingState == expectedState) return
            PendingAuthorization(
                state = expectedState,
                clientId = preferences.getString(KEY_OAUTH_CLIENT_ID, null) ?: requireClientId(),
                verifier = preferences.getString(KEY_PKCE_VERIFIER, null).orEmpty(),
            ).also { exchangingState = expectedState }
        }

        try {
            uri.getQueryParameter("error")?.takeIf(String::isNotBlank)?.let { error ->
                throw SpotifyApiException(SpotifyAuthorization.errorMessage(error))
            }
            val code = uri.getQueryParameter("code")
                ?.takeIf(String::isNotBlank)
                ?: throw SpotifyApiException("Spotify returned no authorization code. Connect again.")
            require(pending.verifier.isNotBlank()) {
                "The Spotify connection data is missing. Connect again."
            }
            val token = tokenRequest(
                mapOf(
                    "client_id" to pending.clientId,
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to pending.verifier,
                ),
            )
            synchronized(authorizationLock) {
                if (preferences.getString(KEY_OAUTH_STATE, null) == pending.state) {
                    persistTokenResponse(token, previousRefreshToken = null)
                    authEventChannel.trySend(
                        SpotifyAuthorizationEvent.Connected("Spotify connected. Choose a playlist to transfer."),
                    )
                }
            }
        } catch (failure: Exception) {
            synchronized(authorizationLock) {
                if (preferences.getString(KEY_OAUTH_STATE, null) == pending.state) {
                    authEventChannel.trySend(
                        SpotifyAuthorizationEvent.Failed(failure.message ?: "Spotify connection failed. Try again."),
                    )
                }
            }
        } finally {
            synchronized(authorizationLock) {
                if (preferences.getString(KEY_OAUTH_STATE, null) == pending.state) clearPendingAuthorization()
                if (exchangingState == pending.state) exchangingState = null
            }
        }
    }

    suspend fun loadPlaylists(): List<SpotifyPlaylistSummary> = withContext(Dispatchers.IO) {
        val profile = apiGet("$API_BASE/me")
        val currentUserId = profile.optString("id").trim()
        val output = mutableListOf<SpotifyPlaylistSummary>()
        var nextUrl: String? = "$API_BASE/me/playlists?limit=50&offset=0"
        var pageCount = 0
        val visitedPages = mutableSetOf<String>()

        while (nextUrl != null) {
            if (++pageCount > MAX_PAGES || !visitedPages.add(nextUrl)) throw SpotifyApiException("Spotify returned repeated or too many playlist pages. Try again.")
            val page = apiGet(nextUrl)
            val items = page.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isBlank() || name.isBlank()) continue
                val owner = item.optJSONObject("owner")
                val ownerId = owner?.optString("id").orEmpty().trim()
                val collaborative = item.optBoolean("collaborative", false)
                val collection = item.optJSONObject("items") ?: item.optJSONObject("tracks")
                output += SpotifyPlaylistSummary(
                    id = id,
                    name = name,
                    description = item.optString("description").takeUnless { it == "null" }.orEmpty(),
                    ownerName = owner?.optString("display_name")
                        ?.takeUnless { it.isBlank() || it == "null" }
                        ?: ownerId,
                    totalTracks = collection?.optInt("total", 0) ?: 0,
                    imageUrl = firstImageUrl(item.optJSONArray("images")),
                    spotifyUrl = item.optJSONObject("external_urls")
                        ?.optString("spotify")
                        ?.takeUnless { it.isBlank() || it == "null" },
                    snapshotId = item.optString("snapshot_id")
                        .takeUnless { it.isBlank() || it == "null" },
                    canImportItems = ownerId == currentUserId || collaborative,
                )
            }
            nextUrl = page.optString("next")
                .takeUnless { it.isBlank() || it == "null" }
        }
        output.distinctBy { it.id }
    }

    suspend fun loadPlaylist(playlistId: String): Pair<SpotifyPlaylistSummary, List<SpotifyPlaylistTrack>> =
        withContext(Dispatchers.IO) {
            require(playlistId.matches(SPOTIFY_ID)) { "That Spotify playlist link is not valid." }
            val profile = apiGet("$API_BASE/me")
            val currentUserId = profile.optString("id").trim()
            val details = apiGet("$API_BASE/playlists/$playlistId")
            val owner = details.optJSONObject("owner")
            val ownerId = owner?.optString("id").orEmpty().trim()
            val collaborative = details.optBoolean("collaborative", false)
            val collection = details.optJSONObject("items") ?: details.optJSONObject("tracks")
            val summary = SpotifyPlaylistSummary(
                id = details.optString("id").ifBlank { playlistId },
                name = details.optString("name").ifBlank { "Spotify playlist" },
                description = details.optString("description").takeUnless { it == "null" }.orEmpty(),
                ownerName = owner?.optString("display_name")
                    ?.takeUnless { it.isBlank() || it == "null" }
                    ?: ownerId,
                totalTracks = collection?.optInt("total", 0) ?: 0,
                imageUrl = firstImageUrl(details.optJSONArray("images")),
                spotifyUrl = details.optJSONObject("external_urls")
                    ?.optString("spotify")
                    ?.takeUnless { it.isBlank() || it == "null" },
                snapshotId = details.optString("snapshot_id")
                    .takeUnless { it.isBlank() || it == "null" },
                canImportItems = ownerId == currentUserId || collaborative,
            )
            if (!summary.canImportItems) {
                throw SpotifyApiException(
                    "Spotify currently exposes playlist items only when you own the playlist or are a collaborator.",
                    httpCode = 403,
                )
            }
            summary to loadPlaylistTracks(playlistId)
        }

    private suspend fun loadPlaylistTracks(playlistId: String): List<SpotifyPlaylistTrack> {
        val output = mutableListOf<SpotifyPlaylistTrack>()
        var nextUrl: String? = "$API_BASE/playlists/$playlistId/items?limit=50&offset=0"
        var pageCount = 0
        var position = 0
        val visitedPages = mutableSetOf<String>()

        while (nextUrl != null) {
            if (++pageCount > MAX_PAGES || !visitedPages.add(nextUrl)) throw SpotifyApiException("Spotify returned repeated or too many track pages. Try again.")
            val page = apiGet(nextUrl)
            val items = page.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val wrapper = items.optJSONObject(index) ?: continue
                val item = wrapper.optJSONObject("item") ?: wrapper.optJSONObject("track") ?: continue
                val currentPosition = position++
                if (item.optString("type", "track") != "track" || wrapper.optBoolean("is_local", false)) {
                    continue
                }
                val title = item.optString("name").trim()
                val artistsArray = item.optJSONArray("artists") ?: JSONArray()
                val artists = buildList {
                    for (artistIndex in 0 until artistsArray.length()) {
                        artistsArray.optJSONObject(artistIndex)?.optString("name")
                            ?.trim()?.takeIf(String::isNotBlank)?.let(::add)
                    }
                }.joinToString(", ")
                if (title.isBlank() || artists.isBlank()) continue
                val album = item.optJSONObject("album")
                output += SpotifyPlaylistTrack(
                    spotifyId = item.optString("id").takeUnless { it.isBlank() || it == "null" },
                    title = title,
                    artists = artists,
                    album = album?.optString("name").orEmpty().takeUnless { it == "null" }.orEmpty(),
                    durationMs = item.optLong("duration_ms", 0L).coerceAtLeast(0L),
                    isrc = item.optJSONObject("external_ids")?.optString("isrc")
                        ?.takeUnless { it.isBlank() || it == "null" },
                    artworkUrl = firstImageUrl(album?.optJSONArray("images")),
                    spotifyUrl = item.optJSONObject("external_urls")?.optString("spotify")
                        ?.takeUnless { it.isBlank() || it == "null" },
                    position = currentPosition,
                )
            }
            nextUrl = page.optString("next").takeUnless { it.isBlank() || it == "null" }
        }
        return output
    }

    fun disconnect() = synchronized(authorizationLock) {
        clearTokens()
        clearPendingAuthorization()
    }

    fun cancelAuthorization() = synchronized(authorizationLock) {
        clearPendingAuthorization()
    }

    private suspend fun apiGet(url: String): JSONObject {
        currentCoroutineContext().ensureActive()
        if (!SpotifyApiUrlPolicy.isAllowed(url)) throw SpotifyApiException("Spotify returned an invalid page address.")
        var token = ensureAccessToken(forceRefresh = false)
        var response = get(url, token)
        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            token = ensureAccessToken(forceRefresh = true)
            response = get(url, token)
        }
        if (response.code == 429) {
            val retry = response.retryAfterSeconds?.let { " Try again in $it seconds." }.orEmpty()
            throw SpotifyApiException("Spotify is rate-limiting playlist requests.$retry", 429)
        }
        if (response.code == HttpURLConnection.HTTP_FORBIDDEN) {
            throw SpotifyApiException(
                "Spotify refused access (403). In Developer Dashboard, check Users Management for the signed-in account " +
                    "and that the app owner has Premium. Playlist items must also be yours or collaborative in Development Mode.",
                403,
            )
        }
        if (response.code !in 200..299) {
            val message = runCatching {
                JSONObject(response.body).optJSONObject("error")?.optString("message")
            }.getOrNull()?.takeIf(String::isNotBlank)
            throw SpotifyApiException(message ?: "Spotify request failed (HTTP ${response.code}).", response.code)
        }
        return runCatching { JSONObject(response.body) }
            .getOrElse { throw SpotifyApiException("Spotify returned an unreadable response.") }
    }

    private suspend fun ensureAccessToken(forceRefresh: Boolean): String = tokenMutex.withLock {
      val pending = synchronized(authorizationLock) {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (!forceRefresh && !accessToken.isNullOrBlank() &&
            System.currentTimeMillis() + TOKEN_EXPIRY_MARGIN_MS < expiresAt
        ) {
            return@withLock accessToken
        }

        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
            ?.takeIf(String::isNotBlank)
            ?: throw SpotifyApiException("Connect Spotify again to refresh playlist access.")
        Triple(requireClientId(), refreshToken, tokenGeneration)
      }
        val response = tokenRequest(
            mapOf(
                "client_id" to pending.first,
                "grant_type" to "refresh_token",
                "refresh_token" to pending.second,
            ),
        )
      currentCoroutineContext().ensureActive()
      synchronized(authorizationLock) {
        if (tokenGeneration != pending.third || savedClientId() != pending.first) {
            throw SpotifyApiException("Spotify connection changed. Connect again if needed.")
        }
        persistTokenResponse(response, previousRefreshToken = pending.second)
        preferences.getString(KEY_ACCESS_TOKEN, null)
            ?.takeIf(String::isNotBlank)
            ?: throw SpotifyApiException("Spotify returned no access token.")
      }
    }

    private fun tokenRequest(parameters: Map<String, String>): JSONObject {
        val body = parameters.entries.joinToString("&") { (key, value) ->
            "${formEncode(key)}=${formEncode(value)}"
        }
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val responseBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val error = runCatching { JSONObject(responseBody) }.getOrNull()
                throw SpotifyApiException(
                    SpotifyAuthorization.errorMessage(
                        error?.optString("error")?.takeIf(String::isNotBlank) ?: "HTTP $code",
                        error?.optString("error_description"),
                    ),
                    code,
                )
            }
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String, accessToken: String): HttpResponse {
        require(SpotifyApiUrlPolicy.isAllowed(url)) { "Invalid Spotify API address" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResponse(
                code = code,
                body = body,
                retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun persistTokenResponse(response: JSONObject, previousRefreshToken: String?) {
        val access = response.optString("access_token").trim()
        if (access.isBlank() || access == "null") throw SpotifyApiException("Spotify returned no access token.")
        val refresh = response.optString("refresh_token")
            .takeUnless { it.isBlank() || it == "null" }
            ?: previousRefreshToken
        val expiresIn = response.optLong("expires_in", DEFAULT_TOKEN_LIFETIME_SECONDS)
            .coerceIn(60L, 86_400L)
        tokenGeneration++
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, access)
            .putString(KEY_REFRESH_TOKEN, refresh)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1_000L)
            .apply()
    }

    private fun requireClientId(): String = savedClientId().also { id ->
        require(isValidClientId(id)) { "Configure your Spotify Client ID first." }
    }

    private fun clearTokens() {
        tokenGeneration++
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    private fun clearPendingAuthorization() {
        preferences.edit()
            .remove(KEY_PKCE_VERIFIER)
            .remove(KEY_OAUTH_STATE)
            .remove(KEY_OAUTH_CLIENT_ID)
            .remove(KEY_OAUTH_STARTED_AT)
            .apply()
    }

    private fun firstImageUrl(images: JSONArray?): String? {
        if (images == null || images.length() == 0) return null
        return images.optJSONObject(0)?.optString("url")
            ?.takeUnless { it.isBlank() || it == "null" }
    }

    private fun formEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private data class HttpResponse(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?,
    )

    private data class PendingAuthorization(val state: String, val clientId: String, val verifier: String)

    companion object {
        const val REDIRECT_URI = SpotifyAuthorization.REDIRECT_URI
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val API_BASE = "https://api.spotify.com/v1"
        private const val REQUIRED_SCOPES = "playlist-read-private playlist-read-collaborative"
        private const val PREFERENCES_NAME = "harmony_spotify_playlist_transfer"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_PKCE_VERIFIER = "pkce_verifier"
        private const val KEY_OAUTH_STATE = "oauth_state"
        private const val KEY_OAUTH_CLIENT_ID = "oauth_client_id"
        private const val KEY_OAUTH_STARTED_AT = "oauth_started_at"
        private const val NETWORK_TIMEOUT_MS = 15_000
        private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
        private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3_600L
        private const val MAX_PAGES = 2_000
        private const val USER_AGENT = "Harmony/1.0 Android PlaylistTransfer"
        private val SPOTIFY_ID = Regex("[A-Za-z0-9]{10,64}")
        private val PLAYLIST_LINK = Regex(
            "(?:open\\.spotify\\.com/(?:intl-[^/]+/)?playlist/|spotify:playlist:)([A-Za-z0-9]{10,64})",
            RegexOption.IGNORE_CASE,
        )

        fun isValidClientId(value: String): Boolean = SpotifyAuthorization.isValidClientId(value)

        fun extractPlaylistId(value: String): String? = PLAYLIST_LINK.find(value.trim())
            ?.groupValues?.getOrNull(1)
    }
}
