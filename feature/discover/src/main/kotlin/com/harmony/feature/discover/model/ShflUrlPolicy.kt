package com.harmony.feature.discover.model

import java.net.URI
import java.util.Locale
import com.harmony.feature.discover.provider.ShflAlbumCatalog

/**
 * The allowlist that decides whether Harmony is willing to hand a URL to a
 * browser from the Discover screen.
 *
 * Deliberately written against [java.net.URI] rather than `android.net.Uri`:
 * this is the one piece of the SHFL gateway where a mistake actually matters,
 * and a pure-JVM implementation can be covered by an ordinary unit test
 * (`ShflUrlPolicyTest`) instead of needing an instrumented run. The Android
 * side ([com.harmony.feature.discover.provider.ShflLauncher]) re-checks scheme
 * and host on the parsed `android.net.Uri` afterwards, so both parsers have to
 * agree before anything opens.
 *
 * The policy includes the public navigation pages and verified album pages
 * in Harmony's curated catalogue. Arbitrary browser destinations stay blocked.
 */
object ShflUrlPolicy {

    /** The only scheme Harmony will open. Plain http is rejected, not upgraded. */
    const val SCHEME = "https"

    /** Hosts that may appear in an allowed destination. */
    val ALLOWED_HOSTS: Set<String> = setOf("theshfl.com", "www.theshfl.com")

    const val HOME = "https://theshfl.com/"
    const val GUIDES = "https://theshfl.com/guides"
    const val COLLECTIONS = "https://theshfl.com/collections"
    const val BEST_OF = "https://theshfl.com/best-of"
    const val ABOUT = "https://theshfl.com/about-shfl"

    /** Every destination the Discover screen is permitted to open. */
    val ALLOWED_DESTINATIONS: List<String> = listOf(HOME, GUIDES, COLLECTIONS, BEST_OF, ABOUT) +
        ShflAlbumCatalog.albums.map { it.shflUrl }

    private val allowedCanonical: Set<String> =
        ALLOWED_DESTINATIONS.mapNotNull(::canonicalize).toSet()

    /**
     * Scheme and host check only: true when [url] parses as an absolute https
     * URL on an SHFL host, with no user-info trick and no unexpected port.
     *
     * This is the first of the launcher's two gates and is intentionally
     * weaker than [isAllowedDestination] — it says "this is SHFL over TLS",
     * not "Harmony is allowed to open this page".
     */
    fun isAllowedHost(url: String): Boolean = hostOf(url) != null

    /** True when [url] resolves to one of [ALLOWED_DESTINATIONS]. */
    fun isAllowedDestination(url: String): Boolean = canonicalize(url) in allowedCanonical

    /**
     * Returns the canonical form of [url] when it is an allowed destination,
     * or null when it is not. Callers open the returned string rather than
     * their own input, so whatever reaches the browser is always a value this
     * object produced.
     */
    fun sanitizeDestination(url: String): String? =
        canonicalize(url)?.takeIf { it in allowedCanonical }

    /**
     * Parses [url] and returns its lowercase host when the transport-level
     * checks pass, or null otherwise.
     *
     * Rejected here, each for a reason:
     *  - unparseable, relative or opaque input — there is nothing to validate;
     *  - a scheme other than https, which rules out cleartext as well as
     *    `javascript:` and `intent:` (the latter being a way to launch an
     *    arbitrary component through something that looks like a link);
     *  - user info, because `https://theshfl.com@example.com/` has host
     *    `example.com` and reads to a human as though it does not;
     *  - a port other than the https default.
     */
    private fun hostOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        if (!uri.isAbsolute || uri.isOpaque) return null
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return host.takeIf { it in ALLOWED_HOSTS }
    }

    /**
     * Reduces a URL to `https://host/path`, or null when it fails any check,
     * so every rejection funnels through one place.
     *
     * On top of [hostOf], this drops nothing and rejects instead: a query or
     * fragment is refused outright, since that is where anything resembling
     * Harmony's library or listening history would have to be smuggled if it
     * were ever going to leave the device by this route. Path traversal
     * segments are refused for the same reason a host filter isn't enough —
     * `/guides/../private` is not one of the five pages.
     */
    private fun canonicalize(url: String): String? {
        hostOf(url) ?: return null
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        if (uri.rawQuery != null || uri.rawFragment != null) return null

        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        if (!path.startsWith("/")) return null
        if (path.split("/").any { it == ".." || it == "." }) return null

        return "$SCHEME://theshfl.com$path"
    }
}
