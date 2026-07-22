package com.jointheparty.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT
import com.jointheparty.app.ui.theme.machinedDepth
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * UI-04: the Nudge Wheel — "the trim dial" (ui-ux-design-system.md §6.2).
 *
 * A horizontal cylinder EDGE, not a face-on knob: a knurled drum embedded in
 * the bottom bezel that rolls 1:1 under the finger. 1 detent = [DT.Wheel]'s
 * `detentTravelPt` of travel = `detentMs` of trim, clamped to ±`rangeMs` with
 * a rubber-band overtravel and a firm end-stop haptic.
 *
 * Ownership: the caller holds the committed [trimMs] (per-route persistence
 * lives above this composable). The wheel keeps its own gesture-local
 * optimistic value so the drum tracks the finger instantly; [onTrimChange]
 * fires per detent crossed, [onTrimCommit] fires once, [DT.Wheel.commitDebounceMs]
 * after the value last changed (§6.2: "committed value debounced 400 ms →
 * one micro-seek").
 *
 * Two-stream rule (SyncMeter precedent, technical-requirements.md §2.3): the
 * drum's drag offset lives in its own [mutableStateOf] and is read ONLY
 * inside the [Canvas] draw lambda below — a draw-phase read, so finger
 * movement invalidates the draw pass alone. The live value text is a small
 * leaf ([NudgeWheelHeader]) that quantizes through [derivedStateOf] before it
 * recomposes, matching [SyncMeter]'s `MsReadout` pattern.
 */
@Composable
fun NudgeWheel(
    trimMs: Int,
    routeName: String?,
    onTrimChange: (Int) -> Unit,
    onTrimCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = remember(context) { BilletHaptics(context) }

    val isDragging = remember { mutableStateOf(false) }
    val liveTrimMs = remember { mutableStateOf(trimMs) }

    // Draw-phase-only state (two-stream rule): the drum's visual roll,
    // including rubber-band overtravel while dragging past the hard stop.
    val dragOffsetPt = remember { mutableStateOf(trimMs * PT_PER_MS) }

    // External changes (initial load, another surface picking a new route)
    // resync the optimistic display — but never mid-gesture, or the drum
    // would jump under the user's finger while they're rolling it.
    LaunchedEffect(trimMs) {
        if (!isDragging.value) {
            liveTrimMs.value = trimMs
            dragOffsetPt.value = trimMs * PT_PER_MS
        }
    }

    // Debounced commit, keyed on the value itself rather than on drag state
    // (snapshotFlow + debounce, per the ticket's intended pattern): a pause
    // mid-gesture and a drag release both read as "settled" identically, and
    // a settled inertial roll commits the same way a settled drag does.
    LaunchedEffect(Unit) {
        snapshotFlow { liveTrimMs.value }
            .debounce(DT.Wheel.commitDebounceMs.toLong())
            .collect { settled -> onTrimCommit(settled) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        NudgeWheelHeader(liveTrimMs = liveTrimMs, routeName = routeName)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .machinedDepth(DrumShape)
                .pointerInput(Unit) {
                    // Double-tap zeroes the trim; the debounce LaunchedEffect
                    // above carries it through the normal commit path.
                    detectTapGestures(
                        onDoubleTap = {
                            liveTrimMs.value = 0
                            dragOffsetPt.value = 0f
                            onTrimChange(0)
                        },
                    )
                }
                .pointerInput(Unit) {
                    val densityValue = density
                    var rawPt = 0f
                    var atStop = false
                    lateinit var velocityTracker: VelocityTracker

                    // detectHorizontalDragGestures loops internally, so one
                    // call here handles every drag over the drum's lifetime.
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging.value = true
                            velocityTracker = VelocityTracker()
                            rawPt = liveTrimMs.value * PT_PER_MS
                            atStop = false
                        },
                        onDragEnd = {
                            isDragging.value = false
                            val settledPt = liveTrimMs.value * PT_PER_MS
                            // Overtravel relaxes back the instant the finger lifts.
                            dragOffsetPt.value = settledPt

                            val velocityPxPerSec = velocityTracker.calculateVelocity().x
                            val velocityPtPerSec = velocityPxPerSec / densityValue
                            val reducedMotion = isReducedMotionEnabled(context)
                            if (!reducedMotion && abs(velocityPtPerSec) > FLING_MIN_PT_PER_SEC) {
                                scope.launch {
                                    rollInertia(
                                        startPt = settledPt,
                                        initialVelocityPtPerSec = velocityPtPerSec,
                                        liveTrimMs = liveTrimMs,
                                        dragOffsetPt = dragOffsetPt,
                                        onTrimChange = onTrimChange,
                                        haptics = haptics,
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            isDragging.value = false
                            dragOffsetPt.value = liveTrimMs.value * PT_PER_MS
                        },
                        onHorizontalDrag = { change, dragAmountPx ->
                            change.consume()
                            velocityTracker.addPointerInputChange(change)

                            val beforePt = rawPt
                            rawPt += dragAmountPx / densityValue

                            val newMs = advanceDetents(beforePt, rawPt, haptics)
                            liveTrimMs.value = newMs
                            onTrimChange(newMs)

                            dragOffsetPt.value = rubberBandPt(rawPt, RANGE_PT)
                            val overshoot = abs(rawPt) > RANGE_PT
                            if (overshoot && !atStop) {
                                haptics.tick(DT.Haptics.endStop)
                                atStop = true
                            } else if (!overshoot) {
                                atStop = false
                            }
                        },
                    )
                }
                .semantics {
                    contentDescription = "Trim dial, ${"%+d".format(liveTrimMs.value)} milliseconds"
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // All state reads live inside this draw lambda (two-stream
                // rule): a drag delta re-executes only this block.
                val offsetPx = dragOffsetPt.value.dp.toPx()
                val spacingPx = STRIATION_SPACING.toPx()
                val w = size.width
                val h = size.height

                // Striations translate 1:1 with the drag; the pattern is
                // periodic, so only the offset modulo the spacing matters.
                val shift = offsetPx.mod(spacingPx)

                // Alpha fades toward the top/bottom edges to suggest the
                // curvature of a drum rather than a flat knurled plate.
                val grooveBrush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.18f to GrooveColor,
                    0.82f to GrooveColor,
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = h,
                )
                val ridgeBrush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.18f to RidgeColor,
                    0.82f to RidgeColor,
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = h,
                )

                var x = -spacingPx + shift
                while (x < w + spacingPx) {
                    drawLine(brush = grooveBrush, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 1f)
                    drawLine(brush = ridgeBrush, start = Offset(x + 1f, 0f), end = Offset(x + 1f, h), strokeWidth = 1f)
                    x += spacingPx
                }

                // brassBright specular line along the drum's top edge (§6.2).
                drawLine(
                    color = DT.Colors.brassBright,
                    start = Offset(0f, 0.5f),
                    end = Offset(w, 0.5f),
                    strokeWidth = 1f,
                )
            }
        }
    }
}

