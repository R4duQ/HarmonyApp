package com.harmony.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.harmony.core.ui.room.RoomCard
import com.harmony.core.ui.room.RoomSectionTitle
import com.harmony.core.ui.room.RoomTopBar
import com.harmony.core.ui.theme.Room
import com.harmony.core.ui.theme.RoomType

/** Where each door on Add leads; the shell turns these into navigation. */
class AddActions(
    val onSearch: (String) -> Unit = {},
    val onIdentify: () -> Unit = {},
    val onSpotify: () -> Unit = {},
    val onDiscover: () -> Unit = {},
    val onComputer: () -> Unit = {},
)

/**
 * Add: every way music comes into the library, behind one search
 * (principle 2). Type a song or an album, or paste a link, and the search
 * opens with the best source already chosen; the sources themselves are an
 * override there, never a question asked first (principle 1). Below it, the
 * four other ways in, and what is on its way right now.
 */
@Composable
fun AddScreen(
    actions: AddActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    topActions: @Composable RowScope.() -> Unit = {},
    inProgress: @Composable () -> Unit = { ActivityList() },
) {
    val r = Room
    var query by rememberSaveable { mutableStateOf("") }
    Column(
        modifier
            .fillMaxSize()
            .background(r.ground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        RoomTopBar(stringResource(R.string.add_title), actions = topActions)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("add_search"),
            label = { Text(stringResource(R.string.add_search_label)) },
            placeholder = { Text(stringResource(R.string.add_search_placeholder)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.add_clear))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            textStyle = RoomType.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) actions.onSearch(query.trim()) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = r.surface,
                unfocusedContainerColor = r.surface,
                focusedBorderColor = r.ink,
                unfocusedBorderColor = r.outline,
                focusedLabelColor = r.ink,
                unfocusedLabelColor = r.muted,
                cursorColor = r.ink,
            ),
        )

        // Two by two; one per row when the system text is large, so nothing is squeezed.
        val stacked = LocalDensity.current.fontScale > 1.3f
        val doors: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> Door(Icons.Rounded.GraphicEq, stringResource(R.string.add_door_identify), stringResource(R.string.add_door_identify_body), m, actions.onIdentify) },
            { m -> Door(Icons.Rounded.PlaylistAdd, stringResource(R.string.add_door_spotify), stringResource(R.string.add_door_spotify_body), m, actions.onSpotify) },
            { m -> Door(Icons.Rounded.AutoAwesome, stringResource(R.string.add_door_discover), stringResource(R.string.add_door_discover_body), m, actions.onDiscover) },
            { m -> Door(Icons.Rounded.Computer, stringResource(R.string.add_door_computer), stringResource(R.string.add_door_computer_body), m, actions.onComputer) },
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (stacked) {
                doors.forEach { it(Modifier.fillMaxWidth()) }
            } else {
                doors.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { pair.forEach { it(Modifier.weight(1f)) } }
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoomSectionTitle(stringResource(R.string.add_in_progress))
            inProgress()
        }
    }
}

@Composable
private fun Door(icon: ImageVector, title: String, body: String, modifier: Modifier, onClick: () -> Unit) {
    val r = Room
    RoomCard(modifier.heightIn(min = 120.dp), onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = r.amberText, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(title, style = RoomType.title, color = r.ink)
            Text(body, style = RoomType.bodySmall, color = r.muted)
        }
    }
}
