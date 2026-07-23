package com.jointheparty.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jointheparty.app.R

/**
 * UI-01: Billet theme foundation (ui-ux-design-system.md §2–3).
 *
 * Type is built from the generated [DT.Type] tokens. Instrument Sans
 * (variable) ships in a later ticket; until the font file lands we ride the
 * platform sans so every style, size, weight, tracking and `tnum` setting is
 * already exact — swapping in the real family is a one-line change here.
 */
/**
 * Instrument Sans variable (res/font/instrument_sans.ttf, OFL — license at
 * docs/licenses/OFL-InstrumentSans.txt). One variable file registered per
 * weight the ramp uses, each pinned to its `wght` instance; on API < 26
 * (no variable-font axes) the platform renders the default instance —
 * acceptable at minSdk 24.
 */
@OptIn(ExperimentalTextApi::class)
private val BilletFontFamily: FontFamily = FontFamily(
    Font(
        R.font.instrument_sans, weight = FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300)),
    ),
    Font(
        R.font.instrument_sans, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.instrument_sans, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.instrument_sans, weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

private fun DT.TextToken.toTextStyle(): TextStyle = TextStyle(
    fontFamily = BilletFontFamily,
    fontSize = sizeSp.sp,
    fontWeight = FontWeight(weight),
    // trackingPct is percent-of-size (ui spec §3): −2% at 76sp = −1.52sp.
    letterSpacing = (trackingPct / 100f * sizeSp).sp,
    lineHeight = (lineHeight * sizeSp).sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

/** The Billet type ramp, one TextStyle per DT token. */
object BilletType {
    val heroMs = DT.Type.heroMs.toTextStyle()
    val heroUnit = DT.Type.heroUnit.toTextStyle()
    val title = DT.Type.title.toTextStyle()
    val subtitle = DT.Type.subtitle.toTextStyle()
    val body = DT.Type.body.toTextStyle()
    val label = DT.Type.label.toTextStyle()
    val engraved = DT.Type.engraved.toTextStyle()
    val fine = DT.Type.fine.toTextStyle()
}

/**
 * Billet is a committed dark system (§1): one scheme, warm near-black.
 * Saturated color appears only through the heat scale and the primary CTA.
 */
private val BilletColorScheme = darkColorScheme(
    background = DT.Colors.void,
    surface = DT.Colors.billet,
    surfaceVariant = DT.Colors.recess,
    primary = DT.Colors.brass,
    onPrimary = DT.Colors.void,
    onBackground = DT.Colors.ink,
    onSurface = DT.Colors.ink,
    onSurfaceVariant = DT.Colors.ink2,
    outline = DT.Colors.hairline,
    error = DT.Colors.oxide,
)

@Composable
fun BilletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BilletColorScheme,
        content = content,
    )
}
