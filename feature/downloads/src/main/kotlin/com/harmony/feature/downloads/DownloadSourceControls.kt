package com.harmony.feature.downloads

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.GlassInsetPanel
import com.harmony.core.ui.component.glassFill

private val SegmentShape = RoundedCornerShape(18.dp)
private val DownloadMethodShape = RoundedCornerShape(28.dp)
private val SourceInfoShape = RoundedCornerShape(22.dp)
private const val SEGMENT_ANIM_MS = 220

/**
 * Premium download-source card used on the Downloads screen.
 *
 * The old card was technically correct but visually flat: title, small toggle,
 * then a one-line tagline. This version mirrors the approved Harmony mockup:
 * a clear download glyph, stronger hierarchy, a large segmented source picker,
 * and an always-visible contextual panel that explains the active source.
 *
 * It is still the SAME functional source selector. Tapping a segment calls the
 * same [onSelect] callback, so the rest of Downloads immediately switches to
 * the corresponding Soulseek or SpotiFLAC flow and the persisted preference is
 * kept by DownloadsViewModel exactly as before.
 */
@Composable
fun DownloadMethodCard(
    selected: DownloadSource,
    onSelect: (DownloadSource) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    val identity = selected.identity()
    // Opaque: Compose scales shadow colour by its own elevation-derived
    // alpha, so pre-fading this multiplies the two and the shadow
    // vanishes. Tint only, no alpha.
    val halo = identity.brand
    val outline = palette.line.copy(alpha = 0.78f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = DownloadMethodShape,
                ambientColor = halo,
                spotColor = halo,
            ),
        shape = DownloadMethodShape,
        // Transparent, with the frosted fill applied to the Column below —
        // an opaque colour here would paint over the gradient and flatten
        // the pane back out. The brand halo in the shadow above is kept:
        // it tints the card's cast shadow with whichever engine is armed.
        color = Color.Transparent,
        contentColor = palette.ink,
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.72f),
                    Color.White.copy(alpha = 0.16f),
                    outline.copy(alpha = 0.30f),
                ),
            ),
        ),
    ) {
        Column(
            Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            // Frosted, brightest at the top so the pane
                            // reads as lit from above, with a trace of the
                            // armed engine's brand warming the top edge.
                            lerp(glassFill(palette, strength = 0.74f), identity.brand, 0.05f),
                            glassFill(palette, strength = 0.62f),
                            glassFill(palette, strength = 0.56f),
                        ),
                    ),
                )
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            SourceRoundIcon(
                icon = Icons.Rounded.Download,
                tint = identity.brand,
                modifier = Modifier.size(58.dp),
                iconSize = 30.dp,
            )

            Spacer(Modifier.height(22.dp))

            Text(
                text = "Choose your preferred source",
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                color = palette.ink,
            )
            Text(
                text = "Harmony will use the source you select here for all downloads and won’t switch automatically.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 9.dp),
            )

            DownloadSourceRail(
                selected = selected,
                onSelect = onSelect,
                palette = palette,
                modifier = Modifier.padding(top = 22.dp),
            )

            SourceInfoPanel(
                source = selected,
                palette = palette,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
}

/**
 * The engine picker. Both the main premium card and the confirm dialog use the
 * same component, so the active state can never disagree with the real source.
 */
@Composable
fun DownloadSourceSelector(
    selected: DownloadSource,
    onSelect: (DownloadSource) -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * Which engines this particular picker may offer.
     *
     * Defaults to all of them, but it has to be restrictable: this same
     * component backs the Spotify playlist transfer screen, and YT Converter
     * cannot do a transfer — it resolves a track from a URL you paste, and
     * Harmony has no YouTube search to turn a playlist of names into links.
     * Iterating DownloadSource.entries unconditionally would put a segment
     * there that throws the moment it's used.
     */
    sources: List<DownloadSource> = DownloadSource.entries,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(SegmentShape)
            .background(palette.ink.copy(alpha = 0.028f))
            .border(1.25.dp, palette.line.copy(alpha = 0.70f), SegmentShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sources.forEach { source ->
            SourceSegment(
                source = source,
                selected = source == selected,
                onClick = { if (enabled) onSelect(source) },
                palette = palette,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The engines that can resolve a track from metadata alone. */
val TransferCapableSources: List<DownloadSource> =
    listOf(DownloadSource.SPOTIFLAC, DownloadSource.SOULSEEK)

@Composable
private fun SourceSegment(
    source: DownloadSource,
    selected: Boolean,
    onClick: () -> Unit,
    palette: EditorialPalette,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val identity = source.identity()
    val spec = tween<Color>(SEGMENT_ANIM_MS)
    val container by animateColorAsState(
        if (selected) identity.brand else Color.Transparent,
        spec,
        label = "source-segment-container",
    )
    val content by animateColorAsState(
        when {
            selected -> identity.onBrand
            enabled -> palette.ink
            else -> palette.muted
        },
        spec,
        label = "source-segment-content",
    )
    val segmentOutline by animateColorAsState(
        if (selected) identity.brand.copy(alpha = 0.95f) else Color.Transparent,
        spec,
        label = "source-segment-outline",
    )

    Row(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(container)
            .border(1.25.dp, segmentOutline, RoundedCornerShape(15.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = source.selectorIcon(),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = source.displayName,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            letterSpacing = 0.1.sp,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun SourceInfoPanel(
    source: DownloadSource,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    val identity = source.identity()
    val description = when (source) {
        DownloadSource.SPOTIFLAC ->
            "Verified provider downloads with FLAC lossless or MP3 320 output."
        DownloadSource.SOULSEEK ->
            "Peer-to-peer search with explicit peer and file selection for accurate results."
        DownloadSource.YTCONVERTER ->
            "Paste a YouTube link and Harmony converts it to FLAC or MP3 on your phone."
    }

    // Inset, not another card: this sits inside DownloadMethodCard, and a
    // second raised pane on top of the first would double the shadow.
    GlassInsetPanel(
        palette = palette,
        modifier = modifier.fillMaxWidth(),
        shape = SourceInfoShape,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceRoundIcon(
                icon = Icons.Rounded.Info,
                tint = identity.brand,
                modifier = Modifier.size(44.dp),
                iconSize = 22.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Using ${identity.displayName}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = identity.brand,
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceRoundIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

private fun DownloadSource.selectorIcon(): ImageVector = when (this) {
    DownloadSource.SPOTIFLAC -> Icons.Rounded.GraphicEq
    DownloadSource.SOULSEEK -> Icons.Rounded.Share
    DownloadSource.YTCONVERTER -> Icons.Rounded.Link
}

/**
 * Same glyph mapping as [selectorIcon], exposed for [DownloadSourceRail],
 * which lives in another file. Kept as a thin delegate rather than making
 * [selectorIcon] public so there is still exactly one place that decides
 * which icon means which engine.
 */
internal fun DownloadSource.railIcon(): ImageVector = selectorIcon()

/**
 * Compact marker kept for dialogs/debug surfaces that still want a terse
 * indicator. The main Downloads screen now uses [DownloadMethodCard] instead.
 */
@Composable
fun ActiveSourceBadge(
    identity: DownloadSourceIdentity,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.accent)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = identity.monogram,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.6.sp,
            color = palette.onAccent,
        )
        Text(
            text = identity.displayName,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.onAccent,
        )
    }
}
