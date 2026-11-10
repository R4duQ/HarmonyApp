package com.harmony.feature.downloads

import java.util.EnumMap
import java.util.Locale

/**
 * Takes credentials out of diagnostic text before anyone can read or share it.
 *
 * Provider answers and errors can carry session tokens, decryption keys and
 * signed stream URLs (the signature is the access). Diagnostics keep what
 * explains a failure (hosts, paths, statuses, error types) and lose what
 * grants access:
 *  - a URL keeps its scheme, host and path; its query and fragment go, and so
 *    does a path segment that is itself a signature (`hdnea=…`, long opaque
 *    blobs);
 *  - the value of any field whose name says it is a secret (token, key,
 *    signature, cookie, authorization, code, state…), in JSON or `name=value`
 *    form;
 *  - whatever follows `Bearer`/`Basic`, and the whole value of an
 *    `Authorization` or `Cookie` header line.
 */
internal object SpotiFlacRedaction {
    const val MARK = "<redacted>"

    private const val SECRET_NAMES =
        "access_token|refresh_token|id_token|token|api_key|apikey|key|decryption_key|master_key|masterkey|license|" +
            "signature|sig|hmac|policy|key-pair-id|authorization|proxy-authorization|cookie|set-cookie|password|" +
            "passwd|secret|client_secret|session|session_id|sessionid|grant|code|state|hdnea|hdnts|credential|credentials|" +
            "x-amz-signature|x-amz-credential|x-amz-security-token"

