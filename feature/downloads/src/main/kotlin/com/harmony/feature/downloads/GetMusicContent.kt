package com.harmony.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.room.RoomButton
import com.harmony.core.ui.room.RoomButtonKind
import com.harmony.core.ui.room.RoomChip
import com.harmony.core.ui.room.RoomSectionTitle
import com.harmony.core.ui.room.RoomSegmented
import com.harmony.core.ui.room.StatusLine
import com.harmony.core.ui.room.StatusTone
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType
import com.harmony.domain.analysis.model.SpectralReport

/*
 * The sources page ("Get music"), rebuilt in the Listening Room
 * (docs/redesign/05 §2.9, 06 §7). The music comes first: one search, results
 * with Get on every row, the quality in words. Which engine fetches it is an
 * override ("From: SpotiFLAC ▾"), never the first question. A problem is said
 * in three parts: what happened, what Harmony already did, one action.
 *
 * Everything here reads [DownloadsViewModel.State] and reports through
 * [GetMusicActions], so it renders the same in the app, in tests and in
 * design shots. Every action is an existing DownloadsViewModel function.
 */

/** What the page is for right now: a song, an album, or a YouTube link. */
enum class GetMusicMode { Songs, Albums, YouTube }

internal class GetMusicActions(
    val onQueryChange: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onSelect: (SpotiFlacSearchCandidate) -> Unit = {},
    val onGet: (SpotiFlacSearchCandidate) -> Unit = {},
    val onGetIdentified: () -> Unit = {},
    val onCancelSpotiFlac: () -> Unit = {},
    val onFormat: (SpotiFlacOutputFormat) -> Unit = {},
    val onOpenVerification: (String) -> Unit = {},
    val onRefreshVerification: () -> Unit = {},
    val onCheckAndRetry: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOtherSource: () -> Unit = {},
    val onToggleDetails: () -> Unit = {},
    val onRetryYouTube: () -> Unit = {},
    val onConvertYouTube: () -> Unit = {},
    val onRecognize: () -> Unit = {},
    val onOpenSourceSheet: () -> Unit = {},
)

/** A provider id ("tidal-hifi") as the listener knows it ("Tidal"). */
internal fun providerName(id: String?): String =
    id?.substringBefore('-')?.replaceFirstChar { it.titlecase() } ?: "The source"

/** The format choices, in plain words. */
internal fun SpotiFlacOutputFormat.plainLabel(): Int = when (this) {
    SpotiFlacOutputFormat.FLAC_LOSSLESS -> R.string.get_format_cd
    SpotiFlacOutputFormat.MP3_320 -> R.string.get_format_mp3
    else -> R.string.get_format_hires
}

@Composable
internal fun ModeAndSource(
    mode: GetMusicMode,
    onMode: (GetMusicMode) -> Unit,
    source: DownloadSource,
    onOpenSourceSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RoomSegmented(
            options = listOf(
                stringResource(R.string.get_mode_songs),
                stringResource(R.string.get_mode_albums),
                stringResource(R.string.get_mode_youtube),
            ),
            selected = mode.ordinal,
            onSelect = { onMode(GetMusicMode.entries[it]) },
        )
        if (mode != GetMusicMode.YouTube) {
            // The source is an override (principle 1): one quiet line, a sheet to change it.
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.get_change_source), onClick = onOpenSourceSheet)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.get_from), style = RoomType.bodySmall, color = Room.muted)
                Spacer(Modifier.width(4.dp))
                Text(source.displayName, style = RoomType.labelSmall, color = Room.amberText)
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = Room.amberText, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The search field shared by songs, albums and links. */
@Composable
internal fun GetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leading: ImageVector = Icons.Rounded.Search,
    enabled: Boolean = true,
) {
    val r = Room
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("get_search"),
        label = { Text(label) },
        leadingIcon = { Icon(leading, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.add_clear))
                }
            }
        },
        enabled = enabled,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = RoomType.body,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (value.isNotBlank()) onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = r.surface, unfocusedContainerColor = r.surface,
            focusedBorderColor = r.ink, unfocusedBorderColor = r.outline,
            focusedLabelColor = r.ink, unfocusedLabelColor = r.muted, cursorColor = r.ink,
        ),
    )
}

