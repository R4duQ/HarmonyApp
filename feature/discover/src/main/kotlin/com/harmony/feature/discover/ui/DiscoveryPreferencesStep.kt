package com.harmony.feature.discover.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.glassFill
import com.harmony.core.ui.component.glassRim
import com.harmony.domain.library.discovery.ExplorationLevel
import com.harmony.domain.library.discovery.RecommendationMixer
import com.harmony.domain.library.repository.DiscoveryFilter
import com.harmony.feature.discover.provider.SourceState
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreferencesStep(
    state: DiscoveryUiState,
    palette: EditorialPalette,
    actions: DiscoveryActions,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val draft = state.draft
    val list = rememberLazyListState()
    val large = LocalDensity.current.fontScale > 1.3f
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxWidth().testTag(DiscoveryTags.PREFERENCES),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 20.dp, bottom = bottomPadding + 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Discover.", color = palette.ink, fontSize = if (large) 30.sp else 38.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Text("A playlist built from what you actually listen to.", color = palette.muted, fontSize = 15.sp)
                StepIndicator(0, palette)
            }
        }
        if (state.connection != Connection.ONLINE && state.connection != Connection.CHECKING) item(key = "offline") {
            ConnectionNotice(state, palette, Modifier.testTag(DiscoveryTags.OFFLINE))
        }
        if (draft != null && draft.items.isNotEmpty()) item(key = "resume") {
            DiscoverCard(palette) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.History, null, tint = palette.ink)
                    Column(Modifier.weight(1f)) {
                        Text("Your selection is saved", color = palette.ink, fontWeight = FontWeight.SemiBold)
                        Text("${draft.items.size} songs · ${draft.keptCount} kept", color = palette.muted, fontSize = 13.sp)
                    }
                    GlassButton("Continue", palette, actions.resume, Modifier.testTag(DiscoveryTags.RESUME))
                }
            }
        }
        item(key = "taste") { TasteCard(state, palette, actions) }
        item(key = "level") { LevelPicker(draft?.level ?: ExplorationLevel.BALANCED, palette, actions.setLevel) }
        item(key = "size") { SizePicker(draft?.size ?: RecommendationMixer.DEFAULT_SIZE, palette, actions.setSize) }
        item(key = "filters") { FiltersCard(state.filter, state.genres, palette, actions.setFilter) }
        item(key = "sources") { SourcesCard(state, palette) }
        recentSelections(state, palette, actions)
    }
}

