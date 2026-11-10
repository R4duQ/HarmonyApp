package com.harmony.feature.downloads

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption

/** The bytes of a cover, as the server sent them. */
internal class ArtworkBytes(val bytes: ByteArray, val contentType: String?)

/**
 * Fetches a cover for embedding.
 *
 * The URL can come from a provider's answer, so each hop is checked before
 * anything connects to it: HTTPS, a public host by name, and public by every
 * address the name resolves to ([SpotiFlacUrlSafety]). Redirects are never
 * followed by the HTTP client; the next URL goes through the same checks. The
 * body is read up to [maxBytes] and no further.
 *
 * The last few covers are kept by URL, and two callers asking for the same
 * URL at once share one request, so the tracks of an album fetch their cover
 * once.
 */
internal class SpotiFlacArtworkFetcher(
    private val maxBytes: Long,
    private val maxRedirects: Int = 3,
    cacheEntries: Int = 3,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val open: (String) -> Exchange,
) {
    /** One request to one URL, with redirects off. */
    interface Exchange : AutoCloseable {
        val status: Int
        fun header(name: String): String?
        fun body(): InputStream
    }

    private val cache = object : LinkedHashMap<String, ArtworkBytes>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkBytes>?) = size > cacheEntries
    }
    private val inFlight = HashMap<String, Any>()

    /** The cover at [url], or null when it cannot be had safely. */
    fun fetch(url: String): ArtworkBytes? {
        val key = url.trim()
        // One request per URL at a time: the second caller waits and finds it in the cache.
        val gate = synchronized(inFlight) { inFlight.getOrPut(key) { Any() } }
        try {
            synchronized(gate) {
                synchronized(cache) { cache[key] }?.let { return it }
                val fetched = runCatching { request(key) }.getOrNull() ?: return null
                synchronized(cache) { cache[key] = fetched }
                return fetched
            }
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
        }
    }

    private fun request(url: String): ArtworkBytes? {
        var target = url
        repeat(maxRedirects + 1) {
            if (!SpotiFlacUrlSafety.isPublicHttpsUrl(target)) return null
            val parsed = URL(target)
            if (!SpotiFlacUrlSafety.resolvesToPublic(parsed.host, resolve)) return null
            open(target).use { exchange ->
                val status = exchange.status
                if (status in 300..399) {
                    val location = exchange.header("Location")?.takeIf(String::isNotBlank) ?: return null
                    target = URL(parsed, location).toString()
                    return@repeat
                }
                if (status !in 200..299) return null
                val advertised = exchange.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                if (advertised > maxBytes) return null
                val out = ByteArrayOutputStream(if (advertised in 1..maxBytes) advertised.toInt() else 256 * 1024)
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                exchange.body().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) return null
                        out.write(buffer, 0, read)
                    }
                }
                return if (total == 0L) null else ArtworkBytes(out.toByteArray(), exchange.header("Content-Type"))
            }
        }
        return null // More redirects than allowed.
    }
}

/**
 * Which files a download may hand on.
 *
 * A path in a provider's answer, or a file found by looking through the
 * staging folder, is used only when it is a regular file inside this job's
 * own folder once every symbolic link is resolved. Anything else (another
 * job's file, a link out of the folder, a folder, a device) is not this
 * download's output, whatever it is called.
 */
internal object SpotiFlacJobFiles {
    /** [candidate] as a real path when it is this job's own regular, non-empty file; otherwise null. */
    fun owned(candidate: File, jobDir: File): File? = runCatching {
        val root = jobDir.toPath().toRealPath()
        val real = candidate.toPath().toRealPath()          // follows links: where the bytes really are
        if (!real.startsWith(root) || real == root) return@runCatching null
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return@runCatching null
        real.toFile().takeIf { it.length() > 0L }
    }.getOrNull()

    /** Every regular file of this job (links out of the folder are not followed or returned). */
    fun all(jobDir: File): List<File> = jobDir.walkTopDown()
        .onEnter { directory -> directory == jobDir || !Files.isSymbolicLink(directory.toPath()) }
        .filter { it != jobDir && !it.isDirectory }
        .mapNotNull { owned(it, jobDir) }
        .distinct()
        .toList()
}
