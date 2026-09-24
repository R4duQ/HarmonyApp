package com.harmony.feature.discover.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.glassFill
import com.harmony.core.ui.component.glassRim
import com.harmony.domain.library.discovery.CandidateKind
import com.harmony.domain.library.discovery.DraftItem
import com.harmony.domain.library.discovery.ExplorationLevel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun SongsStep(
    state: DiscoveryUiState,
    preview: PreviewState,
    palette: EditorialPalette,
    actions: DiscoveryActions,
    bottomPadding: Dp,
) {
    val draft = state.draft ?: return
    val items = draft.items
    val pager = rememberPagerState { items.size }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val large = LocalDensity.current.fontScale > 1.3f
    val currentItems by rememberUpdatedState(items)
    // The settled page is the song on screen: a preview for any other song stops.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page -> actions.focus(currentItems.getOrNull(page)?.key) }
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().testTag(DiscoveryTags.SONGS),
        contentPadding = PaddingValues(top = 12.dp, bottom = bottomPadding + 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepHeader("Your selection", "${items.size} of ${draft.size} songs · ${draft.keptCount} kept", "Back to preferences",
                    actions.backToPreferences, palette, subtitleModifier = Modifier.testTag(DiscoveryTags.COUNT))
                StepIndicator(1, palette)
            }
        }
        if (state.connection != Connection.ONLINE && state.connection != Connection.CHECKING) item(key = "offline") {
            ConnectionNotice(state, palette, Modifier.padding(horizontal = Gutter).testTag(DiscoveryTags.OFFLINE))
        }
        if (state.showingSaved || state.notes.isNotEmpty()) item(key = "notes") {
            DiscoverCard(palette, Modifier.padding(horizontal = Gutter).testTag(DiscoveryTags.NOTES)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.showingSaved) Text("Saved results · made ${ago(draft.generatedAt)}. Connect to refresh.",
                        color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.testTag(DiscoveryTags.SAVED))
                    state.notes.take(3).forEach { Text(it, color = palette.muted, fontSize = 13.sp, lineHeight = 17.sp) }
                }
            }
        }
        item(key = "level") {
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExplorationLevel.entries.forEach { level ->
                        ChoicePill(level.label, draft.level == level, palette, { actions.changeLevel(level) }, enabled = !state.generating,
                            modifier = Modifier.testTag(DiscoveryTags.level(level)))
                    }
                }
                val close = items.count { it.kind == CandidateKind.CLOSE }
                Text("$close close to your taste · ${items.size - close} discoveries" +
                    if (state.generating) " · updating…" else "", color = palette.muted, fontSize = 13.sp)
            }
        }
        item(key = "pager") {
            HorizontalPager(
                state = pager,
                key = { items.getOrNull(it)?.key ?: "gone-$it" },
                contentPadding = PaddingValues(horizontal = Gutter + 8.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth().testTag(DiscoveryTags.PAGER),
            ) { page ->
                val item = items.getOrNull(page) ?: return@HorizontalPager
                FocusCard(item, page, items.size, preview, item.key in state.replacing, palette, actions, large)
            }
        }
        item(key = "list-title") { SectionTitle("All songs", palette, Modifier.padding(horizontal = Gutter)) }
        itemsIndexed(items, key = { _, it -> "row:${it.key}" }) { index, item ->
            SongRow(index, item, item.key in state.replacing, palette,
                onClick = { scope.launch { pager.animateScrollToPage(index); list.animateScrollToItem(0) } })
        }
    }
}

