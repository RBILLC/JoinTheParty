package com.jointheparty.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jointheparty.app.data.CalibrationProfile
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT
import com.jointheparty.app.ui.theme.recessedWell

/**
 * CAL-10 seam: the trim-promotion banner's content, rendered by
 * [DeviceDetail] whenever supplied. This ticket (CAL-08) never constructs
 * one — every call site passes `null` — so the slot stays completely inert;
 * CAL-10 fills in ≥3-commit detection and starts passing a real value.
 */
// ui-ux §6.5 Device detail — banner/action copy verbatim; `internal const`
// so ProvenanceTest's copy audit diffs against the real strings.
internal const val DETAIL_RECALIBRATE_LABEL = "Calibrate again"
// CFX-02 (tech-req §2.6 "Recalibration targeting"): shown beneath a
// disabled "Calibrate again" — recalibration always measures whatever
// route is live right now, so viewing a device that ISN'T the connected
// one can't offer a tappable pill that would either no-op silently or
// measure the wrong device.
internal const val DETAIL_RECALIBRATE_DISABLED_REASON = "Reconnect this device to recalibrate it"
internal const val DRIFT_BANNER_BODY = "This one's drifted from where we measured it. A quick " +
    "recalibration will tighten it back up."
internal const val DRIFT_BANNER_PRIMARY = "Recalibrate"
internal const val DRIFT_BANNER_QUIET = "Later"
internal const val TRIM_PROMOTION_PRIMARY = "Use this offset"
internal const val TRIM_PROMOTION_QUIET = "Keep as is"
internal const val TRIM_PROMOTION_CONFIRMATION =
    "Folded into the calibration — the wheel's back at zero."

data class TrimPromotionBannerState(
    val medianMs: Int,
    val onAccept: () -> Unit,
    val onDecline: () -> Unit,
    // ui-ux §6.5: "On accept... confirmation in fine: 'Folded into the
    // calibration — the wheel's back at zero.'" — shown in place of the
    // accept/decline actions once true.
    val accepted: Boolean = false,
)

/**
 * CAL-08 (ui-ux-design-system.md §6.5 "Device detail"): device name (drawn
 * by the caller as the sheet's title, matching [CalibrationSheet]'s eyebrow
 * + title header — not repeated here), the hero latency, the full
 * [CaliperScale] well at [DT.Calibration.detailScaleHeightPt], provenance
 * (or a banner in its place), and the "Calibrate again" secondary action.
 *
 * Stateless, matching [CalibrationSheet]'s shape.
 *
 * The hero value is always `ink` — NEVER `brass`, even for the connected
 * device: "brass is spent once, on the caliper line, not doubled onto the
 * number" (§6.5's deliberate call to keep "exactly one warm element"
 * unambiguous). Only [CaliperScale]'s settled line renders `brass`, and
 * only when [connected] is true.
 *
 * Drift and trim-promotion banners are mutually exclusive by construction:
 * the `when` below checks [CalibrationProfile.drifted] before [
 * trimPromotion], so a state where both are true still renders only the
 * drift banner — satisfying that acceptance criterion regardless of what
 * the caller passes.
 *
 * CFX-03 (tech-req §2.6 "Connected-state encoding"): [connected] also feeds
 * a `· Connected` text qualifier onto [ProvenanceLine] (in both the plain
 * and drift-banner branches below) — the caliper's `brass` settled line is
 * never the only connection signal.
 */
@Composable
fun DeviceDetail(
    profile: CalibrationProfile,
    connected: Boolean,
    onRecalibrate: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissBanner: () -> Unit = {},
    trimPromotion: TrimPromotionBannerState? = null,
    nowMs: Long = System.currentTimeMillis(),
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${profile.latencyMs}", style = BilletType.heroMs, color = DT.Colors.ink)
            Text(
                text = "ms",
                style = BilletType.heroUnit,
                color = DT.Colors.ink3,
                modifier = Modifier.padding(start = 8.dp, bottom = 14.dp),
            )
        }
        Spacer(Modifier.height(DT.Space.sectionGap))
        CaliperScale(
            samples = profile.refereeSamples.map { it.residualMs.toFloat() },
            mode = CaliperMode.ReadOut(
                settledValueMs = profile.latencyMs.toFloat(),
                connected = connected,
                solid = profile.method != CalibrationProfile.Method.ESTIMATED,
            ),
            showAxisLabels = true,
            // detailScaleHeightPt sizes the drawn scale; the well wraps that
            // plus its axis labels. Forcing the whole column to that height
            // instead would squeeze the two together.
            caliperHeight = DT.Calibration.detailScaleHeightPt.dp,
            modifier = Modifier
                .fillMaxWidth()
                .recessedWell(RoundedCornerShape(DT.Shape.radiusCard)),
        )
        Spacer(Modifier.height(DT.Space.sectionGap))

        when {
            // Drift wins over trim-promotion whenever both happen to be
            // true — see the doc comment above.
            profile.drifted -> {
                ProvenanceLine(profile, nowMs, connected = connected)
                Spacer(Modifier.height(DT.Space.grid))
                // CFX-02: the drift banner's "Recalibrate" routes through
                // the same onRecalibrate as the plain provenance case below
                // — same disabled/reason treatment when this pane's device
                // isn't the connected one.
                DriftBanner(connected = connected, onRecalibrate = onRecalibrate, onLater = onDismissBanner)
            }
            trimPromotion != null -> TrimPromotionBanner(trimPromotion)
            else -> {
                ProvenanceLine(profile, nowMs, connected = connected)
                Spacer(Modifier.height(DT.Space.sectionGap))
                // CFX-02 (tech-req §2.6): disabled, with a plain-language
                // reason beneath, whenever this pane's device isn't the
                // connected route — never a tappable pill that either
                // no-ops or opens a guided flow titled with some other
                // device.
                SheetPill(DETAIL_RECALIBRATE_LABEL, primary = false, enabled = connected, onTap = onRecalibrate)
                if (!connected) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = DETAIL_RECALIBRATE_DISABLED_REASON,
                        style = BilletType.fine,
                        color = DT.Colors.ink3,
                    )
                }
            }
        }
    }
}