/**
 * Header above the drum: engraved `TRIM` label, the live value, and (when
 * present) the persisted route name — "making per-route persistence visible"
 * (§6.2). A small leaf, like `SyncMeter`'s `MsReadout`: it recomposes on
 * every optimistic value change, but nothing above it does.
 */
@Composable
private fun NudgeWheelHeader(liveTrimMs: State<Int>, routeName: String?) {
    val displayMs by remember { derivedStateOf { liveTrimMs.value } }
    Column(modifier = Modifier.padding(horizontal = DT.Space.gutter)) {
        Text(text = "TRIM", style = BilletType.engraved, color = DT.Colors.ink3)
        Text(
            text = "%+d ms".format(displayMs),
            style = TrimValueStyle,
            color = DT.Colors.ink,
        )
        if (routeName != null) {
            Text(
                text = routeName,
                style = BilletType.fine,
                color = DT.Colors.ink3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Post-release inertial roll (§5 `heavy`, §6.2 "flick for inertial roll with
 * heavy friction"): heavy exponential friction, detent haptics continue to
 * fire per frame, hard-stops (with the end-stop haptic) at the clamp. Not
 * entered at all when reduced motion is on — callers only reach this via the
 * reduced-motion check at the call site.
 */
private suspend fun rollInertia(
    startPt: Float,
    initialVelocityPtPerSec: Float,
    liveTrimMs: MutableState<Int>,
    dragOffsetPt: MutableState<Float>,
    onTrimChange: (Int) -> Unit,
    haptics: BilletHaptics,
) {
    var lastPt = startPt
    var stoppedAtBound = false
    val decaySpec = exponentialDecay<Float>(frictionMultiplier = 3f)

    AnimationState(initialValue = startPt, initialVelocity = initialVelocityPtPerSec)
        .animateDecay(decaySpec) {
            val clamped = value.coerceIn(-RANGE_PT, RANGE_PT)
            if (clamped != value) stoppedAtBound = true

            val newMs = advanceDetents(lastPt, clamped, haptics)
            lastPt = clamped
            liveTrimMs.value = newMs
            dragOffsetPt.value = clamped
            onTrimChange(newMs)

            if (stoppedAtBound) cancelAnimation()
        }

    if (stoppedAtBound) haptics.tick(DT.Haptics.endStop)
}

/**
 * Fires one haptic per detent boundary crossed between [oldPt] and [newPt]
 * (§6.2: "detents click past ... through haptics" — during both drag and
 * inertial roll), and returns the resulting clamped, detent-quantized trim
 * in ms. `oldPt`/`newPt` are raw (unclamped, un-rubber-banded) drag-space
 * points; clamping happens here, once, against the true value.
 */
private fun advanceDetents(oldPt: Float, newPt: Float, haptics: BilletHaptics): Int {
    val range = DT.Wheel.rangeMs
    val oldMs = (oldPt / PT_PER_MS).coerceIn(-range, range)
    val newMs = (newPt / PT_PER_MS).coerceIn(-range, range)
    val oldDetent = quantizeToDetent(oldMs)
    val newDetent = quantizeToDetent(newMs)

    if (oldDetent != newDetent) {
        val step = if (newDetent > oldDetent) DT.Wheel.detentMs else -DT.Wheel.detentMs
        var cur = oldDetent
        var ticksFired = 0
        // Guarded: a single synthetic jump (e.g. a very fast flick frame)
        // could in principle cross dozens of detents; cap the *haptic* count
        // so we never queue a vibration storm. The returned value is exact
        // regardless — only the tick count is capped.
        while (cur != newDetent && ticksFired < MAX_HAPTIC_TICKS_PER_UPDATE) {
            cur += step
            val onCoarseBoundary = abs(cur % DT.Wheel.coarseStepMs) < 0.01f
            haptics.tick(if (onCoarseBoundary) DT.Haptics.wheelCoarse else DT.Haptics.wheelDetent)
            ticksFired++
        }
    }
    return newDetent.roundToInt()
}

/** Nearest multiple of [DT.Wheel.detentMs]. */
private fun quantizeToDetent(ms: Float): Float =
    (ms / DT.Wheel.detentMs).roundToInt() * DT.Wheel.detentMs

/**
 * Drag past the ±[DT.Wheel.rangeMs] hard stop moves the drum at ~30% rate
 * (§6.2's "compressed-spring rubber band") while the reported value stays
 * clamped at the true limit — this only ever affects what's drawn.
 */
private fun rubberBandPt(rawPt: Float, rangePt: Float): Float = when {
    rawPt > rangePt -> rangePt + (rawPt - rangePt) * RUBBER_BAND_RATE
    rawPt < -rangePt -> -rangePt + (rawPt + rangePt) * RUBBER_BAND_RATE
    else -> rawPt
}

/** §5: "Reduced Motion ... wheel inertia off (detent-step only)." */
private fun isReducedMotionEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

// ---- Layout / drawing constants -------------------------------------------

/** Fully-round on controls (§4) — a pill the height of the drum. */
private val DrumShape = RoundedCornerShape(44.dp)

private val STRIATION_SPACING = 6.dp
private val GrooveColor = DT.Colors.void.copy(alpha = 0.55f)
private val RidgeColor = DT.Colors.ink.copy(alpha = 0.05f)

/**
 * 22sp Light tabular for the live trim readout (§6.2). Not a generated
 * [BilletType] token — the spec calls out this exact size for the wheel
 * only — so it mirrors BilletTheme's own construction: `tnum` figures on the
 * platform-sans stand-in for Instrument Sans until UI-01b lands the real
 * variable font.
 */
private val TrimValueStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 22.sp,
    fontWeight = FontWeight.Light,
    fontFeatureSettings = "tnum",
)

// pt of drag travel per ms of trim, and the pt equivalent of the ±750 ms
// range — derived once from DT.Wheel so nothing here hardcodes the ratio.
private val PT_PER_MS = DT.Wheel.detentTravelPt / DT.Wheel.detentMs
private val RANGE_PT = DT.Wheel.rangeMs * PT_PER_MS

/** Below this release speed we don't bother animating a roll; the drum just
 *  stays where the finger left it. An implementation-detail cutoff, not a
 *  design-system token. */
private const val FLING_MIN_PT_PER_SEC = 40f

private const val RUBBER_BAND_RATE = 0.3f
private const val MAX_HAPTIC_TICKS_PER_UPDATE = 24

// ---- Haptics ----------------------------------------------------------------

/**
 * UI-04: minimal haptic driver for the wheel (ui-ux-design-system.md §5).
 *
 * Android has no direct analogue to Core Haptics' intensity/sharpness pair.
 * This maps a [DT.HapticToken] onto the closest primitive — PRIMITIVE_TICK
 * for the sharp/light events (detent ticks), PRIMITIVE_CLICK for the duller,
 * heavier ones (coarse boundary, end stop) — via composition on API 30+
 * devices that support it. Below that, or where primitives aren't
 * supported, it falls back to an amplitude-scaled one-shot buzz: coarser,
 * but it still communicates "something happened here."
 */
private class BilletHaptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val supportsPrimitives: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) == true

    /** No haptic spam: callers only invoke this on an actual boundary crossing. */
    fun tick(token: DT.HapticToken) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val intensity = token.intensity.coerceIn(0f, 1f)

        if (supportsPrimitives) {
            val primitive = if (token.sharpness >= 0.5f) {
                VibrationEffect.Composition.PRIMITIVE_TICK
            } else {
                VibrationEffect.Composition.PRIMITIVE_CLICK
            }
            val composed = VibrationEffect.startComposition()
                .addPrimitive(primitive, intensity)
                .compose()
            v.vibrate(composed)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = (intensity * 255).roundToInt().coerceIn(1, 255)
            v.vibrate(VibrationEffect.createOneShot(FALLBACK_TICK_MS, amplitude))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(FALLBACK_TICK_MS)
        }
    }

    private companion object {
        const val FALLBACK_TICK_MS = 12L
    }
}

// ---- Previews ---------------------------------------------------------------

@Preview(name = "Zero trim, no route", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun NudgeWheelPreviewZero() {
    BilletTheme {
        NudgeWheel(
            trimMs = 0,
            routeName = null,
            onTrimChange = {},
            onTrimCommit = {},
        )
    }
}

@Preview(name = "-180 ms, AirPods Pro", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun NudgeWheelPreviewTrimmed() {
    BilletTheme {
        NudgeWheel(
            trimMs = -180,
            routeName = "AirPods Pro",
            onTrimChange = {},
            onTrimCommit = {},
        )
    }
}
