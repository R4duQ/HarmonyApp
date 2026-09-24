package com.harmony.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.core.ui.component.GlassCard
import com.harmony.core.ui.component.glassFill
import com.harmony.core.ui.component.glassRim

/** Horizontal page margin shared by every section, so edges line up down the page. */
internal val HomeGutter = 20.dp

/** Width of one card in a horizontal row; its cover is square at this width. */
internal val RowCardWidth = 148.dp

/**
 * Tap handling with a short scale-down while pressed.
 *
 * No ripple: on a glass card a grey ripple reads as a smear across the
 * frost. The scale is a graphicsLayer transform, so pressing never
 * re-measures or re-lays-out anything.
 */
@Composable
internal fun Modifier.pressable(
    onClick: () -> Unit,
    onClickLabel: String? = null,
    role: Role = Role.Button,
    pressedScale: Float = 0.97f,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = source,
            indication = null,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}

/**
 * Fades a section in, drifting up a few dp, the first time the page has
 * data. [index] staggers sections by a few frames so the page assembles top
 * to bottom rather than popping in at once.
 *
 * If the data was already there when the section appeared — coming back to
 * Home from an album — it starts fully visible and nothing animates. It is a
 * graphicsLayer effect only, so it never shifts the layout.
 */
@Composable
internal fun Modifier.appearOnLoad(loaded: Boolean, index: Int): Modifier {
    val progress = remember { Animatable(if (loaded) 1f else 0f) }
    LaunchedEffect(loaded) {
        if (loaded && progress.value < 1f) {
            progress.animateTo(
                1f,
                tween(durationMillis = 320, delayMillis = 40 * index.coerceAtMost(6), easing = FastOutSlowInEasing),
            )
        }
    }
    val lift = with(LocalDensity.current) { 12.dp.toPx() }
    return graphicsLayer {
        alpha = if (loaded) progress.value else 1f
        translationY = if (loaded) (1f - progress.value) * lift else 0f
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = HomeGutter, end = HomeGutter - 8.dp, top = 26.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 21.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = palette.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.accent,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .pressable(onAction)
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * A cover over its own placeholder: the placeholder shows while the image
 * loads and stays if it fails, so there is never a hole or a size change —
 * the box is sized by the caller, never by the image.
 */
@Composable
internal fun Cover(
    uri: String?,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    glyph: ImageVector = Icons.Rounded.MusicNote,
    glyphSize: Dp = 28.dp,
    elevation: Dp = 0.dp,
) {
    Box(
        modifier
            .then(
                if (elevation > 0.dp) {
                    Modifier.shadow(elevation, shape, ambientColor = palette.ink, spotColor = palette.ink)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(palette.accent.copy(alpha = 0.26f), palette.accent.copy(alpha = 0.10f)),
                ),
            )
            .border(1.dp, palette.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, contentDescription = null, tint = palette.accent.copy(alpha = 0.8f), modifier = Modifier.size(glyphSize))
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Up to four covers in a 2x2 grid; fewer fill the tile with the first. */
@Composable
internal fun MosaicCover(artwork: List<String>, palette: EditorialPalette, modifier: Modifier, glyph: ImageVector) {
    val shape = RoundedCornerShape(16.dp)
    if (artwork.size < 4) {
        Cover(artwork.firstOrNull(), palette, modifier, shape, glyph = glyph, elevation = 6.dp)
        return
    }
    Box(
        modifier
            .shadow(6.dp, shape, ambientColor = palette.ink, spotColor = palette.ink)
            .clip(shape)
            .background(palette.field),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                MosaicTile(artwork[0], palette, glyph)
                MosaicTile(artwork[1], palette, glyph)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                MosaicTile(artwork[2], palette, glyph)
                MosaicTile(artwork[3], palette, glyph)
            }
        }
    }
}

@Composable
private fun RowScope.MosaicTile(uri: String, palette: EditorialPalette, glyph: ImageVector) {
    Cover(
        uri = uri,
        palette = palette,
        modifier = Modifier.weight(1f).fillMaxSize(),
        shape = RoundedCornerShape(0.dp),
        glyph = glyph,
        glyphSize = 18.dp,
    )
}

/**
 * The small Play disc that sits on a cover's corner. It is its own touch
 * target with its own label, separate from the card around it, so "open this
 * album" and "play this album" can never be confused: the card opens, the
 * disc plays.
 */
@Composable
internal fun CoverPlayButton(
    contentDescription: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .pressable(onClick, pressedScale = 0.88f)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .shadow(8.dp, CircleShape, ambientColor = palette.ink, spotColor = palette.ink)
                .clip(CircleShape)
                .background(palette.accent)
                .border(1.dp, glassRim(palette), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = palette.onAccent, modifier = Modifier.size(22.dp))
        }
    }
}

/** 48dp touch target around a 44dp frosted disc — the header and card buttons. */
@Composable
internal fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    palette: EditorialPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .pressable(onClick, pressedScale = 0.9f)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(glassFill(palette, 0.9f))
                // palette.line rather than the white glass rim, which
                // disappears against the near-white field in light mode.
                .border(1.dp, palette.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.ink, modifier = Modifier.size(21.dp))
        }
    }
}

/** A pulsing block standing in for content that has not arrived yet, at its final size. */
@Composable
internal fun Placeholder(palette: EditorialPalette, modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val pulse = rememberInfiniteTransition(label = "placeholder")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "placeholder-alpha",
    )
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .background(palette.line.copy(alpha = 0.6f)),
    )
}

/** The album glyph used where an album has no cover. */
internal val AlbumGlyph: ImageVector get() = Icons.Rounded.Album

/**
 * [GlassCard], with its frosted gradient pre-composited onto the page colour.
 *
 * GlassCard's fill is slightly translucent and it casts a shadow, and a
 * shadow is drawn under the card's whole outline — so it showed through the
 * frost as a darker band inside every card. On Home the card only ever sits
 * on the plain field, so painting the same gradient already blended onto
 * that field looks identical and hides the shadow. GlassCard itself is left
 * alone; Downloads and the other screens that use it are unaffected.
 */
@Composable
internal fun HomeCard(
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val body = remember(palette) {
        Brush.verticalGradient(
            listOf(
                glassFill(palette, strength = 0.72f).compositeOver(palette.field),
                glassFill(palette, strength = 0.58f).compositeOver(palette.field),
            ),
        )
    }
    GlassCard(palette = palette, modifier = modifier) {
        Column(Modifier.fillMaxWidth().background(body), content = content)
    }
}
