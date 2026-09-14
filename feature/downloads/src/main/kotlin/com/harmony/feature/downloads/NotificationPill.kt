package com.harmony.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.harmony.core.ui.component.EditorialPalette

/**
 * The dark notification-style row used across the Downloads section.
 *
 * Shared rather than duplicated because it now backs two different lists
 * (completed downloads, and the album edition chooser reached from
 * Discover) that should stay visually identical — the whole point of the
 * treatment is that a row means the same thing wherever it appears.
 *
 * The dark surface is a fixed colour, not palette-derived, and that is
 * deliberate: inverting it in dark mode would collapse it into just
 * another light card and lose the contrast against the amber cards that
 * make up the rest of the screen. It reads as a discrete "item" against
 * the section's own field in both themes, the same way the mini player's
 * fixed yellow does.
 *
 * [action] renders OUTSIDE the pill, to its right — a check for something
 * finished, a chevron for something you can open. It is tinted from
 * [palette] rather than fixed, since it sits on the section's field, not
 * on the dark surface.
 */
@Composable
internal fun NotificationPill(
    title: String,
    detail: String,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    coverUrl: String? = null,
    leadingIcon: ImageVector = Icons.Rounded.Album,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val surface = Color(0xFF1C1C1E)
    val onSurface = Color(0xFFFFFFFF)
    val onSurfaceMuted = Color(0xFF9A9A9F)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(surface)
                .then(
                    if (onClick != null && enabled) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2E2E32)),
                contentAlignment = Alignment.Center,
            ) {
                if (coverUrl.isNullOrBlank()) {
                    Icon(
                        leadingIcon,
                        contentDescription = null,
                        tint = onSurfaceMuted,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (trailingLabel != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            trailingLabel,
                            fontSize = 12.sp,
                            color = onSurfaceMuted,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    detail,
                    fontSize = 13.sp,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (action != null) {
            Box(Modifier.padding(start = 10.dp)) { action() }
        }
    }
}

/** The circular affordance that sits beside a [NotificationPill]. */
@Composable
internal fun PillAction(
    icon: ImageVector,
    contentDescription: String?,
    palette: EditorialPalette,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(palette.ink.copy(alpha = if (enabled) 0.14f else 0.06f))
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = palette.ink.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}
