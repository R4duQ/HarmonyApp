package com.harmony.feature.downloads.transfer

import android.os.StatFs
import android.webkit.MimeTypeMap
import com.harmony.core.database.dao.SongDao
import com.harmony.core.remote.transfer.PhoneLibrary
import com.harmony.core.remote.transfer.TransferItem
import com.harmony.feature.downloads.MusicDownloadRepository
import java.io.File

/**
 * The phone's side of saving a song sent from a computer: free space, songs
 * the library already has (by content, the scanner's own hash), and saving
 * through the same MediaStore/SAF path as downloads. The library picks the new
 * file up by itself (MediaStore observer, incremental scan, analysis later).
 */
class PhoneTransferLibrary(
    private val downloads: MusicDownloadRepository,
    private val songs: SongDao,
    private val staging: File,
) : PhoneLibrary {
    override fun freeBytes(): Long = runCatching { staging.mkdirs(); StatFs(staging.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    override fun findInLibrary(item: TransferItem): String? =
        item.libraryHash.takeIf { it.isNotEmpty() }?.let { runCatching { songs.uriWithContent(it, item.sizeBytes) }.getOrNull() }

    override fun stillThere(address: String): Boolean = downloads.stillReadable(address)

    override fun publish(file: File, item: TransferItem): String =
        downloads.publishFromComputer(file, item.name, mimeOf(item), item.libraryHash).toString()

    private fun mimeOf(item: TransferItem): String {
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return item.mime.takeIf { it.startsWith("audio/") }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.startsWith("audio/") }
            ?: when (ext) {
                "ape" -> "audio/x-ape"
                "wv" -> "audio/x-wavpack"
                "dsf", "dff" -> "audio/x-dsf"
                "wma" -> "audio/x-ms-wma"
                "aif", "aiff" -> "audio/x-aiff"
                else -> "audio/mpeg"
            }
    }
}
