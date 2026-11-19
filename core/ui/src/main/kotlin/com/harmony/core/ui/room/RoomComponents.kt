package com.harmony.core.ui.room

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.R
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType

/*
 * The Listening Room's shared components (docs/redesign/09-handoff.md §3).
 * Every one meets the 48 dp target, shows focus with a 2 dp ink ring, and
 * never lets the amber fill carry a state on its own.
 */

enum class RoomButtonKind { Filled, Tonal, Text, Destructive }

/**
 * The button. Filled is the one amber action of a view; Tonal is a secondary
 * action; Text is the quietest; Destructive is wine and always says what it
 * destroys. [prominent] makes it 56 dp tall, for a flow's main action and on
 * glance surfaces.
 */
@Composable
fun RoomButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: RoomButtonKind = RoomButtonKind.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    prominent: Boolean = false,
    fillWidth: Boolean = false,
) {
    val r = Room
    val (fill, content) = when (kind) {
        RoomButtonKind.Filled -> r.amber to r.onAmber
        RoomButtonKind.Tonal -> r.amberSoft to r.onAmberSoft
        RoomButtonKind.Text -> Color.Transparent to r.amberText
        RoomButtonKind.Destructive -> r.wine to r.onWine
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = CircleShape
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = if (prominent) 56.dp else 48.dp)
            .then(if (focused) Modifier.border(2.dp, r.ink, shape).padding(2.dp) else Modifier)
            .clip(shape)
            .background(if (enabled) fill else if (kind == RoomButtonKind.Text) Color.Transparent else r.raised)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = content),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (kind == RoomButtonKind.Text) 12.dp else 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = content.copy(alpha = alpha)
        when {
            loading -> {
                CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            icon != null -> {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(text, style = RoomType.label, color = tint, textAlign = TextAlign.Center)
    }
}

/**
 * A chip that is on or off. On shows a check as well as the soft amber fill,
 * so the state never rests on colour (accessibility A23).
 */
@Composable
fun RoomChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val r = Room
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(if (selected) r.amberSoft else r.surface)
                .then(if (selected) Modifier else Modifier.border(1.dp, r.line, shape))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (selected) r.onAmberSoft else r.ink
            val alpha = if (enabled) 1f else 0.38f
            val lead = if (selected) Icons.Rounded.Check else icon
            if (lead != null) {
                Icon(lead, null, tint = color.copy(alpha = alpha), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = RoomType.labelSmall, color = color.copy(alpha = alpha), maxLines = 1)
        }
    }
}

enum class StatusTone { Info, Success, Waiting, Error }

/**
 * A status in words: what happened, then (in [body]) what Harmony already
 * tried, then one action. Announced politely to screen readers when it
 * changes. The tone is an icon and a rule as well as a colour.
 */
@Composable
fun StatusLine(
    tone: StatusTone,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val r = Room
    val (color, icon) = when (tone) {
        StatusTone.Info -> r.muted to Icons.Rounded.Info
        StatusTone.Success -> r.moss to Icons.Rounded.CheckCircle
        StatusTone.Waiting -> r.slate to Icons.Rounded.Schedule
        StatusTone.Error -> r.wine to Icons.Rounded.ErrorOutline
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(r.surface)
            .border(1.dp, r.line, shape)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 56.dp).background(color))
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = RoomType.title, color = r.ink)
                if (body != null) Text(body, style = RoomType.bodySmall, color = r.muted)
                if (action != null && onAction != null) {
                    RoomButton(action, onAction, kind = RoomButtonKind.Text, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/**
 * The file's quality as a small tag ("Hi-Res 24/96", "Lossless", "Online").
 * [spoken] is how a screen reader says it, in full words.
 */
@Composable
fun FormatTag(label: String, modifier: Modifier = Modifier, spoken: String = label, onRecord: Boolean = false) {
    val r = Room
    val color = if (onRecord) r.onRecord else r.ink
    val edge = if (onRecord) r.onRecordMuted.copy(alpha = 0.5f) else r.line
    Text(
        label.uppercase(),
        style = RoomType.micro,
        color = color,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, edge, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = spoken },
    )
}

/**
 * What Harmony is doing in the background, in the top bar: hidden when
 * nothing runs, a count while things run, slate when something needs you,
 * wine when something failed. Opens the Activity sheet.
 */
@Composable
fun ActivityPill(
    running: Int,
    needsYou: Int,
    failed: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = running + needsYou + failed > 0
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        val r = Room
        val (dot, count) = when {
            failed > 0 -> r.wine to failed
            needsYou > 0 -> r.slate to needsYou
            else -> r.amberText to running
        }
        val spoken = stringResource(R.string.room_activity_summary, running, needsYou, failed)
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.clip(CircleShape).background(r.raised).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text(count.toString(), style = RoomType.labelSmall, color = r.ink)
            }
        }
    }
}

/** An icon button with a 48 dp target and a label for screen readers. */
@Composable
fun RoomIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Room.ink,
    size: Dp = 24.dp,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size))
    }
}

/**
 * The top of a page. A destination shows its name in the display face; a
 * detail page shows a Back button and a smaller name. [actions] sit at the end.
 */
@Composable
fun RoomTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    applyStatusBarInset: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val r = Room
    Row(
        modifier
            .fillMaxWidth()
            .then(if (applyStatusBarInset) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .heightIn(min = 64.dp)
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            RoomIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.room_back), onBack)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            style = if (onBack == null) RoomType.display else RoomType.nameL,
            color = r.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * Two to four options, one selected: a raised track with the selection on a
 * surface thumb, its label in amber text and semibold (the state is not
 * colour alone). A selectable group to screen readers, each option a tab.
 */
@Composable
fun RoomSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val r = Room
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(r.raised)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(if (on) r.surface else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = RoomType.labelSmall,
                    color = if (on) r.amberText else r.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** A section's name, read as a heading. */
@Composable
fun RoomSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = RoomType.nameM,
            color = Room.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (action != null && onAction != null) RoomButton(action, onAction, kind = RoomButtonKind.Text)
    }
}

/**
 * An empty state: what this is, why it is empty, and how to start. No
 * illustration; a title, one sentence and one action.
 */
@Composable
fun RoomEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    secondAction: String? = null,
    onSecondAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = RoomType.nameM, color = Room.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(body, style = RoomType.body, color = Room.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (action != null && onAction != null) RoomButton(action, onAction)
        if (secondAction != null && onSecondAction != null) RoomButton(secondAction, onSecondAction, kind = RoomButtonKind.Text)
    }
}

/** A card on the surface tone with a hairline: one step off the ground. */
@Composable
fun RoomCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(Room.surface)
            .border(1.dp, Room.line, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
    ) { content() }
}