/**
 * Songs through SpotiFLAC: search, pick, Get. The provider check only shows
 * when it needs the listener; the format is one row of chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpotiFlacSongs(state: DownloadsViewModel.State, actions: GetMusicActions, modifier: Modifier = Modifier) {
    val r = Room
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GetSearchField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            onSearch = actions.onSearch,
            label = stringResource(R.string.get_search_songs),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoomButton(
                stringResource(R.string.get_search),
                onClick = actions.onSearch,
                icon = Icons.Rounded.Search,
                enabled = state.query.isNotBlank() && !state.isIdentifying,
                loading = state.isIdentifying,
            )
            RoomButton(stringResource(R.string.get_identify), onClick = actions.onRecognize, kind = RoomButtonKind.Text, icon = Icons.Rounded.GraphicEq)
        }

        ProviderReadiness(state, actions)

        state.spotiFlacTransfer?.let { transfer ->
            TransferRow(
                title = state.identifiedTrack?.title ?: stringResource(R.string.get_this_song),
                detail = transfer.stage.label + (transfer.provider?.let { " · ${providerName(it)}" } ?: ""),
                fraction = transfer.fraction,
                onStop = actions.onCancelSpotiFlac,
            )
        }

        DownloadProblem(state, actions)
        state.report?.let { QualityVerdict(it) }

        when {
            state.isIdentifying && state.spotiFlacSearchResults.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = r.amberText, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.get_searching), style = RoomType.body, color = r.muted)
            }
            state.spotiFlacSearchResults.isNotEmpty() -> {
                RoomSectionTitle(stringResource(R.string.get_results))
                Column {
                    state.spotiFlacSearchResults.forEach { candidate ->
                        val selected = candidate.metadataId == state.selectedSpotiFlacMetadataId
                        ResultRow(
                            title = candidate.title,
                            subtitle = listOf(candidate.artist, candidate.album).filter { it.isNotBlank() }.joinToString(" · "),
                            artworkUrl = candidate.thumbnailUrl,
                            durationMs = candidate.durationMs,
                            selected = selected,
                            busy = selected && (state.isSpotiFlacDownloading || state.isIdentifying),
                            onSelect = { actions.onSelect(candidate) },
                            onGet = { actions.onGet(candidate) },
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.get_save_as), style = RoomType.labelSmall, color = r.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SpotiFlacOutputFormat.entries.forEach { format ->
                    RoomChip(stringResource(format.plainLabel()), selected = state.spotiFlacOutputFormat == format, onClick = { actions.onFormat(format) })
                }
            }
        }
    }
}

/**
 * The provider check, only when it needs the listener (principle 1). Ready
 * says nothing; checking is one quiet line; a needed check is a waiting
 * status with the browser one tap away.
 */
@Composable
private fun ProviderReadiness(state: DownloadsViewModel.State, actions: GetMusicActions) {
    if (state.failedDownloadSource == DownloadSource.SPOTIFLAC && state.error != null) return // DownloadProblem says it
    val challenge = state.spotiFlacVerificationChallenge
    val url = challenge?.verificationUrl ?: state.spotiFlacVerificationUrl
    val provider = providerName(state.spotiFlacVerificationProvider ?: challenge?.providerId)
    when {
        state.spotiFlacPreflightReady || challenge?.authenticated == true -> Unit
        state.isCheckingSpotiFlacVerification -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Room.muted, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.get_checking_sources), style = RoomType.bodySmall, color = Room.muted)
        }
        url != null -> StatusLine(
            StatusTone.Waiting,
            title = stringResource(R.string.get_check_needed, provider),
            body = stringResource(R.string.get_check_needed_body),
            action = stringResource(R.string.get_open_browser),
            onAction = { actions.onOpenVerification(url) },
        )
        else -> Unit
    }
}

/**
 * A failed download in three parts (docs/redesign/06 §7): what happened, what
 * Harmony already did, one action, with the engine's own words a tap away.
 */
