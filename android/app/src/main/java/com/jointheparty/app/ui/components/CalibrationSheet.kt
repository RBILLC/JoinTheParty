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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jointheparty.app.ui.session.CalibrationState
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT
import com.jointheparty.app.ui.theme.isReducedMotionEnabled
import com.jointheparty.app.ui.theme.recessedWell
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

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
    // CAL-07: by-ear (tone-match) intents. Defaults keep previews/simple
    // hosts terse, same convention as the acoustic intents above.
    onTryByEar: () -> Unit = {},
    onStartByEar: () -> Unit = {},
    onCancelByEar: () -> Unit = {},
    onCommitByEar: (Int) -> Unit = {},
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
                    Spacer(Modifier.height(8.dp))
                    // ui-ux §6.5: "The Quiet exit on Failed is new: By ear
                    // is a fallback on every route, not just headphones" —
                    // shown unconditionally, no device-class check.
                    QuietText("Try by ear instead", onTap = onTryByEar)
                }

                // ---- CAL-07: tone-match (by ear) -----------------------
                CalibrationState.ByEarIdle -> {
                    Text(
                        "Headphones can't be heard by the phone's mic — " +
                            "so you're the instrument here. We'll play a " +
                            "tone on a loop; drag the mark on the scale " +
                            "until it lands with what you hear.",
                        style = BilletType.body,
                        color = DT.Colors.ink2,
                    )
                    Spacer(Modifier.height(DT.Space.sectionGap))
                    SheetPill("Start", primary = true, onTap = onStartByEar)
                }
                CalibrationState.ByEarRunning -> {
                    ToneMatchCaliper(onCommit = onCommitByEar)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Drag until they land together.",
                        style = BilletType.body,
                        color = DT.Colors.ink2,
                    )
                    Spacer(Modifier.height(DT.Space.sectionGap))
                    QuietText("Cancel", onTap = onCancelByEar)
                }
                is CalibrationState.ByEarSuccess -> {
                    Text(
                        "By-ear offset set: ${calibration.latencyMs} ms",
                        style = BilletType.title,
                        color = DT.Colors.brass,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Good to about ±${DT.Calibration.byEarAccuracyMs.roundToInt()} ms " +
                            "— enough to keep things tight. Nudge it " +
                            "further anytime from the trim wheel.",
                        style = BilletType.fine,
                        color = DT.Colors.ink3,
                    )
                    Spacer(Modifier.height(DT.Space.sectionGap))
                    SheetPill("Done", primary = true, onTap = onDismiss)
                }
            }
        }
    }
}

/**
 * CAL-07: the Running-state interactive caliper — tone loop plays (owned by
 * the ViewModel's [com.jointheparty.app.audio.TonePlayer]), while THIS
 * composable owns the visual strike + `abClick` haptic beat, one repetition
 * every [DT.Calibration.toneMatchPeriodMs] (ui-ux §6.5 "The visual beat").
 *
 * Deliberately a `LaunchedEffect` (UI-side, this composable's own
 * composition-scoped coroutine) rather than anything on
 * `SessionViewModel.scope`: that scope runs on the JVM test suite's
 * `StandardTestDispatcher`, and an eagerly-launched `while(true) { delay
 * (...) }` there is exactly the pattern that hung the whole suite in CAL-04
 * (see `SessionViewModel.maybeSampleReferee`'s doc comment). This loop only
 * ever runs inside a live Compose composition — never touched by, and
 * incapable of hanging, `advanceUntilIdle()`.
 */
@Composable
private fun ToneMatchCaliper(onCommit: (Int) -> Unit) {
    val context = LocalContext.current
    val haptics = remember(context) { BilletHaptics(context) }
    val reducedMotion = isReducedMotionEnabled(context)

    val cursorMs = remember { mutableStateOf(DT.Calibration.scaleRangeMs / 2f) }
    val hasDragged = remember { mutableStateOf(false) }
    val struck = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(DT.Calibration.toneMatchPeriodMs.toLong())
            // abClick fires once per repetition — Running's primary beat
            // reference, and the ONLY beat reference under Reduced Motion
            // (ui-ux §6.5). lockThunk is never used here — it's reserved
            // for session sync engagement alone.
            haptics.tick(DT.Haptics.abClick)
            struck.value = true
            delay(DT.Calibration.toneMatchStrikeMs.toLong())
            struck.value = false
        }
    }

    CaliperScale(
        samples = emptyList(),
        mode = CaliperMode.Input(
            cursorMs = cursorMs.value,
            onCursorChange = {
                cursorMs.value = it
                hasDragged.value = true
            },
            struck = struck.value,
            reducedMotion = reducedMotion,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(DT.Calibration.detailScaleHeightPt.dp)
            .recessedWell(RoundedCornerShape(DT.Shape.radiusCard)),
    )
    Spacer(Modifier.height(DT.Space.sectionGap))
    SheetPill(
        "That's it",
        primary = true,
        enabled = hasDragged.value,
        onTap = { onCommit(cursorMs.value.roundToInt()) },
    )
}

/** Local pill matching §6.3 (SessionScreen's pills are private to it). */
@Composable
private fun SheetPill(
    label: String,
    primary: Boolean,
    onTap: () -> Unit,
    enabled: Boolean = true,
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
        .padding(horizontal = 28.dp, vertical = 14.dp)
    Text(
        text = label,
        style = BilletType.label,
        color = if (primary) DT.Colors.void else DT.Colors.ink,
        modifier = base,
    )
}

/** Quiet variant (ui-ux §6.3): "Text-only, ink2" — tertiary dismissals/exits. */
@Composable
private fun QuietText(label: String, onTap: () -> Unit) {
    Text(
        text = label,
        style = BilletType.label,
        color = DT.Colors.ink2,
        modifier = Modifier
            .clickable(onClick = onTap)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
