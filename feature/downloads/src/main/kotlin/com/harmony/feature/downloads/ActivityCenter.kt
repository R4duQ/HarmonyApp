package com.harmony.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.room.RoomButton
import com.harmony.core.ui.room.RoomButtonKind
import com.harmony.core.ui.room.RoomEmptyState
import com.harmony.core.ui.room.RoomSectionTitle
import com.harmony.core.ui.room.StatusLine
import com.harmony.core.ui.room.StatusTone
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType

/** How much is going on, for the Activity pill in every top bar. */
data class ActivityCounts(val running: Int, val needsYou: Int, val failed: Int)

/** Counts what is running, waiting on the listener, and failed, from the download mirror and the album queue. */
@Composable
fun rememberActivityCounts(
    banner: DownloadBannerViewModel = hiltViewModel(),
    albums: AlbumQueueViewModel = hiltViewModel(),
): ActivityCounts {
    val active by banner.active.collectAsStateWithLifecycle()
    val outcome by banner.outcome.collectAsStateWithLifecycle()
    val batches by albums.batches.collectAsStateWithLifecycle()
    val runningAlbums = batches.count { it.active }
    val running = runningAlbums + if (active != null && active?.albumId == null) 1 else 0
    val failed = batches.count { it.state == AlbumBatchState.FAILED || it.state == AlbumBatchState.PARTIAL } +
        if (outcome?.succeeded == false) 1 else 0
    val waiting = batches.count { it.state == AlbumBatchState.PAUSED }
    return ActivityCounts(running = running, needsYou = waiting, failed = failed)
}

/**
 * Everything Harmony is doing in the background, in one place
 * (principle 4): the song being fetched, each album in the queue with its
 * own Pause, Resume and Cancel, and how the last download ended. The
 * engines' own screens stay one tap away ([onOpenAll]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitySheet(
    onDismiss: () -> Unit,
    onOpenAll: () -> Unit,
    banner: DownloadBannerViewModel = hiltViewModel(),
    albums: AlbumQueueViewModel = hiltViewModel(),
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Room.surface,
        contentColor = Room.ink,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.activity_title), style = RoomType.nameL, color = Room.ink)
            ActivityList(banner = banner, albums = albums, showEmpty = true)
            RoomButton(
                stringResource(R.string.activity_open_all),
                onClick = { onDismiss(); onOpenAll() },
                kind = RoomButtonKind.Tonal,
                fillWidth = true,
            )
        }
    }
}

/**
 * The running and finished work as a list. Shared by the Activity sheet and
 * Add's "In progress" section, so both always say the same thing.
 */
@Composable
fun ActivityList(
    modifier: Modifier = Modifier,
    showEmpty: Boolean = false,
    banner: DownloadBannerViewModel = hiltViewModel(),
    albums: AlbumQueueViewModel = hiltViewModel(),
) {
    val active by banner.active.collectAsStateWithLifecycle()
    val outcome by banner.outcome.collectAsStateWithLifecycle()
    val batches by albums.batches.collectAsStateWithLifecycle()
    val visibleBatches = batches.filter { it.state != AlbumBatchState.READY && it.state != AlbumBatchState.COMPLETED }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val single = active?.takeIf { it.albumId == null }
        if (single == null && visibleBatches.isEmpty() && outcome == null) {
            if (showEmpty) {
                RoomEmptyState(
                    title = stringResource(R.string.activity_idle_title),
                    body = stringResource(R.string.activity_idle_body),
                )
            }
            return@Column
        }
        if (single != null) {
            ProgressRow(
                title = single.title,
                detail = single.detail ?: stringResource(R.string.activity_getting),
                fraction = single.fraction,
                showBar = true,
            )
        }
        if (visibleBatches.isNotEmpty()) {
            RoomSectionTitle(stringResource(R.string.activity_albums))
            visibleBatches.forEach { batch ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ProgressRow(
                        title = batch.title,
                        detail = "${batch.artist} · ${batch.statusLabel}",
                        fraction = batch.fraction,
                        showBar = batch.active,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (batch.canPause) {
                            RoomButton(stringResource(R.string.activity_pause), { albums.pause(batch.albumId) }, kind = RoomButtonKind.Text)
                        }
                        if (batch.canResume) {
                            RoomButton(stringResource(R.string.activity_resume), { albums.resume(batch.albumId) }, kind = RoomButtonKind.Tonal)
                        }
                        if (batch.canCancel) {
                            RoomButton(stringResource(R.string.activity_stop), { albums.cancel(batch.albumId) }, kind = RoomButtonKind.Text)
                        }
                    }
                }
            }
        }
        outcome?.let { last ->
            StatusLine(
                tone = if (last.succeeded) StatusTone.Success else StatusTone.Error,
                title = if (last.succeeded) {
                    stringResource(R.string.activity_added, last.title)
                } else {
                    stringResource(R.string.activity_failed, last.title)
                },
                body = last.message,
                action = stringResource(R.string.activity_dismiss),
                onAction = { banner.consumeOutcome(last.id) },
            )
        }
    }
}

@Composable
private fun ProgressRow(title: String, detail: String, fraction: Float?, showBar: Boolean) {
    val r = Room
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $detail"
                if (showBar && fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = RoomType.title, color = r.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = RoomType.bodySmall, color = r.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        if (!showBar) return@Column
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = r.amber,
                trackColor = r.raised,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = r.amber,
                trackColor = r.raised,
                gapSize = 0.dp,
            )
        }
    }
}
