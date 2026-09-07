package com.harmony.feature.downloads

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Shared, Android-independent rules for Spotify's public-client PKCE flow. */
internal object SpotifyAuthorization {
    const val REDIRECT_URI = "com.harmony.app://spotify-playlist-callback"
    const val MAX_AGE_MS = 10 * 60_000L

    // This checks the Dashboard format only. Only Spotify can determine
    // whether an ID identifies an active app; a Client Secret can look alike.
    private val clientIdPattern = Regex("[0-9a-fA-F]{32}")

    fun isValidClientId(value: String): Boolean = value.matches(clientIdPattern)

    fun clientIdError(value: String): String? = when {
        value.isBlank() ->
            "Set up Spotify first: open Developer Dashboard, create an app, then copy its Client ID from Settings."
        !isValidClientId(value) ->
            "Paste the 32-character Client ID from Spotify app Settings / Basic Information. " +
                "Use Client ID, not your Spotify username, a playlist link, or Client Secret."
        else -> null
    }

    fun newVerifier(): String = randomBase64Url(64)

    fun newState(): String = randomBase64Url(32)

    fun challenge(verifier: String): String = base64Url(
        MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
    )

    fun isExpectedCallback(value: String): Boolean = callbackLocationError(value) == null

    /**
     * Validate only the callback destination, not its response parameters.
     * An empty path and a root slash are supported for this native endpoint.
     * Fragments (including a trailing '#' or '#_=_') are ignored: the PKCE
     * response's code/error and state are read ONLY from the query by the client.
     * A matching location alone never authorizes a response.
     *
     * Return fixed labels only; never expose the URL, code, state or tokens in
     * an error message, even when URI parsing fails.
     */
    fun callbackLocationError(value: String): String? {
        val actual = runCatching { URI(value) }.getOrNull() ?: return "malformed address"
        val expected = URI(REDIRECT_URI)
        return when {
            !actual.scheme.equals(expected.scheme, ignoreCase = true) -> "scheme mismatch"
            !actual.host.equals(expected.host, ignoreCase = true) -> "host mismatch"
            actual.rawUserInfo != null -> "unexpected user information"
            // Compare the raw authority too, so even an empty explicit port
            // (which java.net.URI reports as -1) is not accepted.
            !actual.rawAuthority.equals(expected.rawAuthority, ignoreCase = true) -> "unexpected port"
            actual.rawPath.orEmpty() !in setOf("", "/") -> "unexpected path"
            else -> null
        }
    }

    fun validateState(expected: String?, returned: String?, startedAt: Long, now: Long) {
        require(!expected.isNullOrBlank() && !returned.isNullOrBlank() &&
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.UTF_8),
                returned.toByteArray(StandardCharsets.UTF_8),
            )
        ) { "Spotify authorization state did not match. Start the connection again." }
        require(startedAt > 0L && now >= startedAt && now - startedAt <= MAX_AGE_MS) {
            "Spotify authorization expired. Start the connection again."
        }
    }

    fun errorMessage(error: String, description: String? = null): String = when (error) {
        "invalid_client" ->
            "Spotify rejected the Client ID. Copy Client ID from an active app in Spotify Developer Dashboard, " +
                "then replace the value in Harmony. Client Secret is not used."
        "invalid_grant" ->
            "Spotify's connection code or session has expired. Connect Spotify again."
        "access_denied" -> "Spotify access was declined. Tap Connect to try again."
        else -> description?.takeIf { it.isNotBlank() && it != "null" }
            ?: "Spotify authorization failed ($error). Tap Connect to try again."
    }

    private fun randomBase64Url(byteCount: Int): String =
        ByteArray(byteCount).also(SecureRandom()::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
