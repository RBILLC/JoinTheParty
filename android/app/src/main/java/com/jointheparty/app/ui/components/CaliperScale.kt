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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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
    // The drawn height of the scale itself. The shelf strip
    // (DT.Calibration.shelfStripHeightPt) and the detail well
    // (detailScaleHeightPt) are the same scale at different sizes — a
    // thumbnail, not a crop. Constraining the caller's `modifier` instead
    // would clip the ticks and the settled line off at the bottom rather
    // than drawing them shorter.
    caliperHeight: Dp = CALIPER_HEIGHT,
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
                .height(caliperHeight)
                // CFX-03 (tech-req §2.6 "CaliperScale accessibility
                // contract"; ui-ux §6.5 "Accessibility contract (Input
                // mode)"): the drag gesture below is layered on TOP of this
                // modifier, so it never shadows the semantics node — a
                // screen-reader user reaches the same value/commit path
                // whether or not touch exploration is on.
                .semantics {
                    when (mode) {
                        is CaliperMode.ReadOut -> applyCaliperReadOutSemantics(mode.settledValueMs)
                        is CaliperMode.Input -> applyCaliperInputSemantics(mode)
                    }
                }
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

// ---- Accessibility (CFX-03) --------------------------------------------------
//
// Plain functions/constants, not inlined into the semantics block above, so
// a JVM test can exercise the announcement copy and the step arithmetic
// directly — same convention as Provenance.kt's provenanceLabel/
// provenanceQualifier ("string-diff audit... testable on the JVM without
// Compose"). applyCaliperReadOutSemantics/applyCaliperInputSemantics (below)
// extend that same testability to the semantics ATTACHMENT itself: this
// project has no Robolectric/instrumentation suite (only JVM unit tests),
// but `SemanticsConfiguration` has no Android framework dependency, so
// CaliperScaleTest applies these two functions to a real one and reads back
// contentDescription/stateDescription/progressBarRangeInfo/setProgress
// exactly as CaliperScale's `.semantics { }` block does. What remains
// uncovered — genuinely only observable on a device/emulator with TalkBack
// running — is whether the platform accessibility service actually
// discovers, announces, and drives this node; see the ticket's outstanding
// "needs a device pass" criterion.

/** ReadOut's contentDescription: names the control, nothing else (see the KDoc at its call site for why device name/provenance are deliberately excluded). */
const val CALIPER_READOUT_CONTENT_DESCRIPTION = "Calibration scale"

/** Input's contentDescription — distinct wording so a TalkBack user can tell, by ear, whether the node they've landed on is browsing a value or setting one. */
const val CALIPER_INPUT_CONTENT_DESCRIPTION = "Tone-match calibration scale"

/**
 * ReadOut mode's semantics: value/state description only (CFX-03 acceptance
 * criteria) — deliberately just "what this control is," never the device
 * name or provenance word/qualifier, which are the surrounding row's job
 * (ProvenanceLine, DeviceShelfRow/DeviceDetail); duplicating them here would
 * repeat the same words twice in one TalkBack pass over a merged row.
 *
 * A [SemanticsPropertyReceiver] extension rather than inlined into the
 * `.semantics { }` block at the call site, so a JVM unit test can apply it
 * directly to a real `SemanticsConfiguration()` (which implements this same
 * receiver interface) and read back what a screen reader would actually see
 * — not just the pure announcement-copy functions it delegates to.
 */
internal fun SemanticsPropertyReceiver.applyCaliperReadOutSemantics(settledValueMs: Float?) {
    contentDescription = CALIPER_READOUT_CONTENT_DESCRIPTION
    stateDescription = caliperReadOutStateDescription(settledValueMs)
}

/**
 * Input mode's semantics (CFX-03 acceptance criteria / tech-req §2.6
 * "CaliperScale accessibility contract"): value/state description plus a
 * drag-free commit path. See [applyCaliperReadOutSemantics] for why this is
 * a standalone [SemanticsPropertyReceiver] extension rather than inlined.
 */
