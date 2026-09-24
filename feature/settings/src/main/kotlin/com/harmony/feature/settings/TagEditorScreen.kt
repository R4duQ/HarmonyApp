package com.harmony.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harmony.core.ui.component.Artwork
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.EditorialPill
import com.harmony.core.ui.component.EditorialSectionLabel
import com.harmony.core.ui.component.EditorialTextAction
import com.harmony.core.ui.component.stonePalette
import com.harmony.core.ui.component.LocalFloatingChromeHeight

/**
 * Tag editor: find a song, correct its title/artist/album, and replace or
 * remove its artwork.
 *
 * Scope, stated in the UI as well as here: these corrections live in
 * Harmony's database and apply everywhere the app shows the song, including
 * Android Auto and the notification. They do NOT rewrite the audio file, so
 * other apps still read the original tags. Writing tags in place risks
 * corrupting files and needs per-file write permission on modern Android —
 * a much larger and riskier job than it looks.
 */
@Composable
fun TagEditorScreen(viewModel: TagEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = stonePalette()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.pickArtwork(uri.toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.field),
    ) {
        Text(
            if (state.selected == null) "Edit tags" else "Editing",
            fontSize = 34.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.9).sp,
            color = palette.ink,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp),
        )

        if (state.busy || state.error != null) {
            Text(
                state.error ?: "Working…",
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
        if (state.selected == null) {
            Text(
                "Corrections apply everywhere in Harmony, including Android Auto. " +
                    "The audio files themselves aren't changed.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.muted,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                placeholder = { Text("Find a song", color = palette.muted) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.line,
                    unfocusedBorderColor = palette.line,
                    focusedTextColor = palette.ink,
                    unfocusedTextColor = palette.ink,
                    cursorColor = palette.ink,
                    focusedLeadingIconColor = palette.ink,
                    unfocusedLeadingIconColor = palette.muted,
                ),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.results, key = { it.id }) { song ->
                    EditorialCard(
                        palette = palette,
                        onClick = { viewModel.select(song) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 5.dp),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(
                                song.artworkUri,
                                null,
                                Modifier.size(44.dp),
                                cornerRadius = 8.dp,
                            )
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(
                                    song.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    song.artist,
                                    fontSize = 12.sp,
                                    color = palette.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            return@Column
        }

        // ---- Editing one song ----
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalFloatingChromeHeight.current),
        ) {
            EditorialSectionLabel(
                "Artwork",
                palette,
                Modifier.padding(start = 22.dp, top = 16.dp, bottom = 8.dp),
            )
            Row(
                Modifier.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .border(1.5.dp, palette.line, RoundedCornerShape(12.dp)),
                ) {
                    Artwork(
                        state.previewArtwork,
                        null,
                        Modifier
                            .fillMaxSize()
                            .padding(1.5.dp),
                        cornerRadius = 10.dp,
                    )
                }
                Column(
                    Modifier.padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditorialPill(
                        text = "Choose image",
                        icon = Icons.Rounded.Image,
                        onClick = { picker.launch(arrayOf("image/*")) },
                        palette = palette,
                    )
                    // Only offered when there IS artwork to remove, and
                    // swapped for an undo once removed.
                    if (state.artworkCleared) {
                        EditorialTextAction(
                            text = "Restore artwork",
                            onClick = viewModel::restoreArtwork,
                            palette = palette,
                        )
                    } else if (state.previewArtwork != null) {
                        EditorialTextAction(
                            text = "Remove artwork",
                            onClick = viewModel::clearArtwork,
                            palette = palette,
                        )
                    }
                }
            }

            EditorialSectionLabel(
                "Details",
                palette,
                Modifier.padding(start = 22.dp, top = 22.dp, bottom = 8.dp),
            )
            EditField("Title", state.title, viewModel::onTitleChange, palette)
            EditField("Artist", state.artist, viewModel::onArtistChange, palette)
            EditField("Album", state.album, viewModel::onAlbumChange, palette)
            EditField(
                "Album artist",
                state.albumArtist,
                viewModel::onAlbumArtistChange,
                palette,
            )

            Row(
                Modifier.padding(start = 22.dp, top = 18.dp, end = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorialPill(
                    text = "Save",
                    icon = Icons.Rounded.Check,
                    onClick = viewModel::save,
                    palette = palette,
                )
                EditorialTextAction(
                    text = "Undo all edits",
                    onClick = viewModel::revert,
                    palette = palette,
                )
                EditorialTextAction(
                    text = "Back",
                    onClick = viewModel::back,
                    palette = palette,
                )
            }

            AnimatedVisibility(visible = state.saved) {
                Text(
                    "Saved — the change is live across the app.",
                    fontSize = 12.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 22.dp, top = 12.dp),
                )
            }

            Box(Modifier.size(28.dp))
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    palette: EditorialPalette,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.line,
            unfocusedBorderColor = palette.line,
            focusedTextColor = palette.ink,
            unfocusedTextColor = palette.ink,
            focusedLabelColor = palette.muted,
            unfocusedLabelColor = palette.muted,
            cursorColor = palette.ink,
        ),
    )
}
