package com.harmony.feature.discover.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.GlassCard
import com.harmony.core.ui.component.glassFill
import com.harmony.core.ui.component.glassRim

internal val Gutter = 20.dp

/** A small scale-down on press; no ripple, it fights the frosted surface. */
@Composable
internal fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role = Role.Button,
    pressedScale: Float = 0.97f,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
}

/**
 * [GlassCard] with its frost pre-composited onto the page colour, so the
 * card's own shadow can't show through the translucent fill as a dark band.
 * GlassCard itself is untouched.
 */
@Composable
internal fun DiscoverCard(
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body = remember(palette) {
        Brush.verticalGradient(listOf(
            glassFill(palette, strength = 0.72f).compositeOver(palette.field),
            glassFill(palette, strength = 0.58f).compositeOver(palette.field),
        ))
    }
    GlassCard(palette = palette, modifier = modifier) {
        Column(Modifier.fillMaxWidth().background(body), content = content)
    }
}

@Composable
internal fun Cover(
    uri: String?,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    glyphSize: Dp = 22.dp,
    elevation: Dp = 0.dp,
) {
    Box(
        modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = palette.ink, spotColor = palette.ink) else Modifier)
            .clip(shape)
            .background(Brush.linearGradient(listOf(palette.accent.copy(alpha = 0.26f), palette.accent.copy(alpha = 0.10f))))
            .border(1.dp, palette.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = palette.accent.copy(alpha = 0.8f), modifier = Modifier.size(glyphSize))
        if (!uri.isNullOrBlank()) AsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Four covers in a square, for a playlist that doesn't exist yet. */
@Composable
internal fun MosaicCover(artwork: List<String>, palette: EditorialPalette, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    if (artwork.size < 4) { Cover(artwork.firstOrNull(), palette, modifier, shape, glyphSize = 40.dp, elevation = 8.dp); return }
    Box(modifier.shadow(8.dp, shape, ambientColor = palette.ink, spotColor = palette.ink).clip(shape).background(palette.field)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            for (row in 0..1) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                for (col in 0..1) Cover(artwork[row * 2 + col], palette, Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
            }
        }
    }
}

