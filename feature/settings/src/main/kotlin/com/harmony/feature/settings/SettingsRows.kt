package com.harmony.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType

/*
 * Settings rows in the Listening Room: a heading per section, rows with a
 * title and one line of what it does, the whole row the target (48 dp+),
 * switches that say their state, sliders with labelled ends.
 */

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            title,
            style = RoomType.nameM,
            color = Room.ink,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        )
        content()
    }
}

@Composable
internal fun SettingText(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = RoomType.title, color = Room.ink)
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = RoomType.bodySmall, color = Room.muted)
    }
}

/** A row that opens something: the whole row is the button, with a chevron. */
@Composable
internal fun SettingLink(title: String, subtitle: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Room.muted)
    }
}

/** An on/off setting: the whole row toggles, and the switch is drawn, not a separate target. */
@Composable
internal fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val r = Room
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = r.amber, checkedThumbColor = r.onAmber,
                uncheckedTrackColor = r.raised, uncheckedBorderColor = r.outline, uncheckedThumbColor = r.outline,
            ),
        )
    }
}

/** A choice of a few options, as chips under the title. */
@Composable
internal fun SettingChoice(title: String, subtitle: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingText(title, subtitle)
        com.harmony.core.ui.room.RoomSegmented(options, selected, onSelect)
    }
}

/** A slider with words at both ends, read as a value to TalkBack. */
@Composable
internal fun SettingSlider(
    title: String,
    low: String,
    high: String,
    value: Float,
    onChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    spoken: String = "${(value * 100).toInt()}%",
) {
    val r = Room
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = RoomType.title, color = r.ink)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = spoken
            },
            colors = SliderDefaults.colors(
                thumbColor = r.amberText, activeTrackColor = r.amberText, inactiveTrackColor = r.raised,
                activeTickColor = r.onAmber, inactiveTickColor = r.faint,
            ),
        )
        Row {
            Text(low, style = RoomType.bodySmall, color = r.muted, modifier = Modifier.weight(1f))
            Text(high, style = RoomType.bodySmall, color = r.muted)
        }
    }
}