/** The bottom action for step 1. Offline it builds from the library, and says so. */
@Composable
internal fun PreferencesAction(state: DiscoveryUiState, palette: EditorialPalette, actions: DiscoveryActions, modifier: Modifier) {
    val work = state.work as? DiscoveryWork.Generating
    val needsPick = state.taste.loaded && !state.taste.hasHistory && state.draft?.pickedArtists.isNullOrEmpty() &&
        state.draft?.pickedGenres.isNullOrEmpty() && !state.online
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (work != null) Text(work.message, color = palette.muted, fontSize = 13.sp, modifier = Modifier.testTag(DiscoveryTags.WORK))
        PrimaryButton(
            text = when {
                work != null -> "Finding songs…"
                state.online -> "Find songs"
                else -> "Build from my library"
            },
            palette = palette,
            onClick = actions.generate,
            // Busy keeps the accent (the button is working, not unavailable); taps are ignored while busy.
            enabled = state.loaded && !needsPick && !(state.libraryEmpty && !state.online) &&
                state.connection != Connection.RECONNECTING,
            busy = work != null,
            icon = if (state.online) Icons.Rounded.AutoAwesome else Icons.Rounded.LibraryMusic,
            modifier = Modifier.fillMaxWidth().testTag(DiscoveryTags.GENERATE),
        )
        when {
            state.libraryEmpty && !state.online -> Text("Offline with an empty library: connect to find new songs.", color = palette.muted, fontSize = 12.sp)
            needsPick -> Text("Pick an artist or genre first.", color = palette.muted, fontSize = 12.sp)
            state.connection == Connection.RECONNECTING -> Text("Waiting for the connection to settle…", color = palette.muted, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TasteCard(state: DiscoveryUiState, palette: EditorialPalette, actions: DiscoveryActions) {
    val taste = state.taste
    val draft = state.draft
    var adding by rememberSaveable { mutableStateOf(false) }
    DiscoverCard(palette, Modifier.testTag(DiscoveryTags.TASTE)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!taste.loaded) {
                Placeholder(palette, Modifier.fillMaxWidth(0.6f).heightIn(min = 18.dp))
                Placeholder(palette, Modifier.fillMaxWidth().heightIn(min = 44.dp))
                return@Column
            }
            if (taste.hasHistory && (taste.artists.isNotEmpty() || taste.genres.isNotEmpty())) {
                SectionTitle("Built from your listening", palette)
                Text("History, favorites, playlists, skips and your Discover choices. Recent listening counts more.",
                    color = palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                taste.artists.take(4).forEach { (name, reason) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(palette.accent))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = palette.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(reason, color = palette.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (taste.genres.isNotEmpty()) Text("Genres: " + taste.genres.joinToString(", "), color = palette.muted, fontSize = 13.sp)
                GlassButton(if (adding) "Done adding" else "Add artists or genres", palette, { adding = !adding },
                    icon = if (adding) Icons.Rounded.ExpandLess else Icons.Rounded.Add, modifier = Modifier.testTag(DiscoveryTags.ADD_PICKS))
            } else {
                SectionTitle("What do you like?", palette)
                Text("There's no listening history yet. Pick a few artists or genres and Harmony starts from there.",
                    color = palette.muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.testTag(DiscoveryTags.COLD_START))
            }
            val showPickers = adding || !taste.hasHistory || (taste.artists.isEmpty() && taste.genres.isEmpty())
            if (showPickers) Pickers(state, palette, actions)
            val picked = draft?.pickedArtists.orEmpty() + draft?.pickedGenres.orEmpty()
            if (picked.isNotEmpty() && !showPickers) Text("Also using: " + picked.joinToString(", "), color = palette.muted, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pickers(state: DiscoveryUiState, palette: EditorialPalette, actions: DiscoveryActions) {
    val draft = state.draft
    Text("Genres", color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.genres.take(18).forEach { g ->
            val on = draft?.pickedGenres.orEmpty().any { it.equals(g, true) }
            ChoicePill(g, on, palette, { actions.toggleGenre(g) })
        }
    }
    Text("Artists", color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    OutlinedTextField(
        value = state.artistQuery,
        onValueChange = { actions.searchArtists(it.take(60)) },
        placeholder = { Text(if (state.online) "Search artists" else "Search artists in your library") },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(percent = 50),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = glassFill(palette), unfocusedContainerColor = glassFill(palette),
            focusedBorderColor = palette.ink.copy(alpha = 0.4f), unfocusedBorderColor = glassRim(palette),
            focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
        ),
        modifier = Modifier.fillMaxWidth().testTag(DiscoveryTags.ARTIST_SEARCH),
    )
    val picked = draft?.pickedArtists.orEmpty()
    val shown = (picked + state.artistResults).distinctBy { it.lowercase(Locale.ROOT) }
    if (shown.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { name -> ChoicePill(name, picked.any { it.equals(name, true) }, palette, { actions.toggleArtist(name) }) }
    }
}

@Composable
private fun LevelPicker(level: ExplorationLevel, palette: EditorialPalette, onPick: (ExplorationLevel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { selectableGroup() }) {
        SectionTitle("How adventurous?", palette)
        ExplorationLevel.entries.forEach { option ->
            val on = option == level
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
                    .background(if (on) palette.accent.copy(alpha = 0.16f) else glassFill(palette))
                    .border(if (on) 2.dp else 1.dp, if (on) palette.accent else glassRim(palette), shape)
                    .pressable({ onPick(option) }, role = Role.RadioButton)
                    .semantics { selected = on; stateDescription = if (on) "Selected" else "Not selected" }
                    .testTag(DiscoveryTags.level(option))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) palette.accent else palette.muted, CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(palette.accent))
                }
                Column(Modifier.weight(1f)) {
                    Text(option.label, color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(levelText(option), color = palette.muted, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

internal fun levelText(level: ExplorationLevel) = when (level) {
    ExplorationLevel.FOR_MY_TASTE -> "About ${level.closePercent}% artists and genres you play, ${level.explorePercent}% new"
    ExplorationLevel.BALANCED -> "About ${level.closePercent}% familiar, ${level.explorePercent}% discoveries"
    ExplorationLevel.SURPRISE_ME -> "About ${level.explorePercent}% new artists and genres, each linked to your taste"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizePicker(size: Int, palette: EditorialPalette, onSize: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Playlist size", palette)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundAction(Icons.Rounded.Remove, "Fewer songs", palette, { onSize(size - 5) }, enabled = size > RecommendationMixer.MIN_SIZE)
            Text("$size songs", color = palette.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(DiscoveryTags.SIZE).semantics { contentDescription = "$size songs" })
            RoundAction(Icons.Rounded.Add, "More songs", palette, { onSize(size + 5) }, enabled = size < RecommendationMixer.MAX_SIZE)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 50, 75, 100).forEach { n -> ChoicePill("$n", n == size, palette, { onSize(n) }) }
        }
    }
}

/** The earlier Discover's filters, kept: country chart or artist country, genre, playlist keyword. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersCard(filter: DiscoveryFilter, genres: List<String>, palette: EditorialPalette, onFilter: (DiscoveryFilter) -> Unit) {
    var open by rememberSaveable { mutableStateOf(filter != DiscoveryFilter()) }
    var chooser by rememberSaveable { mutableStateOf<String?>(null) }
    DiscoverCard(palette) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().pressable({ open = !open }, pressedScale = 1f).semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("More filters", color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(if (filter == DiscoveryFilter()) "Optional · country, genre, playlist keyword" else filterSummary(filter),
                        color = palette.muted, fontSize = 13.sp)
                }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = palette.ink)
            }
            if (open) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoicePill(if (filter.country.isBlank()) "Any country" else countryName(filter.country), filter.country.isNotBlank(), palette, { chooser = "Country" })
                    ChoicePill(filter.genre.ifBlank { "Any genre" }, filter.genre.isNotBlank(), palette, { chooser = "Genre" })
                    ChoicePill(filter.vibe.ifBlank { "Playlist keyword" }, filter.vibe.isNotBlank(), palette, { chooser = "Keyword" })
                }
                if (filter.country.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoicePill("Popular there", !filter.artistCountryOnly, palette, { onFilter(filter.copy(artistCountryOnly = false)) })
                    ChoicePill("Artists from there", filter.artistCountryOnly, palette, { onFilter(filter.copy(artistCountryOnly = true)) })
                }
                Text(
                    when {
                        filter.artistCountryOnly -> "Artist country comes from MusicBrainz links to Deezer. It isn't proof of birthplace; unknown countries are left out."
                        filter.vibe.isNotBlank() -> "Keywords search Deezer playlists by name. Harmony doesn't measure mood or tempo."
                        else -> "A country uses that country's Apple Music chart. It doesn't mean the artist is from there."
                    },
                    color = palette.muted, fontSize = 12.sp, lineHeight = 16.sp,
                )
                if (filter != DiscoveryFilter()) TextButton(onClick = { onFilter(DiscoveryFilter()) }) { Text("Clear filters", color = palette.ink) }
            }
        }
    }
    chooser?.let { kind ->
        val choices: List<Pair<String, String>> = when (kind) {
            "Country" -> listOf("" to "Any country") + (listOf("RO", "GB", "US") + Locale.getISOCountries().sortedBy(::countryName)).distinct().map { it to countryName(it) }
            "Genre" -> listOf("" to "Any genre") + genres.sorted().map { it to it }
            else -> listOf("" to "No keyword") + listOf("Chill", "Focus", "Workout", "Party", "Melancholic", "Romantic").map { it to it }
        }
        ChoiceDialog(kind, choices, custom = kind != "Country", onDismiss = { chooser = null }) { value ->
            onFilter(when (kind) {
                "Country" -> filter.copy(country = value, artistCountryOnly = filter.artistCountryOnly && value.isNotBlank())
                "Genre" -> filter.copy(genre = value, genreId = 0)
                else -> filter.copy(vibe = value)
            })
            chooser = null
        }
    }
}

private fun filterSummary(f: DiscoveryFilter) = listOfNotNull(
    f.country.ifBlank { null }?.let(::countryName), f.genre.ifBlank { null }, f.vibe.ifBlank { null }?.let { "“$it”" },
).joinToString(" · ")

@Composable
private fun ChoiceDialog(title: String, choices: List<Pair<String, String>>, custom: Boolean, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column {
                OutlinedTextField(query, { query = it.take(60) }, label = { Text(if (custom) "Search or type your own" else "Search") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(choices.filter { it.second.contains(query, true) }, key = { it.first }) { choice ->
                        TextButton(onClick = { onSelect(choice.first) }, modifier = Modifier.fillMaxWidth()) { Text(choice.second, Modifier.fillMaxWidth()) }
                    }
                    if (custom && query.trim().length >= 2 && choices.none { it.second.equals(query.trim(), true) }) item {
                        TextButton(onClick = { onSelect(query.trim()) }) { Text("Use “${query.trim()}”") }
                    }
                }
            }
        })
}

@Composable
private fun SourcesCard(state: DiscoveryUiState, palette: EditorialPalette) {
    var open by rememberSaveable { mutableStateOf(false) }
    DiscoverCard(palette, Modifier.testTag(DiscoveryTags.SOURCES)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().pressable({ open = !open }, pressedScale = 1f), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Where songs come from", color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Harmony picks the songs. Catalogs only supply facts: artists, tracks, charts, previews.",
                        color = palette.muted, fontSize = 13.sp, lineHeight = 17.sp)
                }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = palette.ink)
            }
            if (open) state.sources.forEach { s ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    val (dot, label) = when (s.state) {
                        SourceState.READY -> palette.accent to "Available"
                        SourceState.LIMITED -> palette.muted to "Rate limited"
                        SourceState.DOWN -> palette.muted.copy(alpha = 0.5f) to "Unavailable"
                        SourceState.NOT_USED -> palette.line to "Not used"
                    }
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(dot))
                    Column(Modifier.weight(1f)) {
                        Text("${s.name} · $label", color = palette.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(s.note.ifBlank { s.role }, color = palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.recentSelections(state: DiscoveryUiState, palette: EditorialPalette, actions: DiscoveryActions) {
    if (state.batches.isEmpty()) return
    item(key = "recent-title") { SectionTitle("Earlier selections", palette) }
    items(state.batches.take(8), key = { "batch:${it.id}" }) { b ->
        DiscoverCard(palette, Modifier.pressable({ actions.openBatch(b.id) }).testTag(DiscoveryTags.batch(b.id))) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.name, color = palette.ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (b.playlistId != null) "In Your playlists · ${b.availableCount} of ${b.songCount} songs"
                        else "${b.availableCount} of ${b.songCount} songs available · not saved yet", color = palette.muted, fontSize = 13.sp)
                }
            }
        }
    }
}

internal fun countryName(code: String): String = if (code == "GB") "United Kingdom" else Locale("", code).getDisplayCountry(Locale.ENGLISH)
