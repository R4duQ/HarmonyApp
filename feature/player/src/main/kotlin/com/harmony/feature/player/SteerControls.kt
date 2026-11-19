package com.harmony.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.harmony.core.model.MoodFilter
import com.harmony.core.model.QueuedDecision
import com.harmony.core.model.ReasonTag
import com.harmony.core.ui.room.RoomButton
import com.harmony.core.ui.room.RoomButtonKind
import com.harmony.core.ui.room.RoomChip
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType

/** How far one tap of Calmer or Livelier moves the energy the mix aims for. */
internal const val STEER_STEP = 0.2f

/** The energy one step calmer or livelier than [current] (no target yet counts as the middle). */
internal fun steppedEnergy(current: Float?, livelier: Boolean): Float =
    ((current ?: 0.5f) + if (livelier) STEER_STEP else -STEER_STEP).coerceIn(0f, 1f)

/**
 * Steering the mix in words (principle 1): Calmer and Livelier move the energy
 * target, a mood filters or eases toward a mood, Tune opens the full Smart
 * Shuffle sheet. Every action is an existing [PlayerViewModel] call.
 */
internal class SteerCallbacks(
    val steerable: Boolean,
    val energy: Float?,
    val mood: MoodFilter?,
    val onEnergy: (Float) -> Unit = {},
    val onMood: (MoodFilter?) -> Unit = {},
    val onJourney: (MoodFilter) -> Unit = {},
    val onTune: () -> Unit = {},
    val onStartMix: () -> Unit = {},
)

/** The moods offered while steering, in the listener's words (the same as Listen's). */
internal val SteerMoods: List<Pair<MoodFilter, Int>> = listOf(
    MoodFilter.CALM to R.string.steer_mood_calm,
    MoodFilter.FOCUS to R.string.steer_mood_focus,
    MoodFilter.WORKOUT to R.string.steer_mood_energy,
    MoodFilter.HAPPY to R.string.steer_mood_happy,
    MoodFilter.DARK to R.string.steer_mood_late_night,
    MoodFilter.PARTY to R.string.steer_mood_party,
    MoodFilter.RELAXING to R.string.steer_mood_relaxed,
    MoodFilter.SAD to R.string.steer_mood_melancholy,
    MoodFilter.EPIC to R.string.steer_mood_epic,
    MoodFilter.EMOTIONAL to R.string.steer_mood_emotional,
    MoodFilter.AGGRESSIVE to R.string.steer_mood_intense,
)

/**
 * The steering chips under the transport, on screens tall enough for them
 * (shorter screens and landscape get one Steer action instead). Without a
 * mix, a single chip starts one from this song.
 */
@Composable
internal fun SteeringRow(steer: SteerCallbacks, onOpenMoods: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!steer.steerable) {
            RoomChip(stringResource(R.string.steer_start_mix), selected = false, onClick = steer.onStartMix, icon = Icons.Rounded.AutoAwesome)
            return@Row
        }
        RoomChip(stringResource(R.string.steer_calmer), selected = false, icon = Icons.Rounded.Bedtime,
            onClick = { steer.onEnergy(steppedEnergy(steer.energy, livelier = false)) })
        RoomChip(stringResource(R.string.steer_livelier), selected = false, icon = Icons.Rounded.Bolt,
            onClick = { steer.onEnergy(steppedEnergy(steer.energy, livelier = true)) })
        val moodLabel = steer.mood?.let { m -> SteerMoods.firstOrNull { it.first == m }?.second }
        RoomChip(
            moodLabel?.let { stringResource(it) } ?: stringResource(R.string.steer_mood),
            selected = steer.mood != null,
            icon = Icons.Rounded.ExpandMore,
            onClick = onOpenMoods,
        )
        RoomChip(stringResource(R.string.steer_tune), selected = false, icon = Icons.Rounded.Tune, onClick = steer.onTune)
    }
}