@Composable
internal fun DownloadProblem(state: DownloadsViewModel.State, actions: GetMusicActions) {
    val error = state.error ?: return
    val song = state.identifiedTrack?.title
    val verification = state.failedDownloadSource == DownloadSource.SPOTIFLAC && state.spotiFlacVerificationProvider != null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (verification) {
            val challenge = state.spotiFlacVerificationChallenge
            val url = challenge?.verificationUrl ?: state.spotiFlacVerificationUrl
            StatusLine(
                StatusTone.Waiting,
                title = stringResource(R.string.get_check_needed, providerName(state.spotiFlacVerificationProvider)),
                body = challenge?.instructions?.takeIf { it.isNotBlank() } ?: stringResource(R.string.get_paused_until_check),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (url != null) {
                    RoomButton(stringResource(R.string.get_open_browser), { actions.onOpenVerification(url) },
                        kind = RoomButtonKind.Tonal, icon = Icons.Rounded.OpenInBrowser, enabled = !state.isCheckingSpotiFlacVerification)
                }
                RoomButton(
                    stringResource(if (state.isCheckingSpotiFlacVerification) R.string.get_checking else R.string.get_check_again),
                    actions.onCheckAndRetry, kind = RoomButtonKind.Text, enabled = !state.isCheckingSpotiFlacVerification,
                )
            }
        } else {
            StatusLine(
                StatusTone.Error,
                title = if (song != null) stringResource(R.string.get_failed_song, song) else stringResource(R.string.get_failed),
                body = error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.canRetryYouTube) {
                    RoomButton(stringResource(R.string.get_try_again), actions.onRetryYouTube, kind = RoomButtonKind.Tonal)
                } else if (state.failedDownloadSource != null) {
                    RoomButton(stringResource(R.string.get_try_again), actions.onRetry, kind = RoomButtonKind.Tonal)
                    RoomButton(
                        stringResource(R.string.get_try_with, state.failedDownloadSource.alternative().displayName),
                        actions.onOtherSource, kind = RoomButtonKind.Text,
                    )
                }
            }
        }
        if (state.errorDetails != null) {
            RoomButton(
                stringResource(if (state.showErrorDetails) R.string.get_hide_details else R.string.get_details),
                actions.onToggleDetails, kind = RoomButtonKind.Text,
            )
            if (state.showErrorDetails) {
                Text(state.errorDetails, style = RoomType.bodySmall, color = Room.muted, maxLines = 18, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The quality check after a download, as a verdict in words. */
@Composable
internal fun QualityVerdict(report: SpectralReport) {
    val (tone, title) = when (report.verdict) {
        SpectralReport.Verdict.GENUINE_LOSSLESS -> StatusTone.Success to R.string.get_verdict_lossless
        SpectralReport.Verdict.LIKELY_TRANSCODE -> StatusTone.Error to R.string.get_verdict_transcode
        SpectralReport.Verdict.LOSSY_AS_LABELLED -> StatusTone.Info to R.string.get_verdict_lossy
        SpectralReport.Verdict.INCONCLUSIVE -> StatusTone.Info to R.string.get_verdict_unclear
    }
    StatusLine(tone, title = stringResource(title, report.fileName), body = report.explanation)
}

/** A transfer in progress: what, how far, and Stop. */
@Composable
internal fun TransferRow(title: String, detail: String, fraction: Float?, onStop: (() -> Unit)?) {
    val r = Room
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(r.surface)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $detail"
                if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = RoomType.title, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    detail + (fraction?.let { " · ${(it * 100).toInt().coerceIn(0, 100)}%" } ?: ""),
                    style = RoomType.bodySmall, color = r.muted, maxLines = 2,
                )
            }
            if (onStop != null) RoomButton(stringResource(R.string.activity_stop), onStop, kind = RoomButtonKind.Text)
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = r.amber, trackColor = r.raised, gapSize = 0.dp, drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = r.amber, trackColor = r.raised, gapSize = 0.dp)
        }
    }
}