internal fun SemanticsPropertyReceiver.applyCaliperInputSemantics(mode: CaliperMode.Input) {
    contentDescription = CALIPER_INPUT_CONTENT_DESCRIPTION
    stateDescription = caliperInputStateDescription(mode.cursorMs)
    // progressBarRangeInfo + setProgress (not a bare contentDescription, per
    // NudgeWheel's read-only precedent) is the platform's actual drag-free
    // path: it's what makes this node "Adjustable" to TalkBack, offering an
    // increment/decrement gesture that calls [setProgress] instead of
    // requiring a drag. `steps` quantizes that gesture to
    // [CALIPER_ADJUST_STEPS] stops — one call, so the system-computed
    // default step can't silently drift from [CALIPER_ADJUST_STEP_MS].
    progressBarRangeInfo = ProgressBarRangeInfo(
        current = mode.cursorMs,
        range = 0f..DT.Calibration.scaleRangeMs,
        steps = CALIPER_ADJUST_STEPS,
    )
    setProgress { targetValue ->
        // Snapped rather than passed through raw: guarantees "exactly one
        // defined step" per adjust gesture (CFX-03 acceptance criteria)
        // regardless of what raw value the accessibility framework computes.
        // mode.onCursorChange is the SAME callback the drag path uses
        // (above) — it's also what CalibrationSheet.kt's ToneMatchCaliper
        // reads to flip `hasDragged` (which enables "That's it"), so this
        // path reaches the exact same commit-enabled state a drag would.
        mode.onCursorChange(caliperSnapToStep(targetValue))
        true
    }
}

/**
 * ReadOut's stateDescription (tech-req §2.6): the settled value in ms, or
 * "Not calibrated" for ui-ux §6.5's "no profile at all" case
 * ([CaliperMode.ReadOut.settledValueMs] `null`) — the same wording the
 * caller uses in place of a provenance word, so a screen-reader user and a
 * sighted user land on the same fact.
 */
fun caliperReadOutStateDescription(settledValueMs: Float?): String =
    if (settledValueMs == null) "Not calibrated" else "${settledValueMs.roundToInt()} ms"

/** Input's stateDescription: the live cursor value, updated on every accepted change (drag or accessibility). */
fun caliperInputStateDescription(cursorMs: Float): String = "${cursorMs.roundToInt()} ms"

/**
 * Accessibility adjustment step (tech-req §2.6 / ui-ux §6.5 "Accessibility
 * contract (Input mode)"): anchored to [DT.Calibration.byEarAccuracyMs] (±30
 * ms) — the by-ear method's own stated accuracy floor. A finer step would
 * promise more precision than tone-match ever claims (Success copy: "Good
 * to about ±30 ms"); a coarser one would make the accessible path less
 * precise than the sighted drag path, which has no such floor.
 */
val CALIPER_ADJUST_STEP_MS: Float = DT.Calibration.byEarAccuracyMs

/**
 * [ProgressBarRangeInfo.steps]: the number of discrete stops BETWEEN the two
 * endpoints (`Slider`'s own convention — a range split into `steps + 1`
 * equal segments). scaleRangeMs (600) / stepMs (30) = 20 segments, so 19
 * interior stops.
 */
private val CALIPER_ADJUST_STEPS: Int =
    (DT.Calibration.scaleRangeMs / CALIPER_ADJUST_STEP_MS).roundToInt() - 1

/**
 * Snaps an accessibility-reported target (e.g. TalkBack's Adjust gesture,
 * via `setProgress`) onto the nearest [CALIPER_ADJUST_STEP_MS] multiple and
 * clamps into the axis range — guarantees "offset by exactly one defined
 * step" regardless of whatever raw float the framework computes, and is the
 * one piece of this contract testable on the JVM as a pure function.
 */
fun caliperSnapToStep(targetMs: Float): Float {
    val stepped = (targetMs / CALIPER_ADJUST_STEP_MS).roundToInt() * CALIPER_ADJUST_STEP_MS
    return stepped.coerceIn(0f, DT.Calibration.scaleRangeMs)
}

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
