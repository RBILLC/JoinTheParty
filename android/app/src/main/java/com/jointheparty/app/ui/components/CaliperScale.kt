package com.jointheparty.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT
import kotlin.math.roundToInt

/**
 * CAL-07/CAL-08: the caliper scale — ui-ux-design-system.md §6.5's signature
 * element, "a precision instrument you happen to hold." One composable, two
 * modes ([CaliperMode.ReadOut] / [CaliperMode.Input]): "the display *is* the
 * control." Built once, shared unchanged by both tickets — CAL-07 (tone-match
 * input) and CAL-08's device shelf/detail (read-out), so it deliberately
 * carries no by-ear- or shelf-specific concept beyond what §6.5 assigns to
 * the caliper itself.
 *
 * Stateless: the caller owns retained samples, the settled/committed value,
 * and — in Input mode — the live drag value. This composable only draws and
 * reports drag deltas via [CaliperMode.Input.onCursorChange]; it never
 * persists anything.
 *
 * Canvas + `drawLine` only, no shaders/gradients (ticket constraint). The
 * drag value is read inside the [Canvas] draw lambda only (`SyncMeter`'s
 * two-stream discipline, technical-requirements.md §2.3) — a drag delta
 * invalidates the draw pass alone. [samples] and [mode]'s read-out fields
 * are ordinary composable inputs: they change only on a deliberate event
 * (a new tick recorded, a settled value committed), never per animation
 * frame, so they don't need the same treatment.
 */
sealed interface CaliperMode {
    /**
     * Browse/inspect (device shelf, device detail): a static settled line
     * drawn last, on top of the retained ticks.
     *
     * @param settledValueMs the profile's committed value. `null` renders
     *   neither ticks nor a line — ui-ux §6.5's "no profile at all" case
     *   (the caller shows "Not calibrated" in place of a provenance word).
     * @param connected `true` for the currently-connected device — the line
     *   renders `brass`, the one warm accent. `false` for every other known
     *   device — `ink2`. Exactly one [connected] caliper should be on
     *   screen at a time; enforcing that is the caller's job (§6.5).
     * @param solid `true` when the line is backed by real ticks (Measured,
     *   By ear) — solid stroke. `false` for Estimated — dashed stroke, "the
     *   honest tell that this number was never actually taken."
     */
    data class ReadOut(
        val settledValueMs: Float?,
        val connected: Boolean,
        val solid: Boolean,
    ) : CaliperMode

    /**
     * Tone-match drag input (CAL-07): a draggable cursor stands in for the
     * settled line — "there is no separate handle or thumb graphic: the
     * line you'll end up looking at forever *is* the thing you're dragging
     * now." Tone-match only ever runs on the active/connected route, so
     * unlike [ReadOut] there is no `connected` flag — the cursor is always
     * drawn in the connected device's line color, `brass`.
     *
     * @param cursorMs current dragged value, clamped to
     *   0..[DT.Calibration.scaleRangeMs].
     * @param onCursorChange fired with the new clamped value as the user
     *   drags; the caller is responsible for holding it (e.g. in
     *   `remember { mutableStateOf(...) }`) and feeding it back as
     *   [cursorMs] on recomposition.
     * @param struck `true` during the tone's
     *   [DT.Calibration.toneMatchStrikeMs] registration window — hard-cuts
     *   the cursor to `brassBright`, then hard-cuts back. Not animated: "a
     *   discrete registration mark, not a fade or a glow."
     * @param reducedMotion drops the brightness flash (ui-ux §6.5 Reduced
     *   Motion) and instead draws a static `engraved`-styled mark at the
     *   cursor position for the same [struck] window — the cursor itself
     *   stays resting `brass` throughout. The caller determines this via
     *   [com.jointheparty.app.ui.theme.isReducedMotionEnabled].
     */
    data class Input(
        val cursorMs: Float,
        val onCursorChange: (Float) -> Unit,
        val struck: Boolean = false,
        val reducedMotion: Boolean = false,
    ) : CaliperMode
}