    private val url = Regex("""\b(https?)://([^\s/?#"'\\<>]+)([^\s?#"'\\<>]*)(\?[^\s#"'\\<>]*)?(#[^\s"'\\<>]*)?""", RegexOption.IGNORE_CASE)
    private val jsonField = Regex("""("(?:$SECRET_NAMES)"\s*:\s*)("(?:[^"\\]|\\.)*"|[^\s,}\]]+)""", RegexOption.IGNORE_CASE)
    private val pair = Regex("""(?<![\w-])((?:$SECRET_NAMES)\s*[=:]\s*)([^\s&;,"'}\]]+)""", RegexOption.IGNORE_CASE)
    private val scheme = Regex("""\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{8,}""", RegexOption.IGNORE_CASE)
    // A credential header's whole value, to the end of its line (a cookie line holds several).
    private val header = Regex("""\b((?:proxy-)?authorization|(?:set-)?cookie)(\s*:\s*)[^\r\n"]+""", RegexOption.IGNORE_CASE)
    private val opaqueSegment = Regex("""[A-Za-z0-9_~-]{48,}""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = url.replace(text) { match ->
            val (protocol, host, path, query, fragment) = match.destructured
            val safePath = path.split('/').joinToString("/") { segment ->
                // A segment with "=" is a parameter in the path (Akamai tokens); a long opaque one is a signature.
                if ('=' in segment || opaqueSegment.matches(segment)) MARK else segment
            }
            buildString {
                append(protocol).append("://").append(host.substringAfterLast('@')).append(safePath)
                if (query.isNotEmpty() || fragment.isNotEmpty()) append("?").append(MARK)
            }
        }
        out = header.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$MARK" }
        out = scheme.replace(out) { "${it.groupValues[1]} $MARK" }
        out = jsonField.replace(out) { "${it.groupValues[1]}\"$MARK\"" }
        out = pair.replace(out) { match ->
            // Already handled above (a header line, a JSON field).
            if (match.groupValues[2].startsWith(MARK.take(2))) match.value else "${match.groupValues[1]}$MARK"
        }
        return out
    }
}

/**
 * Where one download's time went, for the baseline and for every later
 * comparison: ids, counts and timings only.
 *
 * The phases, in order: [Phase.INIT] (provider packages and the runtime;
 * [coldStart] says whether the runtime had to be started), [Phase.RESOLVE]
 * (the track's provider ids), then for each provider call [Phase.FIRST_BYTE]
 * (from the call to the first byte received: the provider's own lookups,
 * sign-in and stream negotiation) and [Phase.TRANSFER] (from the first byte
 * to the end of the call), [Phase.FINALIZE] (container, quality limit and
 * tags, on the phone) and [Phase.IMPORT] (checks and the copy into the
 * library, reported by whoever imports the file).
 *
 * Progress is sampled about twice a second, so the first byte is known to
 * that resolution.
 */
internal class DownloadTimeline(private val now: () -> Long) {
    enum class Phase { INIT, RESOLVE, FIRST_BYTE, TRANSFER, FINALIZE, IMPORT }

    private val durations = EnumMap<Phase, Long>(Phase::class.java)

    /** True when this download had to start the runtime (the first one after the app started). */
    @Volatile var coldStart: Boolean = false

    private var callStartedAt = -1L
    private var firstByteAt = -1L
    private var lastBytes = 0L
    private var calls = 0
    private var nativeRetries = 0
    private var requests = 0
    private var received = 0L
    private var resent = 0L
    private var resumedFrom = 0L
    private var carried = 0L

    @Synchronized fun add(phase: Phase, ms: Long) {
        durations[phase] = (durations[phase] ?: 0L) + ms.coerceAtLeast(0L)
    }

    inline fun <T> measure(phase: Phase, block: () -> T): T {
        val startedAt = clock()
        try {
            return block()
        } finally {
            add(phase, clock() - startedAt)
        }
    }

    @PublishedApi internal fun clock(): Long = now()

    /** Requests Harmony itself made (registry, package, song.link, cover). */
    @Synchronized fun hostRequests(count: Int) {
        requests += count.coerceAtLeast(0)
    }

    /**
     * A provider call begins (each provider tried is one call). [onDiskBytes]
     * is what an earlier attempt left in the job's folder: the runtime either
     * continues from it or starts that part again.
     */
    @Synchronized fun callStarted(onDiskBytes: Long = 0L) {
        calls++
        callStartedAt = now()
        firstByteAt = -1L
        lastBytes = 0L
        carried = onDiskBytes.coerceAtLeast(0L)
    }

    /** The bytes the runtime says it has for this call. */
    @Synchronized fun progress(downloadedBytes: Long) {
        if (callStartedAt < 0L || downloadedBytes <= 0L) return
        if (firstByteAt < 0L) {
            firstByteAt = now()
            add(Phase.FIRST_BYTE, firstByteAt - callStartedAt)
            if (carried > 0L && downloadedBytes >= carried) {
                // Continued where the earlier attempt stopped: those bytes were not sent again.
                resumedFrom += carried
                received += downloadedBytes - carried
            } else {
                // Started from nothing; what an earlier attempt had is being sent again.
                resent += carried
                received += downloadedBytes
            }
            carried = 0L
        } else if (downloadedBytes >= lastBytes) {
            received += downloadedBytes - lastBytes
        } else {
            // The count went back: the runtime started that part again.
            resent += lastBytes - downloadedBytes
        }
        lastBytes = downloadedBytes
    }

    /** The call returned. [nativeAttempts] is the runtime's own attempt count when it reports one. */
    @Synchronized fun callEnded(nativeAttempts: Int = 0) {
        if (callStartedAt < 0L) return
        val end = now()
        if (firstByteAt >= 0L) add(Phase.TRANSFER, end - firstByteAt) else add(Phase.FIRST_BYTE, end - callStartedAt)
        if (nativeAttempts > 1) nativeRetries += nativeAttempts - 1
        callStartedAt = -1L
    }

    @Synchronized fun millis(phase: Phase): Long = durations[phase] ?: 0L

    val providerCalls: Int @Synchronized get() = calls

    /** Provider calls after the first, plus the runtime's own retries inside a call. */
    val retries: Int @Synchronized get() = (calls - 1).coerceAtLeast(0) + nativeRetries

    val receivedBytes: Long @Synchronized get() = received
    val retransmittedBytes: Long @Synchronized get() = resent
    val resumedBytes: Long @Synchronized get() = resumedFrom
    val hostRequests: Int @Synchronized get() = requests

    /** One line: no titles, no hosts, no URLs. */
    @Synchronized fun summary(jobId: String, outcome: String): String = String.format(
        Locale.US,
        "DOWNLOAD job=%s outcome=%s cold=%s init=%dms resolve=%dms first_byte=%dms transfer=%dms finalize=%dms " +
            "import=%dms calls=%d retries=%d host_requests=%d received=%d resumed=%d resent=%d",
        jobId.takeLast(12), outcome, coldStart,
        millis(Phase.INIT), millis(Phase.RESOLVE), millis(Phase.FIRST_BYTE), millis(Phase.TRANSFER),
        millis(Phase.FINALIZE), millis(Phase.IMPORT), calls, retries, requests, received, resumedFrom, resent,
    )
}
