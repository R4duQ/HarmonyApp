package com.harmony.feature.downloads

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialCard
import com.harmony.core.ui.component.EditorialPalette

private val ErrorRed = Color(0xFFB3261E)
private val LiveGreen = Color(0xFF2E7D4F)

/**
 * Shown in place of the search card when Soulseek is the armed engine and there
 * is no session.
 *
 * Previously the "Search Soulseek" card rendered unconditionally, so the obvious
 * thing to do — type a query and hit search — failed with "Connect to Soulseek
 * before searching the peer network", and the credential fields were in a
 * separate card much further down the scroll. Offering an input that cannot
 * work yet is the problem; this replaces it with the thing that has to happen
 * first.
 */
@Composable
fun SoulseekConnectCard(
    connection: SoulseekConnectionState,
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    val connecting = connection.status == SoulseekConnectionStatus.CONNECTING
    val failed = connection.status == SoulseekConnectionStatus.ERROR
    val canSubmit = username.isNotBlank() && password.isNotBlank() && !connecting

    EditorialCard(palette = palette, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            ConnectionStatusRow(connection = connection, palette = palette)

            Text(
                "Sign in to search the peer network",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "Soulseek is peer-to-peer, so there is nothing to search until you have a " +
                    "session. The search field appears once you are connected.",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 4.dp),
            )

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                singleLine = true,
                enabled = !connecting,
                isError = failed,
                label = { Text("Soulseek username") },
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true,
                enabled = !connecting,
                isError = failed,
                label = { Text("Soulseek password") },
                visualTransformation = PasswordVisualTransformation(),
            )

            Text(
                "Use your existing Soulseek account. Harmony remembers only the username; " +
                    "enter the password again after the app process restarts.",
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 7.dp),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (canSubmit || connecting) "" else "Both fields are required.",
                    fontSize = 10.sp,
                    color = palette.muted,
                    modifier = Modifier.weight(1f),
                )
                GatedPill(
                    text = if (connecting) "Connecting…" else "Connect",
                    enabled = canSubmit,
                    onClick = onConnect,
                    palette = palette,
                )
            }
        }
    }
}

/** Status dot plus the server's own message. */
@Composable
private fun ConnectionStatusRow(
    connection: SoulseekConnectionState,
    palette: EditorialPalette,
) {
    val dot = when (connection.status) {
        SoulseekConnectionStatus.CONNECTED -> LiveGreen
        SoulseekConnectionStatus.CONNECTING -> palette.accent
        SoulseekConnectionStatus.ERROR -> ErrorRed
        SoulseekConnectionStatus.DISCONNECTED -> palette.muted
    }
    val label = when (connection.status) {
        SoulseekConnectionStatus.CONNECTED -> "Connected"
        SoulseekConnectionStatus.CONNECTING -> "Connecting"
        SoulseekConnectionStatus.ERROR -> "Connection failed"
        SoulseekConnectionStatus.DISCONNECTED -> "Not connected"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
            color = dot,
            modifier = Modifier.padding(start = 7.dp),
        )
    }

    // The server's own wording matters here: a session taken over from another
    // device reads very differently from a bad password, and collapsing both
    // into "connection failed" makes the second look like a Harmony bug.
    if (connection.message.isNotBlank()) {
        Text(
            connection.message,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = if (connection.status == SoulseekConnectionStatus.ERROR) ErrorRed else palette.muted,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * EditorialPill has no `enabled` parameter and lives in core/ui, which this
 * feature should not be reshaping for one screen. Same geometry, plus a
 * disabled state.
 */
@Composable
fun GatedPill(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (enabled) palette.accent else palette.muted.copy(alpha = 0.25f),
        tween(180),
        label = "gated-pill-container",
    )
    val content by animateColorAsState(
        if (enabled) palette.onAccent else palette.muted,
        tween(180),
        label = "gated-pill-content",
    )

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(
                text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * One-line "you are signed in" marker for the top of the search card.
 *
 * The search card is only reachable with a live session, but that is invisible
 * once you are looking at it — the only session indicator used to be further
 * down in Peer Search, so a session dropped mid-use looked like search silently
 * breaking.
 */
@Composable
fun SessionStrip(
    username: String,
    onDisconnect: () -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.ink.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(LiveGreen),
            )
            Text(
                if (username.isBlank()) "Connected" else "Connected as $username",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = palette.ink,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
        Text(
            "Disconnect",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.muted,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onDisconnect)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
