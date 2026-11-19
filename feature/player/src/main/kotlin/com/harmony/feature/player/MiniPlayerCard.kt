package com.harmony.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.Song
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType
import kotlin.math.abs

/** Everything the mini player shows. */
data class MiniPlayerUi(
    val song: Song,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val shuffleOn: Boolean,
    val repeatMode: RepeatMode,
    /** Where the music is playing, as the listener knows it ("Pixel Buds Pro"). */
    val deviceLabel: String,
    val deviceIcon: ImageVector,
    /** The next songs, with their place in the queue. */
    val upNext: List<Pair<Int, Song>>,
    /**
     * What the mix is doing or why this song was picked, in plain words
     * ([miniTelemetryLine]); shown under the title instead of the artist when
     * there is one. Null for none.
     */
    val telemetry: String? = null,
)

class MiniPlayerActions(
    val onExpand: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onUpNext: (Int) -> Unit = {},
)

internal object MiniPlayerTags {
    const val CARD = "mini_card"
    const val PROGRESS = "mini_progress"
    const val PLAY = "mini_play"
}

/** How far a sideways swipe travels before it changes the song. */
private val SwipeToSkip = 56.dp
/** How far an upward swipe travels before it opens the player. */
private val SwipeToOpen = 32.dp

/**
 * The mini player: the record block, the one dark object on every page.
 *
 * Built for a glance from arm's length (principle 3): the song's name in the
 * name face at 22 sp (16 sp when the system text is large), the artist or
 * what the mix is doing beneath it, and play/pause as a 56 dp amber target
 * at the right edge, where the thumb rests. A thin line along the bottom shows
 * how far into the song you are.
 *
 * Tap or swipe up to open Now Playing; swipe sideways to change the song.
 * To a screen reader it is one item ("Now playing: …, by …. Playing.") whose
 * actions are Play or Pause, Next song, Previous song and Open player, so no
 * gesture is the only way to do anything (accessibility A3, A8).
 */
@Composable
fun MiniPlayerCard(ui: MiniPlayerUi, actions: MiniPlayerActions, modifier: Modifier = Modifier) {
    val r = Room
    val current by rememberUpdatedState(actions)
    val density = LocalDensity.current
    val largeText = density.fontScale > 1.3f
    val second = ui.telemetry ?: ui.song.artist

    val playLabel = stringResource(if (ui.isPlaying) R.string.player_pause else R.string.player_play)
    val nextLabel = stringResource(R.string.player_next)
    val previousLabel = stringResource(R.string.player_previous)
    val openLabel = stringResource(R.string.player_open)
    val spoken = stringResource(R.string.player_mini_spoken, ui.song.title, ui.song.artist) +
        (ui.telemetry?.let { ". $it" } ?: "")
    val state = stringResource(if (ui.isPlaying) R.string.player_state_playing else R.string.player_state_paused)

    val shape = RoundedCornerShape(20.dp)
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val skipPx = with(density) { SwipeToSkip.toPx() }
    val openPx = with(density) { SwipeToOpen.toPx() }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .shadow(12.dp, shape, ambientColor = r.record, spotColor = r.record)
            .clip(shape)
            .background(r.record)
            .testTag(MiniPlayerTags.CARD)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragX = 0f; dragY = 0f },
                    onDragEnd = {
                        when {
                            -dragY > openPx && abs(dragY) > abs(dragX) -> current.onExpand()
                            dragX < -skipPx -> current.onNext()
                            dragX > skipPx -> current.onPrevious()
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                    },
                )
            }
            .clickable(role = Role.Button) { current.onExpand() }
            .clearAndSetSemantics {
                contentDescription = spoken
                stateDescription = state
                role = Role.Button
                onClick(openLabel) { current.onExpand(); true }
                customActions = listOf(
                    CustomAccessibilityAction(playLabel) { current.onPlayPause(); true },
                    CustomAccessibilityAction(nextLabel) { current.onNext(); true },
                    CustomAccessibilityAction(previousLabel) { current.onPrevious(); true },
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                ui.song.artworkUri,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                cornerRadius = 8.dp,
                fallback = {
                    Box(Modifier.size(48.dp).background(r.raised.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MusicNote, null, tint = r.onRecordMuted)
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    ui.song.title,
                    style = if (largeText) RoomType.title else RoomType.nameL,
                    color = r.onRecord,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    second,
                    style = if (ui.telemetry != null) RoomType.bodySmall else RoomType.glance.copy(fontSize = RoomType.bodySmall.fontSize, lineHeight = RoomType.bodySmall.lineHeight),
                    color = r.onRecordMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(r.recordLive)
                    .clickable(onClick = { current.onPlayPause() })
                    .testTag(MiniPlayerTags.PLAY),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = r.record,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        val fraction = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp)
                .padding(bottom = 4.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(r.onRecord.copy(alpha = 0.14f))
                .testTag(MiniPlayerTags.PROGRESS),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(r.recordLive))
        }
    }
}
