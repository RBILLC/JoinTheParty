package com.jointheparty.app.ui.components

import com.jointheparty.app.ui.theme.DT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CFX-03 (tech-req §2.6 "CaliperScale accessibility contract"): JVM
 * coverage for the pure pieces of [CaliperScale]'s accessibility semantics —
 * the announcement copy and the step arithmetic behind its
 * `progressBarRangeInfo`/`setProgress` wiring (CaliperScale.kt).
 *
 * What this file CANNOT cover, and why: attaching `Modifier.semantics { }`
 * to a `Canvas` and reading it back requires either an instrumented test
 * (a real device/emulator) or Robolectric's shadow framework — this project
 * has neither (only JVM unit tests; see build.gradle.kts's `dependencies`
 * block: no `androidx.compose.ui:ui-test*`, no `robolectric`). So the
 * semantics ATTACHMENT itself — does the Canvas node really expose
 * `stateDescription`/`progressBarRangeInfo`/`setProgress`, does TalkBack
 * actually announce and act on them — is verified by code inspection here
 * and remains a "needs a device/TalkBack pass" item (backlog-tickets.md
 * CFX-03's own explicit acceptance criterion), not a JVM test.
 */
class CaliperScaleTest {

    // ---- ReadOut announcement copy -----------------------------------------

    @Test
    fun readOutStateDescriptionContainsTheMsValue() {
        assertEquals("204 ms", caliperReadOutStateDescription(204f))
    }

    @Test
    fun readOutStateDescriptionRoundsToWholeMs() {
        assertEquals("183 ms", caliperReadOutStateDescription(182.6f))
    }

    @Test
    fun readOutStateDescriptionIsNotCalibratedWhenNoProfileExists() {
        // ui-ux §6.5 "Zero / one / many samples": "no profile at all... the
        // row/pane reads 'Not calibrated' instead of a provenance word" —
        // the caliper's own read-only announcement matches that wording.
        assertEquals("Not calibrated", caliperReadOutStateDescription(null))
    }

    @Test
    fun readOutContentDescriptionNamesTheControlOnly() {
        // Deliberately excludes device name/provenance — that's the
        // surrounding row's job (ProvenanceLine); duplicating it here would
        // repeat the same words twice in one TalkBack pass over a merged row.
        assertEquals("Calibration scale", CALIPER_READOUT_CONTENT_DESCRIPTION)
        assertTrue(!CALIPER_READOUT_CONTENT_DESCRIPTION.contains("measured", ignoreCase = true))
    }

    // ---- Input announcement copy -------------------------------------------

    @Test
    fun inputStateDescriptionContainsTheLiveMsValue() {
        assertEquals("260 ms", caliperInputStateDescription(260f))
    }

    @Test
    fun inputContentDescriptionIsDistinctFromReadOut() {
        // So a TalkBack user can tell, without looking, whether the node
        // they've landed on is browsing a value or setting one.
        assertTrue(CALIPER_INPUT_CONTENT_DESCRIPTION != CALIPER_READOUT_CONTENT_DESCRIPTION)
    }

    // ---- Adjustment step (tech-req §2.6 / ui-ux §6.5 "Accessibility
    // contract (Input mode)": "a drag-free path... increment/decrement") ----

    @Test
    fun adjustStepIsAnchoredToTheByEarAccuracyFloor() {
        // "Choose a sensible adjustment step" — anchored to the by-ear
        // method's own stated accuracy (DT.Calibration.byEarAccuracyMs, ±30
        // ms): finer promises precision tone-match doesn't have; coarser
        // makes the accessible path worse than the sighted drag path.
        assertEquals(DT.Calibration.byEarAccuracyMs, CALIPER_ADJUST_STEP_MS)
    }

    @Test
    fun snapToStepMovesByExactlyOneStepFromAStepAlignedValue() {
        val current = 300f
        val incrementTarget = current + CALIPER_ADJUST_STEP_MS
        val decrementTarget = current - CALIPER_ADJUST_STEP_MS
        assertEquals(330f, caliperSnapToStep(incrementTarget))
        assertEquals(270f, caliperSnapToStep(decrementTarget))
    }

    @Test
    fun snapToStepRoundsAnUnalignedRawValueOntoTheNearestStep() {
        // Whatever raw float the accessibility framework computes (it isn't
        // obligated to already be step-aligned), the reported target is
        // always a clean multiple of the step.
        assertEquals(210f, caliperSnapToStep(198f))
        assertEquals(210f, caliperSnapToStep(223f))
    }

    @Test
    fun snapToStepClampsAtTheAxisFloorAndCeiling() {
        assertEquals(0f, caliperSnapToStep(-45f))
        assertEquals(0f, caliperSnapToStep(10f))
        assertEquals(DT.Calibration.scaleRangeMs, caliperSnapToStep(DT.Calibration.scaleRangeMs + 45f))
        assertEquals(DT.Calibration.scaleRangeMs, caliperSnapToStep(590f))
    }
}
