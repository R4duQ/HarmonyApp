package com.harmony.feature.downloads

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.animatedEditorialPalette
import com.harmony.domain.analysis.model.SpectralReport

@Composable
fun DownloadsScreen(onOpenAlbum: (String) -> Unit = {}, viewModel: DownloadsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val identity = state.preferredDownloadSource.identity()
    val palette = animatedEditorialPalette(identity.palette)

    // Soulseek is peer-to-peer and keeps its login-gated layout. SpotiFLAC
    // checks its provider session in parallel, while public metadata search is
    // always available; only an actual provider download needs verification.
    val soulseekArmed = state.preferredDownloadSource == DownloadSource.SOULSEEK
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.selectDownloadFolder(uri)
    }
    // Sharing a folder needs OpenDocumentTree, which grants recursive read on
    // the selected subtree. That is a wider grant than the v1.7.4 single-file
    // picker gave, and it is unavoidable for folder sharing — the indexer
    // limits the blast radius by walking audio files only, bounded in count
    // and depth. The UI states the trade-off rather than hiding it.
    val sharedFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.setSharedFolder(uri)
    }

    LaunchedEffect(state.preferredDownloadSource) {
        if (state.preferredDownloadSource == DownloadSource.SPOTIFLAC) {
            viewModel.prepareSpotiFlacVerification()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.downloadsHostResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Downloads",
            fontSize = 40.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
            color = palette.ink,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp),
        )
        Text(
            "Find a track, then explicitly choose SpotiFLAC or Soulseek before downloading. Harmony never switches engines automatically, and both save through the same local-file pipeline.",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = palette.muted,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp),
        )

        EditorialSectionLabel(
            "Download method",
            palette,
            Modifier.padding(start = 22.dp, bottom = 10.dp),
        )
        DownloadMethodCard(
            selected = state.preferredDownloadSource,
            onSelect = viewModel::selectPreferredDownloadSource,
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        AlbumDownloadsShelf(onOpenAlbum)

        if (soulseekArmed) {
            SoulseekDownloadsLayout(
                state = state,
                palette = palette,
                onUsernameChange = viewModel::setSoulseekUsername,
                onPasswordChange = viewModel::setSoulseekPassword,
                onConnect = viewModel::connectSoulseek,
                onDisconnect = viewModel::disconnectSoulseek,
                onQueryChange = viewModel::setQuery,
                onSearchModeChange = viewModel::setSoulseekSearchMode,
                onFormatPreferenceChange = viewModel::setSoulseekFormatPreference,
                onOnlyFreeSlotsChange = viewModel::setSoulseekOnlyFreeSlots,
                onSearch = viewModel::searchSelectedSource,
                onDownload = viewModel::downloadSoulseek,
                onDownloadBest = viewModel::downloadBestSoulseek,
                onCancelDownload = viewModel::cancelSoulseekDownload,
                onPickDownloadFolder = { folderPicker.launch(null) },
                onUseDefaultDownloadFolder = viewModel::useDefaultDownloadFolder,
                onPickUploadFolder = { sharedFolderPicker.launch(null) },
                onRefreshUploadFolder = viewModel::refreshSharedFolder,
                onClearUploadFolder = viewModel::clearSharedFolder,
            )
        }

        if (!soulseekArmed) {
            SpotiFlacDownloadsLayout(
                state = state,
                palette = palette,
                context = context,
                onQueryChange = viewModel::setQuery,
                onSearch = viewModel::searchSelectedSource,
                onSelect = viewModel::selectSpotiFlacSearchResult,
                onOutputFormatChange = viewModel::setSpotiFlacOutputFormat,
                onDownload = viewModel::requestDownloadForIdentifiedTrack,
                onCancelDownload = viewModel::cancelSpotiFlacDownload,
                onPrepareVerification = viewModel::prepareSpotiFlacVerification,
                onVerificationOpened = viewModel::providerVerificationOpened,
                onVerificationLaunchFailed = viewModel::providerVerificationLaunchFailed,
                onRefreshVerification = viewModel::refreshProviderVerification,
                onCheckVerificationAndRetry = viewModel::checkProviderVerificationAndRetry,
                onToggleErrorDetails = viewModel::toggleErrorDetails,
            )
        }

        if (soulseekArmed) state.message?.let { message ->
            Text(
                message,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            )
        }
        if (soulseekArmed) state.error?.let { error ->
            Spacer(Modifier.height(14.dp))
            EditorialCard(
                palette = palette,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Download failed",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB3261E),
                    )
                    Text(
                        error,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    val verificationChallenge = state.spotiFlacVerificationChallenge
                    val verificationUrl = verificationChallenge?.verificationUrl ?: state.spotiFlacVerificationUrl
                    val verificationProvider = state.spotiFlacVerificationProvider
                        ?.substringBefore('-')
                        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        ?: "Provider"
                    val hasProviderVerification = state.failedDownloadSource == DownloadSource.SPOTIFLAC &&
                        state.spotiFlacVerificationProvider != null
                    if (hasProviderVerification) {
                        Text(
                            when {
                                verificationChallenge?.authenticated == true ->
                                    "$verificationProvider now reports an authenticated extension session."
                                verificationUrl != null ->
                                    "$verificationProvider requires an interactive verification step. Harmony fetched the challenge from SpotiFLAC's pending-auth API; it will not solve or bypass it."
                                verificationChallenge?.pending == true ->
                                    "$verificationProvider has a pending browser verification request. Refresh the challenge if the authorization page is not available yet."
                                else ->
                                    "$verificationProvider requires verification. Refresh the challenge to ask SpotiFLAC for the current pending-auth state."
                            },
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        verificationChallenge?.instructions
                            ?.takeIf { it.isNotBlank() }
                            ?.let { instructions ->
                                Text(
                                    instructions,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = palette.muted,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }

                        if (verificationUrl != null) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    enabled = !state.isCheckingSpotiFlacVerification,
                                    onClick = {
                                        if (openProviderVerificationBrowser(context, verificationUrl)) {
                                            viewModel.providerVerificationOpened()
                                        } else {
                                            viewModel.providerVerificationLaunchFailed()
                                        }
                                    },
                                ) {
                                    Text("Verify $verificationProvider")
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                enabled = !state.isCheckingSpotiFlacVerification,
                                onClick = viewModel::refreshProviderVerification,
                            ) {
                                Text("Refresh challenge")
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (state.failedDownloadSource != null) {
                            TextButton(
                                enabled = !state.isCheckingSpotiFlacVerification,
                                onClick = viewModel::retryFailedEngineDownload,
                            ) {
                                Text(
                                    if (state.failedDownloadSource == DownloadSource.SPOTIFLAC &&
                                        state.spotiFlacVerificationProvider != null
                                    ) {
                                        if (state.isCheckingSpotiFlacVerification) "Checking…" else "Check & retry"
                                    } else {
                                        "Try Again"
                                    },
                                )
                            }
                            TextButton(onClick = viewModel::requestAlternativeDownloadSource) {
                                Text(
                                    state.failedDownloadSource
                                        ?.alternative()
                                        ?.displayName
                                        ?.let { "Use $it" }
                                        ?: "Use the other source",
                                )
                            }
                        }
                        if (state.errorDetails != null) {
                            TextButton(onClick = viewModel::toggleErrorDetails) {
                                Text(if (state.showErrorDetails) "Hide details" else "Details")
                            }
                        }
                        if (state.canRetryYouTube) {
                            EditorialPill(
                                text = "Retry",
                                icon = Icons.Rounded.Download,
                                onClick = viewModel::retryYouTubeDownload,
                                palette = palette,
                            )
                        }
                    }
                    if (state.showErrorDetails) {
                        Text(
                            state.errorDetails.orEmpty(),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = Color(0xFFB3261E),
                            modifier = Modifier.padding(top = 8.dp),
                            maxLines = 18,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (soulseekArmed && (state.isIdentifying || state.isValidating)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp))
            }
        }

        if (state.downloadHistory.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            EditorialSectionLabel(
                "Recent downloads",
                palette,
                Modifier.padding(start = 22.dp, bottom = 8.dp),
            )
            state.downloadHistory.take(8).forEach { item ->
                DownloadHistoryCard(item, palette)
                Spacer(Modifier.height(8.dp))
            }
        }

        Text(
            "Use downloads only for files you are permitted to obtain. Soulseek is an unencrypted peer-to-peer protocol, and SpotiFLAC providers are third-party extensions. Harmony does not silently switch sources or bypass paid-access controls.",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = palette.muted,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
        )
    }

    if (state.showDownloadSourceSelector) {
        // The dialog has its OWN selection (selectedDownloadSource), separate
        // from the screen's armed source (preferredDownloadSource) — that's by
        // design, since confirming here is what commits it. But the selector
        // was painted with the outer screen `palette`, which tracks the ARMED
        // source, not the dialog's own pick. Tap "Soulseek" here while
        // SpotiFLAC is armed and the segment filled in rose instead of teal —
        // the exact "selection looks wrong" bug. The dialog needs its own
        // palette, derived from its own selection.
        val dialogIdentity = state.selectedDownloadSource.identity()
        val dialogPalette = animatedEditorialPalette(dialogIdentity.palette)
        AlertDialog(
            onDismissRequest = viewModel::dismissDownloadSourceSelector,
            title = { Text("Choose download source") },
            text = {
                Column {
                    Text(
                        "Harmony will use only the source you confirm. If it fails, the other engine will not start automatically.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    DownloadSourceSelector(
                        selected = state.selectedDownloadSource,
                        onSelect = viewModel::selectPendingDownloadSource,
                        palette = dialogPalette,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        dialogIdentity.tagline,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDownloadSource) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDownloadSourceSelector) { Text("Cancel") }
            },
        )
    }
}



@Composable
private fun SpotiFlacDownloadsLayout(
    state: DownloadsViewModel.State,
    palette: EditorialPalette,
    context: Context,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelect: (SpotiFlacSearchCandidate) -> Unit,
    onOutputFormatChange: (SpotiFlacOutputFormat) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onPrepareVerification: () -> Unit,
    onVerificationOpened: () -> Unit,
    onVerificationLaunchFailed: () -> Unit,
    onRefreshVerification: () -> Unit,
    onCheckVerificationAndRetry: () -> Unit,
    onToggleErrorDetails: () -> Unit,
) {
    // Check provider readiness early, without holding public metadata search
    // hostage to a browser callback. Verification is enforced by the engine
    // when an actual provider download starts.
    val challenge = state.spotiFlacVerificationChallenge
    val verificationUrl = challenge?.verificationUrl ?: state.spotiFlacVerificationUrl
    val providerId = state.spotiFlacVerificationProvider
    val providerName = providerId
        ?.substringBefore('-')
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        ?: "Tidal"
    val verificationPending = challenge?.pending == true && challenge.authenticated != true

    Spacer(Modifier.height(18.dp))
    EditorialSectionLabel(
        "Provider verification",
        palette,
        Modifier.padding(start = 22.dp, bottom = 8.dp),
    )
    EditorialCard(
        palette = palette,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when {
                    state.isCheckingSpotiFlacVerification -> "Checking $providerName verification…"
                    state.spotiFlacPreflightReady && challenge?.authenticated == true -> "Verification complete"
                    state.spotiFlacPreflightReady -> "Provider ready"
                    verificationPending -> "Verification pending for downloads"
                    else -> "Verify SpotiFLAC before metadata"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (verificationPending) Color(0xFF8A6D1F) else palette.ink,
            )
            Text(
                when {
                    state.isCheckingSpotiFlacVerification ->
                        "Harmony is checking whether the primary lossless provider is ready for downloading. Metadata search remains available."
                    state.spotiFlacPreflightReady && challenge?.authenticated == true ->
                        "$providerName reports an authenticated session. Downloads are ready."
                    state.spotiFlacPreflightReady ->
                        "$providerName does not currently require an interactive challenge. Downloads are ready."
                    verificationPending && verificationUrl != null ->
                        "The official $providerName verification is still needed before downloading. You can search and select a track now."
                    verificationPending ->
                        "$providerName requires verification before downloading. Refresh the challenge to obtain the current verification page; metadata search remains available."
                    else ->
                        "Harmony is checking provider readiness for downloads. Use Check verification if the automatic check did not start."
                },
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 5.dp),
            )
            challenge?.instructions
                ?.takeIf { verificationPending && it.isNotBlank() }
                ?.let { instructions ->
                    Text(
                        instructions,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            state.error
                ?.takeIf { !state.spotiFlacPreflightReady }
                ?.let { error ->
                    Text(
                        error,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = Color(0xFFB3261E),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            if (state.isCheckingSpotiFlacVerification) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            } else if (!state.spotiFlacPreflightReady) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (verificationUrl != null) {
                        TextButton(
                            onClick = {
                                if (openProviderVerificationBrowser(context, verificationUrl)) {
                                    onVerificationOpened()
                                } else {
                                    onVerificationLaunchFailed()
                                }
                            },
                        ) {
                            Text("Verify $providerName")
                        }
                    }
                    if (providerId != null) {
                        TextButton(onClick = onRefreshVerification) {
                            Text("Refresh")
                        }
                        TextButton(onClick = onCheckVerificationAndRetry) {
                            Text("Check & continue")
                        }
                    } else {
                        TextButton(onClick = onPrepareVerification) {
                            Text("Check verification")
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    EditorialSectionLabel(
        "Search SpotiFLAC",
        palette,
        Modifier.padding(start = 22.dp, bottom = 8.dp),
    )
    EditorialCard(
        palette = palette,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Find a track",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
            )
            Text(
                if (state.spotiFlacPreflightReady) {
                    "Provider ready. Search by artist and title, select the exact result, then start the download."
                } else {
                    "Search by artist and title now. Provider verification is required only when you start downloading."
                },
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Audio format",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.muted,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpotiFlacOutputFormat.entries.forEach { format ->
                    FilterChip(
                        selected = state.spotiFlacOutputFormat == format,
                        onClick = { if (!state.isSpotiFlacDownloading) onOutputFormatChange(format) },
                        enabled = !state.isSpotiFlacDownloading,
                        label = { Text(format.label, fontSize = 10.sp) },
                    )
                }
            }
            Text(
                state.spotiFlacOutputFormat.summary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                singleLine = true,
                label = { Text("Artist and track") },
                placeholder = { Text("e.g. Marko Glass Dior") },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                EditorialPill(
                    text = if (state.isIdentifying && state.spotiFlacSearchResults.isEmpty()) {
                        "Searching…"
                    } else {
                        "Search SpotiFLAC"
                    },
                    icon = Icons.Rounded.Search,
                    onClick = { if (!state.isIdentifying) onSearch() },
                    palette = palette,
                )
            }
            if (state.isIdentifying && state.spotiFlacSearchResults.isEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    EditorialSectionLabel(
        "Results",
        palette,
        Modifier.padding(start = 22.dp, bottom = 8.dp),
    )
    EditorialCard(
        palette = palette,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            when {
                state.isIdentifying && state.spotiFlacSearchResults.isEmpty() -> {
                    Text(
                        "Searching SpotiFLAC…",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                    )
                    Text(
                        "Metadata matches will appear here.",
                        fontSize = 11.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                state.spotiFlacSearchResults.isEmpty() -> {
                    Text(
                        "No results yet",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                    )
                    Text(
                        "Search above, then select the exact track before starting a lossless-provider download.",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                else -> {
                    Text(
                        "${state.spotiFlacSearchResults.size} metadata match${if (state.spotiFlacSearchResults.size == 1) "" else "es"}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                    )
                    Text(
                        "Select one result. The Download button appears only after Harmony resolves that selection.",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                    state.spotiFlacSearchResults.forEachIndexed { index, candidate ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                                    .height(1.dp)
                                    .background(palette.muted.copy(alpha = 0.18f)),
                            )
                        }
                        val selected = candidate.metadataId == state.selectedSpotiFlacMetadataId
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    candidate.title,
                                    fontSize = 15.sp,
                                    lineHeight = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    candidate.artist,
                                    fontSize = 12.sp,
                                    color = palette.muted,
                                    modifier = Modifier.padding(top = 2.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (candidate.album.isNotBlank()) {
                                    Text(
                                        candidate.album,
                                        fontSize = 10.sp,
                                        color = palette.muted,
                                        modifier = Modifier.padding(top = 2.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    buildString {
                                        append(candidate.matchScore)
                                        append("% match")
                                        if (candidate.durationMs > 0L) {
                                            append(" · ")
                                            append(formatTrackDuration(candidate.durationMs))
                                        }
                                    },
                                    fontSize = 10.sp,
                                    color = if (candidate.matchScore >= 90) palette.ink else palette.muted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            TextButton(
                                enabled = !state.isIdentifying && !state.isSpotiFlacDownloading,
                                onClick = { onSelect(candidate) },
                            ) {
                                Text(if (selected) "Selected" else "Select")
                            }
                        }
                    }
                }
            }

            if (state.isIdentifying && state.spotiFlacSearchResults.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Text(
                    "Resolving the selected metadata result…",
                    fontSize = 10.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }

            state.identifiedTrack?.let { track ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .height(1.dp)
                        .background(palette.muted.copy(alpha = 0.18f)),
                )
                Text(
                    "Selected for download",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.muted,
                )
                Text(
                    track.title,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                    modifier = Modifier.padding(top = 3.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track.artist.isNotBlank()) {
                    Text(
                        track.artist,
                        fontSize = 12.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    "Output: ${state.spotiFlacOutputFormat.label}",
                    fontSize = 10.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    EditorialPill(
                        text = if (state.isSpotiFlacDownloading) "Downloading…" else "Download ${if (state.spotiFlacOutputFormat.isLosslessOutput) "FLAC" else "MP3"}",
                        icon = Icons.Rounded.Download,
                        onClick = { if (!state.isSpotiFlacDownloading) onDownload() },
                        palette = palette,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    EditorialSectionLabel(
        "Download status",
        palette,
        Modifier.padding(start = 22.dp, bottom = 8.dp),
    )
    state.spotiFlacTransfer?.let { transfer ->
        SpotiFlacTransferCard(
            transfer = transfer,
            canCancel = state.isSpotiFlacDownloading,
            palette = palette,
            onCancel = onCancelDownload,
        )
    } ?: EditorialCard(
        palette = palette,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val waitingForVerification = state.spotiFlacVerificationChallenge?.let { challenge ->
                challenge.pending && !challenge.authenticated
            } == true
            Text(
                when {
                    waitingForVerification -> "Waiting for provider verification"
                    state.error != null -> "Download failed"
                    state.identifiedTrack != null -> "Ready to download"
                    else -> "No download started"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.error != null && !waitingForVerification) Color(0xFFB3261E) else palette.ink,
            )
            Text(
                when {
                    waitingForVerification -> "Complete the verification card above. Harmony will resume the selected track after the signed callback returns."
                    state.error != null -> state.error
                    state.identifiedTrack != null -> "The selected track is ready. Press Download in Results to fetch a verified lossless source and produce ${state.spotiFlacOutputFormat.label}."
                    else -> "Select a metadata result above. Transfer progress will appear here after you press Download."
                },
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 5.dp),
            )
            state.message
                ?.takeIf { it.isNotBlank() && !waitingForVerification }
                ?.let { message ->
                    Text(
                        message,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            if (state.error != null && state.errorDetails != null && !waitingForVerification) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onToggleErrorDetails) {
                        Text(if (state.showErrorDetails) "Hide details" else "Details")
                    }
                }
                if (state.showErrorDetails) {
                    Text(
                        state.errorDetails,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = Color(0xFFB3261E),
                        maxLines = 18,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    EditorialSectionLabel(
        if (state.spotiFlacOutputFormat.isLosslessOutput) "FLAC checker" else "MP3 checker",
        palette,
        Modifier.padding(start = 22.dp, bottom = 8.dp),
    )
    val spotiFlacReport = state.report
    if (spotiFlacReport != null) {
        SpectralResultCard(spotiFlacReport, palette)
    } else {
        EditorialCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                val checkerStage = state.spotiFlacTransfer?.stage
                val checking = state.isSpotiFlacQualityChecking ||
                    checkerStage == SpotiFlacStage.VALIDATING ||
                    checkerStage == SpotiFlacStage.IMPORTING
                Text(
                    if (checking) {
                        if (state.spotiFlacOutputFormat.isLosslessOutput) "Checking FLAC…" else "Checking MP3…"
                    } else {
                        "Waiting for a completed SpotiFLAC download"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
                Text(
                    if (checking) {
                        if (state.spotiFlacOutputFormat.isLosslessOutput) {
                            "Harmony is validating the native FLAC and running the spectral lossless check."
                        } else {
                            "Harmony is validating the MPEG audio container and checking the spectral content of the selected 320 kbps MP3 output."
                        }
                    } else {
                        if (state.spotiFlacOutputFormat.isLosslessOutput) {
                            "After a SpotiFLAC download completes, its FLAC validation and spectral result will appear here."
                        } else {
                            "MP3 is intentionally lossy. Harmony verifies the MP3 container and shows its spectral quality instead of pretending it is lossless."
                        }
                    },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
                if (checking) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SoulseekDownloadsLayout(
    state: DownloadsViewModel.State,
    palette: EditorialPalette,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchModeChange: (SoulseekSearchMode) -> Unit,
    onFormatPreferenceChange: (SoulseekFormatPreference) -> Unit,
    onOnlyFreeSlotsChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onDownload: (SoulseekSearchCandidate) -> Unit,
    onDownloadBest: () -> Unit,
    onCancelDownload: () -> Unit,
    onPickDownloadFolder: () -> Unit,
    onUseDefaultDownloadFolder: () -> Unit,
    onPickUploadFolder: () -> Unit,
    onRefreshUploadFolder: () -> Unit,
    onClearUploadFolder: () -> Unit,
) {
    val connected = state.soulseekConnection.status == SoulseekConnectionStatus.CONNECTED

    Spacer(Modifier.height(18.dp))
    EditorialSectionLabel(
        "Soulseek login",
        palette,
        Modifier.padding(start = 22.dp, bottom = 8.dp),
    )
    if (connected) {
        EditorialCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Login details",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
                Text(
                    "Your Soulseek session is active. Search becomes available only while this session is connected.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                SessionStrip(
                    username = state.soulseekConnection.username,
                    onDisconnect = onDisconnect,
                    palette = palette,
                )
            }
        }
    } else {
        SoulseekConnectCard(
            connection = state.soulseekConnection,
            username = state.soulseekUsername,
            password = state.soulseekPassword,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            onConnect = onConnect,
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }

    if (connected) {
        Spacer(Modifier.height(18.dp))
        EditorialSectionLabel(
            "Search Soulseek",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        EditorialCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Search the peer network",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
                Text(
                    "Enter the artist and track title, then choose how broadly Harmony should search.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    singleLine = true,
                    label = { Text("Artist and track") },
                    placeholder = { Text("e.g. Marko Glass Dior") },
                )

                Text(
                    "Search mode",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.soulseekSearchMode != SoulseekSearchMode.DEEP,
                        onClick = { onSearchModeChange(SoulseekSearchMode.BALANCED) },
                        label = { Text("Balanced", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = state.soulseekSearchMode == SoulseekSearchMode.DEEP,
                        onClick = { onSearchModeChange(SoulseekSearchMode.DEEP) },
                        label = { Text("Deep", fontSize = 11.sp) },
                    )
                }
                Text(
                    if (state.soulseekSearchMode == SoulseekSearchMode.DEEP) {
                        "Deep searches longer and uses broader fallbacks for difficult tracks."
                    } else {
                        "Balanced cleans noisy queries, tries safe variants and ranks diverse peers. Recommended."
                    },
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Text(
                    "Format",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 12.dp),
                )
                // Four chips rather than a FLAC/MP3 toggle: "only" and
                // "prefer" are genuinely different answers. Someone saving
                // space usually still wants the track when no MP3 is shared,
                // and someone who cares about lossless does not want a silent
                // downgrade.
                SoulseekFormatPreference.entries.chunked(2).forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { preference ->
                            FilterChip(
                                selected = state.soulseekFormatPreference == preference,
                                onClick = { onFormatPreferenceChange(preference) },
                                label = { Text(preference.label, fontSize = 11.sp) },
                            )
                        }
                    }
                }
                Text(
                    state.soulseekFormatPreference.summary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 5.dp),
                )

                FilterChip(
                    selected = state.soulseekOnlyFreeSlots,
                    onClick = { onOnlyFreeSlotsChange(!state.soulseekOnlyFreeSlots) },
                    label = { Text("Only free upload slots", fontSize = 11.sp) },
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    EditorialPill(
                        text = if (state.isSoulseekSearching) "Searching…" else "Search Soulseek",
                        icon = Icons.Rounded.Search,
                        onClick = { if (!state.isSoulseekSearching) onSearch() },
                        palette = palette,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        EditorialSectionLabel(
            "Results",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        EditorialCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                when {
                    state.isSoulseekSearching && state.soulseekResults.isEmpty() -> {
                        Text(
                            "Searching Soulseek…",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.ink,
                        )
                        Text(
                            "Results will appear here as peers answer.",
                            fontSize = 11.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                    state.soulseekResults.isEmpty() -> {
                        Text(
                            "No results yet",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.ink,
                        )
                        Text(
                            "Run a search above. Harmony will rank matching FLAC sources by track accuracy, quality, queue and peer speed.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    else -> {
                        Text(
                            "Top ${state.soulseekResults.size} sources",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.ink,
                        )
                        state.soulseekResults.forEachIndexed { index, candidate ->
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                        .height(1.dp)
                                        .background(palette.muted.copy(alpha = 0.18f)),
                                )
                            }
                            SoulseekResultRow(
                                rank = index + 1,
                                candidate = candidate,
                                isDownloading = state.soulseekDownloadingId == candidate.id,
                                downloadsBusy = state.soulseekDownloadingId != null,
                                activeTransferStatus = if (state.soulseekDownloadingId == candidate.id) {
                                    state.soulseekTransfer?.status
                                } else {
                                    null
                                },
                                palette = palette,
                                onDownload = { onDownload(candidate) },
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            EditorialPill(
                                text = if (state.soulseekDownloadingId != null) {
                                    soulseekResultActionLabel(state.soulseekTransfer?.status)
                                } else {
                                    "Download best"
                                },
                                icon = Icons.Rounded.Download,
                                onClick = { if (state.soulseekDownloadingId == null) onDownloadBest() },
                                palette = palette,
                            )
                        }
                    }
                }
            }
        }

        state.soulseekTransfer?.let { transfer ->
            Spacer(Modifier.height(18.dp))
            EditorialSectionLabel(
                "Download status",
                palette,
                Modifier.padding(start = 22.dp, bottom = 8.dp),
            )
            SoulseekTransferCard(
                transfer = transfer,
                sourceAttempt = state.soulseekSourceAttempt,
                sourceTotal = state.soulseekSourceTotal,
                palette = palette,
                onCancel = onCancelDownload,
            )
        }

        Spacer(Modifier.height(18.dp))
        EditorialSectionLabel(
            "FLAC checker",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        SoulseekFlacCheckerCard(
            report = state.report,
            transfer = state.soulseekTransfer,
            palette = palette,
        )

        Spacer(Modifier.height(18.dp))
        EditorialSectionLabel(
            "Download selection",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        EditorialCard(
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Where downloaded files are saved",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
                Text(
                    state.downloadFolderLabel,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 5.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Choose a writable folder for files you download from Soulseek or SpotiFLAC.",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (state.usingCustomDownloadFolder) {
                        TextButton(onClick = onUseDefaultDownloadFolder) {
                            Text("Use default")
                        }
                    }
                    EditorialPill(
                        text = "Choose download folder",
                        icon = Icons.Rounded.Folder,
                        onClick = onPickDownloadFolder,
                        palette = palette,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        EditorialSectionLabel(
            "Upload selection",
            palette,
            Modifier.padding(start = 22.dp, bottom = 8.dp),
        )
        SoulseekSharingCard(
            sharedFolder = state.soulseekSharedFolder,
            isIndexing = state.isIndexingShare,
            onPick = onPickUploadFolder,
            onRefresh = onRefreshUploadFolder,
            onClear = onClearUploadFolder,
            palette = palette,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun SoulseekResultRow(
    rank: Int,
    candidate: SoulseekSearchCandidate,
    isDownloading: Boolean,
    downloadsBusy: Boolean,
    activeTransferStatus: String?,
    palette: EditorialPalette,
    onDownload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "#$rank · Harmony score ${candidate.score}/100",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (rank == 1) Color(0xFF2E7D4F) else palette.muted,
        )
        Text(
            candidate.fileNameOnly,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Bold,
            color = palette.ink,
            modifier = Modifier.padding(top = 3.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            candidate.qualityLabel.ifBlank { "FLAC" } + " · " + formatBytes(candidate.sizeBytes),
            fontSize = 11.sp,
            color = palette.muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "${candidate.username} · ${if (candidate.freeUploadSlot) "free slot" else "busy"} · ${formatPeerSpeed(candidate.averageSpeedBytesPerSecond)} · queue ${candidate.queueLength}",
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = palette.muted,
            modifier = Modifier.padding(top = 3.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (candidate.recommendation.isNotBlank()) {
            Text(
                candidate.recommendation,
                fontSize = 10.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            EditorialPill(
                text = if (isDownloading) soulseekResultActionLabel(activeTransferStatus) else "Download",
                icon = Icons.Rounded.Download,
                onClick = { if (!downloadsBusy) onDownload() },
                palette = palette,
            )
        }
    }
}

@Composable
private fun SoulseekFlacCheckerCard(
    report: SpectralReport?,
    transfer: SoulseekTransferProgress?,
    palette: EditorialPalette,
) {
    EditorialCard(
        palette = palette,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (report == null) {
                val checking = transfer?.status.orEmpty().let {
                    "spectrum" in it.lowercase() || "checking" in it.lowercase()
                }
                Text(
                    if (checking) "Checking FLAC…" else "Waiting for a completed FLAC download",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                )
                Text(
                    "After a Soulseek download finishes, Harmony validates the file and runs the spectral lossless check here.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 5.dp),
                )
                if (checking) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            } else {
                val (title, accent) = when (report.verdict) {
                    SpectralReport.Verdict.GENUINE_LOSSLESS -> "Looks genuinely lossless" to Color(0xFF2E7D4F)
                    SpectralReport.Verdict.LIKELY_TRANSCODE -> "Probably a transcode" to Color(0xFFB3261E)
                    SpectralReport.Verdict.LOSSY_AS_LABELLED -> "Lossy, as labelled" to Color(0xFF8A6D1F)
                    SpectralReport.Verdict.INCONCLUSIVE -> "Inconclusive" to palette.muted
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(accent, RoundedCornerShape(50)),
                    )
                    Text(
                        title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                        modifier = Modifier.padding(start = 9.dp),
                    )
                }
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        "${report.sampleRate / 1000f} kHz · ${report.bitDepth}-bit · cutoff ${"%.1f".format(report.cutoffHz / 1000f)} kHz",
                        fontSize = 12.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    report.explanation,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun openProviderVerificationBrowser(context: Context, authUrl: String): Boolean {
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

@Composable
private fun DownloadHistoryCard(item: DownloadHistoryItem, palette: EditorialPalette) {
    EditorialCard(palette = palette, modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.artist.isNotBlank()) Text(item.artist, fontSize = 11.sp, color = palette.muted)
            val origin = buildList {
                add(item.source.displayName)
                item.provider?.let(::add)
                item.soulseekUsername?.let { add("Peer: $it") }
            }.joinToString(" · ")
            Text(origin, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = palette.muted, modifier = Modifier.padding(top = 4.dp))
            val quality = listOfNotNull(
                item.format,
                item.bitDepth?.let { "$it-bit" },
                item.sampleRateHz?.takeIf { it > 0 }?.let { "%.1f kHz".format(it / 1000.0) },
            ).joinToString(" · ")
            if (quality.isNotBlank()) Text(quality, fontSize = 10.sp, color = palette.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SoulseekResultCard(
    rank: Int,
    candidate: SoulseekSearchCandidate,
    isDownloading: Boolean,
    downloadsBusy: Boolean,
    activeTransferStatus: String?,
    palette: EditorialPalette,
    onDownload: () -> Unit,
) {
    EditorialCard(
        palette = palette,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "#$rank · Harmony score ${candidate.score}/100",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (rank == 1) Color(0xFF2E7D4F) else palette.muted,
                    )
                    Text(
                        candidate.fileNameOnly,
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                        modifier = Modifier.padding(top = 3.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        candidate.qualityLabel.ifBlank { "FLAC" } + " · " + formatBytes(candidate.sizeBytes),
                        fontSize = 11.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "${candidate.username} · ${if (candidate.freeUploadSlot) "free slot" else "busy"} · ${formatPeerSpeed(candidate.averageSpeedBytesPerSecond)} · queue ${candidate.queueLength}",
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 3.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        candidate.recommendation,
                        fontSize = 10.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 9.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                EditorialPill(
                    text = if (isDownloading) soulseekResultActionLabel(activeTransferStatus) else "Download",
                    icon = Icons.Rounded.Download,
                    onClick = { if (!downloadsBusy) onDownload() },
                    palette = palette,
                )
            }
        }
    }
}

@Composable
private fun SpotiFlacTransferCard(
    transfer: SpotiFlacTransferProgress,
    canCancel: Boolean,
    palette: EditorialPalette,
    onCancel: () -> Unit,
) {
    EditorialCard(palette = palette, modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "SpotiFLAC",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
            )
            Text(
                transfer.provider?.let { "Provider: $it" } ?: "Lossless provider engine",
                fontSize = 11.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                transfer.detail ?: transfer.stage.label,
                fontSize = 12.sp,
                color = palette.ink,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (transfer.fraction != null) {
                LinearProgressIndicator(
                    progress = { transfer.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(transfer.fraction * 100).toInt()}%" +
                        if (transfer.totalBytes > 0) " · ${formatBytes(transfer.downloadedBytes)} / ${formatBytes(transfer.totalBytes)}" else "",
                    fontSize = 10.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (canCancel) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel SpotiFLAC") }
                }
            }
        }
    }
}

@Composable
private fun SoulseekTransferCard(
    transfer: SoulseekTransferProgress,
    sourceAttempt: Int,
    sourceTotal: Int,
    palette: EditorialPalette,
    onCancel: () -> Unit,
) {
    val stage = soulseekDownloadStage(transfer.status)
    val isActivelyReceiving = stage == "Downloading" || stage == "Resuming"
    val isFinished = stage == "Complete" || stage == "Saving" || stage == "Checking"
    val fraction = transfer.fraction
    val eta = formatPeerEta(
        downloadedBytes = transfer.downloadedBytes,
        totalBytes = transfer.totalBytes,
        speedBytesPerSecond = transfer.speedBytesPerSecond,
    )

    EditorialCard(
        palette = palette,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "DOWNLOAD STATUS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.muted,
                    )
                    Text(
                        stage,
                        fontSize = 17.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (sourceAttempt > 0 && sourceTotal > 0) {
                    Text(
                        "Source $sourceAttempt/$sourceTotal",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.muted,
                    )
                }
            }

            Text(
                transfer.filename,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
                modifier = Modifier.padding(top = 9.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Soulseek · Peer: ${transfer.username}",
                fontSize = 10.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (!isFinished) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel Soulseek") }
                }
            }

            val queuePlace = transfer.queuePlace
            // Show the panel whenever we are waiting on a peer, not only once a
            // number has arrived. The position often turns up seconds later, and
            // hiding the panel until then left the wait looking like nothing was
            // happening at all.
            val isWaitingOnPeer = stage == "Queued" || stage == "Waiting for peer"
            if ((queuePlace != null && queuePlace > 0 || isWaitingOnPeer) &&
                !isActivelyReceiving && !isFinished
            ) {
                // A frozen number is indistinguishable from a stalled queue, so
                // the elapsed clock keeps ticking between the peer's replies.
                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(transfer.queueWaitStartedAtMs) {
                    while (true) {
                        nowMs = System.currentTimeMillis()
                        kotlinx.coroutines.delay(1_000L)
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.field)
                        .padding(12.dp),
                ) {
                    Text(
                        "IN QUEUE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.muted,
                    )
                    Text(
                        when {
                            queuePlace == null || queuePlace <= 0 -> "Position not reported yet"
                            queuePlace == 1 -> "You are next"
                            else -> "$queuePlace ahead of you"
                        },
                        fontSize = if (queuePlace == null || queuePlace <= 0) 15.sp else 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    val advanced = transfer.queueAdvancedBy
                    val startedAt = transfer.queueWaitStartedAtMs
                    val waitedSeconds = startedAt
                        ?.let { ((nowMs - it) / 1000L).coerceAtLeast(0L) }

                    Text(
                        buildList {
                            if (advanced != null) {
                                add("moved up $advanced from #${transfer.queueStartPlace}")
                            }
                            if (waitedSeconds != null) add("waiting ${formatQueueWait(waitedSeconds)}")
                        }.joinToString(" · ").ifBlank {
                            "asking the peer for your position…"
                        },
                        fontSize = 11.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )

                    val sinceUpdate = transfer.queueUpdatedAtMs
                        ?.let { ((nowMs - it) / 1000L).coerceAtLeast(0L) }
                    if (sinceUpdate != null) {
                        Text(
                            "Position checked ${formatQueueWait(sinceUpdate)} ago · rechecks up to every 10s",
                            fontSize = 10.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (fraction != null && (isActivelyReceiving || isFinished || transfer.downloadedBytes > 0L)) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }

            if (transfer.totalBytes > 0L) {
                val percent = ((fraction ?: 0f) * 100f).toInt().coerceIn(0, 100)
                Text(
                    "$percent% · ${formatBytes(transfer.downloadedBytes)} / ${formatBytes(transfer.totalBytes)}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.ink,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (transfer.speedBytesPerSecond > 0L || eta != null) {
                Text(
                    buildString {
                        if (transfer.speedBytesPerSecond > 0L) {
                            append(formatPeerSpeed(transfer.speedBytesPerSecond))
                        }
                        eta?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                        }
                    },
                    fontSize = 10.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }

            Text(
                transfer.status,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

private fun soulseekDownloadStage(status: String): String {
    val value = status.lowercase()
    return when {
        "complete" in value -> "Complete"
        "spectrum" in value || "checking" in value -> "Checking"
        "saving" in value -> "Saving"
        "failed" in value || "denied" in value || "could not" in value -> "Failed"
        "network" in value || "switching" in value || "reconnect" in value -> "Switching network"
        "retry" in value || "resume" in value || "resuming" in value || "interrupted" in value -> "Resuming"
        "downloading" in value -> "Downloading"
        "queued" in value || "upload slot" in value -> "Queued"
        "accepted" in value || "opening file transfer" in value || "starting transfer" in value ||
            "transfer opened" in value || "audio data" in value -> "Starting"
        "request sent" in value || "waiting briefly" in value -> "Waiting for peer"
        "connecting" in value || "testing peer" in value || "direct + reverse" in value -> "Connecting"
        else -> "Preparing"
    }
}


private fun soulseekResultActionLabel(status: String?): String = when (soulseekDownloadStage(status.orEmpty())) {
    "Connecting" -> "Connecting…"
    "Waiting for peer" -> "Waiting…"
    "Queued" -> "Queued…"
    "Starting" -> "Starting…"
    "Downloading" -> "Downloading…"
    "Resuming" -> "Resuming…"
    "Switching network" -> "Switching…"
    "Saving" -> "Saving…"
    "Checking" -> "Checking…"
    "Complete" -> "Complete"
    "Failed" -> "Failed"
    else -> "Preparing…"
}

private fun formatPeerEta(
    downloadedBytes: Long,
    totalBytes: Long,
    speedBytesPerSecond: Long,
): String? {
    if (totalBytes <= 0L || speedBytesPerSecond <= 0L || downloadedBytes >= totalBytes) return null
    val seconds = ((totalBytes - downloadedBytes) / speedBytesPerSecond).coerceAtLeast(1L)
    return when {
        seconds < 60L -> "~${seconds}s left"
        seconds < 3_600L -> "~${seconds / 60L}m ${seconds % 60L}s left"
        else -> "~${seconds / 3_600L}h ${(seconds % 3_600L) / 60L}m left"
    }
}

private fun formatPeerSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024L -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
    bytesPerSecond > 0L -> "$bytesPerSecond B/s"
    else -> "speed unknown"
}

@Composable
private fun DownloadProgressCard(
    download: HarmonyDownloadProgress,
    palette: EditorialPalette,
) {
    EditorialCard(
        palette = palette,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when (download.status) {
                    HarmonyDownloadStatus.PENDING -> "Queued"
                    HarmonyDownloadStatus.RUNNING -> "Downloading"
                    HarmonyDownloadStatus.PAUSED -> "Download paused"
                    HarmonyDownloadStatus.SUCCESSFUL -> "Download complete"
                    HarmonyDownloadStatus.FAILED -> "Download failed"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
            )
            val fraction = download.fraction
            if (fraction != null && download.status != HarmonyDownloadStatus.SUCCESSFUL) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
                Text(
                    "${(fraction * 100).toInt()}% · ${formatBytes(download.downloadedBytes)} / ${formatBytes(download.totalBytes)}",
                    fontSize = 11.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SpectralResultCard(
    report: SpectralReport,
    palette: EditorialPalette,
) {
    val (title, accent) = when (report.verdict) {
        SpectralReport.Verdict.GENUINE_LOSSLESS -> "Looks genuinely lossless" to Color(0xFF2E7D4F)
        SpectralReport.Verdict.LIKELY_TRANSCODE -> "Probably a transcode" to Color(0xFFB3261E)
        SpectralReport.Verdict.LOSSY_AS_LABELLED -> "Lossy, as labelled" to Color(0xFF8A6D1F)
        SpectralReport.Verdict.INCONCLUSIVE -> "Inconclusive" to palette.muted
    }
    EditorialCard(
        palette = palette,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(accent, RoundedCornerShape(50)),
                )
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                    modifier = Modifier.padding(start = 9.dp),
                )
            }
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = palette.muted,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    "${report.sampleRate / 1000f} kHz · ${report.bitDepth}-bit · cutoff ${"%.1f".format(report.cutoffHz / 1000f)} kHz",
                    fontSize = 12.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                report.explanation,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatQueueWait(totalSeconds: Long): String {
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return when {
        minutes >= 60L -> "${minutes / 60L}h ${minutes % 60L}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}


private fun formatTrackDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
