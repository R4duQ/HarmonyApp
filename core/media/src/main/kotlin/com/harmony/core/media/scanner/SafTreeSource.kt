package com.harmony.core.media.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.harmony.core.media.model.MediaCandidate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Covers storage MediaStore can't see — primarily USB OTG drives.
 *
 * Approach: the user grants a tree once via ACTION_OPEN_DOCUMENT_TREE (the
 * settings screen in Phase 8 exposes "Add music folder"); we take a persisted
 * permission and re-traverse the tree on each scan.
 *
 * Traversal uses DocumentsContract child queries directly instead of the
 * DocumentFile convenience wrapper: DocumentFile issues one ContentResolver
 * round-trip per property per file, which is catastrophically slow on large
 * USB trees. A single projection per directory is 10-50x faster in practice.
 */
@Singleton
class SafTreeSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Trees the user has granted and we persisted. */
    fun persistedTreeUris(): List<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }

    fun queryAll(trees: List<Uri> = persistedTreeUris()): List<MediaCandidate> =
        trees.flatMap { tree -> traverse(tree) }

    private fun traverse(treeUri: Uri): List<MediaCandidate> {
        val result = ArrayList<MediaCandidate>(256)
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<String>().apply { add(rootDocId) }
        val volumeTag = "usb:$treeUri"

        while (stack.isNotEmpty()) {
            val dirDocId = stack.removeLast()
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
            checkNotNull(context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )) { "The music folder is unavailable. Its library entries were kept." }.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.add(docId)
                    } else if (SupportedFormats.isSupported(name, mime)) {
                        result += MediaCandidate(
                            uri = DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, docId)
                                .toString(),
                            displayName = name,
                            sizeBytes = cursor.getLong(3),
                            lastModified = cursor.getLong(4),
                            storageVolume = volumeTag,
                            folder = docId.substringBeforeLast('/', ""),
                            physicalPath = ScanIdentity.documentPath(treeUri.authority, docId),
                        )
                    }
                }
            }
        }
        return result
    }
}