/** One search result: tap the row to pick it, Get to download it straight away. */
@Composable
internal fun ResultRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    durationMs: Long,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onGet: () -> Unit,
    getLabel: String = stringResource(R.string.get_get),
    showGet: Boolean = true,
) {
    val r = Room
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) r.amberSoft.copy(alpha = 0.5f) else r.ground)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.get_pick), onClick = onSelect)
            .heightIn(min = 72.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            artworkUrl, contentDescription = null, modifier = Modifier.size(52.dp), cornerRadius = 8.dp,
            fallback = {
                Box(Modifier.size(52.dp).background(r.raised), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = r.faint)
                }
            },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = RoomType.title, color = if (selected) r.amberText else r.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle + (if (durationMs > 0) " · ${formatDuration(durationMs)}" else ""),
                style = RoomType.bodySmall, color = r.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (busy) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), color = r.amberText, strokeWidth = 2.dp)
        } else if (showGet) {
            RoomButton(getLabel, onGet, kind = RoomButtonKind.Tonal, icon = Icons.Rounded.Download)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * A YouTube link: paste, check the title, convert. YouTube audio is lossy;
 * the page says so before converting, so the FLAC isn't mistaken for more.
 */
@Composable
internal fun YouTubeLink(state: DownloadsViewModel.State, actions: GetMusicActions, modifier: Modifier = Modifier) {
    val r = Room
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GetSearchField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            onSearch = actions.onSearch,
            label = stringResource(R.string.get_youtube_label),
            leading = Icons.Rounded.Link,
            enabled = !state.isConverting,
        )
        StatusLine(StatusTone.Info, title = stringResource(R.string.get_youtube_lossy))
        state.identifiedTrack?.let { track ->
            ResultRow(
                title = track.title, subtitle = track.artist, artworkUrl = track.thumbnailUrl, durationMs = track.durationMs,
                selected = true, busy = false, onSelect = {}, onGet = {}, showGet = false,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoomButton(
                stringResource(R.string.get_check_title), actions.onSearch, kind = RoomButtonKind.Tonal,
                enabled = state.query.isNotBlank() && !state.isIdentifying && !state.isConverting, loading = state.isIdentifying,
            )
            RoomButton(
                stringResource(R.string.get_convert), actions.onConvertYouTube,
                enabled = state.query.isNotBlank() && !state.isConverting, loading = state.isConverting,
            )
        }
        if (state.isConverting) {
            TransferRow(
                title = state.identifiedTrack?.title ?: stringResource(R.string.get_this_song),
                detail = state.conversionStatus.ifBlank { stringResource(R.string.get_converting) },
                fraction = state.conversionProgress.takeIf { it > 0f },
                onStop = null,
            )
        }
        DownloadProblem(state, actions)
        state.report?.let { QualityVerdict(it) }
        state.message?.takeIf { state.error == null }?.let { Text(it, style = RoomType.bodySmall, color = r.muted) }
    }
}

/** The recent downloads, newest first, with where each came from and what it is. */
@Composable
internal fun RecentDownloads(items: List<DownloadHistoryItem>) {
    if (items.isEmpty()) return
    val r = Room
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RoomSectionTitle(stringResource(R.string.get_recent))
        items.take(8).forEach { item ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = r.moss, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = RoomType.title, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val quality = listOfNotNull(
                        item.format,
                        item.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
                        item.sampleRateHz?.takeIf { it > 0 }?.let { "${it / 1000.0} kHz".replace(".0 ", " ") },
                    ).joinToString(" ")
                    Text(
                        listOf(item.artist, item.source.displayName, quality).filter { it.isNotBlank() }.joinToString(" · "),
                        style = RoomType.bodySmall, color = r.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Choosing the source: a sheet with each engine said in plain words. The
 * choice only changes when the listener confirms it; Harmony never switches
 * sources on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSheet(
    selected: DownloadSource,
    onSelect: (DownloadSource) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val r = Room
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = r.surface,
        contentColor = r.ink,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.get_source_title), style = RoomType.nameL, color = r.ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.get_source_body), style = RoomType.bodySmall, color = r.muted)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(DownloadSource.SPOTIFLAC, R.string.get_source_spotiflac, R.string.get_source_spotiflac_body),
                    Triple(DownloadSource.SOULSEEK, R.string.get_source_soulseek, R.string.get_source_soulseek_body),
                ).forEach { (source, title, body) ->
                    val on = source == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) r.amberSoft else r.ground)
                            .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(source) })
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (on) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null, tint = if (on) r.onAmberSoft else r.outline,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(title), style = RoomType.title, color = if (on) r.onAmberSoft else r.ink)
                            Text(stringResource(body), style = RoomType.bodySmall, color = if (on) r.onAmberSoft else r.muted)
                        }
                    }
                }
            }
            RoomButton(stringResource(R.string.get_source_use), onConfirm, prominent = true, fillWidth = true)
        }
    }
}

/** The whole page below the top bar, for the app and for tests. */
@Composable
internal fun GetMusicBody(
    state: DownloadsViewModel.State,
    mode: GetMusicMode,
    onMode: (GetMusicMode) -> Unit,
    actions: GetMusicActions,
    modifier: Modifier = Modifier,
    soulseek: @Composable () -> Unit = { SoulseekSongs(state, actions, SoulseekActions()) },
    albums: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ModeAndSource(mode, onMode, state.preferredDownloadSource, actions.onOpenSourceSheet)
        when {
            mode == GetMusicMode.YouTube -> YouTubeLink(state, actions)
            mode == GetMusicMode.Albums -> albums()
            state.preferredDownloadSource == DownloadSource.SOULSEEK -> soulseek()
            else -> SpotiFlacSongs(state, actions)
        }
        RecentDownloads(state.downloadHistory)
        Text(stringResource(R.string.get_fair_use), style = RoomType.bodySmall, color = Room.muted)
    }
}
