package com.harmony.core.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Height of the button face. Comfortably past the 48dp touch-target floor. */
private val FACE_HEIGHT = 54.dp

/**
 * The one control in the editorial kit that looks like a physical object.
 *
 * [EditorialPill] is flat because it sits inside dense rows where a shadow
 * would be noise. This is for the opposite case: a screen's single primary
 * action, where the button should read as something you press rather than
 * something you tap. It gets there with a hard offset shelf rather than a
 * Material blur — the editorial screens are built on flat poster color, and a
 * soft elevation shadow is the one thing that would give away that this is a
 * Material app underneath.
 *
 * Pressing collapses the offset to zero, so the face travels down onto its
 * shelf and back. The motion is 90ms and 5dp — enough to feel mechanical, too
 * small to read as decoration, and short enough that it doesn't need a
 * reduced-motion escape hatch (the project has no such setting yet; if one is
 * added, [depth] is the value to zero out).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorialRaisedButton(
    text: String,
    onClick: () -> Unit,
    palette: EditorialPalette,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String = text,
    enabled: Boolean = true,
    depth: Dp = 5.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Rest = raised by `depth`; pressed = flush with the shelf.
    val lift by animateDpAsState(
        targetValue = if (pressed || !enabled) 0.dp else depth,
        animationSpec = tween(90),
        label = "raised-button-lift",
    )
    val shape = RoundedCornerShape(14.dp)

    Box(modifier = modifier.height(FACE_HEIGHT + depth)) {
        // The shelf: the same shape, parked at full depth, never moving. What
        // the face lifts off of.
        Box(
            Modifier
                .fillMaxWidth()
                .height(FACE_HEIGHT)
                .offset(y = depth)
                .background(palette.line, shape),
        )
        Surface(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .height(FACE_HEIGHT)
                .offset(y = depth - lift)
                // The label lives on the Surface so TalkBack announces one
                // button rather than a button wrapping separate text.
                .semantics { this.contentDescription = contentDescription },
            shape = shape,
            color = palette.accent,
            contentColor = palette.onAccent,
            border = BorderStroke(1.5.dp, palette.line),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(19.dp)
                            .padding(end = 1.dp),
                    )
                }
                Text(
                    text,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = if (icon != null) 9.dp else 0.dp),
                )
            }
        }
    }
}
