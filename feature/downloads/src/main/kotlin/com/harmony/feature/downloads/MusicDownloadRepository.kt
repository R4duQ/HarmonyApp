package com.harmony.feature.downloads

import kotlinx.coroutines.ensureActive

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicDownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    // Lazy: most of this repository never touches SpotiFLAC, and its engine is heavy to set up.
    private val spotiFlac: dagger.Lazy<SpotiFlacDownloadEngine>,
) {
    private val downloadManager: DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val ytdlpInitMutex = Mutex()
    private val ytdlpUpdateMutex = Mutex()
    @Volatile private var ytdlpReady = false
    @Volatile private var ytdlpUpdatedThisProcess = false

    private val downloaderPreferences by lazy {
        context.getSharedPreferences("harmony_downloader", Context.MODE_PRIVATE)
    }

    suspend fun identify(input: String): IdentifiedTrack = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        require(trimmed.isNotBlank()) { "Paste a YouTube link or enter an artist and song title." }

        if (!looksLikeYouTubeUrl(trimmed)) {
            return@withContext parseFreeText(trimmed)
        }

        // oEmbed is deliberately used for fast identification. yt-dlp itself is
        // only initialized when the user actually starts a conversion.
        val endpoint = buildString {
            append("https://www.youtube.com/oembed?format=json&url=")
            append(URLEncoder.encode(trimmed, StandardCharsets.UTF_8.toString()))
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Harmony/1.0 Android")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalArgumentException("YouTube couldn't identify this link (HTTP $code).")
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val objectJson = JSONObject(json)
            val sourceTitle = objectJson.optString("title").trim()
            val author = objectJson.optString("author_name").trim()
            if (sourceTitle.isBlank()) {
                throw IllegalArgumentException("YouTube returned no title for this link.")
            }
            parseYouTubeMetadata(
                sourceTitle = sourceTitle,
                author = author,
                thumbnailUrl = objectJson.optString("thumbnail_url").takeIf { it.isNotBlank() },
            )
        } finally {
            connection.disconnect()
        }
    }

    fun isYouTubeUrl(value: String): Boolean = looksLikeYouTubeUrl(value.trim())

    /**
     * Real text search for the SpotiFLAC flow: several phrasings on Deezer,
     * then Apple Music, all filtered by the same strict title/artist check.
     * See [SpotiFlacTrackSearch].
     */
    suspend fun searchSpotiFlacTracks(rawQuery: String): List<SpotiFlacSearchCandidate> =
        withContext(Dispatchers.IO) {
            val query = rawQuery.trim().replace(Regex("\\s+"), " ")
            validateSpotiFlacQuery(query)
            val resolvedQuery = if (looksLikeYouTubeUrl(query)) identify(query).displayName else query
            SpotiFlacTrackSearch(
                fetch = { url -> fetchMetadata(url) },
                providers = { q, limit -> spotiFlac.get().searchProviderTracks(q, limit) },
            ).search(resolvedQuery)
        }

    private fun fetchMetadata(url: String): SearchResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Harmony/1.0 Android MetadataSearch")
        }
        return try {
            val code = connection.responseCode
            val body = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else ""
            SearchResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun resolveSpotiFlacTrack(candidate: SpotiFlacSearchCandidate): IdentifiedTrack =
        withContext(Dispatchers.IO) {
            require(candidate.metadataId.isNotBlank()) { "The selected metadata result has no track ID." }
            // Found on Apple Music: there is no Deezer id to look up. The download engine
            // works from artist, title and duration; never hand it Apple's id as a Deezer one.
            if (candidate.metadataId.startsWith(SpotiFlacTrackSearch.APPLE_PREFIX) ||
                candidate.metadataId.startsWith(SpotiFlacTrackSearch.PROVIDER_PREFIX)) {
                return@withContext candidate.toIdentifiedTrack().copy(metadataId = null)
            }
            val endpoint = "https://api.deezer.com/track/${URLEncoder.encode(candidate.metadataId, StandardCharsets.UTF_8.toString())}"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Harmony/1.0 Android MetadataResolve")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    return@withContext candidate.toIdentifiedTrack()
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val item = JSONObject(body)
                if (item.has("error")) return@withContext candidate.toIdentifiedTrack()

                val artist = item.optJSONObject("artist")?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?: candidate.artist
                val title = item.optString("title_short")
                    .ifBlank { item.optString("title") }
                    .ifBlank { candidate.title }
                val albumObject = item.optJSONObject("album")
                val album = albumObject?.optString("title")
                    ?.takeIf { it.isNotBlank() }
                    ?: candidate.album
                val cover = albumObject?.optString("cover_xl")
                    ?.takeIf { it.isNotBlank() }
                    ?: albumObject?.optString("cover_big")?.takeIf { it.isNotBlank() }
                    ?: candidate.thumbnailUrl
                val durationMs = item.optLong("duration")
                    .takeIf { it > 0L }
                    ?.times(1000L)
                    ?: candidate.durationMs
                val isrc = item.optString("isrc").trim().takeIf { it.isNotBlank() } ?: candidate.isrc

                IdentifiedTrack(
                    artist = artist,
                    title = title,
                    sourceTitle = "$artist - $title",
                    thumbnailUrl = cover,
                    album = album,
                    durationMs = durationMs,
                    isrc = isrc,
                    metadataId = candidate.metadataId,
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun SpotiFlacSearchCandidate.toIdentifiedTrack(): IdentifiedTrack = IdentifiedTrack(
        artist = artist,
        title = title,
        sourceTitle = displayName,
        thumbnailUrl = thumbnailUrl,
        album = album,
        durationMs = durationMs,
        isrc = isrc,
        metadataId = metadataId,
    )

    /** Same similarity as the search, so a file is judged by the rule that found it. */
    private fun fuzzyTextScore(expected: String, actual: String): Int = SearchScoring.fuzzy(expected, actual)

    private fun validateSpotiFlacQuery(query: String) {
        if (query.isBlank()) {
            throw IllegalArgumentException("Enter an artist and song title first.")
        }
        if (looksLikeYouTubeUrl(query)) return

        val lettersAndDigits = query.count(Char::isLetterOrDigit)
        if (lettersAndDigits < SPOTIFLAC_MIN_QUERY_CHARS) {
            throw IllegalArgumentException(
                "The SpotiFLAC search is too short. Enter at least $SPOTIFLAC_MIN_QUERY_CHARS letters/numbers, preferably artist and song title."
            )
        }
        if (query.length > SPOTIFLAC_MAX_QUERY_LENGTH) {
            throw IllegalArgumentException("The search is too long. Use a concise artist - song query.")
        }
        if (query.all { !it.isLetterOrDigit() }) {
            throw IllegalArgumentException("Enter a real artist or song title, not only punctuation.")
        }
    }

    fun lastDownloadSource(): DownloadSource = runCatching {
        DownloadSource.valueOf(
            downloaderPreferences.getString(PREF_LAST_DOWNLOAD_SOURCE, null) ?: DownloadSource.SOULSEEK.name
        )
    }.getOrDefault(DownloadSource.SOULSEEK)

    fun rememberDownloadSource(source: DownloadSource) {
        downloaderPreferences.edit().putString(PREF_LAST_DOWNLOAD_SOURCE, source.name).apply()
    }

    fun downloadFolderLabel(): String {
        val uri = selectedDownloadFolderUri()
        return if (uri == null) {
            "Music/Harmony/Downloads"
        } else {
            downloaderPreferences.getString(PREF_DOWNLOAD_FOLDER_LABEL, null)
                ?.takeIf { it.isNotBlank() }
                ?: readableTreeLabel(uri)
        }
    }

    fun hasCustomDownloadFolder(): Boolean = selectedDownloadFolderUri() != null

    fun selectDownloadFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        downloaderPreferences.edit()
            .putString(PREF_DOWNLOAD_FOLDER_URI, uri.toString())
            .putString(PREF_DOWNLOAD_FOLDER_LABEL, readableTreeLabel(uri))
            .apply()
    }

    fun useDefaultDownloadFolder() {
        // Keep the persisted SAF permission. Harmony's library scanner may also
        // be using the same folder as a source, so resetting the download target
        // must not silently revoke library access.
        downloaderPreferences.edit()
            .remove(PREF_DOWNLOAD_FOLDER_URI)
            .remove(PREF_DOWNLOAD_FOLDER_LABEL)
            .apply()
    }

    private fun selectedDownloadFolderUri(): Uri? = downloaderPreferences
        .getString(PREF_DOWNLOAD_FOLDER_URI, null)
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)

    private fun readableTreeLabel(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return uri.lastPathSegment?.let(Uri::decode).orEmpty().ifBlank { "Selected folder" }
        val parts = documentId.split(':', limit = 2)
        val volume = parts.firstOrNull().orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        return when {
            volume.equals("primary", ignoreCase = true) && path.isNotBlank() -> "Internal storage/$path"
            volume.equals("primary", ignoreCase = true) -> "Internal storage"
            path.isNotBlank() -> "$volume/$path"
            volume.isNotBlank() -> volume
            else -> "Selected folder"
        }
    }

    /**
     * Free/local conversion path:
     *  1) yt-dlp downloads the best available audio stream.
     *  2) bundled FFmpeg decodes that stream and writes FLAC in app cache.
     *  3) the finished FLAC is copied through MediaStore to Music/Harmony/Downloads.
     *
     * This is a lossless *container/codec conversion*, not a quality restoration:
     * a lossy YouTube source remains lossy information inside the resulting FLAC.
     */
    suspend fun downloadYouTubeAsFlac(
        youtubeUrl: String,
        preferredBaseName: String,
        onProgress: (progressPercent: Float, statusLine: String) -> Unit,
    ): ConvertedFlac = withContext(Dispatchers.IO) {
        require(looksLikeYouTubeUrl(youtubeUrl)) { "Paste a valid YouTube URL first." }

        onProgress(0f, "Preparing downloader…")
        ensureYtDlpReady(onProgress)

        val jobId = UUID.randomUUID().toString()
        val workDir = File(context.cacheDir, "harmony-ytdlp/$jobId").apply { mkdirs() }

        try {
            var attempt = 0
            var lastFailure: Throwable? = null

            while (attempt < 2) {
                attempt += 1
                val processId = "harmony-flac-$jobId-$attempt"

                if (attempt == 2) {
                    workDir.deleteRecursively()
                    workDir.mkdirs()
                    onProgress(0f, "YouTube changed its protocol. Updating yt-dlp and retrying…")
                    val updateFailure = updateYtDlpNightly(force = true, onProgress = onProgress)
                    if (updateFailure != null) {
                        throw HarmonyDownloadException(
                            userMessage = "Harmony couldn't update its YouTube downloader. Check your connection and try again.",
                            technicalDetails = updateFailure.stackTraceToString().take(MAX_TECHNICAL_DETAILS),
                            cause = updateFailure,
                        )
                    }
                }

                try {
                    executeYouTubeFlacRequest(
                        youtubeUrl = youtubeUrl,
                        workDir = workDir,
                        processId = processId,
                        onProgress = onProgress,
                    )
                    lastFailure = null
                    break
                } catch (t: Throwable) {
                    lastFailure = t
                    runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                    if (attempt >= 2 || !looksLikeYoutubeProtocolFailure(t)) break
                }
            }

            if (lastFailure != null) {
                throw friendlyYoutubeFailure(lastFailure)
            }

            val flac = workDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("flac", ignoreCase = true) }
                .maxByOrNull { it.lastModified() }
                ?: throw HarmonyDownloadException(
                    userMessage = "The download finished, but FFmpeg did not create a FLAC file.",
                    technicalDetails = "No .flac output was found in ${workDir.absolutePath}",
                )

            onProgress(99f, "Adding FLAC to the Harmony library…")
            val displayBase = sanitizeFileName(preferredBaseName)
                .ifBlank { flac.nameWithoutExtension.ifBlank { "Harmony download" } }
            val publishedUri = publishFlac(flac, "$displayBase.flac")
            val publishedSize = context.contentResolver.openAssetFileDescriptor(publishedUri, "r")
                ?.use { it.length }
                ?.takeIf { it >= 0L }
                ?: flac.length()

            onProgress(100f, "FLAC conversion complete")
            ConvertedFlac(
                uri = publishedUri,
                displayName = "$displayBase.flac",
                sizeBytes = publishedSize,
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun executeYouTubeFlacRequest(
        youtubeUrl: String,
        workDir: File,
        processId: String,
        onProgress: (progressPercent: Float, statusLine: String) -> Unit,
    ) {
        val request = YoutubeDLRequest(youtubeUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-update")
            addOption("--js-runtimes", "quickjs")
            addOption("-f", "bestaudio/best")
            addOption("-x")
            addOption("--audio-format", "flac")
            addOption("--audio-quality", "0")
            addOption("--embed-metadata")
            addOption("-o", File(workDir, "%(title).180B [%(id)s].%(ext)s").absolutePath)
        }

        YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
            val cleanStatus = compactYtDlpStatus(line.orEmpty(), progress)
            onProgress(progress.coerceIn(0f, 98f), cleanStatus)
        }
    }

    private suspend fun ensureYtDlpReady(
        onProgress: (progressPercent: Float, statusLine: String) -> Unit,
    ) {
        if (!ytdlpReady) {
            ytdlpInitMutex.withLock {
                if (!ytdlpReady) {
                    withContext(Dispatchers.IO) {
                        onProgress(0f, "Starting yt-dlp + FFmpeg…")
                        YoutubeDL.getInstance().init(context)
                        FFmpeg.getInstance().init(context)
                    }
                    ytdlpReady = true
                }
            }
        }

        // The bundled yt-dlp can age quickly as YouTube changes its extractor.
        // Refresh at most once every 12 hours, and always once per fresh install.
        updateYtDlpNightly(force = false, onProgress = onProgress)
    }

    /**
     * Returns the update error instead of immediately aborting. This lets a recent
     * bundled binary continue working if GitHub/update infrastructure is temporarily
     * unavailable; a YouTube protocol failure will force one more update + retry.
     */
    private suspend fun updateYtDlpNightly(
        force: Boolean,
        onProgress: (progressPercent: Float, statusLine: String) -> Unit,
    ): Throwable? = ytdlpUpdateMutex.withLock {
        val now = System.currentTimeMillis()
        val lastUpdate = downloaderPreferences.getLong(PREF_LAST_YTDLP_UPDATE, 0L)
        val freshEnough = now - lastUpdate in 0 until YTDLP_UPDATE_INTERVAL_MS

        if (!force && (ytdlpUpdatedThisProcess || freshEnough)) {
            return@withLock null
        }

        onProgress(0f, if (force) "Refreshing yt-dlp before retry…" else "Checking for yt-dlp updates…")
        return@withLock runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(
                context,
                YoutubeDL.UpdateChannel._NIGHTLY,
            )
            ytdlpUpdatedThisProcess = true
            downloaderPreferences.edit()
                .putLong(PREF_LAST_YTDLP_UPDATE, System.currentTimeMillis())
                .apply()
            val version = runCatching { YoutubeDL.getInstance().versionName(context) }.getOrNull()
            onProgress(
                0f,
                if (version.isNullOrBlank()) "yt-dlp ready" else "yt-dlp $version ready",
            )
        }.exceptionOrNull()
    }

    private fun compactYtDlpStatus(line: String, progress: Float): String {
        val lower = line.lowercase()
        return when {
            "[extractaudio]" in lower || "destination:" in lower && ".flac" in lower ->
                "Converting to FLAC…"
            "[metadata]" in lower || "adding metadata" in lower ->
                "Writing metadata…"
            "[download]" in lower || progress > 0f ->
                "Downloading best available audio…"
            else -> "Preparing audio…"
        }
    }

    private fun looksLikeYoutubeProtocolFailure(t: Throwable): Boolean {
        val text = buildString {
            append(t.message.orEmpty())
            append('\n')
            append(t.cause?.message.orEmpty())
        }.lowercase()
        return RETRYABLE_YOUTUBE_MARKERS.any(text::contains)
    }

    private fun friendlyYoutubeFailure(t: Throwable): HarmonyDownloadException {
        val technical = t.stackTraceToString().take(MAX_TECHNICAL_DETAILS)
        val text = technical.lowercase()
        val message = when {
            RETRYABLE_YOUTUBE_MARKERS.any(text::contains) ->
                "YouTube rejected this download after Harmony updated yt-dlp and retried. Try again later; YouTube may have changed its download protocol again."
            "sign in" in text || "login" in text || "age-restricted" in text ->
                "This video requires a YouTube session/login that Harmony does not have."
            "network" in text || "timed out" in text || "unable to connect" in text ->
                "Harmony couldn't reach YouTube. Check your connection and try again."
            else -> "The YouTube download failed. Tap Details for the technical log."
        }
        return HarmonyDownloadException(
            userMessage = message,
            technicalDetails = technical,
            cause = t,
        )
    }

    /**
     * Publish a completed engine download through Harmony's one shared storage path.
     * The source remains in app-private staging until MediaStore/SAF has accepted it.
     */
    suspend fun publishStagedFlac(tempFile: File, suggestedFileName: String): ConvertedFlac =
        withContext(Dispatchers.IO) {
            val requestedName = sanitizeFileName(suggestedFileName).let {
                if (it.endsWith(".flac", ignoreCase = true)) it else "$it.flac"
            }
            try {
                val destination = publishFlac(tempFile, requestedName)
                val size = context.contentResolver.openAssetFileDescriptor(destination, "r")
                    ?.use { it.length }
                    ?.takeIf { it >= 0L }
                    ?: tempFile.length()
                ConvertedFlac(destination, requestedName, size)
            } finally {
                runCatching { tempFile.delete() }
            }
        }

    /** Publish a locally encoded SpotiFLAC MP3 through the same download folder. */
    suspend fun publishStagedMp3(tempFile: File, suggestedFileName: String): ConvertedFlac =
        withContext(Dispatchers.IO) {
            val requestedName = sanitizeFileName(suggestedFileName).let {
                if (it.endsWith(".mp3", ignoreCase = true)) it else "$it.mp3"
            }
            try {
                val destination = publishAudio(tempFile, requestedName, "audio/mpeg", "MP3")
                val size = context.contentResolver.openAssetFileDescriptor(destination, "r")
                    ?.use { it.length }
                    ?.takeIf { it >= 0L }
                    ?: tempFile.length()
                ConvertedFlac(destination, requestedName, size)
            } finally {
                runCatching { tempFile.delete() }
            }
        }

    suspend fun validateStagedMp3(file: File): StagedAudioInfo = withContext(Dispatchers.IO) {
        val containerError = verifyMp3Container(file)
        if (containerError != null) {
            throw HarmonyDownloadException(
                userMessage = "The generated MP3 did not pass Harmony's MPEG container check.",
                technicalDetails = containerError,
            )
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0L }
                ?: throw HarmonyDownloadException("Android could not determine the generated MP3 duration.")
            StagedAudioInfo(
                codec = "MP3",
                bitDepth = null,
                sampleRateHz = null,
                durationMs = durationMs,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Validate an app-private staged FLAC before it is allowed into MediaStore/SAF.
     *
     * v1.6.8 deliberately does NOT use Android's OEM MediaExtractor MIME report as
     * the authority for a native FLAC file. Some device media parsers can expose a
     * valid fLaC stream with a generic/unknown audio MIME, which caused Harmony to
     * reject files that its own Media3/ExoPlayer playback stack can read.
     *
     * Instead we validate the FLAC container itself: marker, mandatory STREAMINFO,
     * plausible stream parameters, bounded metadata blocks, and the first audio
     * frame sync word. Duration is derived from STREAMINFO whenever totalSamples is
     * present; Android's metadata retriever is only a fallback for the legal
     * totalSamples=0 (unknown duration) case.
     */
    suspend fun validateStagedFlac(file: File): StagedAudioInfo = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L) {
            throw HarmonyDownloadException("The downloaded file is empty or missing.")
        }

        val structural = parseNativeFlacStructure(file)
        val durationMs = structural.durationMs ?: run {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: throw HarmonyDownloadException(
                        "The FLAC container is structurally valid, but its duration could not be determined.",
                    )
            } catch (t: HarmonyDownloadException) {
                throw t
            } catch (t: Throwable) {
                throw HarmonyDownloadException(
                    userMessage = "The FLAC container is structurally valid, but Android could not read its duration.",
                    technicalDetails = t.stackTraceToString().take(MAX_TECHNICAL_DETAILS),
                    cause = t,
                )
            } finally {
                runCatching { retriever.release() }
            }
        }

        StagedAudioInfo(
            codec = "FLAC",
            bitDepth = structural.bitDepth,
            sampleRateHz = structural.sampleRateHz,
            durationMs = durationMs,
        )
    }

    private data class NativeFlacStructure(
        val bitDepth: Int,
        val sampleRateHz: Int,
        val channels: Int,
        val totalSamples: Long,
        val durationMs: Long?,
    )

    /**
     * Minimal native FLAC parser used as a device-independent trust boundary.
     * This intentionally validates structure instead of trusting a filename or
     * Android codec MIME classification.
     */
    private fun parseNativeFlacStructure(file: File): NativeFlacStructure {
        val fileLength = file.length()
        if (fileLength < 44L) {
            throw HarmonyDownloadException("The downloaded FLAC is too small to contain STREAMINFO and audio frames.")
        }

        try {
            RandomAccessFile(file, "r").use { raf ->
                val signature = ByteArray(4)
                raf.readFully(signature)
                if (!signature.contentEquals(byteArrayOf(0x66, 0x4C, 0x61, 0x43))) {
                    throw HarmonyDownloadException("The downloaded file is not a valid native FLAC container.")
                }

                var firstBlock = true
                var sawLastMetadataBlock = false
                var sampleRateHz = 0
                var channels = 0
                var bitDepth = 0
                var totalSamples = 0L

                while (!sawLastMetadataBlock) {
                    if (raf.filePointer + 4L > fileLength) {
                        throw HarmonyDownloadException("The FLAC metadata header is truncated.")
                    }

                    val blockHeader = raf.readUnsignedByte()
                    sawLastMetadataBlock = (blockHeader and 0x80) != 0
                    val blockType = blockHeader and 0x7F
                    val blockLength =
                        (raf.readUnsignedByte() shl 16) or
                            (raf.readUnsignedByte() shl 8) or
                            raf.readUnsignedByte()

                    if (blockType == 127) {
                        throw HarmonyDownloadException("The FLAC contains an invalid metadata block type.")
                    }
                    if (raf.filePointer + blockLength.toLong() > fileLength) {
                        throw HarmonyDownloadException("The FLAC metadata block extends beyond the end of the file.")
                    }

                    if (firstBlock) {
                        if (blockType != 0 || blockLength != 34) {
                            throw HarmonyDownloadException("The FLAC does not begin with the mandatory 34-byte STREAMINFO block.")
                        }

                        val streamInfo = ByteArray(34)
                        raf.readFully(streamInfo)

                        fun u16be(index: Int): Int =
                            ((streamInfo[index].toInt() and 0xFF) shl 8) or
                                (streamInfo[index + 1].toInt() and 0xFF)
                        fun u24be(index: Int): Int =
                            ((streamInfo[index].toInt() and 0xFF) shl 16) or
                                ((streamInfo[index + 1].toInt() and 0xFF) shl 8) or
                                (streamInfo[index + 2].toInt() and 0xFF)

                        val minBlockSize = u16be(0)
                        val maxBlockSize = u16be(2)
                        val minFrameSize = u24be(4)
                        val maxFrameSize = u24be(7)
                        val packed12 = streamInfo[12].toInt() and 0xFF
                        val packed13 = streamInfo[13].toInt() and 0xFF

                        sampleRateHz =
                            ((streamInfo[10].toInt() and 0xFF) shl 12) or
                                ((streamInfo[11].toInt() and 0xFF) shl 4) or
                                ((packed12 and 0xF0) ushr 4)
                        channels = ((packed12 and 0x0E) ushr 1) + 1
                        bitDepth = (((packed12 and 0x01) shl 4) or ((packed13 and 0xF0) ushr 4)) + 1
                        totalSamples =
                            ((packed13 and 0x0F).toLong() shl 32) or
                                ((streamInfo[14].toLong() and 0xFF) shl 24) or
                                ((streamInfo[15].toLong() and 0xFF) shl 16) or
                                ((streamInfo[16].toLong() and 0xFF) shl 8) or
                                (streamInfo[17].toLong() and 0xFF)

                        if (minBlockSize !in 16..65_535 || maxBlockSize !in 16..65_535 || minBlockSize > maxBlockSize) {
                            throw HarmonyDownloadException("The FLAC STREAMINFO block sizes are invalid.")
                        }
                        if (minFrameSize != 0 && maxFrameSize != 0 && minFrameSize > maxFrameSize) {
                            throw HarmonyDownloadException("The FLAC STREAMINFO frame sizes are invalid.")
                        }
                        if (sampleRateHz !in 1..655_350) {
                            throw HarmonyDownloadException("The FLAC STREAMINFO sample rate is invalid: $sampleRateHz Hz.")
                        }
                        if (channels !in 1..8) {
                            throw HarmonyDownloadException("The FLAC STREAMINFO channel count is invalid: $channels.")
                        }
                        if (bitDepth !in 4..32) {
                            throw HarmonyDownloadException("The FLAC STREAMINFO bit depth is invalid: $bitDepth-bit.")
                        }
                    } else {
                        raf.seek(raf.filePointer + blockLength.toLong())
                    }

                    firstBlock = false
                }

                // A native FLAC metadata chain must be followed by at least one
                // audio frame. Check the fixed 14-bit FLAC sync code plus the
                // reserved bit, while allowing either blocking strategy bit.
                if (raf.filePointer + 2L > fileLength) {
                    throw HarmonyDownloadException("The FLAC contains metadata but no audio frames.")
                }
                val frame0 = raf.readUnsignedByte()
                val frame1 = raf.readUnsignedByte()
                if (frame0 != 0xFF || (frame1 and 0xFE) != 0xF8) {
                    throw HarmonyDownloadException("The FLAC audio frame sync marker is invalid.")
                }

                val durationMs = if (totalSamples > 0L) {
                    ((totalSamples * 1000L) / sampleRateHz.toLong()).takeIf { it > 0L }
                } else {
                    null
                }

                return NativeFlacStructure(
                    bitDepth = bitDepth,
                    sampleRateHz = sampleRateHz,
                    channels = channels,
                    totalSamples = totalSamples,
                    durationMs = durationMs,
                )
            }
        } catch (t: HarmonyDownloadException) {
            throw t
        } catch (t: Throwable) {
            throw HarmonyDownloadException(
                userMessage = "Harmony could not parse the downloaded native FLAC container.",
                technicalDetails = t.stackTraceToString().take(MAX_TECHNICAL_DETAILS),
                cause = t,
            )
        }
    }

    /**
     * Final identity guard for SpotiFLAC. Container validity alone is not enough:
     * a provider can return a perfectly valid audio file for the wrong song. Compare
     * embedded title/artist and duration with the metadata result explicitly
     * selected by the user before the file is published to the library.
     */
    suspend fun validateSpotiFlacIdentity(
        file: File,
        expected: IdentifiedTrack,
        validatedDurationMs: Long,
    ) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            // Embedded title/artist are a useful identity signal, but they are not
            // allowed to make a structurally valid audio file fail just because an
            // OEM metadata parser cannot expose Vorbis comments on a given device.
            val embeddedIdentity = runCatching {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim() to
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim()
            }.getOrElse { "" to "" }
            val actualTitle = embeddedIdentity.first
            val actualArtist = embeddedIdentity.second

            if (actualTitle.isNotBlank() && expected.title.isNotBlank()) {
                val titleScore = fuzzyTextScore(expected.title, actualTitle)
                if (titleScore < 68) {
                    throw HarmonyDownloadException(
                        userMessage = "The provider returned a different track title. Harmony refused to import the audio file.",
                        technicalDetails = "Expected title='${expected.title}', actual title='$actualTitle', similarity=$titleScore%",
                    )
                }
            }

            if (actualArtist.isNotBlank() && expected.artist.isNotBlank()) {
                val artistScore = fuzzyTextScore(expected.artist, actualArtist)
                if (artistScore < 52) {
                    throw HarmonyDownloadException(
                        userMessage = "The provider returned a different artist. Harmony refused to import the audio file.",
                        technicalDetails = "Expected artist='${expected.artist}', actual artist='$actualArtist', similarity=$artistScore%",
                    )
                }
            }

            if (expected.durationMs > 0L && validatedDurationMs > 0L) {
                val difference = kotlin.math.abs(expected.durationMs - validatedDurationMs)
                val tolerance = maxOf(12_000L, (expected.durationMs * 8L) / 100L)
                if (difference > tolerance) {
                    throw HarmonyDownloadException(
                        userMessage = "The downloaded audio duration does not match the selected track. Harmony refused to import it.",
                        technicalDetails = "Expected duration=${expected.durationMs}ms, actual=${validatedDurationMs}ms, tolerance=${tolerance}ms",
                    )
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Backwards-compatible Soulseek entry point; network logic stays untouched. */
    suspend fun publishSoulseekFlac(downloaded: SoulseekDownloadedFile): ConvertedFlac =
        publishStagedFlac(downloaded.tempFile, downloaded.suggestedFileName)

    private fun publishAudio(source: File, requestedName: String, mimeType: String, formatLabel: String): Uri {
        val customFolder = selectedDownloadFolderUri()
        return if (customFolder == null) {
            publishAudioToMediaStore(source, requestedName, mimeType, formatLabel)
        } else {
            publishAudioToTree(source, requestedName, mimeType, formatLabel, customFolder)
        }
    }

    private fun publishAudioToTree(
        source: File,
        requestedName: String,
        mimeType: String,
        formatLabel: String,
        treeUri: Uri,
    ): Uri {
        val resolver = context.contentResolver
        val hasWritePermission = resolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isWritePermission
        }
        if (!hasWritePermission) {
            throw HarmonyDownloadException(
                userMessage = "The selected download folder is no longer available. Choose the folder again and retry.",
                technicalDetails = "Persisted SAF write permission is missing for $treeUri",
            )
        }
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val destination = DocumentsContract.createDocument(
            resolver,
            parentDocumentUri,
            mimeType,
            requestedName,
        ) ?: throw HarmonyDownloadException(
            userMessage = "Android couldn't create the $formatLabel file in the selected folder.",
            technicalDetails = "DocumentsContract.createDocument returned null for $treeUri",
        )
        try {
            resolver.openOutputStream(destination, "w")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Android couldn't open the selected folder for writing.")
            return destination
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw HarmonyDownloadException(
                userMessage = "Harmony couldn't save the $formatLabel file in the selected folder.",
                technicalDetails = t.stackTraceToString().take(MAX_TECHNICAL_DETAILS),
                cause = t,
            )
        }
    }

    private fun publishAudioToMediaStore(
        source: File,
        requestedName: String,
        mimeType: String,
        formatLabel: String,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, requestedName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Harmony/Downloads")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Android couldn't create the $formatLabel file in MediaStore.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Android couldn't open the $formatLabel destination.")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0) { "Android could not finish saving this file." }
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun verifyMp3Container(file: File): String? {
        if (!file.isFile || file.length() < 128L) return "file is too small for MP3"
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val prefix = ByteArray(minOf(10L, file.length()).toInt())
                val prefixRead = raf.read(prefix)
                if (prefixRead <= 0) return@use "could not read MP3 header"

                var audioStart = 0L
                if (
                    prefixRead >= 10 && prefix[0] == 'I'.code.toByte() &&
                    prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte()
                ) {
                    val tagSize = ((prefix[6].toInt() and 0x7F) shl 21) or
                        ((prefix[7].toInt() and 0x7F) shl 14) or
                        ((prefix[8].toInt() and 0x7F) shl 7) or
                        (prefix[9].toInt() and 0x7F)
                    audioStart = 10L + tagSize.toLong()
                    if (audioStart >= file.length()) return@use "ID3 tag claims to be larger than the file"
                }

                raf.seek(audioStart)
                val probeSize = minOf(file.length() - audioStart, 64L * 1024L).toInt()
                if (probeSize < 4) return@use "MP3 audio payload is too small"
                val bytes = ByteArray(probeSize)
                val read = raf.read(bytes)
                if (read <= 0) return@use "could not read MP3 audio payload"

                var offset = 0
                var scanned = 0
                while (offset + 4 <= read && scanned < 8_192) {
                    val b0 = bytes[offset].toInt() and 0xFF
                    val b1 = bytes[offset + 1].toInt() and 0xFF
                    val b2 = bytes[offset + 2].toInt() and 0xFF
                    val valid = b0 == 0xFF && b1 and 0xE0 == 0xE0 &&
                        b1 and 0x18 != 0x08 && b1 and 0x06 != 0x00 &&
                        ((b2 and 0xF0) ushr 4).let { it != 0x00 && it != 0x0F } &&
                        b2 and 0x0C != 0x0C
                    if (valid) return@use null
                    offset++
                    scanned++
                }
                "no legal MPEG audio frame was found"
            }
        }.getOrElse { "could not validate MP3 container: ${it.message ?: it::class.java.simpleName}" }
    }

    private fun publishFlac(source: File, requestedName: String): Uri {
        val customFolder = selectedDownloadFolderUri()
        return if (customFolder == null) {
            publishFlacToMediaStore(source, requestedName)
        } else {
            publishFlacToTree(source, requestedName, customFolder)
        }
    }

    private fun publishFlacToTree(source: File, requestedName: String, treeUri: Uri): Uri {
        val resolver = context.contentResolver
        val hasWritePermission = resolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isWritePermission
        }
        if (!hasWritePermission) {
            throw HarmonyDownloadException(
                userMessage = "The selected download folder is no longer available. Choose the folder again and retry.",
                technicalDetails = "Persisted SAF write permission is missing for $treeUri",
            )
        }

        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val destination = DocumentsContract.createDocument(
            resolver,
            parentDocumentUri,
            "audio/flac",
            requestedName,
        ) ?: throw HarmonyDownloadException(
            userMessage = "Android couldn't create the FLAC in the selected folder.",
            technicalDetails = "DocumentsContract.createDocument returned null for $treeUri",
        )

        try {
            resolver.openOutputStream(destination, "w")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Android couldn't open the selected folder for writing.")
            return destination
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw HarmonyDownloadException(
                userMessage = "Harmony couldn't save the FLAC in the selected folder.",
                technicalDetails = t.stackTraceToString().take(MAX_TECHNICAL_DETAILS),
                cause = t,
            )
        }
    }

    private fun publishFlacToMediaStore(source: File, requestedName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, requestedName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/Harmony/Downloads",
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Android couldn't create the FLAC in MediaStore.")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Android couldn't open the FLAC destination.")

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0) { "Android could not finish saving this file." }
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    suspend fun downloadDirectFlac(
        url: String,
        preferredBaseName: String,
        onProgress: (progressPercent: Float, statusLine: String) -> Unit,
    ): ConvertedFlac = withContext(Dispatchers.IO) {
        val fileName = sanitizeFileName(preferredBaseName).let {
            if (it.endsWith(".flac", ignoreCase = true)) it else "$it.flac"
        }
        val tempDir = File(context.cacheDir, "harmony-direct/${UUID.randomUUID()}").apply { mkdirs() }
        val tempFile = File(tempDir, fileName)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "audio/flac, audio/x-flac, application/octet-stream, */*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Harmony/1.0 Android")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalArgumentException("The server rejected the FLAC download (HTTP $code).")
            }
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                tempFile.outputStream().buffered().use { output ->
                    val header = ByteArray(4)
                    var headerRead = 0
                    while (headerRead < header.size) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(header, headerRead, header.size - headerRead)
                        if (n <= 0) break
                        headerRead += n
                    }
                    if (headerRead < 4 ||
                        header[0] != 'f'.code.toByte() ||
                        header[1] != 'L'.code.toByte() ||
                        header[2] != 'a'.code.toByte() ||
                        header[3] != 'C'.code.toByte()
                    ) {
                        throw IllegalArgumentException("That URL did not return a real FLAC file.")
                    }
                    output.write(header)
                    var downloaded = headerRead.toLong()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        val percent = if (total > 0L) {
                            (downloaded * 98f / total).coerceIn(1f, 98f)
                        } else {
                            50f
                        }
                        onProgress(percent, "Downloading FLAC…")
                    }
                    if (total > 0 && downloaded != total) throw java.io.IOException("The FLAC download was incomplete. Try again.")
                }
            }

            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            validateStagedFlac(tempFile)
            onProgress(99f, "Saving FLAC to selected folder…")
            val destination = publishFlac(tempFile, fileName)
            val size = context.contentResolver.openAssetFileDescriptor(destination, "r")
                ?.use { it.length }
                ?.takeIf { it >= 0L }
                ?: tempFile.length()
            onProgress(100f, "FLAC download complete")
            ConvertedFlac(destination, fileName, size)
        } finally {
            connection.disconnect()
            tempDir.deleteRecursively()
        }
    }

    /**
     * Advanced path for an already-existing direct FLAC URL. Verifies the
     * fLaC marker first so HTML/error pages aren't saved as .flac files.
     */
    suspend fun validateFlacUrl(rawUrl: String): ValidatedFlac = withContext(Dispatchers.IO) {
        val uri = Uri.parse(rawUrl.trim())
        require(uri.scheme == "https" || uri.scheme == "http") {
            "The download link must start with https:// or http://."
        }

        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-31")
            setRequestProperty("Accept", "audio/flac, audio/x-flac, application/octet-stream, */*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Harmony/1.0 Android")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalArgumentException("The server rejected the FLAC link (HTTP $code).")
            }

            val header = ByteArray(4)
            BufferedInputStream(connection.inputStream).use { input ->
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 4 ||
                    header[0] != 'f'.code.toByte() ||
                    header[1] != 'L'.code.toByte() ||
                    header[2] != 'a'.code.toByte() ||
                    header[3] != 'C'.code.toByte()
                ) {
                    throw IllegalArgumentException("That URL did not return a real FLAC file.")
                }
            }

            ValidatedFlac(
                finalUrl = connection.url.toString(),
                suggestedFileName = fileNameFromHeaders(connection),
                contentType = connection.contentType,
            )
        } finally {
            connection.disconnect()
        }
    }

    fun enqueueFlac(url: String, preferredBaseName: String): Long {
        val fileName = sanitizeFileName(preferredBaseName).let {
            if (it.endsWith(".flac", ignoreCase = true)) it else "$it.flac"
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName.removeSuffix(".flac"))
            .setDescription("Downloading lossless audio to Harmony")
            .setMimeType("audio/flac")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MUSIC,
                "Harmony/Downloads/$fileName",
            )
            .apply {
                @Suppress("DEPRECATION")
                allowScanningByMediaScanner()
            }
        return downloadManager.enqueue(request)
    }

    fun queryDownload(id: Long): HarmonyDownloadProgress? {
        val query = DownloadManager.Query().setFilterById(id)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null

            fun long(column: String): Long {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0) cursor.getLong(index) else 0L
            }

            val status = when (long(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_PENDING -> HarmonyDownloadStatus.PENDING
                DownloadManager.STATUS_RUNNING -> HarmonyDownloadStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> HarmonyDownloadStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> HarmonyDownloadStatus.SUCCESSFUL
                DownloadManager.STATUS_FAILED -> HarmonyDownloadStatus.FAILED
                else -> HarmonyDownloadStatus.PENDING
            }

            return HarmonyDownloadProgress(
                id = id,
                status = status,
                downloadedBytes = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                totalBytes = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                reason = long(DownloadManager.COLUMN_REASON).toInt(),
                localUri = downloadManager.getUriForDownloadedFile(id),
            )
        }
        return null
    }

    fun displayNameForDownloadedUri(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return null
    }

    private fun looksLikeYouTubeUrl(value: String): Boolean {
        val host = runCatching { Uri.parse(value).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")
    }

    private fun parseFreeText(text: String): IdentifiedTrack {
        val parts = text.split(" - ", limit = 2)
        return if (parts.size == 2) {
            IdentifiedTrack(
                artist = parts[0].trim(),
                title = parts[1].trim(),
                sourceTitle = text,
            )
        } else {
            IdentifiedTrack(artist = "", title = text.trim(), sourceTitle = text)
        }
    }

    private fun parseYouTubeMetadata(
        sourceTitle: String,
        author: String,
        thumbnailUrl: String?,
    ): IdentifiedTrack {
        val topicArtist = author.removeSuffix(" - Topic").trim()
        val cleanedTitle = cleanVideoDecorations(sourceTitle)
        val split = cleanedTitle.split(" - ", limit = 2)

        val (artist, title) = when {
            author.endsWith(" - Topic") -> topicArtist to cleanedTitle
            split.size == 2 -> split[0].trim() to split[1].trim()
            topicArtist.isNotBlank() -> topicArtist to cleanedTitle
            else -> "" to cleanedTitle
        }

        return IdentifiedTrack(
            artist = artist,
            title = title.ifBlank { sourceTitle },
            sourceTitle = sourceTitle,
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun cleanVideoDecorations(value: String): String {
        var result = value.trim()
        val decoration = Regex(
            pattern = "\\s*[\\[(](?:official\\s+(?:music\\s+)?video|official\\s+audio|audio|lyrics?|lyric\\s+video|visuali[sz]er|music\\s+video)[^\\])]*[\\])]\\s*",
            option = RegexOption.IGNORE_CASE,
        )
        result = result.replace(decoration, " ").replace(Regex("\\s{2,}"), " ").trim()
        return result
    }

    private fun fileNameFromHeaders(connection: HttpURLConnection): String? {
        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        val fromDisposition = Regex("filename\\*?=(?:UTF-8''|\\\")?([^;\\\"]+)", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { Uri.decode(it.trim()) }
            ?.takeIf { it.isNotBlank() }
        if (fromDisposition != null) return fromDisposition
        return Uri.parse(connection.url.toString()).lastPathSegment
            ?.takeIf { it.endsWith(".flac", ignoreCase = true) }
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .trim('.')
        .take(180)
        .ifBlank { "Harmony download" }

    private companion object {
        const val SPOTIFLAC_MIN_QUERY_CHARS = 3
        const val SPOTIFLAC_MAX_QUERY_LENGTH = 160
        const val PREF_LAST_YTDLP_UPDATE = "last_ytdlp_nightly_update_ms"
        const val PREF_DOWNLOAD_FOLDER_URI = "download_folder_uri"
        const val PREF_DOWNLOAD_FOLDER_LABEL = "download_folder_label"
        const val PREF_LAST_DOWNLOAD_SOURCE = "last_download_source"
        const val YTDLP_UPDATE_INTERVAL_MS = 12L * 60L * 60L * 1000L
        const val MAX_TECHNICAL_DETAILS = 8_000

        val RETRYABLE_YOUTUBE_MARKERS = listOf(
            "http error 403",
            "403 forbidden",
            "signature solving failed",
            "challenge solving failed",
            "error solving",
            "quickjs",
            "sabr",
            "unable to download video data",
            "player response",
        )
    }

}
