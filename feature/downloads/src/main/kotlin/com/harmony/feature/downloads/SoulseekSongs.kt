package com.harmony.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.component.catalogPalette
import com.harmony.core.ui.room.RoomButton
import com.harmony.core.ui.room.RoomButtonKind
import com.harmony.core.ui.room.RoomCard
import com.harmony.core.ui.room.RoomChip
import com.harmony.core.ui.room.RoomSectionTitle
import com.harmony.core.ui.room.StatusLine
import com.harmony.core.ui.room.StatusTone
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType

/** What the Soulseek part of the page can do; every one is a DownloadsViewModel call. */
internal class SoulseekActions(
    val onUsername: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onSearchMode: (SoulseekSearchMode) -> Unit = {},
    val onFormat: (SoulseekFormatPreference) -> Unit = {},
    val onOnlyFreeSlots: (Boolean) -> Unit = {},
    val onGet: (SoulseekSearchCandidate) -> Unit = {},
    val onGetBest: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onPickDownloadFolder: () -> Unit = {},
    val onUseDefaultFolder: () -> Unit = {},
    val onPickShare: () -> Unit = {},
    val onRefreshShare: () -> Unit = {},
    val onClearShare: () -> Unit = {},
)

/**
 * Songs through Soulseek, in the same shape as SpotiFLAC: sign in once, then
 * one search, results with Get, and the network's options folded away.
 * Where files are saved and what you share sit under "Soulseek settings".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SoulseekSongs(
    state: DownloadsViewModel.State,
    actions: GetMusicActions,
    soulseek: SoulseekActions,
    modifier: Modifier = Modifier,
) {
    val r = Room
    val connection = state.soulseekConnection
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (connection.status != SoulseekConnectionStatus.CONNECTED) {
            SoulseekSignIn(state, soulseek)
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Person, null, tint = r.moss, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.slsk_signed_in, connection.username), style = RoomType.bodySmall, color = r.ink, modifier = Modifier.weight(1f))
            RoomButton(stringResource(R.string.slsk_sign_out), soulseek.onDisconnect, kind = RoomButtonKind.Text)
        }

        GetSearchField(state.query, actions.onQueryChange, actions.onSearch, stringResource(R.string.get_search_songs))
        var options by rememberSaveable { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoomButton(
                stringResource(R.string.get_search), actions.onSearch, icon = Icons.Rounded.Search,
                enabled = state.query.isNotBlank() && !state.isSoulseekSearching, loading = state.isSoulseekSearching,
            )
            RoomChip(stringResource(R.string.slsk_options), selected = options, onClick = { options = !options }, icon = Icons.Rounded.Tune)
        }
        if (options) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.slsk_how_wide), style = RoomType.labelSmall, color = r.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        SoulseekSearchMode.QUICK to R.string.slsk_mode_quick,
                        SoulseekSearchMode.BALANCED to R.string.slsk_mode_balanced,
                        SoulseekSearchMode.DEEP to R.string.slsk_mode_deep,
                    ).forEach { (mode, label) ->
                        RoomChip(stringResource(label), selected = state.soulseekSearchMode == mode, onClick = { soulseek.onSearchMode(mode) })
                    }
                }
                Text(stringResource(R.string.slsk_format), style = RoomType.labelSmall, color = r.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SoulseekFormatPreference.entries.forEach { pref ->
                        RoomChip(pref.label, selected = state.soulseekFormatPreference == pref, onClick = { soulseek.onFormat(pref) })
                    }
                }
                Text(state.soulseekFormatPreference.summary, style = RoomType.bodySmall, color = r.muted)
                RoomChip(stringResource(R.string.slsk_free_slots), selected = state.soulseekOnlyFreeSlots,
                    onClick = { soulseek.onOnlyFreeSlots(!state.soulseekOnlyFreeSlots) })
            }
        }

        state.soulseekTransfer?.let { transfer ->
            val fraction = if (transfer.totalBytes > 0) transfer.downloadedBytes.toFloat() / transfer.totalBytes else null
            TransferRow(
                title = transfer.filename.substringAfterLast('\\').substringAfterLast('/'),
                detail = transfer.queuePlace?.let { stringResource(R.string.slsk_queue_place, it, transfer.username) }
                    ?: "${transfer.status} · ${transfer.username}",
                fraction = fraction,
                onStop = soulseek.onCancel,
            )
        }
        if (state.soulseekSourceTotal > 1 && state.soulseekDownloadingId != null) {
            Text(stringResource(R.string.slsk_trying_source, state.soulseekSourceAttempt, state.soulseekSourceTotal), style = RoomType.bodySmall, color = r.muted)
        }
        DownloadProblem(state, actions)
        state.report?.let { QualityVerdict(it) }

        when {
            state.isSoulseekSearching && state.soulseekResults.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = r.amberText, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.slsk_searching), style = RoomType.body, color = r.muted)
            }
            state.soulseekResults.isNotEmpty() -> {
                RoomSectionTitle(
                    stringResource(R.string.get_results),
                    action = if (state.soulseekDownloadingId == null) stringResource(R.string.slsk_get_best) else null,
                    onAction = soulseek.onGetBest,
                )
                state.soulseekResults.forEach { c ->
                    val quality = listOfNotNull(
                        c.extension.uppercase(),
                        c.bitDepth?.takeIf { it > 0 }?.let { d -> c.sampleRate?.takeIf { it > 0 }?.let { "$d/${it / 1000}" } },
                        c.bitrateKbps?.takeIf { it > 0 && !c.isLossless }?.let { "$it kbps" },
                        "%.0f MB".format(c.sizeBytes / 1_048_576.0),
                    ).joinToString(" ")
                    val availability = if (c.freeUploadSlot) stringResource(R.string.slsk_free_now)
                    else stringResource(R.string.slsk_in_queue, c.queueLength)
                    ResultRow(
                        title = c.fileNameOnly,
                        subtitle = listOf(quality, c.username, availability).joinToString(" · "),
                        artworkUrl = null,
                        durationMs = (c.durationSeconds ?: 0) * 1000L,
                        selected = state.soulseekDownloadingId == c.id,
                        busy = state.soulseekDownloadingId == c.id,
                        onSelect = {},
                        onGet = { if (state.soulseekDownloadingId == null) soulseek.onGet(c) },
                    )
                }
            }
            state.message != null && state.error == null -> StatusLine(StatusTone.Info, title = state.message)
        }

        var settings by rememberSaveable { mutableStateOf(false) }
        RoomButton(
            stringResource(if (settings) R.string.slsk_hide_settings else R.string.slsk_settings),
            { settings = !settings }, kind = RoomButtonKind.Text, icon = Icons.Rounded.Folder,
        )
        if (settings) {
            RoomCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.slsk_saved_to), style = RoomType.title, color = r.ink)
                    Text(state.downloadFolderLabel, style = RoomType.bodySmall, color = r.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RoomButton(stringResource(R.string.slsk_choose_folder), soulseek.onPickDownloadFolder, kind = RoomButtonKind.Tonal)
                        if (state.usingCustomDownloadFolder) {
                            RoomButton(stringResource(R.string.slsk_use_default), soulseek.onUseDefaultFolder, kind = RoomButtonKind.Text)
                        }
                    }
                }
            }
            SoulseekSharingCard(
                sharedFolder = state.soulseekSharedFolder,
                isIndexing = state.isIndexingShare,
                onPick = soulseek.onPickShare,
                onRefresh = soulseek.onRefreshShare,
                onClear = soulseek.onClearShare,
                palette = catalogPalette(),
            )
        }
    }
}

/** Signing in to Soulseek: two fields and one button, with what went wrong said plainly. */
@Composable
internal fun SoulseekSignIn(state: DownloadsViewModel.State, soulseek: SoulseekActions) {
    val r = Room
    val connecting = state.soulseekConnection.status == SoulseekConnectionStatus.CONNECTING
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = r.surface, unfocusedContainerColor = r.surface,
        focusedBorderColor = r.ink, unfocusedBorderColor = r.outline,
        focusedLabelColor = r.ink, unfocusedLabelColor = r.muted, cursorColor = r.ink,
    )
    RoomCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.slsk_sign_in_title), style = RoomType.nameM, color = r.ink)
            Text(stringResource(R.string.slsk_sign_in_body), style = RoomType.bodySmall, color = r.muted)
            OutlinedTextField(
                state.soulseekUsername, soulseek.onUsername, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.slsk_username)) }, singleLine = true, enabled = !connecting,
                shape = RoundedCornerShape(12.dp), colors = colors, textStyle = RoomType.body,
            )
            OutlinedTextField(
                state.soulseekPassword, soulseek.onPassword, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.slsk_password)) }, singleLine = true, enabled = !connecting,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(12.dp), colors = colors, textStyle = RoomType.body,
            )
            if (state.soulseekConnection.status == SoulseekConnectionStatus.ERROR) {
                StatusLine(StatusTone.Error, title = state.soulseekConnection.message)
            }
            RoomButton(
                stringResource(R.string.slsk_sign_in), soulseek.onConnect, prominent = true, fillWidth = true,
                enabled = state.soulseekUsername.isNotBlank() && state.soulseekPassword.isNotBlank(), loading = connecting,
            )
        }
    }
}