/** ui-ux §6.5 Drift banner — copy verbatim. CFX-02: [connected] mirrors the plain-provenance case's disabled/reason treatment. */
@Composable
private fun DriftBanner(connected: Boolean, onRecalibrate: () -> Unit, onLater: () -> Unit) {
    Text(
        text = DRIFT_BANNER_BODY,
        style = BilletType.body,
        color = DT.Colors.ink2,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(DT.Space.sectionGap))
    SheetPill(DRIFT_BANNER_PRIMARY, primary = true, enabled = connected, onTap = onRecalibrate)
    if (!connected) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = DETAIL_RECALIBRATE_DISABLED_REASON,
            style = BilletType.fine,
            color = DT.Colors.ink3,
        )
    }
    Spacer(Modifier.height(DT.Space.grid))
    QuietText(DRIFT_BANNER_QUIET, onTap = onLater)
}

/** ui-ux §6.5 Trim promotion banner — copy verbatim (CAL-10 seam; see [TrimPromotionBannerState]). */
@Composable
private fun TrimPromotionBanner(state: TrimPromotionBannerState) {
    Text(
        text = "You've nudged this by about ${"%+d".format(state.medianMs)} ms, " +
            "three times running. Make that the calibration?",
        style = BilletType.body,
        color = DT.Colors.ink2,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(DT.Space.sectionGap))
    if (state.accepted) {
        Text(
            text = TRIM_PROMOTION_CONFIRMATION,
            style = BilletType.fine,
            color = DT.Colors.ink3,
        )
    } else {
        SheetPill(TRIM_PROMOTION_PRIMARY, primary = true, onTap = state.onAccept)
        Spacer(Modifier.height(DT.Space.grid))
        QuietText(TRIM_PROMOTION_QUIET, onTap = state.onDecline)
    }
}

// ---- Previews ---------------------------------------------------------------

private fun previewProfile(
    routeId: String,
    deviceName: String,
    method: CalibrationProfile.Method,
    latencyMs: Int,
    samples: List<Float>,
    nowMs: Long,
    updatedDaysAgo: Long,
    drifted: Boolean = false,
) = CalibrationProfile(
    routeId = routeId,
    routeClass = "SPEAKER",
    deviceName = deviceName,
    method = method,
    latencyMs = latencyMs,
    confidence = 1.0f,
    sampleCount = samples.size,
    acousticallyReachable = method == CalibrationProfile.Method.MEASURED,
    createdAtMs = nowMs - 30 * 86_400_000L,
    updatedAtMs = nowMs - updatedDaysAgo * 86_400_000L,
    refereeSamples = samples.mapIndexed { i, ms ->
        CalibrationProfile.RefereeSample(residualMs = ms.toInt(), atMs = i.toLong())
    },
    drifted = drifted,
)

@Preview(name = "Device detail — Measured, connected", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun DeviceDetailMeasuredConnectedPreview() {
    val nowMs = 30 * 86_400_000L
    BilletTheme {
        DeviceDetail(
            profile = previewProfile(
                routeId = "speaker",
                deviceName = "Living room speaker",
                method = CalibrationProfile.Method.MEASURED,
                latencyMs = 204,
                samples = listOf(198f, 202f, 204f, 205f, 201f, 203f),
                nowMs = nowMs,
                updatedDaysAgo = 2,
            ),
            connected = true,
            onRecalibrate = {},
            nowMs = nowMs,
        )
    }
}

@Preview(name = "Device detail — Estimated, not connected", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun DeviceDetailEstimatedPreview() {
    val nowMs = 30 * 86_400_000L
    BilletTheme {
        DeviceDetail(
            profile = previewProfile(
                routeId = "bluetooth:AirPods Pro",
                deviceName = "AirPods Pro",
                method = CalibrationProfile.Method.ESTIMATED,
                latencyMs = 182,
                samples = emptyList(),
                nowMs = nowMs,
                updatedDaysAgo = 0,
            ),
            connected = false,
            onRecalibrate = {},
            nowMs = nowMs,
        )
    }
}

@Preview(name = "Device detail — drift banner", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun DeviceDetailDriftPreview() {
    val nowMs = 30 * 86_400_000L
    BilletTheme {
        DeviceDetail(
            profile = previewProfile(
                routeId = "speaker",
                deviceName = "Living room speaker",
                method = CalibrationProfile.Method.MEASURED,
                latencyMs = 204,
                samples = listOf(198f, 202f, 204f, 205f, 201f, 203f),
                nowMs = nowMs,
                updatedDaysAgo = 2,
                drifted = true,
            ),
            connected = true,
            onRecalibrate = {},
            nowMs = nowMs,
        )
    }
}
