package com.harmony.feature.downloads

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.MaterialTheme
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.harmony.core.ui.component.GlassCard
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.animatedEditorialPalette
import com.harmony.core.ui.component.LocalFloatingChromeHeight
import com.harmony.domain.analysis.model.SpectralReport

@Composable
fun DownloadsScreen(
    onOpenAlbum: (String) -> Unit = {},
    onOpenRecognize: () -> Unit = {},
    /** A search typed on Add: run once, with the preferred source, when the page opens. */
    initialQuery: String? = null,
    onBack: (() -> Unit)? = null,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var handedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(initialQuery) {
        val q = initialQuery?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (handedQuery == q) return@LaunchedEffect
        handedQuery = q
        // A pasted YouTube link goes to the converter, anything else to the
        // preferred source (docs/redesign/05 §2.9).
        if (looksLikeYouTube(q)) viewModel.selectPreferredDownloadSource(DownloadSource.YTCONVERTER)
        viewModel.setQuery(q)
        viewModel.searchSelectedSource()
    }
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.selectDownloadFolder(uri)
    }
    // Sharing a folder needs OpenDocumentTree, which grants recursive read on
    // the selected subtree; the indexer walks audio files only, bounded in
    // count and depth, and the Soulseek panel states the trade-off.
    val sharedFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setSharedFolder(uri)
    }

    LaunchedEffect(state.preferredDownloadSource) {
        if (state.preferredDownloadSource == DownloadSource.SPOTIFLAC) viewModel.prepareSpotiFlacVerification()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.downloadsHostResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Songs, Albums or a YouTube link. The link mode arms the converter; leaving
    // it goes back to the source the listener had before.
    var albumsMode by rememberSaveable { mutableStateOf(false) }
    var lastSongSource by rememberSaveable { mutableStateOf(DownloadSource.SPOTIFLAC) }
    if (state.preferredDownloadSource != DownloadSource.YTCONVERTER) lastSongSource = state.preferredDownloadSource
    val mode = when {
        state.preferredDownloadSource == DownloadSource.YTCONVERTER -> GetMusicMode.YouTube
        albumsMode -> GetMusicMode.Albums
        else -> GetMusicMode.Songs
    }
    var sourceSheet by rememberSaveable { mutableStateOf(false) }
    var pickedSource by rememberSaveable { mutableStateOf(state.preferredDownloadSource) }

    val actions = GetMusicActions(
        onQueryChange = viewModel::setQuery,
        onSearch = viewModel::searchSelectedSource,
        onSelect = viewModel::selectSpotiFlacSearchResult,
        onGet = viewModel::downloadSpotiFlacSearchResult,
        onGetIdentified = viewModel::requestDownloadForIdentifiedTrack,
        onCancelSpotiFlac = viewModel::cancelSpotiFlacDownload,
        onFormat = viewModel::setSpotiFlacOutputFormat,
        onOpenVerification = { url ->
            if (openProviderVerificationBrowser(context, url)) viewModel.providerVerificationOpened()
            else viewModel.providerVerificationLaunchFailed()
        },
        onRefreshVerification = viewModel::refreshProviderVerification,
        onCheckAndRetry = viewModel::checkProviderVerificationAndRetry,
        onRetry = viewModel::retryFailedEngineDownload,
        onOtherSource = viewModel::requestAlternativeDownloadSource,
        onToggleDetails = viewModel::toggleErrorDetails,
        onRetryYouTube = viewModel::retryYouTubeDownload,
        onConvertYouTube = viewModel::downloadYouTubeAsFlac,
        onRecognize = onOpenRecognize,
        onOpenSourceSheet = { pickedSource = lastSongSource; sourceSheet = true },
    )
    val palette = com.harmony.core.ui.component.catalogPalette()

    Column(
        Modifier
            .fillMaxSize()
            .background(com.harmony.core.ui.theme.Room.ground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalFloatingChromeHeight.current + 16.dp),
    ) {
        com.harmony.core.ui.room.RoomTopBar(
            androidx.compose.ui.res.stringResource(R.string.get_title),
            onBack = onBack,
        )
        GetMusicBody(
            state = state,
            mode = mode,
            onMode = { next ->
                when (next) {
                    GetMusicMode.YouTube -> viewModel.selectPreferredDownloadSource(DownloadSource.YTCONVERTER)
                    else -> {
                        albumsMode = next == GetMusicMode.Albums
                        if (state.preferredDownloadSource == DownloadSource.YTCONVERTER) {
                            viewModel.selectPreferredDownloadSource(lastSongSource)
                        }
                    }
                }
            },
            actions = actions,
            soulseek = {
                SoulseekSongs(
                    state, actions,
                    SoulseekActions(
                        onUsername = viewModel::setSoulseekUsername,
                        onPassword = viewModel::setSoulseekPassword,
                        onConnect = viewModel::connectSoulseek,
                        onDisconnect = viewModel::disconnectSoulseek,
                        onSearchMode = viewModel::setSoulseekSearchMode,
                        onFormat = viewModel::setSoulseekFormatPreference,
                        onOnlyFreeSlots = viewModel::setSoulseekOnlyFreeSlots,
                        onGet = viewModel::downloadSoulseek,
                        onGetBest = viewModel::downloadBestSoulseek,
                        onCancel = viewModel::cancelSoulseekDownload,
                        onPickDownloadFolder = { folderPicker.launch(null) },
                        onUseDefaultFolder = viewModel::useDefaultDownloadFolder,
                        onPickShare = { sharedFolderPicker.launch(null) },
                        onRefreshShare = viewModel::refreshSharedFolder,
                        onClearShare = viewModel::clearSharedFolder,
                    ),
                )
            },
            albums = {
                val soulseekArmed = state.preferredDownloadSource == DownloadSource.SOULSEEK
                val soulseekReady = state.soulseekConnection.status == SoulseekConnectionStatus.CONNECTED
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (soulseekArmed && !soulseekReady) {
                        SoulseekSignIn(state, SoulseekActions(
                            onUsername = viewModel::setSoulseekUsername,
                            onPassword = viewModel::setSoulseekPassword,
                            onConnect = viewModel::connectSoulseek,
                        ))
                    } else {
                        AlbumSearchSection(
                            source = state.preferredDownloadSource,
                            format = if (soulseekArmed) {
                                if (state.soulseekFormatPreference.preferredExtension == "mp3") SpotiFlacOutputFormat.MP3_320
                                else SpotiFlacOutputFormat.FLAC_LOSSLESS
                            } else state.spotiFlacOutputFormat,
                            onOpenAlbum = onOpenAlbum,
                        )
                    }
                    AlbumDownloadsShelf(onOpenAlbum)
                }
            },
        )
    }

    if (sourceSheet) {
        SourceSheet(
            selected = pickedSource,
            onSelect = { pickedSource = it },
            onConfirm = { viewModel.selectPreferredDownloadSource(pickedSource); sourceSheet = false },
            onDismiss = { sourceSheet = false },
        )
    }
    // A failed download asked to try the other engine: the same sheet, committing on confirm.
    if (state.showDownloadSourceSelector) {
        SourceSheet(
            selected = state.selectedDownloadSource,
            onSelect = viewModel::selectPendingDownloadSource,
            onConfirm = viewModel::confirmDownloadSource,
            onDismiss = viewModel::dismissDownloadSourceSelector,
        )
    }
}

/** A YouTube or YouTube Music address, so Add can hand it to the converter. */
internal fun looksLikeYouTube(text: String): Boolean {
    val t = text.trim().lowercase()
    return t.startsWith("http") && (t.contains("youtube.com/") || t.contains("youtu.be/"))
}

internal fun openProviderVerificationBrowser(context: Context, authUrl: String): Boolean {
    val uri = runCatching { Uri.parse(authUrl) }.getOrNull() ?: return false
    if (!uri.scheme.orEmpty().equals("https", ignoreCase = true)) return false
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
