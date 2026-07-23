package com.jointheparty.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jointheparty.app.ui.session.CalibrationState
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT

/**
 * INT-03: the per-route latency calibration sheet (arch §6.4, ui-ux §6.4
 * error-voice rules: state what happened, state the fix, no apology
 * theater). Billet-styled ModalBottomSheet on the `billet` surface.
 *
 * Stateless: the caller owns [CalibrationState] (from SyncState) and the
 * intents. Quiet by design — one action at a time, no progress bars; the
 * Running state is a sentence, not a spinner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationSheet(
    routeName: String?,
    calibration: CalibrationState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DT.Colors.billet,
        contentColor = DT.Colors.ink,
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
            Text("CALIBRATE", style = BilletType.engraved, color = DT.Colors.ink3)
            Spacer(Modifier.height(8.dp))
            Text(
                text = routeName ?: "Phone speaker",
                style = BilletType.title,
                color = DT.Colors.ink,
            )
            Spacer(Modifier.height(DT.Space.sectionGap))

            when (calibration) {
                CalibrationState.Idle -> {
                    Text(
                        "Plays a short tone and measures how long this route " +
                            "takes to make it audible.",
                        style = BilletType.body,
                        color = DT.Colors.ink2,
                    )
                    Spacer(Modifier.height(DT.Space.sectionGap))
                    SheetPill("Start calibration", primary = true, onTap = onStart)
                }
                CalibrationState.Running -> {
                    Text(
                        "Listening for the chirp…",
                        style = BilletType.body,
                        color = DT.Colors.ink2,
                    )
                    Spacer(Modifier.height(DT.Space.sectionGap))
                    SheetPill("Cancel", primary = false, onTap = onCancel)
                }
                is CalibrationState.Success -> {
                    Text(
                        "Latency measured: ${calibration.latencyMs} ms",
                        style = BilletType.title,
                        color = DT.Colors.brass,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saved for this route — sync will aim ahead by it " +
                            "automatically.",
                        style = BilletType.fine,
                        color = DT.Colors.ink3,
                    )
                    Spacer(Modifier.height(DT.Space.sectionGap))
                    SheetPill("Done", primary = true, onTap = onDismiss)
                }
                CalibrationState.Failed -> {
                    Text(
                        "Couldn't hear the chirp — turn the volume up and " +
                            "try again.",
                        style = BilletType.body,
                        color = DT.Colors.ink2,
                    )
                    Spacer(Modifier.height(DT.Space.sectionGap))
                    SheetPill("Try again", primary = true, onTap = onStart)
                }
            }
        }
    }
}

/** Local pill matching §6.3 (SessionScreen's pills are private to it). */
@Composable
private fun SheetPill(label: String, primary: Boolean, onTap: () -> Unit) {
    val shape = RoundedCornerShape(44.dp)
    val base = Modifier
        .clip(shape)
        .let {
            if (primary) it.background(DT.Colors.brass)
            else it.border(1.dp, DT.Colors.hairline, shape)
        }
        .clickable(onClick = onTap)
        .padding(horizontal = 28.dp, vertical = 14.dp)
    Text(
        text = label,
        style = BilletType.label,
        color = if (primary) DT.Colors.void else DT.Colors.ink,
        modifier = base,
    )
}

// ---- Previews (sheet content only; ModalBottomSheet doesn't preview) -----

@Composable
private fun PreviewSheetBody(state: CalibrationState) {
    BilletTheme {
        Column(Modifier.background(DT.Colors.billet).padding(DT.Space.gutter)) {
            when (state) {
                is CalibrationState.Success ->
                    Text(
                        "Latency measured: ${state.latencyMs} ms",
                        style = BilletType.title,
                        color = DT.Colors.brass,
                    )
                else ->
                    Text(
                        "Listening for the chirp…",
                        style = BilletType.body,
                        color = DT.Colors.ink2,
                    )
            }
        }
    }
}

@Preview(name = "Running", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun CalibrationRunningPreview() = PreviewSheetBody(CalibrationState.Running)

@Preview(name = "Success", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun CalibrationSuccessPreview() =
    PreviewSheetBody(CalibrationState.Success(182))
