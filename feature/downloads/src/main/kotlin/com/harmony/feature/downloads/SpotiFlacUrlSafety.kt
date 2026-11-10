package com.harmony.feature.downloads

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * URLs that came from a provider package (a cover, a verification page) are
 * only ever fetched or opened when they are HTTPS on the public internet. A
 * provider's own network access is limited to its declared hosts by the
 * runtime; without this check a URL in its answer would let it reach others
 * through Harmony, the phone's local network included.
 */
internal object SpotiFlacUrlSafety {
    /** HTTPS, no embedded credentials, and a host that is not a local or private address by name. */
    fun isPublicHttpsUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw.trim())
        if (!uri.scheme.equals("https", ignoreCase = true)) return@runCatching false
        if (uri.rawUserInfo != null) return@runCatching false
        val host = uri.host?.lowercase(Locale.ROOT)?.trim().orEmpty()
        host.isNotBlank() && !isLocalName(host) && !isPrivateLiteral(host)
    }.getOrDefault(false)

    private fun isLocalName(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home.arpa") ||
            // A single-label name ("router") is a local one.
            ('.' !in host && ':' !in host)

    /** An IPv4 or IPv6 literal in a loopback, private, link-local, shared or unspecified range. */
    fun isPrivateLiteral(host: String): Boolean {
        val literal = host.trim().trim('[', ']').substringBefore('%')
        val v4 = literal.split('.')
        if (v4.size == 4 && v4.all { part -> part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) }) {
            val (a, b) = v4.map(String::toInt)
            return a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }
        if (':' in literal) {
            val lower = literal.lowercase(Locale.ROOT)
            if (lower == "::" || lower == "::1") return true
            // IPv4-mapped (::ffff:a.b.c.d): judged as the IPv4 address it carries.
            if (lower.startsWith("::ffff:") && '.' in lower) return isPrivateLiteral(lower.removePrefix("::ffff:"))
            val first = lower.substringBefore(':').padStart(4, '0')
            return first.startsWith("fc") || first.startsWith("fd") ||   // unique local
                first.startsWith("fe8") || first.startsWith("fe9") || first.startsWith("fea") || first.startsWith("feb") || // link-local
                first.startsWith("ff")                                       // multicast
        }
        // A bare number ("2130706433") is an address to some resolvers: never a public host name.
        return literal.isNotEmpty() && literal.all(Char::isDigit)
    }

    /** Whether every address [host] resolves to is public (a name can point at a private address). */
    fun resolvesToPublic(
        host: String,
        resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): Boolean = runCatching {
        val addresses = resolve(host)
        addresses.isNotEmpty() && addresses.none { address ->
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress || isPrivateLiteral(address.hostAddress.orEmpty())
        }
    }.getOrDefault(false)
}
