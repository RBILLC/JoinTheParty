package com.jointheparty.app.ui.model

import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.ui.theme.DT

/**
 * The high-frequency meter stream's UI model (technical-requirements.md
 * §2.1): ≤15 Hz, conflated, consumed only by the SyncMeter — never by the
 * session screen root (the two-stream rule).
 */
data class MeterFrame(
    val errorMs: Double,
    val driftPpm: Double,
    val confidence: Float,
    val converged: Boolean,
) {
    /** Fused-line state: converged and currently inside the deadband. */
    val locked: Boolean
        get() = converged && kotlin.math.abs(errorMs) <= DT.Meter.deadbandMs

    companion object {
        /** Pre-first-estimate state: cold, uncertain, centered. */
        val Initial = MeterFrame(0.0, 0.0, 0f, converged = false)
    }
}

fun SyncCore.Event.SyncEstimate.toMeterFrame() = MeterFrame(
    errorMs = errorMs,
    driftPpm = driftPpm,
    confidence = confidence,
    converged = converged,
)
