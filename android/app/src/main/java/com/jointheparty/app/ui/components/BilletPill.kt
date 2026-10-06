package com.jointheparty.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT

/**
 * The §6.3 pill: `brass` fill / `void` text when [primary], otherwise a 1px
 * `hairline` outline with `ink` text. The one pill idiom for the sheets,
 * the session screen and onboarding alike.
 * [horizontalPadding] exists because the full-screen pills (join, concierge)
 * sit on the [DT.Space.gutter] rhythm while the sheet pills are 28dp.
 */
@Composable
internal fun BilletPill(
    label: String,
    primary: Boolean,
    onTap: () -> Unit,
    enabled: Boolean = true,
    horizontalPadding: Dp = 28.dp,
) {
    val shape = RoundedCornerShape(44.dp)
    val base = Modifier
        .alpha(if (enabled) 1f else 0.4f)
        .clip(shape)
        .let {
            if (primary) it.background(DT.Colors.brass)
            else it.border(1.dp, DT.Colors.hairline, shape)
        }
        .clickable(enabled = enabled, onClick = onTap)
        .padding(horizontal = horizontalPadding, vertical = 14.dp)
    Text(
        text = label,
        style = BilletType.label,
        color = if (primary) DT.Colors.void else DT.Colors.ink,
        modifier = base,
    )
}
