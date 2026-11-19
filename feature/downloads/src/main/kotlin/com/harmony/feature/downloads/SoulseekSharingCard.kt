package com.harmony.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.room.RoomButton
import com.harmony.core.ui.room.RoomButtonKind
import com.harmony.core.ui.room.RoomCard
import com.harmony.core.ui.room.StatusLine
import com.harmony.core.ui.room.StatusTone
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType
import androidx.compose.material3.Text
import java.util.Locale

/**
 * Sharing back on Soulseek. The network runs on give-and-take and some people
 * refuse to send to someone who shares nothing, so the card says that, and
 * says plainly what picking a folder gives Harmony access to.
 */
@Composable
fun SoulseekSharingCard(
    sharedFolder: SoulseekSharedFolder?,
    isIndexing: Boolean,
    onPick: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    @Suppress("UNUSED_PARAMETER") palette: com.harmony.core.ui.component.EditorialPalette? = null,
    modifier: Modifier = Modifier,
) {
    val r = Room
    RoomCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.share_title), style = RoomType.title, color = r.ink)
            when {
                isIndexing -> StatusLine(StatusTone.Info, stringResource(R.string.share_indexing))
                sharedFolder == null -> {
                    Text(stringResource(R.string.share_nothing), style = RoomType.bodySmall, color = r.muted)
                    Text(stringResource(R.string.share_access_note), style = RoomType.bodySmall, color = r.muted)
                }
                else -> {
                    StatusLine(
                        StatusTone.Success,
                        stringResource(R.string.share_sharing, sharedFolder.displayName),
                        body = stringResource(R.string.share_counts, sharedFolder.fileCount, sharedFolder.folderCount, size(sharedFolder.totalBytes)) +
                            if (sharedFolder.truncated) "\n" + stringResource(R.string.share_truncated) else "",
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RoomButton(
                    stringResource(if (sharedFolder == null) R.string.share_choose else R.string.share_change),
                    onPick, kind = RoomButtonKind.Tonal, enabled = !isIndexing,
                )
                if (sharedFolder != null) {
                    RoomButton(stringResource(R.string.share_rescan), onRefresh, kind = RoomButtonKind.Text, enabled = !isIndexing)
                    RoomButton(stringResource(R.string.share_stop), onClear, kind = RoomButtonKind.Text)
                }
            }
        }
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes bytes"
}
