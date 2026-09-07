package com.harmony.feature.library

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.harmony.core.model.Song
import com.harmony.domain.library.gateway.LibraryWriteGateway
import com.harmony.domain.library.repository.AlbumJourneyRepository
import com.harmony.domain.playback.PlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryFileActions @Inject constructor() {
    private val pending = MutableStateFlow<List<String>>(emptyList())
    val deletionRequests = pending.asStateFlow()
    fun requestDelete(songs: List<Song>) { if (pending.value.isEmpty()) pending.value = songs.map { it.uri }.distinct() }
    fun consumed() { pending.value = emptyList() }
}

data class FileDeleteStep(val deleted: List<String> = emptyList(), val consent: IntentSender? = null,
    val consentUris: List<String> = emptyList(), val retryAfterConsent: Boolean = false)

/** Removes exact files; a permission error is never treated as proof that a file is gone. */
@Singleton
class LibraryFileDeleter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writes: LibraryWriteGateway,
    private val playback: PlaybackController,
    private val albums: AlbumJourneyRepository,
) {
    private val resolver get() = context.contentResolver

    suspend fun next(uris: List<String>, allowConsent: Boolean = true): FileDeleteStep = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext FileDeleteStep()
        val first = Uri.parse(uris.first())
        if (missing(first)) return@withContext FileDeleteStep(listOf(first.toString()))
        if (isMediaItem(first) && Build.VERSION.SDK_INT >= 30 && allowConsent) {
            val group = uris.map(Uri::parse).filter(::isMediaItem).take(100)
            return@withContext FileDeleteStep(consent = MediaStore.createDeleteRequest(resolver, group).intentSender,
                consentUris = group.map(Uri::toString))
        }
        try {
            val removed = when {
                DocumentsContract.isDocumentUri(context, first) -> DocumentsContract.deleteDocument(resolver, first)
                isMediaItem(first) -> resolver.delete(first, null, null) > 0
                first.scheme == "file" -> File(requireNotNull(first.path)).let { it.isFile && it.delete() }
                else -> error("This storage provider does not support deleting this audio file. Use the Files app.")
            }
            check(removed || missing(first)) { "The file could not be deleted. It remains in your library." }
            FileDeleteStep(deleted = listOf(first.toString()))
        } catch (permission: RecoverableSecurityException) {
            if (!allowConsent) throw SecurityException("Android did not grant access to delete this file.")
            FileDeleteStep(consent = permission.userAction.actionIntent.intentSender,
                consentUris = listOf(first.toString()), retryAfterConsent = true)
        }
    }

    suspend fun verifyRemoved(uris: List<String>): List<String> = withContext(Dispatchers.IO) {
        uris.filter { missing(Uri.parse(it)) }
    }

    suspend fun cleanLibrary(uris: List<String>) {
        if (uris.isEmpty()) return
        writes.removeByUris(uris)
        albums.filesRemoved(uris.toSet())
        withContext(Dispatchers.Main.immediate) {
            playback.removeSongsByUri(uris.toSet())
        }
    }

    private fun isMediaItem(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == "media" &&
        uri.pathSegments.let { path -> path.size >= 3 && path.last().toLongOrNull() != null &&
            (path.dropLast(1).takeLast(2) == listOf("audio", "media") || path[path.lastIndex - 1] == "file") }

    private fun missing(uri: Uri): Boolean {
        if (uri.scheme == "file") return !File(requireNotNull(uri.path)).exists()
        check(uri.scheme == "content") { "Unsupported audio location." }
        return try {
            resolver.query(uri, null, null, null, null)?.use { !it.moveToFirst() }
                ?: throw IllegalStateException("The storage provider could not verify this file. Try again.")
        } catch (_: FileNotFoundException) { true }
    }
}