/** The action bar for step 2. */
@Composable
internal fun SongsAction(state: DiscoveryUiState, palette: EditorialPalette, actions: DiscoveryActions, modifier: Modifier) {
    val draft = state.draft ?: return
    val large = LocalDensity.current.fontScale > 1.3f
    val secondary: @Composable (Modifier) -> Unit = { m ->
        if (draft.items.size < draft.size) GlassButton("Fill to ${draft.size}", palette, actions.fill, enabled = !state.generating,
            modifier = m.testTag(DiscoveryTags.FILL))
        else GlassButton("New songs", palette, actions.regenerate, enabled = !state.generating, icon = Icons.Rounded.Refresh,
            modifier = m.testTag(DiscoveryTags.REGENERATE).semantics { contentDescription = "Replace every song that isn't kept" })
    }
    val primary: @Composable (Modifier) -> Unit = { m ->
        PrimaryButton("Review (${draft.items.size})", palette, actions.goToReview, enabled = draft.items.isNotEmpty() && !state.generating,
            modifier = m.testTag(DiscoveryTags.REVIEW))
    }
    // Large text: full-width buttons, one under the other, so neither label breaks.
    if (large) Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { primary(Modifier.fillMaxWidth()); secondary(Modifier.fillMaxWidth()) }
    else Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        secondary(Modifier); primary(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusCard(
    item: DraftItem,
    page: Int,
    total: Int,
    preview: PreviewState,
    replacing: Boolean,
    palette: EditorialPalette,
    actions: DiscoveryActions,
    large: Boolean,
) {
    val mine = preview.takeIf { it.songId == item.key }
    DiscoverCard(palette, Modifier.fillMaxWidth().animateContentSize().testTag(DiscoveryTags.focus(item.key))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val cover: @Composable () -> Unit = {
                Box(Modifier.size(if (large) 112.dp else 96.dp)) {
                    Cover(item.song.artwork, palette, Modifier.fillMaxSize(), RoundedCornerShape(16.dp), glyphSize = 40.dp, elevation = 6.dp)
                    if (replacing) Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(palette.field.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center) { Spinner(palette.ink, Modifier.size(28.dp)) }
                }
            }
            val text: @Composable (Modifier) -> Unit = { m ->
                Column(m.semantics(mergeDescendants = true) {}) {
                    Text("${page + 1} of $total", color = palette.muted, fontSize = 12.sp)
                    Text(item.song.title, color = palette.ink, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(item.song.artist, color = palette.ink.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Cover beside the title so the song, its reason and its actions fit on one screen.
            if (large) { cover(); text(Modifier) }
            else Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                cover(); text(Modifier.weight(1f))
            }
            Text(item.reason.text, color = palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(if (item.kind == CandidateKind.CLOSE) "Close to your taste" else "Discovery", palette)
                if (item.song.localUri != null) Badge("In your library", palette, Icons.Rounded.CheckCircle)
                else Badge("Needs download", palette, Icons.Rounded.CloudDownload)
                if (item.song.provider != "Library") Badge("Info: ${item.song.provider}", palette)
                if (item.kept) Badge("Kept", palette, Icons.Rounded.PushPin)
            }
            GlassButton(
                text = when {
                    mine?.loading == true -> "Loading preview… tap to cancel"
                    mine?.playing == true -> "Stop preview"
                    else -> "Listen · 30 s"
                },
                palette = palette,
                onClick = { actions.togglePreview(item) },
                icon = if (mine?.playing == true || mine?.loading == true) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                enabled = !replacing,
                modifier = Modifier.fillMaxWidth().testTag(DiscoveryTags.PREVIEW),
            )
            mine?.message?.let { Text(it, color = palette.muted, fontSize = 12.sp, modifier = Modifier.testTag(DiscoveryTags.PREVIEW_NOTE)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RoundAction(Icons.Rounded.PushPin, if (item.kept) "Stop keeping" else "Keep when regenerating", palette,
                    { actions.toggleKeep(item.key) }, enabled = !replacing, active = item.kept, modifier = Modifier.testTag(DiscoveryTags.KEEP))
                RoundAction(Icons.Rounded.ThumbUp, "More like this", palette, { actions.moreLike(item.key) }, enabled = !replacing,
                    modifier = Modifier.testTag(DiscoveryTags.MORE_LIKE))
                RoundAction(Icons.Rounded.SwapHoriz, "Replace this song", palette, { actions.replace(item.key) }, enabled = !replacing,
                    modifier = Modifier.testTag(DiscoveryTags.REPLACE))
                RoundAction(Icons.Rounded.Block, "Not interested", palette, { actions.notInterested(item.key) }, enabled = !replacing,
                    modifier = Modifier.testTag(DiscoveryTags.NOT_INTERESTED))
                RoundAction(Icons.Rounded.Delete, "Remove from selection", palette, { actions.remove(item.key) }, enabled = !replacing,
                    modifier = Modifier.testTag(DiscoveryTags.REMOVE))
            }
        }
    }
}

@Composable
private fun SongRow(index: Int, item: DraftItem, replacing: Boolean, palette: EditorialPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick, onClickLabel = "Show in the card above").padding(horizontal = Gutter, vertical = 4.dp)
            .testTag(DiscoveryTags.row(item.key)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${index + 1}", color = palette.muted, fontSize = 13.sp, modifier = Modifier.widthIn(min = 22.dp))
        Box {
            Cover(item.song.artwork, palette, Modifier.size(48.dp))
            if (replacing) Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(palette.field.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center) { Spinner(palette.ink, Modifier.size(18.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(item.song.title, color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.song.artist, color = palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.reason.text, color = palette.muted.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item.kept) Icon(Icons.Rounded.PushPin, "Kept", tint = palette.accent, modifier = Modifier.size(18.dp))
        if (item.song.localUri != null) Icon(Icons.Rounded.CheckCircle, "In your library", tint = palette.ink.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun Badge(text: String, palette: EditorialPalette, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier.clip(shape).background(glassFill(palette)).border(1.dp, palette.line, shape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = palette.ink, modifier = Modifier.size(14.dp))
        Text(text, color = palette.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        at <= 0 -> "earlier"
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
