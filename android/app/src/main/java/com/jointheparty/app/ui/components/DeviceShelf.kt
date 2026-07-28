package com.jointheparty.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/**
 * CAL-08 (ui-ux-design-system.md §6.5 "Device shelf"): one row per known
 * device — name, latency (tabular numerals), provenance, and a compact
 * [CaliperScale] thumbnail at [DT.Calibration.shelfStripHeightPt]. Tapping a
 * row opens Device detail via [onSelectDevice].
 *
 * Stateless: the caller (here, [com.jointheparty.app.ui.session
 * .SessionViewModel]) hoists the profile list and which route is currently
 * connected, matching [CalibrationSheet]'s shape.
 *
 * The one-warm-accent rule (§4) is enforced structurally, not by convention:
 * only the row whose [CalibrationProfile.routeId] equals [connectedRouteId]
 * passes `connected = true` into [CaliperScale] — the caller can never
 * accidentally mark two rows connected at once because there is exactly one
 * comparison, run once per row, against one value.
 */
@Composable
fun DeviceShelf(
    profiles: List<CalibrationProfile>,
    connectedRouteId: String,
    onSelectDevice: (String) -> Unit,
    onCalibratePhoneSpeaker: () -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
) {
    if (profiles.isEmpty()) {
        EmptyDeviceShelf(onCalibratePhoneSpeaker, modifier)
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        profiles.forEachIndexed { index, profile ->
            DeviceShelfRow(
                profile = profile,
                connected = profile.routeId == connectedRouteId,
                nowMs = nowMs,
                onTap = { onSelectDevice(profile.routeId) },
            )
            if (index != profiles.lastIndex) Spacer(Modifier.height(DT.Space.sectionGap))
        }
    }
}

/** ui-ux §6.5 empty-state copy — verbatim; `internal const` so [com.jointheparty.app.ui.components.ProvenanceTest]'s copy audit diffs against the real strings, not a duplicate. */
internal const val DEVICE_SHELF_EMPTY_BODY = "No devices calibrated yet. Play something through a " +
    "speaker or headphones and JoinTheParty will get to know it."
internal const val DEVICE_SHELF_EMPTY_PRIMARY = "Calibrate phone speaker"

/** ui-ux §6.5 empty state — "an invitation, not a shrug." Copy verbatim. */
@Composable
private fun EmptyDeviceShelf(onCalibratePhoneSpeaker: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = DEVICE_SHELF_EMPTY_BODY,
            style = BilletType.body,
            color = DT.Colors.ink2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(DT.Space.sectionGap))
        SheetPill(DEVICE_SHELF_EMPTY_PRIMARY, primary = true, onTap = onCalibratePhoneSpeaker)
    }
}

/**
 * Row anatomy, top to bottom (ui-ux §6.5): name/value line, the provenance
 * line, then the strip, full row width.
 */
@Composable
private fun DeviceShelfRow(
    profile: CalibrationProfile,
    connected: Boolean,
    nowMs: Long,
    onTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(profile.deviceName, style = BilletType.label, color = DT.Colors.ink)
            Text("${profile.latencyMs} ms", style = BilletType.label, color = DT.Colors.ink)
        }
        Spacer(Modifier.height(4.dp))
        ProvenanceLine(profile, nowMs)
        Spacer(Modifier.height(DT.Space.grid))
        // The shelf strip is the detail pane's scale drawn shorter — same
        // stroke weights, same 0..scaleRangeMs axis (ui-ux §6.5). Hence
        // caliperHeight rather than constraining the modifier, which would
        // clip the ticks and settled line off at the bottom instead.
        CaliperScale(
            samples = profile.refereeSamples.map { it.residualMs.toFloat() },
            mode = CaliperMode.ReadOut(
                settledValueMs = profile.latencyMs.toFloat(),
                connected = connected,
                solid = profile.method != CalibrationProfile.Method.ESTIMATED,
            ),
            showAxisLabels = false,
            caliperHeight = DT.Calibration.shelfStripHeightPt.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- Previews ---------------------------------------------------------------

/** ui-ux §6.5's own three example rows: Living room speaker / AirPods Pro / Kitchen speaker. */
private fun shelfPreviewProfiles(nowMs: Long) = listOf(
    CalibrationProfile(
        routeId = "speaker",
        routeClass = "SPEAKER",
        deviceName = "Living room speaker",
        method = CalibrationProfile.Method.MEASURED,
        latencyMs = 204,
        confidence = 1.0f,
        sampleCount = 6,
        acousticallyReachable = true,
        createdAtMs = nowMs - 30 * 86_400_000L,
        updatedAtMs = nowMs - 2 * 86_400_000L,
        refereeSamples = listOf(198f, 202f, 204f, 205f, 201f, 203f).mapIndexed { i, ms ->
            CalibrationProfile.RefereeSample(residualMs = ms.toInt(), atMs = i.toLong())
        },
    ),
    CalibrationProfile(
        routeId = "bluetooth:AirPods Pro",
        routeClass = "BLUETOOTH",
        deviceName = "AirPods Pro",
        method = CalibrationProfile.Method.ESTIMATED,
        latencyMs = 182,
        confidence = 0f,
        sampleCount = 0,
        acousticallyReachable = false,
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
    ),
    CalibrationProfile(
        routeId = "bluetooth:Kitchen speaker",
        routeClass = "BLUETOOTH",
        deviceName = "Kitchen speaker",
        method = CalibrationProfile.Method.BY_EAR,
        latencyMs = 96,
        confidence = 0.7f,
        sampleCount = 3,
        acousticallyReachable = false,
        createdAtMs = nowMs - 10 * 86_400_000L,
        updatedAtMs = nowMs - 6 * 86_400_000L,
        refereeSamples = listOf(94f, 96f, 98f).mapIndexed { i, ms ->
            CalibrationProfile.RefereeSample(residualMs = ms.toInt(), atMs = i.toLong())
        },
    ),
)

@Preview(name = "Device shelf — populated", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun DeviceShelfPopulatedPreview() {
    val nowMs = 30 * 86_400_000L
    BilletTheme {
        DeviceShelf(
            profiles = shelfPreviewProfiles(nowMs),
            connectedRouteId = "speaker",
            onSelectDevice = {},
            onCalibratePhoneSpeaker = {},
            nowMs = nowMs,
        )
    }
}

@Preview(name = "Device shelf — empty", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun DeviceShelfEmptyPreview() {
    BilletTheme {
        DeviceShelf(
            profiles = emptyList(),
            connectedRouteId = "speaker",
            onSelectDevice = {},
            onCalibratePhoneSpeaker = {},
        )
    }
}
