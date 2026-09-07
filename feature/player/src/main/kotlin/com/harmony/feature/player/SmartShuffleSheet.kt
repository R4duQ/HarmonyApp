package com.harmony.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.model.MoodFilter
import com.harmony.core.model.ShuffleMode
import com.harmony.core.ui.component.Artwork
import com.harmony.domain.shuffle.model.SmartShuffleStyle

/**
 * Smart Shuffle control centre.
 *
 * What changed from the previous version, and why:
 *
 *  - **Grouped instead of stacked.** Six sections previously sat at identical
 *    visual weight in one flat scroll, so choosing a personality looked exactly
 *    as important as toggling a mood filter. They are now cards: what it plays,
 *    how it is tuned, what is coming, and how it sounds.
 *  - **Smart-only controls are gated.** Style, tuning and preview used to stay
 *    on screen in Off and Random mode, where none of them do anything — you
 *    could drag Familiarity in Random mode and nothing would happen. They now
 *    collapse, and the sheet says what the selected mode actually does.
 *  - **All eleven moods are reachable.** They were in a LazyRow with no
 *    content padding, so roughly six of the eleven sat off the right edge with
 *    nothing to indicate they existed. FlowRow wraps them.
 *  - **The preview leads with the songs.** It previously opened with a
 *    paragraph about artist repetition and then, usually, "turn on Smart
 *    Shuffle" — explanation before the thing being explained.
 *  - **Player palette, not the Material scheme.** With dynamic colour on, the
 *    chips and sliders were rendering in the wallpaper hue over the player's
 *    own field.
 *
 * The scoring engine itself is untouched. This is its control surface.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartShuffleSheet(viewModel: PlayerViewModel) {
    val energy by viewModel.energySliderValue.collectAsStateWithLifecycle()
    val journeyProgress by viewModel.journeyProgress.collectAsStateWithLifecycle()
    val selectedMood by viewModel.moodFilter.collectAsStateWithLifecycle()
    val activeJourneyMood by viewModel.activeJourneyMood.collectAsStateWithLifecycle()
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val smartStyle by viewModel.smartShuffleStyle.collectAsStateWithLifecycle()
    val familiarity by viewModel.smartFamiliarity.collectAsStateWithLifecycle()
    val discovery by viewModel.smartDiscovery.collectAsStateWithLifecycle()
    val variety by viewModel.smartVariety.collectAsStateWithLifecycle()
    val preview by viewModel.smartQueuePreview.collectAsStateWithLifecycle()
    val palette = playerPalette()

    val mode = state.shuffleMode
    // Journey is a Smart variant, so the Smart chip stays lit during one.
    val smartActive = mode == ShuffleMode.SMART || mode == ShuffleMode.JOURNEY

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        // ---- Header -------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "Smart Shuffle",
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = palette.ink,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // A live one-liner, so the sheet answers "what is it doing right now?"
        // before asking you to change anything.
        Text(
            summaryLine(smartActive, mode, smartStyle, familiarity, discovery, selectedMood),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = palette.muted,
            modifier = Modifier.padding(top = 3.dp, bottom = 16.dp),
        )

        // ---- Mode ---------------------------------------------------------
        SheetCard(palette) {
            CardLabel("Mode", palette)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShufflePill("Off", mode == ShuffleMode.OFF, palette) {
                    viewModel.setShuffleMode(ShuffleMode.OFF)
                }
                ShufflePill("Smart", smartActive, palette) {
                    viewModel.setShuffleMode(ShuffleMode.SMART)
                }
                ShufflePill("Random", mode == ShuffleMode.RANDOM, palette) {
                    viewModel.setShuffleMode(ShuffleMode.RANDOM)
                }
            }
            if (!smartActive) {
                Text(
                    if (mode == ShuffleMode.RANDOM) {
                        "Random picks any track with equal odds. Smart weighs your " +
                            "history, the current song and how often you skip."
                    } else {
                        "Shuffle is off — the queue plays in order."
                    },
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // Everything below only affects Smart mode, so it collapses otherwise
        // rather than sitting there inert.
        AnimatedVisibility(
            visible = smartActive,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                // ---- Style ------------------------------------------------
                SheetCard(palette) {
                    CardLabel("Style", palette)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmartShuffleStyle.entries.forEach { style ->
                            ShufflePill(styleLabel(style), smartStyle == style, palette) {
                                viewModel.onSmartStyleSelected(style)
                            }
                        }
                    }
                    Text(
                        styleDescription(smartStyle),
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                // ---- Tuning -----------------------------------------------
                SheetCard(palette) {
                    CardLabel("Tuning", palette)
                    SmartSlider(
                        title = "Familiarity",
                        readout = describe(familiarity, "Mostly fresh", "Even mix", "Mostly favourites"),
                        value = familiarity,
                        startLabel = "Fresh",
                        endLabel = "Favourites",
                        palette = palette,
                        onValueChange = viewModel::onSmartFamiliarityChange,
                    )
                    SmartSlider(
                        title = "Discovery",
                        readout = describe(discovery, "Stick to known", "Even mix", "Dig deep"),
                        value = discovery,
                        startLabel = "Known",
                        endLabel = "Forgotten gems",
                        palette = palette,
                        onValueChange = viewModel::onSmartDiscoveryChange,
                    )
                    SmartSlider(
                        title = "Variety",
                        readout = describe(variety, "Tight and focused", "Balanced", "Adventurous"),
                        value = variety,
                        startLabel = "Focused",
                        endLabel = "Adventurous",
                        palette = palette,
                        onValueChange = viewModel::onSmartVarietyChange,
                    )
                }

                // ---- Up next ----------------------------------------------
                SheetCard(palette) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { CardLabel("Coming up", palette) }
                        if (preview.isNotEmpty()) {
                            Surface(
                                onClick = viewModel::onRegenerateSmartQueue,
                                shape = RoundedCornerShape(50),
                                color = Color.Transparent,
                                contentColor = palette.ink,
                                border = BorderStroke(1.dp, palette.controlEdge),
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .semantics { contentDescription = "Regenerate the smart queue" },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        "Regenerate",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (preview.isEmpty()) {
                        Text(
                            "Start a song and Harmony will show what it lines up next.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        // Songs first; the explanation of how they were chosen
                        // sits underneath them where it reads as a footnote.
                        preview.forEachIndexed { index, song ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${index + 1}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.muted,
                                    modifier = Modifier.padding(end = 10.dp),
                                )
                                Artwork(song.artworkUri, null, Modifier.size(36.dp), cornerRadius = 7.dp)
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp),
                                ) {
                                    Text(
                                        song.title,
                                        fontSize = 13.5.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 1.dp),
                                    )
                                    Text(
                                        song.artist,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = palette.muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 1.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            "Artist repetition is kept down, and your recent session " +
                                "steers the picks so the mix doesn't drift.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }

                // ---- Sound ------------------------------------------------
                SheetCard(palette) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            Column {
                                CardLabel("Energy target", palette)
                                Text(
                                    if (energy == null) "Off — energy is not constrained"
                                    else describe(energy ?: 0.5f, "Calm", "Middle ground", "Energetic"),
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp,
                                    color = palette.muted,
                                )
                            }
                        }
                        Switch(
                            checked = energy != null,
                            onCheckedChange = { on -> viewModel.onEnergySliderChange(if (on) 0.5f else null) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = palette.onPrimaryControl,
                                checkedTrackColor = palette.accent,
                                uncheckedThumbColor = palette.muted,
                                uncheckedTrackColor = palette.control,
                            ),
                        )
                    }
                    AnimatedVisibility(
                        visible = energy != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column {
                            Slider(
                                value = energy ?: 0.5f,
                                onValueChange = viewModel::onEnergySliderChange,
                                colors = sliderColors(palette),
                            )
                            EndLabels("Calm", "Energetic", palette)
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    CardLabel("Mood filter", palette)
                    Text(
                        if (selectedMood == null) "No filter — tap a mood to narrow the picks"
                        else "Tap ${moodLabel(selectedMood!!)} again to clear it",
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    // FlowRow, not LazyRow: there are eleven moods and the old
                    // single-line row hid roughly six of them off-screen.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MoodFilter.entries.forEach { mood ->
                            ShufflePill(moodLabel(mood), selectedMood == mood, palette) {
                                viewModel.onMoodFilterSelected(
                                    if (selectedMood == mood) null else mood,
                                )
                            }
                        }
                    }
                }

                // ---- Journey ----------------------------------------------
                SheetCard(palette) {
                    CardLabel("Journey", palette)
                    Crossfade(targetState = journeyProgress != null, label = "journey-section") { inProgress ->
                        if (inProgress) {
                            Column {
                                Text(
                                    "Drifting toward " +
                                        (activeJourneyMood?.let { moodLabel(it) } ?: "a mood") +
                                        " · ${((journeyProgress ?: 0f) * 100).toInt()}%",
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.ink,
                                )
                                Box(
                                    Modifier
                                        .padding(top = 10.dp)
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(palette.control),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(journeyProgress ?: 0f)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(palette.accent),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                ShufflePill("Cancel journey", selected = false, palette = palette) {
                                    viewModel.onCancelJourney()
                                }
                            }
                        } else {
                            Column {
                                Text(
                                    "Gradually drift the music toward a mood across the " +
                                        "next 15 songs, instead of switching straight to it.",
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp,
                                    color = palette.muted,
                                    modifier = Modifier.padding(bottom = 10.dp),
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    MoodFilter.entries.forEach { mood ->
                                        ShufflePill("→ ${moodLabel(mood)}", false, palette) {
                                            viewModel.onStartJourneyToMood(mood, steps = 15)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun SheetCard(palette: PlayerPalette, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.control)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        content()
    }
}

@Composable
private fun CardLabel(text: String, palette: PlayerPalette) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = palette.muted,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/** Selectable pill in the player's palette; replaces Material's FilterChip. */
@Composable
private fun ShufflePill(
    label: String,
    selected: Boolean,
    palette: PlayerPalette,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) palette.accent else Color.Transparent,
        contentColor = if (selected) palette.onPrimaryControl else palette.ink,
        border = if (selected) null else BorderStroke(1.dp, palette.controlEdge),
        modifier = Modifier
            .heightIn(min = 40.dp)
            .semantics { contentDescription = if (selected) "$label, selected" else label },
    ) {
        Box(
            Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun sliderColors(palette: PlayerPalette) = SliderDefaults.colors(
    thumbColor = palette.accent,
    activeTrackColor = palette.accent,
    inactiveTrackColor = palette.controlEdge,
)

@Composable
private fun SmartSlider(
    title: String,
    readout: String,
    value: Float,
    startLabel: String,
    endLabel: String,
    palette: PlayerPalette,
    onValueChange: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink,
            modifier = Modifier.weight(1f),
        )
        // A plain track gave no idea where the value sat; naming the current
        // position is more useful here than a percentage.
        Text(readout, fontSize = 12.sp, lineHeight = 16.sp, color = palette.accent)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        colors = sliderColors(palette),
        modifier = Modifier.semantics { contentDescription = "$title, $readout" },
    )
    EndLabels(startLabel, endLabel, palette)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun EndLabels(start: String, end: String, palette: PlayerPalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, fontSize = 11.sp, lineHeight = 15.sp, color = palette.muted)
        Text(end, fontSize = 11.sp, lineHeight = 15.sp, color = palette.muted)
    }
}