@Composable
fun CaliperScale(
    samples: List<Float>,
    mode: CaliperMode,
    modifier: Modifier = Modifier,
    showAxisLabels: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showAxisLabels) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0", style = BilletType.engraved, color = DT.Colors.ink3)
                Text(
                    DT.Calibration.scaleRangeMs.roundToInt().toString(),
                    style = BilletType.engraved,
                    color = DT.Colors.ink3,
                )
            }
        }

        // Gesture-local optimistic drag state (Input mode only, NudgeWheel's
        // precedent): tracks the finger 1:1 while dragging, resyncs from the
        // caller's committed [CaliperMode.Input.cursorMs] whenever it changes
        // externally and no drag is in flight.
        val isDragging = remember { mutableStateOf(false) }
        val liveCursorMs = remember {
            mutableStateOf((mode as? CaliperMode.Input)?.cursorMs ?: 0f)
        }
        if (mode is CaliperMode.Input) {
            LaunchedEffect(mode.cursorMs) {
                if (!isDragging.value) liveCursorMs.value = mode.cursorMs
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CALIPER_HEIGHT)
                .let { base ->
                    if (mode !is CaliperMode.Input) return@let base
                    base.pointerInput(Unit) {
                        // Captured once, like NudgeWheel's `densityValue` —
                        // PointerInputScope.size is valid as soon as this
                        // block starts (post-layout), and referencing it
                        // from inside the nested onHorizontalDrag callback
                        // directly would rely on implicit-receiver capture
                        // through two lambda layers; a local val is explicit.
                        val widthPx = size.width.toFloat()
                        detectHorizontalDragGestures(
                            onDragStart = { isDragging.value = true },
                            onDragEnd = { isDragging.value = false },
                            onDragCancel = { isDragging.value = false },
                            onHorizontalDrag = { change, dragAmountPx ->
                                change.consume()
                                if (widthPx <= 0f) return@detectHorizontalDragGestures
                                val deltaMs =
                                    (dragAmountPx / widthPx) * DT.Calibration.scaleRangeMs
                                val newMs = (liveCursorMs.value + deltaMs)
                                    .coerceIn(0f, DT.Calibration.scaleRangeMs)
                                liveCursorMs.value = newMs
                                mode.onCursorChange(newMs)
                            },
                        )
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val vInset = TICK_V_INSET.toPx()
            val tickWidthPx = DT.Calibration.tickStrokeWidthPt.dp.toPx()
            val settledWidthPx = DT.Calibration.settledLineStrokeWidthPt.dp.toPx()
            val dashEffect = PathEffect.dashPathEffect(
                floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()),
            )

            fun xFor(ms: Float): Float =
                (ms.coerceIn(0f, DT.Calibration.scaleRangeMs) / DT.Calibration.scaleRangeMs) * w

            // Axis — a hairline spanning the full width, 0 at the left,
            // scaleRangeMs at the right, linear.
            drawLine(
                color = DT.Colors.hairline,
                start = Offset(0f, cy),
                end = Offset(w, cy),
                strokeWidth = 1.dp.toPx(),
            )

            // Retained-sample ticks — no stacking logic: several
            // translucent lines drawn on top of each other, compounding
            // under ordinary alpha blending exactly where samples agree
            // (ui-ux §6.5). One sample naturally reads at plain tickAlpha;
            // "many" naturally darkens toward ink3 where they land on the
            // same column — both are this same loop, no special-casing.
            for (sampleMs in samples) {
                val x = xFor(sampleMs)
                drawLine(
                    color = DT.Colors.ink3,
                    alpha = DT.Calibration.tickAlpha,
                    start = Offset(x, vInset),
                    end = Offset(x, h - vInset),
                    strokeWidth = tickWidthPx,
                )
            }

            when (mode) {
                is CaliperMode.ReadOut -> {
                    val settledMs = mode.settledValueMs
                    if (settledMs != null) {
                        val x = xFor(settledMs)
                        drawLine(
                            color = if (mode.connected) DT.Colors.brass else DT.Colors.ink2,
                            start = Offset(x, vInset),
                            end = Offset(x, h - vInset),
                            strokeWidth = settledWidthPx,
                            pathEffect = if (mode.solid) null else dashEffect,
                        )
                    }
                }
                is CaliperMode.Input -> {
                    val x = xFor(liveCursorMs.value)
                    val flashing = mode.struck && !mode.reducedMotion
                    drawLine(
                        color = if (flashing) DT.Colors.brassBright else DT.Colors.brass,
                        start = Offset(x, vInset),
                        end = Offset(x, h - vInset),
                        strokeWidth = settledWidthPx,
                    )
                    // Reduced Motion: no brightness flash — a static
                    // engraved-style registration mark for the same struck
                    // window instead (ui-ux §6.5 Reduced Motion).
                    if (mode.struck && mode.reducedMotion) {
                        drawLine(
                            color = DT.Colors.ink3,
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = tickWidthPx,
                        )
                    }
                }
            }
        }
    }
}

private val CALIPER_HEIGHT = 48.dp
private val TICK_V_INSET = 2.dp
private val DASH_ON = 6.dp
private val DASH_OFF = 4.dp

// ---- Previews ---------------------------------------------------------------

@Preview(name = "Read-out — Measured, connected, many samples", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun CaliperScaleMeasuredConnectedPreview() {
    BilletTheme {
        CaliperScale(
            samples = listOf(198f, 202f, 204f, 205f, 201f, 203f),
            mode = CaliperMode.ReadOut(settledValueMs = 204f, connected = true, solid = true),
        )
    }
}

@Preview(name = "Read-out — By ear, not connected", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun CaliperScaleByEarNotConnectedPreview() {
    BilletTheme {
        CaliperScale(
            samples = listOf(94f, 96f, 98f),
            mode = CaliperMode.ReadOut(settledValueMs = 96f, connected = false, solid = true),
        )
    }
}

@Preview(name = "Read-out — Estimated, zero ticks, dashed", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun CaliperScaleEstimatedPreview() {
    BilletTheme {
        CaliperScale(
            samples = emptyList(),
            mode = CaliperMode.ReadOut(settledValueMs = 182f, connected = false, solid = false),
        )
    }
}

@Preview(name = "Read-out — no profile at all", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun CaliperScaleNoProfilePreview() {
    BilletTheme {
        CaliperScale(samples = emptyList(), mode = CaliperMode.ReadOut(null, connected = false, solid = false))
    }
}

@Preview(name = "Input — resting brass", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun CaliperScaleInputRestingPreview() {
    BilletTheme {
        CaliperScale(
            samples = emptyList(),
            mode = CaliperMode.Input(cursorMs = 260f, onCursorChange = {}, struck = false),
        )
    }
}

@Preview(name = "Input — struck brassBright", showBackground = true, backgroundColor = 0xFF1D1A17)
@Composable
private fun CaliperScaleInputStruckPreview() {
    BilletTheme {
        CaliperScale(
            samples = emptyList(),
            mode = CaliperMode.Input(cursorMs = 260f, onCursorChange = {}, struck = true),
        )
    }
}
