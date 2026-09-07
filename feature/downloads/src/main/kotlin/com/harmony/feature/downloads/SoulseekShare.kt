package com.harmony.feature.downloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/** One shareable file found inside the chosen folder. */
data class SharedEntry(
    /** Resolved document URI, used to open the bytes when a peer requests it. */
    val documentUri: String,
    /** Backslash-delimited path advertised to peers, e.g. `Music\Album\track.flac`. */
    val virtualPath: String,
    val sizeBytes: Long,
) {
    val extension: String
        get() = virtualPath.substringAfterLast('.', "").lowercase(Locale.US)

    /** Everything before the final backslash — the folder this file is announced under. */
    val virtualFolder: String
        get() = virtualPath.substringBeforeLast('\\', "")
}

/**
 * The single folder Harmony shares back to the network.
 *
 * Replaces the single-file share from v1.7.4. The trade-off is deliberate and
 * worth stating plainly: `OpenDocumentTree` grants recursive read access to
 * everything under the folder the user selects, which `OpenDocument` did not.
 * That is inherent to sharing a folder at all — it is the same access every
 * other Soulseek client needs — but it is a genuinely wider grant than before,
 * so the folder choice matters. Harmony still cannot see anything outside the
 * selected tree.
 */
data class SoulseekSharedFolder(
    val treeUri: String,
    val displayName: String,
    val entries: List<SharedEntry>,
    /** True when indexing stopped at [SoulseekShareIndexer.MAX_FILES] rather than running out of files. */
    val truncated: Boolean = false,
) {
    val fileCount: Int get() = entries.size
    val folderCount: Int get() = entries.map { it.virtualFolder }.distinct().size
    val totalBytes: Long get() = entries.sumOf { it.sizeBytes }

    private val byPath: Map<String, SharedEntry> by lazy {
        // Peers echo back the exact path string Harmony advertised, but match
        // case-insensitively anyway: some clients normalise case in transit and
        // a case-only mismatch would look like "file not shared" for a file
        // that plainly is.
        entries.associateBy { it.virtualPath.lowercase(Locale.US) }
    }

    fun find(virtualPath: String): SharedEntry? = byPath[virtualPath.lowercase(Locale.US)]
}

object SoulseekShareIndexer {

    /**
     * Hard ceiling on indexed files.
     *
     * Someone can point the picker at the storage root. Without a cap, indexing
     * would issue tens of thousands of content-provider queries and the share
     * list sent to peers would be far past what any client will accept in one
     * message.
     */
    const val MAX_FILES = 2_000

    /** Depth ceiling, for the same reason plus cycle safety on odd providers. */
    private const val MAX_DEPTH = 8

    /**
     * Only audio is indexed.
     *
     * A music folder frequently also holds scans, playlists, invoices, or
     * whatever else got saved next to it. Sharing a folder should not quietly
     * publish those, and Soulseek is a music network — so the filter is both
     * the safer and the more useful default.
     */
    private val AUDIO_EXTENSIONS = setOf(
        "flac", "mp3", "m4a", "aac", "ogg", "oga", "opus",
        "wav", "aiff", "aif", "ape", "wv", "wma", "alac", "mpc",
    )

    /**
     * Walk [treeUri] breadth-first and return every audio file under it.
     *
     * Uses [DocumentsContract] rather than `DocumentFile`: one cursor query per
     * directory instead of one IPC round-trip per file, which is the difference
     * between a second and a minute on a large library. Blocking — call it off
     * the main thread.
     */
    fun index(context: Context, treeUri: Uri, rootName: String): SoulseekSharedFolder {
        val entries = ArrayList<SharedEntry>()
        var truncated = false

        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return SoulseekSharedFolder(treeUri.toString(), rootName, emptyList())

        // documentId to virtual prefix. Breadth-first so a wide shallow library
        // is fully covered before depth is spent on one deep branch.
        var frontier = listOf(rootId to rootName)
        var depth = 0

        while (frontier.isNotEmpty() && depth < MAX_DEPTH && !truncated) {
            val next = ArrayList<Pair<String, String>>()
            for ((parentId, prefix) in frontier) {
                if (truncated) break
                val childrenUri = runCatching {
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                }.getOrNull() ?: continue

                runCatching {
                    context.contentResolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE,
                        ),
                        null, null, null,
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            val name = cursor.getString(1) ?: continue
                            val mime = cursor.getString(2)
                            val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)

                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                next += id to "$prefix\\$name"
                                continue
                            }
                            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                            if (ext !in AUDIO_EXTENSIONS) continue
                            // A zero or unknown size cannot be advertised: the
                            // TransferRequest carries the size and a peer that
                            // is told 0 will reject or truncate the transfer.
                            if (size <= 0L) continue

                            entries += SharedEntry(
                                documentUri = DocumentsContract
                                    .buildDocumentUriUsingTree(treeUri, id)
                                    .toString(),
                                virtualPath = "$prefix\\$name",
                                sizeBytes = size,
                            )
                            if (entries.size >= MAX_FILES) {
                                truncated = true
                                return@use
                            }
                        }
                    }
                }
            }
            frontier = next
            depth += 1
        }

        return SoulseekSharedFolder(
            treeUri = treeUri.toString(),
            displayName = rootName,
            entries = entries,
            truncated = truncated,
        )
    }

    /** Best-effort human name for the picked tree, for display only. */
    fun treeDisplayName(treeUri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val tail = id?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return tail ?: treeUri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "Shared folder"
    }
}