// ---------------------------------------------------------------------------
// Copy
// ---------------------------------------------------------------------------

private fun describe(value: Float, low: String, mid: String, high: String): String = when {
    value < 0.34f -> low
    value < 0.67f -> mid
    else -> high
}

private fun moodLabel(mood: MoodFilter): String =
    mood.name.lowercase().replaceFirstChar(Char::uppercase)

private fun styleLabel(style: SmartShuffleStyle): String = when (style) {
    SmartShuffleStyle.BALANCED -> "Balanced"
    SmartShuffleStyle.FAMILIAR -> "Familiar"
    SmartShuffleStyle.DISCOVER -> "Discover"
    SmartShuffleStyle.FLOW -> "Flow"
}

private fun styleDescription(style: SmartShuffleStyle): String = when (style) {
    SmartShuffleStyle.BALANCED -> "A natural mix of favourites, forgotten tracks and smooth transitions."
    SmartShuffleStyle.FAMILIAR -> "Leans into favourites and songs you usually finish."
    SmartShuffleStyle.DISCOVER -> "Surfaces tracks you rarely play or haven't heard in a long time."
    SmartShuffleStyle.FLOW -> "Prioritises energy, genre continuity and fewer abrupt vibe changes."
}

/** One line describing the live configuration, shown under the title. */
private fun summaryLine(
    smartActive: Boolean,
    mode: ShuffleMode,
    style: SmartShuffleStyle,
    familiarity: Float,
    discovery: Float,
    mood: MoodFilter?,
): String {
    if (!smartActive) {
        return if (mode == ShuffleMode.RANDOM) "Random — every track equally likely."
        else "Off — playing the queue in order."
    }
    val lean = when {
        familiarity >= 0.67f -> "leaning familiar"
        discovery >= 0.67f -> "digging for forgotten tracks"
        familiarity < 0.34f -> "leaning fresh"
        else -> "evenly balanced"
    }
    val moodPart = mood?.let { ", ${moodLabel(it).lowercase()} only" } ?: ""
    return "${styleLabel(style)} · $lean$moodPart."
}
