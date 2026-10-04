package com.harmony.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.model.AudioOutput
import com.harmony.core.model.OutputForm
import com.harmony.domain.playback.ConnectComputer
import com.harmony.domain.playback.ConnectState

/** What the "Play on" sheet can do. */
internal class PlayOnActions(
    val search: () -> Unit = {},
    val connect: (ConnectComputer) -> Unit = {},
    val pair: (ConnectComputer, String) -> Unit = { _, _ -> },
    val addByAddress: (String) -> Unit = {},
    val playHere: () -> Unit = {},
    val forget: (ConnectComputer) -> Unit = {},
    val dismissError: () -> Unit = {},
)

internal object PlayOnTags {
    const val SHEET = "play_on_sheet"
    const val PHONE = "play_on_phone"
    const val SEARCH = "play_on_search"
    const val CODE = "play_on_code"
    const val PAIR = "play_on_pair"
    const val ADDRESS = "play_on_address"
    const val ERROR = "play_on_error"
    fun computer(id: String) = "play_on_pc_$id"
}

/**
 * Where the music plays: this phone (with the kind of device its output is,
 * as before) or a computer running Harmony for Windows (Harmony Connect).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayOnSheet(
    connect: ConnectState,
    /** The phone's own output, not the computer. */
    phoneOutput: AudioOutput,
    output: OutputChoice,
    actions: PlayOnActions,
) {
    LaunchedEffect(Unit) { actions.search() }
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
            .testTag(PlayOnTags.SHEET),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Play on", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                Text(
                    "Send the music to a computer; this phone stays the remote.",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (connect.searching) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    IconButton(onClick = actions.search, modifier = Modifier.testTag(PlayOnTags.SEARCH)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Search again")
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = connect.error != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                color = colors.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().testTag(PlayOnTags.ERROR),
            ) {
                Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(connect.error.orEmpty(), color = colors.onErrorContainer, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = actions.dismissError) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = colors.onErrorContainer)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        val here = connect.active == null
        PlaceRow(
            icon = if (here) OutputIcons.forOutput(phoneOutput.type, output.form) else Icons.Rounded.PhoneAndroid,
            title = "This phone",
            subtitle = if (here) phoneOutput.label else "Bring the music back, paused where it is",
            selected = here,
            busy = false,
            onClick = { if (!here) actions.playHere() },
            modifier = Modifier.testTag(PlayOnTags.PHONE),
        )
        val pick = output.pick
        AnimatedVisibility(visible = here && pick != null) {
            Column(Modifier.padding(start = 64.dp, top = 6.dp)) {
                Text("What kind of device is it?", fontSize = 12.sp, color = colors.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !output.chosen,
                        onClick = { pick?.invoke(null) },
                        label = { Text("Automatic") },
                    )
                    OutputIcons.pickable(phoneOutput.type).forEach { form: OutputForm ->
                        FilterChip(
                            selected = output.chosen && output.form == form,
                            onClick = { pick?.invoke(form) },
                            label = { Text(form.label) },
                            leadingIcon = { Icon(OutputIcons.forForm(form), null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }

        Text(
            "COMPUTERS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
        )
        if (connect.computers.isEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.5f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Wifi, null, tint = colors.onSurfaceVariant)
                Text(
                    if (connect.searching) "Looking on this Wi-Fi…"
                    else "None found. Open Harmony on your Windows PC, on the same Wi-Fi as this phone.",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        val asking = connect.needsCode
        connect.computers.forEach { pc ->
            val active = connect.active?.id == pc.id
            var menu by remember(pc.id) { mutableStateOf(false) }
            Box {
                PlaceRow(
                    icon = Icons.Rounded.Computer,
                    title = pc.name,
                    subtitle = when {
                        active -> "Playing here"
                        connect.busyWith == pc.id -> "Connecting…"
                        pc.nearby && pc.paired -> "On this Wi-Fi"
                        pc.nearby -> "On this Wi-Fi · needs its code once"
                        else -> "Not found right now"
                    },
                    selected = active,
                    busy = connect.busyWith == pc.id,
                    dim = !pc.nearby && !active,
                    onClick = { actions.connect(pc) },
                    onLongClick = if (pc.paired) ({ menu = true }) else null,
                    trailing = if (pc.paired && !active) ({
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${pc.name}") }
                    }) else null,
                    modifier = Modifier.padding(bottom = 8.dp).testTag(PlayOnTags.computer(pc.id)),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Forget this computer") }, onClick = { menu = false; actions.forget(pc) })
                }
            }
            // Its code, asked for right under it.
            AnimatedVisibility(
                visible = asking?.id == pc.id,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                CodeEntry(pc, busy = connect.busyWith == pc.id, onPair = { actions.pair(pc, it) }, onCancel = actions.dismissError)
            }
        }
        if (asking != null && connect.computers.none { it.id == asking.id }) {
            CodeEntry(asking, busy = connect.busyWith == asking.id, onPair = { actions.pair(asking, it) }, onCancel = actions.dismissError)
        }

        if (asking == null) AddByAddress(busy = connect.busyWith != null && connect.computers.none { it.id == connect.busyWith }, onAdd = actions.addByAddress)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.45f), label = "place-fill")
    val ink = if (selected) colors.onPrimaryContainer else colors.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(fill)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (selected) colors.primary else colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = if (dim) 0.5f else 1f),
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = if (dim) 0.6f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant, maxLines = 2)
        }
        when {
            busy -> CircularProgressIndicator(Modifier.padding(horizontal = 10.dp).size(20.dp), strokeWidth = 2.dp)
            selected -> Icon(Icons.Rounded.Check, contentDescription = "Current", tint = colors.primary, modifier = Modifier.padding(horizontal = 10.dp))
            trailing != null -> trailing()
        }
    }
}

@Composable
private fun CodeEntry(computer: ConnectComputer, busy: Boolean, onPair: (String) -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var code by rememberSaveable(computer.id) { mutableStateOf("") }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = colors.secondaryContainer,
        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.25f)),
        modifier = Modifier.padding(bottom = 10.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Pair with ${computer.name}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.onSecondaryContainer)
            Text(
                "Type the 4-digit code shown on the computer, in Harmony → Connect. Only needed once.",
                fontSize = 13.sp,
                color = colors.onSecondaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    placeholder = { Text("0000") },
                    textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 8.sp, fontWeight = FontWeight.Bold),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.length == 4 && !busy) onPair(code) }),
                    modifier = Modifier.width(150.dp).testTag(PlayOnTags.CODE),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = { onPair(code) }, enabled = code.length == 4 && !busy, modifier = Modifier.testTag(PlayOnTags.PAIR)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                    else Text("Pair")
                }
            }
        }
    }
}

@Composable
private fun AddByAddress(busy: Boolean, onAdd: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }, modifier = Modifier.padding(top = 4.dp)) {
            Text("Not listed? Add it by its address")
        }
        return
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it.trim() },
            singleLine = true,
            label = { Text("Computer's address") },
            placeholder = { Text("192.168.1.20") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) onAdd(address) }),
            modifier = Modifier.weight(1f).testTag(PlayOnTags.ADDRESS),
        )
        Spacer(Modifier.width(10.dp))
        Button(onClick = { onAdd(address) }, enabled = address.isNotBlank() && !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Add")
        }
    }
}
