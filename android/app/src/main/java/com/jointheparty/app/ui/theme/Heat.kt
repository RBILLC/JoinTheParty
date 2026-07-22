package com.jointheparty.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * UI-01: the heat scale (ui-ux-design-system.md §1–2).
 *
 * Sync state is temperature, not color-coding: graphite (cold, searching) →
 * bronze (warming, converging) → brass (hot, locked). `t` ∈ [0, 1].
 */
fun heatColor(t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    return if (clamped <= 0.5f) {
        lerp(DT.Colors.graphite, DT.Colors.bronze, clamped * 2f)
    } else {
        lerp(DT.Colors.bronze, DT.Colors.brass, (clamped - 0.5f) * 2f)
    }
}
