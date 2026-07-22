package com.jointheparty.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jointheparty.app.ui.model.MeterFrame
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT
import com.jointheparty.app.ui.theme.heatColor
import com.jointheparty.app.ui.theme.recessedWell
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * UI-03: the Sync Meter — "phase horizon" (ui-ux-design-system.md §6.1).
 *
 * Two horizontal lines in a recessed well: the reference line (the room) and
 * the local line (your playback), whose vertical offset maps the sync error
 * logarithmically. At lock they fuse into a single 2px brassBright line.
 *
 * Two-stream rule (technical-requirements.md §2.3): the ≤15 Hz frame stream
 * is collected HERE, written into snapshot state, and that state is read
 * ONLY (a) inside the Canvas draw lambda — a draw-phase read, so new frames
 * invalidate the draw pass alone, recomposing nothing — and (b) inside two
 * deliberately tiny leaf composables (readout text, a11y description) whose
 * inputs are quantized via derivedStateOf so they recompose only when the
 * *rounded* value or state word actually changes. The session screen root
 * never observes this stream.
 */
@Composable
fun SyncMeter(frames: Flow<MeterFrame>, modifier: Modifier = Modifier) {
    val frame = remember { mutableStateOf(MeterFrame.Initial) }
    // Damped-mass motion (§5, `settle`: critically damped, no overshoot).
    val offsetPt = remember { Animatable(0f) }
    val heat = remember { Animatable(0f) }

    LaunchedEffect(frames) {
        frames.collect { f ->
            frame.value = f
            launch {
                offsetPt.animateTo(
                    targetValue = logMapPt(f.errorMs),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = 200f,  // ≈ ω² for DT.Motion.settleOmega
                    ),
                )
            }
            launch {
                heat.animateTo(
                    targetValue = temperatureOf(f),
                    animationSpec = tween(DT.Motion.heatDurationMs.toInt()),
                )
            }
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .recessedWell(RoundedCornerShape(DT.Shape.radiusCard))
                .meterSemantics(frame),
        ) {
            val density = LocalDensity.current
            Canvas(Modifier.fillMaxSize()) {
                // All state reads live inside this draw lambda: a new frame
                // or animation tick re-executes only this block.
                val f = frame.value
                val cy = size.height / 2f
                val offsetPx = with(density) { offsetPt.value.dp.toPx() }
                val inset = with(density) { 20.dp.toPx() }

                if (f.locked && abs(offsetPx) < 2f) {
                    // Fusion: two waves become one.
                    drawLine(
                        color = DT.Colors.brassBright,
                        start = Offset(inset, cy),
                        end = Offset(size.width - inset, cy),
                        strokeWidth = with(density) { 2.dp.toPx() },
                    )
                } else {
                    // Reference line — the room.
                    drawLine(
                        color = DT.Colors.ink2,
                        start = Offset(inset, cy),
                        end = Offset(size.width - inset, cy),
                        strokeWidth = with(density) { 1.5.dp.toPx() },
                    )
                    // Local line — your playback. Ahead = above. Uncertainty
                    // reads as faintness, never as flashing (§6.1).
                    drawLine(
                        color = heatColor(heat.value),
                        alpha = 0.35f + 0.65f * f.confidence.coerceIn(0f, 1f),
                        start = Offset(inset, cy - offsetPx),
                        end = Offset(size.width - inset, cy - offsetPx),
                        strokeWidth = with(density) { 1.5.dp.toPx() },
                    )
                }
            }
            // Engraved ± scale at the well's right edge (§6.1).
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("+", style = BilletType.engraved, color = DT.Colors.ink3)
                Text("−", style = BilletType.engraved, color = DT.Colors.ink3)
            }
        }

        MsReadout(frame)
    }
}

/** Leaf readout: recomposes only when the rounded ms (or lock state) changes. */
@Composable
private fun MsReadout(frame: State<MeterFrame>) {
    val text by remember {
        derivedStateOf {
            val f = frame.value
            if (f.locked) "in sync" else "%+d".format(f.errorMs.roundToInt())
        }
    }
    val locked by remember { derivedStateOf { frame.value.locked } }
    Row(
        modifier = Modifier.padding(top = DT.Space.grid),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = text,
            style = BilletType.heroMs,
            color = if (locked) DT.Colors.brass else DT.Colors.ink,
        )
        if (!locked) {
            Text(
                text = "ms",
                style = BilletType.heroUnit,
                color = DT.Colors.ink3,
                modifier = Modifier.padding(start = 8.dp, bottom = 14.dp),
            )
        }
    }
}

/** Position is the encoding; the label narrates it (§6.1 accessibility). */
private fun Modifier.meterSemantics(frame: State<MeterFrame>): Modifier =
    semantics {
        val f = frame.value
        contentDescription = when {
            f.locked -> "In sync"
            f.confidence == 0f -> "Listening"
            else -> {
                val magnitude = (abs(f.errorMs) / 10).roundToInt() * 10
                val direction = if (f.errorMs > 0) "ahead" else "behind"
                val state = if (f.converged) "locked" else "converging"
                "$magnitude milliseconds $direction, $state"
            }
        }
    }

/**
 * Log map (§6.1): ±5 ms ≈ ±2pt, ±250 ms ≈ ±36pt, clamped inside the well.
 * y = 20·ln(1 + |e|/48) fits both anchors within half a point.
 */
private fun logMapPt(errorMs: Double): Float {
    val magnitude = 20f * ln(1f + abs(errorMs).toFloat() / 48f)
    return (sign(errorMs).toFloat() * magnitude).coerceIn(-40f, 40f)
}

/** Cold when uncertain, warming with confidence, full brass only at lock. */
private fun temperatureOf(f: MeterFrame): Float =
    if (f.locked) 1f else 0.7f * f.confidence.coerceIn(0f, 1f)

// ---- Previews (UI-03 directive: drifting / converging / locked) ----------

@Composable
private fun PreviewMeter(frame: MeterFrame) {
    BilletTheme {
        Column(Modifier.padding(DT.Space.gutter)) {
            SyncMeter(frames = MutableStateFlow(frame))
        }
    }
}

@Preview(name = "Drifting — cold graphite", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun SyncMeterDrifting() = PreviewMeter(
    MeterFrame(errorMs = -180.0, driftPpm = 120.0, confidence = 0.35f, converged = false),
)

@Preview(name = "Converging — bronze", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun SyncMeterConverging() = PreviewMeter(
    MeterFrame(errorMs = -30.0, driftPpm = 40.0, confidence = 0.8f, converged = false),
)

@Preview(name = "Locked — fused brass", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun SyncMeterLocked() = PreviewMeter(
    MeterFrame(errorMs = 2.0, driftPpm = 10.0, confidence = 0.97f, converged = true),
)
