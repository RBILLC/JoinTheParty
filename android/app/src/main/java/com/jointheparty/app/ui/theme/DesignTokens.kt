// GENERATED from design/tokens.json — do not edit. Run tools/design-tokens/generate.py.
// System: Billet v1

package com.jointheparty.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object DT {
    object Colors {
        val void = Color(0xFF131110)
        val billet = Color(0xFF1D1A17)
        val recess = Color(0xFF0C0B0A)
        val hairline = Color(0xFF332F2A)
        val ink = Color(0xFFEDE7DE)
        val ink2 = Color(0xFFA79E93)
        val ink3 = Color(0xFF6B645B)
        val graphite = Color(0xFF4A463F)
        val bronze = Color(0xFF8A6F4B)
        val brass = Color(0xFFC79A63)
        val brassBright = Color(0xFFEAD3A6)
        val oxide = Color(0xFFB4574E)
        val spotify = Color(0xFF1DB954)
    }

    const val FONT_FAMILY = "Instrument Sans"

    data class TextToken(
        val sizeSp: Float,
        val weight: Int,
        val trackingPct: Float,
        val lineHeight: Float,
        val tabular: Boolean,
        val uppercase: Boolean,
    )
    object Type {
        val heroMs = TextToken(76f, 300, -2f, 1.2f, true, false)
        val heroUnit = TextToken(17f, 500, 0f, 1.2f, false, false)
        val title = TextToken(28f, 500, -1f, 1.2f, false, false)
        val subtitle = TextToken(16f, 400, 0f, 1.2f, false, false)
        val body = TextToken(15f, 400, 0f, 1.5f, false, false)
        val label = TextToken(13f, 500, 0f, 1.2f, false, false)
        val engraved = TextToken(10f, 600, 14f, 1.2f, false, true)
        val fine = TextToken(11f, 400, 0f, 1.2f, false, false)
    }

    object Space {
        val grid = 8.dp
        val gutter = 24.dp
        val sectionGap = 40.dp
    }
    object Shape {
        val radiusCard = 24.dp
    }

    object Motion {
        const val settleOmega = 14.0f
        const val heavyMass = 1.4f
        const val heatDurationMs = 900.0f
        const val reducedMotionCrossfadeMs = 200.0f
    }

    data class HapticToken(val intensity: Float, val sharpness: Float)
    object Haptics {
        val wheelDetent = HapticToken(0.75f, 0.9f)
        val wheelCoarse = HapticToken(0.95f, 0.7f)
        val lockThunk = HapticToken(1.0f, 0.15f)
        val lockLost = HapticToken(0.5f, 0.4f)
        val abClick = HapticToken(0.8f, 1.0f)
        val endStop = HapticToken(1.0f, 0.6f)
    }

    object Wheel {
        const val detentMs = 5.0f
        const val detentTravelPt = 9.0f
        const val coarseStepMs = 50.0f
        const val rangeMs = 1500.0f
        const val commitDebounceMs = 400.0f
    }
    object Meter {
        const val maxHz = 15.0f
        const val deadbandMs = 25.0f
        const val logMapRefMs = 250.0f
    }
    object Calibration {
        const val scaleRangeMs = 600.0f
        const val tickStrokeWidthPt = 1.0f
        const val tickAlpha = 0.35f
        const val settledLineStrokeWidthPt = 2.0f
        const val maxRetainedSamples = 20.0f
        const val shelfStripHeightPt = 20.0f
        const val detailScaleHeightPt = 72.0f
        const val toneMatchPeriodMs = 1200.0f
        const val toneMatchStrikeMs = 100.0f
        const val byEarAccuracyMs = 30.0f
        const val trimPromotionSampleCount = 3.0f
        const val trimPromotionToleranceMs = 25.0f
        const val driftMinSamples = 3.0f
        const val driftThresholdMs = 50.0f
    }
}
