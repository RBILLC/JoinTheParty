package com.jointheparty.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * UI-01: machined depth (ui-ux-design-system.md §4).
 *
 * Depth = machining, not glass: raised elements get a 1px top inner
 * highlight (ink at 4%) plus a soft shadow; recessed wells get the inverse —
 * carved-in `recess` fill with a 1px bottom inner light edge and a dark top
 * inner edge where the "material" occludes the light. No blur, no
 * translucency, no glow.
 */

private val CardShape = RoundedCornerShape(DT.Shape.radiusCard)

/** Raised, matte, machined-from-billet surface. */
fun Modifier.machinedDepth(shape: Shape = CardShape): Modifier = this
    .shadow(
        elevation = 12.dp,
        shape = shape,
        ambientColor = DT.Colors.void,
        spotColor = DT.Colors.void,
    )
    .clip(shape)
    .background(DT.Colors.billet)
    .drawWithContent {
        drawContent()
        // Top inner highlight: the specular edge of a milled block.
        drawLine(
            color = DT.Colors.ink.copy(alpha = 0.04f),
            start = Offset(0f, 0.5f),
            end = Offset(size.width, 0.5f),
            strokeWidth = 1f,
        )
    }

/** Recessed instrument well — darker than the background, carved in. */
fun Modifier.recessedWell(shape: Shape = CardShape): Modifier = this
    .clip(shape)
    .background(DT.Colors.recess)
    .drawWithContent {
        drawContent()
        // Occlusion at the top lip…
        drawLine(
            color = DT.Colors.void.copy(alpha = 0.9f),
            start = Offset(0f, 0.5f),
            end = Offset(size.width, 0.5f),
            strokeWidth = 1f,
        )
        // …and caught light on the bottom lip.
        drawLine(
            color = DT.Colors.ink.copy(alpha = 0.05f),
            start = Offset(0f, size.height - 0.5f),
            end = Offset(size.width, size.height - 0.5f),
            strokeWidth = 1f,
        )
    }
