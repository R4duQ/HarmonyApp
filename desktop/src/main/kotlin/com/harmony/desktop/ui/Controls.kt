package com.harmony.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Harmony's on/off switch: an accent track when on, a quiet one when off, the knob sliding across. */
@Composable
fun HarmonySwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalHarmonyColors.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val track by animateColorAsState(
        when {
            checked -> Accent.Purple
            hovered -> c.ink.copy(alpha = 0.22f)
            else -> c.ink.copy(alpha = 0.16f)
        },
        tween(160),
    )
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, tween(160))
    Box(
        modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(track)
            .hoverable(hover, enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(hover, indication = null, enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .offset(x = x)
                .size(18.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** A small quiet button for a card's corner (Reset, Pair a phone...). */
@Composable
fun TonalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = LocalHarmonyColors.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val accent = if (c.dark) Accent.PurpleLight else Accent.Purple
    Row(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Accent.Purple.copy(alpha = if (hovered) 0.2f else if (c.dark) 0.12f else 0.08f))
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(hover, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp).padding(end = 0.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent, modifier = Modifier.padding(start = if (icon != null) 6.dp else 0.dp))
    }
}

/** A card's title row: a coloured tile with an icon, the title and a line under it, and room on the right. */
@Composable
fun CardHeader(icon: ImageVector, colors: List<Color>, title: String, subtitle: String?, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = LocalHarmonyColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.ink)
            if (subtitle != null) Text(subtitle, fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
        trailing()
    }
}

/** A choice among several (presets): quiet, with an accent outline and tint when chosen. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHarmonyColors.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    val accent = if (c.dark) Accent.PurpleLight else Accent.Purple
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        color = if (selected) accent else c.ink.copy(alpha = if (hovered) 1f else 0.8f),
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    selected -> Accent.Purple.copy(alpha = if (c.dark) 0.18f else 0.1f)
                    hovered -> c.ink.copy(alpha = 0.08f)
                    else -> c.ink.copy(alpha = 0.045f)
                },
            )
            .border(1.dp, if (selected) Accent.Purple.copy(alpha = 0.55f) else Color.Transparent, shape)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(hover, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
            .semantics { contentDescription = label },
    )
}

/** A small rounded value readout: "+3.0 dB", "130%". */
@Composable
fun ValueChip(text: String, modifier: Modifier = Modifier, tag: String? = null) {
    val c = LocalHarmonyColors.current
    Text(
        text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.ink,
        modifier = modifier
            .width(76.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.ink.copy(alpha = 0.06f))
            .padding(vertical = 5.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
