package com.harmony.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Form controls for the editorial screens (Settings, Equalizer).
 *
 * Material's own Switch / SegmentedButton / Slider pull their colors from the
 * Material scheme, which on a flat saturated field looks like a component
 * from a different app pasted on top. These are the same controls recolored
 * from an [EditorialPalette] — behavior and accessibility unchanged, since
 * each one still wraps the real Material control rather than reimplementing it.
 */

/** The small letterspaced eyebrow that opens a section. */
@Composable
fun EditorialSectionLabel(
    text: String,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.ink,
        modifier = modifier,
    )
}

/**
 * A settings row: title, optional explanation, and a trailing control.
 * [content] is an optional expanded area below the text — used by crossfade
 * for its slider.
 */
@Composable
fun EditorialSettingRow(
    title: String,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    EditorialCard(
        palette = palette,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            fontSize = 12.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (trailing != null) {
                    Box(Modifier.padding(start = 12.dp)) { trailing() }
                }
            }
            if (content != null) {
                Box(Modifier.padding(top = 6.dp)) { content() }
            }
        }
    }
}

@Composable
fun EditorialSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = palette.onAccent,
            checkedTrackColor = palette.accent,
            checkedBorderColor = palette.line,
            uncheckedThumbColor = palette.muted,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = palette.line,
        ),
    )
}

/**
 * Replaces SegmentedButtonRow: a row of pills where the selected one fills.
 * Wraps onto a second line rather than squeezing, so long option sets
 * (ReplayGain's Off / Track / Album) stay readable on narrow screens.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorialChoiceChips(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(50),
                color = if (selected) palette.accent else Color.Transparent,
                contentColor = if (selected) palette.onAccent else palette.ink,
                border = if (selected) null else BorderStroke(1.5.dp, palette.line),
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
fun EditorialSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = palette.ink,
            activeTrackColor = palette.ink,
            activeTickColor = palette.field,
            inactiveTrackColor = palette.muted.copy(alpha = 0.35f),
            inactiveTickColor = palette.muted,
        ),
    )
}

/** A small outlined action, used where a full pill would overpower the row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorialTextAction(
    text: String,
    onClick: () -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        contentColor = palette.ink,
        border = BorderStroke(1.5.dp, palette.line),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Spacer used between editorial sections. */
@Composable
fun EditorialSectionGap(height: Int = 18) {
    Box(Modifier.size(height.dp))
}
