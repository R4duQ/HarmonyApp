package com.harmony.feature.downloads

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.glassRim

private val RailPillShape = RoundedCornerShape(18.dp)

/**
 * The download-source picker: one pill per engine, the armed one filled.
 *
 * Laid out as a ROW rather than the mockup's left-hand rail, and that is a
 * deliberate departure. The mockup is a tablet-width composition: it gives
 * the rail about a third of the frame and the panel the rest. At the
 * Xperia's ~400dp that leaves roughly 250dp of panel, and the result rows
 * that panel has to hold — cover, title, "2:41 | Flac | 16 bit | 44.1 khz",
 * size, and a check — do not fit in 250dp without ellipsizing the metadata
 * line that is the entire point of showing it. The login and search panels
 * would have survived a side rail; the results panel is what breaks.
 *
 * Everything else from the mockup is kept: the filled/hollow pill pair, the
 * rounded geometry, the panel below, and the long-press tooltip.
 *
 * Long-press reveals [DownloadSourceIdentity.tagline] for the pressed
 * engine — the mockup's "information about source (when I hold 2 seconds)"
 * bubble. It's on long-press rather than always-visible because three
 * taglines stacked permanently would cost more vertical space than the
 * picker itself, on the screen where the search box wants to be near the
 * top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadSourceRail(
    selected: DownloadSource,
    onSelect: (DownloadSource) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sources: List<DownloadSource> = DownloadSource.entries,
) {
    var explaining by remember { mutableStateOf<DownloadSource?>(null) }
    val haptics = LocalHapticFeedback.current

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEach { source ->
                val isSelected = source == selected
                val identity = source.identity()

                val container by animateColorAsState(
                    targetValue = when {
                        !enabled -> palette.ink.copy(alpha = 0.05f)
                        isSelected -> identity.brand
                        else -> identity.brand.copy(alpha = 0.16f)
                    },
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "rail-container-${source.name}",
                )
                val content by animateColorAsState(
                    targetValue = when {
                        !enabled -> palette.muted
                        isSelected -> identity.onBrand
                        else -> palette.ink
                    },
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "rail-content-${source.name}",
                )

                val rim by animateColorAsState(
                    targetValue = if (isSelected) glassRim(palette) else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "rail-rim-${source.name}",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                        .clip(RailPillShape)
                        .background(container)
                        .border(1.dp, rim, RailPillShape)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = {
                                explaining = null
                                onSelect(source)
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                // Toggle, so a second long press dismisses
                                // it without needing to find the panel.
                                explaining = if (explaining == source) null else source
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            source.railIcon(),
                            contentDescription = null,
                            tint = content,
                            modifier = Modifier.size(19.dp),
                        )
                        Text(
                            source.displayName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = content,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        explaining?.let { source ->
            val identity = source.identity()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C1E))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(identity.brand),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        identity.monogram,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = identity.onBrand,
                    )
                }
                Text(
                    identity.tagline,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = Color(0xFFE9E9EC),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
