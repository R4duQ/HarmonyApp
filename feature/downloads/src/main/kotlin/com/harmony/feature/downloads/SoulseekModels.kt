package com.harmony.feature.downloads

import java.util.Locale



enum class SoulseekSearchMode {
    QUICK,
    BALANCED,
    DEEP,
}

/**
 * Which audio formats a search accepts, and how it ranks them.
 *
 * Harmony started FLAC-only, which is the right default for a music app built
 * around lossless playback but a poor one for a phone: an album of FLAC runs
 * 250-400 MB against 60-100 MB for a 320 kbps MP3. This lets storage win when
 * the person wants it to.
 *
 * Only FLAC and MP3 are offered. Every accepted format needs its own container
 * verification on the download path — the whole point of that check is that a
 * peer can send anything — so widening this to M4A or OGG means writing those
 * checks first, not just adding an entry here.
 */
enum class SoulseekFormatPreference(
    val label: String,
    val summary: String,
) {
    FLAC_ONLY(
        label = "FLAC only",
        summary = "Lossless. Roughly 250-400 MB per album.",
    ),
    PREFER_FLAC(
        label = "Prefer FLAC",
        summary = "Lossless when a peer has it, MP3 rather than nothing.",
    ),
    PREFER_MP3(
        label = "Prefer MP3",
        summary = "Saves space. Falls back to FLAC when no MP3 is shared.",
    ),
    MP3_ONLY(
        label = "MP3 only",
        summary = "Smallest. Roughly 60-100 MB per album.",
    ),
    ;

    val acceptedExtensions: Set<String>
        get() = when (this) {
            FLAC_ONLY -> setOf("flac")
            MP3_ONLY -> setOf("mp3")
            PREFER_FLAC, PREFER_MP3 -> setOf("flac", "mp3")
        }

    /** The format this preference ranks first when both are available. */
    val preferredExtension: String
        get() = when (this) {
            FLAC_ONLY, PREFER_FLAC -> "flac"
            MP3_ONLY, PREFER_MP3 -> "mp3"
        }

    fun accepts(extension: String): Boolean =
        extension.lowercase(Locale.US) in acceptedExtensions

    fun prefers(extension: String): Boolean =
        extension.equals(preferredExtension, ignoreCase = true)

    companion object {
        /** Formats with a container check on the download path. */
        val SUPPORTED_EXTENSIONS = setOf("flac", "mp3")

        fun isLossless(extension: String): Boolean = extension.equals("flac", ignoreCase = true)

        fun fromName(name: String?): SoulseekFormatPreference =
            entries.firstOrNull { it.name == name } ?: FLAC_ONLY
    }
}

enum class SoulseekConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class SoulseekConnectionState(
    val status: SoulseekConnectionStatus = SoulseekConnectionStatus.DISCONNECTED,
    val username: String = "",
    val message: String = "Not connected",
    val listeningPort: Int = 0,
)

data class SoulseekSearchCandidate(
    val id: String,
    val username: String,
    val filename: String,
    val sizeBytes: Long,
    val extension: String,
    val durationSeconds: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitrateKbps: Int? = null,
    val freeUploadSlot: Boolean,
    val averageSpeedBytesPerSecond: Long,
    val queueLength: Int,
    val score: Int,
    val recommendation: String,
) {
    val fileNameOnly: String
        get() = filename.substringAfterLast('\\').substringAfterLast('/')

    val isLossless: Boolean
        get() = SoulseekFormatPreference.isLossless(extension)

    val qualityLabel: String
        get() = buildList {
            add(extension.uppercase())
            bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
            // Bit depth is meaningless for MP3 and bitrate is the number that
            // actually tells you what you are getting, so show it there.
            if (!isLossless) bitrateKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
            sampleRate?.takeIf { it > 0 }?.let { add("${formatSampleRate(it)} kHz") }
        }.joinToString(" · ")

    private fun formatSampleRate(rate: Int): String {
        val khz = rate / 1000.0
        return if (khz % 1.0 == 0.0) "%.0f".format(khz) else "%.1f".format(khz)
    }
}