/**
 * The steering sheet: the same actions as the chips, plus every mood and the
 * choice to ease into it over the next five songs instead of switching now.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SteerSheet(steer: SteerCallbacks, onDismiss: () -> Unit) {
    val r = Room
    var ease by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = r.surface,
        contentColor = r.ink,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.steer_title), style = RoomType.nameL, color = r.ink, modifier = Modifier.semantics { heading() })
            if (!steer.steerable) {
                Text(stringResource(R.string.steer_no_mix), style = RoomType.body, color = r.muted)
                RoomButton(stringResource(R.string.steer_start_mix), onClick = { steer.onStartMix(); onDismiss() },
                    icon = Icons.Rounded.AutoAwesome, prominent = true, fillWidth = true)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoomButton(stringResource(R.string.steer_calmer), { steer.onEnergy(steppedEnergy(steer.energy, false)) },
                    Modifier.weight(1f), kind = RoomButtonKind.Tonal, icon = Icons.Rounded.Bedtime)
                RoomButton(stringResource(R.string.steer_livelier), { steer.onEnergy(steppedEnergy(steer.energy, true)) },
                    Modifier.weight(1f), kind = RoomButtonKind.Tonal, icon = Icons.Rounded.Bolt)
            }
            Text(stringResource(R.string.steer_mood_heading), style = RoomType.nameM, color = r.ink, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RoomChip(stringResource(R.string.steer_mood_any), selected = steer.mood == null, onClick = { steer.onMood(null) })
                SteerMoods.forEach { (mood, label) ->
                    RoomChip(stringResource(label), selected = steer.mood == mood, onClick = {
                        if (ease) steer.onJourney(mood) else steer.onMood(mood)
                    })
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = ease, role = Role.Switch, onValueChange = { ease = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.steer_ease), style = RoomType.title, color = r.ink)
                    Text(stringResource(R.string.steer_ease_body), style = RoomType.bodySmall, color = r.muted)
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = ease,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = r.amber, checkedThumbColor = r.onAmber,
                        uncheckedTrackColor = r.raised, uncheckedBorderColor = r.outline, uncheckedThumbColor = r.outline,
                    ),
                )
            }
            RoomButton(stringResource(R.string.steer_tune_full), { onDismiss(); steer.onTune() },
                kind = RoomButtonKind.Text, icon = Icons.Rounded.Tune)
        }
    }
}

/** Why Smart Shuffle picked this song, in plain words; null when it did not pick it. */
internal fun pickReason(decision: QueuedDecision?): String? = decision?.let {
    when (it.trace.reason) {
        ReasonTag.VIBE_MATCH -> "Sounds like the last song · ${it.vibeMatchPct}% match"
        ReasonTag.MARKOV_FAVORITE -> "Often follows your last song"
        ReasonTag.BANDIT_GEM -> "One you haven't played in a while"
        ReasonTag.POST_SKIP_PIVOT -> "A change of direction after your skip"
        ReasonTag.PROVEN_FAVORITE -> "One of your favourites"
        ReasonTag.SONIC_ADVENTURE -> "Further from your usual"
        ReasonTag.COLD_START -> "A fresh pick"
    }
}

/**
 * The file's quality as a short tag and in full words: "HI-RES 24/96" read as
 * "Hi-Res lossless, 24-bit, 96 kilohertz". Lossy files say their bitrate.
 */
internal fun formatTagFor(info: MiniPlayerAudioInfo?, bitDepth: Int?): Pair<String, String>? {
    info ?: return null
    val khz = info.sampleRateHz?.takeIf { it > 0 }?.let { hz ->
        val k = hz / 1000.0
        if (k % 1.0 == 0.0) k.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, k)
    }
    return if (info.isLossless) {
        val hiRes = (bitDepth ?: 16) > 16 || (info.sampleRateHz ?: 0) > 48_000
        val depth = bitDepth?.takeIf { it > 0 }
        val short = when {
            hiRes && depth != null && khz != null -> "Hi-Res $depth/$khz"
            hiRes -> "Hi-Res"
            else -> "Lossless"
        }
        val spoken = buildString {
            append(if (hiRes) "Hi-Res lossless" else "Lossless")
            info.format?.let { append(", $it") }
            depth?.let { append(", $it-bit") }
            khz?.let { append(", $it kilohertz") }
        }
        short to spoken
    } else {
        val kbps = info.bitrateKbps?.takeIf { it > 0 }
        val short = listOfNotNull(info.format, kbps?.let { "$it kbps" }).joinToString(" ").ifBlank { "Lossy" }
        short to listOfNotNull("Lossy", info.format, kbps?.let { "$it kilobits per second" }).joinToString(", ")
    }
}

/**
 * One line under the title (critique U1): why this song (from the mix), and
 * the file's quality as a tag at the end. Tapping the reason or the tag shows
 * the full format card below.
 */
@Composable
internal fun ReasonFormatLine(
    reason: String?,
    tag: Pair<String, String>?,
    palette: PlayerPalette,
    expanded: Boolean,
    onToggleDetails: () -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    if (reason == null && tag == null) return
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f && reason != null && tag != null) {
        // Large text: the reason gets the full width and the tag its own line.
        Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggleDetails), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reason, style = RoomType.bodySmall, color = palette.muted)
            com.harmony.core.ui.room.FormatTag(tag.first, spoken = tag.second)
        }
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.np_hide_details else R.string.np_show_details), onClick = onToggleDetails),
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (reason != null) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Rounded.Info, contentDescription = null,
                tint = palette.muted, modifier = Modifier.padding(end = 6.dp).size(18.dp),
            )
            Text(
                reason,
                style = RoomType.bodySmall,
                color = palette.muted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = if (centered) Modifier.weight(1f, fill = false) else Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (tag != null) com.harmony.core.ui.room.FormatTag(tag.first, spoken = tag.second)
    }
}
