package com.jointheparty.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jointheparty.app.ui.session.FirstContactGateState
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT

/**
 * CAL-09 (ui-ux-design-system.md §6.5 "First-contact gate" / tech-req
 * §2.6 "Scope"): shown once, before playback, when an unknown routeId
 * becomes the active output. A guided PROMPT, not a wall — "Not now" is a
 * legitimate choice, never a failure state, answered by
 * [com.jointheparty.app.ui.session.SessionViewModel.declineFirstContactGate]
 * writing a usable ESTIMATED profile immediately.
 *
 * This gates calibration's AIM only — never recognition. Recognition reads
 * the mic, not the speaker, so an unknown/uncalibrated device identifies
 * the room's song exactly as well as a calibrated one; it just can't aim
 * the initial seek yet. See [FirstContactGateState]'s doc comment for the
 * full reasoning — nothing in this file (or its caller) ever touches
 * `startListening`/recognition.
 *
 * CFX-06 (tech-req §2.6 "Gate copy must not pre-commit to a route class" /
 * ui-ux §6.5 "Corrected to a single, route-neutral variant"): formerly two
 * variants (ACOUSTIC/HEADPHONE) branched on route class — a Bluetooth
 * speaker was told the chirp "keeps everyone in sync on this speaker," then
 * timed out and asked for fifteen more seconds by ear; a 3.5mm cable into a
 * PA was told "Headphones can't be heard by the phone's mic" (false for
 * that rig) and routed straight past the acoustic measurement that would
 * have worked. The gate cannot know acoustic-capability in advance — only
 * running the chirp can — so there is now exactly one copy set, for every
 * route.
 */
// ui-ux §6.5 copy deck — verbatim; `internal const` so a string-diff test
// can pin it, matching Provenance.kt/DeviceShelf.kt's established pattern.
internal const val GATE_BODY = "A quick calibration keeps everyone in sync. Takes about ten seconds."
internal const val GATE_PRIMARY = "Calibrate now"
internal const val GATE_QUIET = "Not now"
internal const val GATE_FINE_CAPTION = "We'll use a generic default until you do."

/** "New here: {device name}" (ui-ux §6.5) — templated, so a function rather than a const. */
internal fun firstContactGateTitle(deviceName: String) = "New here: $deviceName"

/** Billet-styled ModalBottomSheet host, matching [CalibrationSheet]'s container/content colors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstContactGateSheet(
    gate: FirstContactGateState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDecline,
        containerColor = DT.Colors.billet,
        contentColor = DT.Colors.ink,
    ) {
        FirstContactGateContent(gate, onAccept, onDecline)
    }
}

/** Stateless content, split out so it previews without a real bottom-sheet host. */
@Composable
internal fun FirstContactGateContent(
    gate: FirstContactGateState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = DT.Space.gutter,
                end = DT.Space.gutter,
                bottom = DT.Space.sectionGap,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = firstContactGateTitle(gate.deviceName),
            style = BilletType.title,
            color = DT.Colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(DT.Space.sectionGap))
        Text(text = GATE_BODY, style = BilletType.body, color = DT.Colors.ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(DT.Space.sectionGap))
        SheetPill(GATE_PRIMARY, primary = true, onTap = onAccept)
        Spacer(Modifier.height(DT.Space.grid))
        QuietText(GATE_QUIET, onTap = onDecline)
        Spacer(Modifier.height(8.dp))
        Text(text = GATE_FINE_CAPTION, style = BilletType.fine, color = DT.Colors.ink3, textAlign = TextAlign.Center)
    }
}

// ---- Previews ---------------------------------------------------------------

// CFX-06: a single preview now that there's one route-neutral variant —
// the acoustic/headphone-class split previously previewed here no longer
// exists (see this file's top-of-file doc comment).
@Preview(name = "First-contact gate", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun FirstContactGatePreview() {
    BilletTheme {
        FirstContactGateContent(
            gate = FirstContactGateState(
                routeId = "speaker",
                deviceName = "Living room speaker",
            ),
            onAccept = {},
            onDecline = {},
        )
    }
}