data class SoulseekTransferProgress(
    val username: String,
    val filename: String,
    val status: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val queuePlace: Int? = null,
    /** Queue position the first time this peer reported one, for trend display. */
    val queueStartPlace: Int? = null,
    /** Wall-clock millis when queue waiting began, for elapsed display. */
    val queueWaitStartedAtMs: Long? = null,
    /** Wall-clock millis of the most recent place report. */
    val queueUpdatedAtMs: Long? = null,
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0L }?.let {
            (downloadedBytes.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    /** How many positions this request has advanced, when that is known. */
    val queueAdvancedBy: Int?
        get() {
            val start = queueStartPlace ?: return null
            val now = queuePlace ?: return null
            return (start - now).takeIf { it > 0 }
        }
}

/**
 * Why a search produced the result count it did.
 *
 * Every rejection path in the client is silent by design — non-FLAC files are
 * skipped, unmatched tokens return early, and peer parse errors are swallowed.
 * That makes "nothing found" indistinguishable from "nothing arrived". These
 * counters separate the two.
 */
data class SoulseekSearchDiagnostics(
    val queriesSent: Int = 0,
    val variants: List<String> = emptyList(),
    val peersResponded: Int = 0,
    val filesSeen: Int = 0,
    val acceptedFiles: Int = 0,
    /** Which format the search was accepting, so the diagnosis can say so. */
    val formatPreference: SoulseekFormatPreference = SoulseekFormatPreference.FLAC_ONLY,
    val rejectedByExtension: Map<String, Int> = emptyMap(),
    val parseFailures: Int = 0,
    val unmatchedTokenResponses: Int = 0,
    val sessionEndReason: String? = null,
    /**
     * Files Harmony is actually sharing at the moment of the search.
     *
     * Carried here because the "nobody answered" diagnosis used to assert that
     * Harmony shares nothing — true when it was download-only, and a plain
     * falsehood once folder sharing existed. A diagnostic that confidently
     * misstates the app's own state sends people to fix the wrong thing.
     */
    val sharedFileCount: Int = 0,
    /** Port peers were told to reach us on, for the reachability reading. */
    val listeningPort: Int = 0,
    /** Peers that opened a connection straight to our listening port. */
    val inboundSocketsAccepted: Int = 0,
    /** Times the server relayed a peer's request for US to call THEM back. */
    val connectToPeerRequests: Int = 0,
    /** Of those, how many our outbound call-back leg failed on. */
    val indirectConnectFailures: Int = 0,
    /**
     * Relay requests dropped without dialling because Harmony does not speak
     * that connection type — overwhelmingly the server soliciting us to join
     * the distributed search network, which Harmony does not do.
     */
    val distributedSolicitations: Int = 0,
    /** Call-backs dropped without dialling: stale stragglers, or dial budget full. */
    val indirectDialsShed: Int = 0,
) {
    /** Relay requests Harmony actually tried to answer. */
    val relaysAttempted: Int get() = (connectToPeerRequests - distributedSolicitations).coerceAtLeast(0)
    /** Compact one-line summary for the Downloads status area. */
    val summary: String
        get() {
            val topRejects = rejectedByExtension.entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(", ") { "${it.value} ${it.key.ifBlank { "?" }}" }
            return buildList {
                add("$queriesSent quer${if (queriesSent == 1) "y" else "ies"}")
                add("$peersResponded peer${if (peersResponded == 1) "" else "s"} replied")
                add("$filesSeen file${if (filesSeen == 1) "" else "s"}")
                add("$acceptedFiles usable")
                if (topRejects.isNotEmpty()) add("skipped: $topRejects")
                if (parseFailures > 0) add("$parseFailures parse fail")
                if (unmatchedTokenResponses > 0) add("$unmatchedTokenResponses late")
                // Reachability line: makes the difference between "nobody tried"
                // and "they tried and it failed" visible without expanding the
                // diagnosis text.
                add("in $inboundSocketsAccepted · relay $relaysAttempted" +
                    (if (indirectConnectFailures > 0) " ($indirectConnectFailures failed)" else "") +
                    (if (distributedSolicitations > 0) " · $distributedSolicitations ignored" else ""))
            }.joinToString(" · ")
        }

    /**
     * The specific, actionable reading of the counters. Null when the search
     * behaved normally and simply found things.
     */
    val diagnosis: String?
        get() = when {
            // A dead session outranks every other reading: no peer can answer a
            // search the server is no longer routing.
            sessionEndReason != null -> sessionEndReason
            peersResponded == 0 && queriesSent > 0 && sharedFileCount == 0 ->
                "No peer answered, and Harmony is sharing nothing. Many clients ignore searches " +
                    "from non-sharing accounts, so this is the first thing to change: pick a " +
                    "folder under Sharing above."

            // Below here the counters do the talking. Zero replies has several
            // very different causes that look identical from the outside, and
            // guessing between them wastes the user's time — so each branch is
            // gated on evidence rather than on the most common explanation.
            peersResponded == 0 && queriesSent > 0 && connectToPeerRequests == 0 &&
                inboundSocketsAccepted == 0 ->
                "No peer tried to contact Harmony at all — not directly, and not via the server. " +
                    "The search was sent and the server accepted it, but nothing came back, which " +
                    "points at the search not reaching peers rather than at replies being lost on " +
                    "the way in. Try a broader query first; if that is also silent, the session is " +
                    "likely logged in but not being served searches."

            // A failure COUNT means nothing on its own — a few hundred failures
            // out of many thousands of attempts is an ordinary P2P network. Only
            // a high failure RATE indicates our outbound leg is the problem.
            peersResponded == 0 && queriesSent > 0 && relaysAttempted >= 10 &&
                indirectConnectFailures * 2 >= relaysAttempted ->
                "$indirectConnectFailures of $relaysAttempted call-backs failed. Peers are finding " +
                    "your search; Harmony cannot complete the return connection often enough to " +
                    "receive replies. This is an outbound problem on this network, not a sharing " +
                    "or query problem."

            peersResponded == 0 && queriesSent > 0 && relaysAttempted > 0 ->
                "$relaysAttempted peer connection(s) completed but no reply parsed out of them. " +
                    "That points at protocol handling rather than the network."

            peersResponded == 0 && queriesSent > 0 ->
                "No peer answered, although you are sharing $sharedFileCount file" +
                    (if (sharedFileCount == 1) "" else "s") + " and " +
                    "$inboundSocketsAccepted inbound connection(s) arrived on port " +
                    "${if (listeningPort > 0) listeningPort.toString() else "?"}. " +
                    "Replies arrive as inbound connections, so the remaining explanations are a " +
                    "query nothing matches, or peers reaching you but not with results."
            filesSeen > 0 && acceptedFiles == 0 ->
                "Peers replied, but none of the files matched the format filter " +
                    "(${formatPreference.label}). Widening it in the Soulseek controls will " +
                    "usually turn these replies into results."
            parseFailures > 0 && acceptedFiles == 0 ->
                "Every peer reply failed to parse. This is a protocol bug, not a missing track."
            else -> null
        }
}

data class SoulseekDownloadedFile(
    val tempFile: java.io.File,
    val suggestedFileName: String,
    val sizeBytes: Long,
)

/**
 * How long a single download attempt is willing to wait on one peer.
 *
 * [FAST] walks many sources looking for one that is immediately free, and
 * abandons anything queued. [PATIENT] is the second pass: it returns to a peer
 * that already proved it is alive and willing, and actually waits for the
 * upload slot instead of giving up on it.
 */
enum class SoulseekDownloadPatience {
    FAST,
    PATIENT,
}

/**
 * A peer answered and placed the request in its upload queue. This is a normal,
 * recoverable outcome — not a dead peer — so the ViewModel records it as a
 * patient-retry candidate rather than discarding the source.
 */
class SoulseekQueuedException(
    val peerUsername: String,
    val queuePlace: Int,
    message: String,
) : Exception(message)

class SoulseekException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