/** A frosted pill; selected pills turn solid. Selection is announced, not only coloured. */
@Composable
internal fun ChoicePill(
    text: String,
    selected: Boolean,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) palette.ink else glassFill(palette))
            .border(1.dp, if (selected) palette.ink else palette.line, shape)
            .pressable(onClick, enabled = enabled, role = Role.Checkbox, pressedScale = 0.95f)
            .semantics { this.selected = selected; stateDescription = if (selected) "Selected" else "Not selected" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (selected) palette.field else palette.ink
        if (selected) Icon(Icons.Rounded.Check, null, tint = ink, modifier = Modifier.size(16.dp))
        else if (leading != null) Icon(leading, null, tint = ink, modifier = Modifier.size(16.dp))
        Text(text, color = if (enabled) ink else ink.copy(alpha = 0.5f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Primary action: solid accent capsule, 52dp tall. */
@Composable
internal fun PrimaryButton(
    text: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    busy: Boolean = false,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .heightIn(min = 52.dp)
            .then(if (enabled) Modifier.shadow(10.dp, shape, ambientColor = palette.accent, spotColor = palette.accent) else Modifier)
            .clip(shape)
            .background(if (enabled) palette.accent else palette.line)
            .pressable(onClick, enabled = enabled && !busy)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (enabled) palette.onAccent else palette.muted
        if (busy) Spinner(ink, Modifier.size(18.dp)) else if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        if (busy || icon != null) Spacer(Modifier.width(8.dp))
        Text(text, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Secondary action: frosted capsule. */
@Composable
internal fun GlassButton(
    text: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(glassFill(palette))
            .border(1.dp, palette.line, shape)
            .pressable(onClick, enabled = enabled)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (enabled) palette.ink else palette.muted.copy(alpha = 0.6f)
        if (icon != null) { Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = ink, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 48dp target, frosted 44dp disc; the label is the accessibility name. */
@Composable
internal fun RoundAction(
    icon: ImageVector,
    label: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    Box(
        modifier.size(48.dp).clip(CircleShape).pressable(onClick, enabled = enabled, pressedScale = 0.9f)
            .semantics { contentDescription = label; if (active) stateDescription = "On" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(if (active) palette.accent else glassFill(palette))
                .border(1.dp, glassRim(palette), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = when {
                !enabled -> palette.muted.copy(alpha = 0.5f)
                active -> palette.onAccent
                else -> palette.ink
            }, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun SectionTitle(text: String, palette: EditorialPalette, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
        Text(text, color = palette.ink, fontSize = if (large) 15.sp else 19.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

/** The three steps, with the current one marked for sight and for TalkBack. */
@Composable
internal fun StepIndicator(current: Int, palette: EditorialPalette, modifier: Modifier = Modifier) {
    val names = listOf("Preferences", "Songs", "Playlist")
    val description = "Step ${current + 1} of 3: ${names[current]}"
    // Large text: one line that always fits, instead of three squeezed labels.
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
        Text("Step ${current + 1} of 3 · ${names[current]}", color = palette.muted, fontSize = 13.sp,
            modifier = modifier.semantics { contentDescription = description })
        return
    }
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        names.forEachIndexed { i, name ->
            val done = i < current; val here = i == current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(if (here || done) palette.accent else glassFill(palette))
                        .border(1.dp, if (here || done) palette.accent else palette.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) Icon(Icons.Rounded.Check, null, tint = palette.onAccent, modifier = Modifier.size(14.dp))
                    else Text("${i + 1}", color = if (here) palette.onAccent else palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(name, color = if (here) palette.ink else palette.muted, fontSize = 12.sp,
                    fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Back button and step title; stacked at large text sizes so the title never breaks mid-word. */
@Composable
internal fun StepHeader(
    title: String,
    subtitle: String?,
    backLabel: String,
    onBack: () -> Unit,
    palette: EditorialPalette,
    backModifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier,
) {
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val back: @Composable () -> Unit = {
        RoundAction(Icons.AutoMirrored.Rounded.ArrowBack, backLabel, palette, onBack, modifier = backModifier)
    }
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(title, color = palette.ink, fontSize = if (large) 18.sp else 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, color = palette.muted, fontSize = 14.sp, modifier = subtitleModifier) }
        }
    }
    if (large) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { back(); text(Modifier) }
    else Row(verticalAlignment = Alignment.CenterVertically) { back(); text(Modifier.weight(1f).padding(start = 6.dp)) }
}

/** Offline / reconnecting / saved-results notice. Says what still works. */
@Composable
internal fun ConnectionNotice(state: DiscoveryUiState, palette: EditorialPalette, modifier: Modifier = Modifier) {
    val (icon, title, body) = when (state.connection) {
        Connection.OFFLINE -> Triple(Icons.Rounded.CloudOff, "You're offline",
            if (state.step == com.harmony.domain.library.discovery.DraftStep.PREFERENCES)
                "New online recommendations are paused. Harmony can still build a selection from your library."
            else "Your selection is saved. Replacing songs uses your library until you're back online.")
        Connection.RECONNECTING -> Triple(Icons.Rounded.Sync, "Back online in ${state.reconnectSeconds} s",
            "Checking the connection before loading new songs.")
        else -> return
    }
    DiscoverCard(palette, modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = palette.ink, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(body, color = palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
internal fun Placeholder(palette: EditorialPalette, modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val pulse = rememberInfiniteTransition(label = "placeholder")
    val alpha by pulse.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "alpha")
    Box(modifier.graphicsLayer { this.alpha = alpha }.clip(shape).background(palette.line.copy(alpha = 0.6f)))
}

/** A small rotating ring; bounded work only, never used as an endless page state. */
@Composable
internal fun Spinner(color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    androidx.compose.material3.CircularProgressIndicator(modifier, color = color, strokeWidth = 2.dp)
}

internal fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val h = minutes / 60; val m = minutes % 60
    return if (h > 0) "${h} hr ${m} min" else "$m min"
}
