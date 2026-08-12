package com.jointheparty.app.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.jointheparty.app.ui.theme.DT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CFX-03 (tech-req §2.6 "CaliperScale accessibility contract"): JVM
 * coverage for [CaliperScale]'s accessibility semantics — both the pure
 * announcement-copy/step-arithmetic pieces AND (below, "Semantics
 * attachment") the actual `SemanticsConfiguration` that
 * [applyCaliperReadOutSemantics]/[applyCaliperInputSemantics] build, applied
 * directly the same way `Modifier.semantics { }` would apply them.
 *
 * How the "Semantics attachment" section works without a device/emulator or
 * Robolectric (this project has neither — see build.gradle.kts's
 * `dependencies` block: no `androidx.compose.ui:ui-test*`, no
 * `robolectric`): `SemanticsConfiguration` (androidx.compose.ui.semantics)
 * is a plain Kotlin class with a public no-arg constructor and no Android
 * framework dependency — it's the same object a real `Canvas` node's
 * semantics eventually populate, just constructed here directly rather than
 * via composition + a `LayoutNode` tree. Applying
 * [applyCaliperReadOutSemantics]/[applyCaliperInputSemantics] to one and
 * reading the properties/actions back exercises the exact same code path
 * `CaliperScale`'s `.semantics { }` block calls, with no test-only
 * duplication of that logic.
 *
 * What this genuinely CANNOT cover, and why: whether TalkBack itself
 * discovers this node, correctly announces `contentDescription`/
 * `stateDescription`, and turns its own increment/decrement gesture into a
 * `setProgress` call — that's the platform accessibility service's
 * behavior, only observable end-to-end on a real device/emulator with
 * TalkBack running. That remains a "needs a device/TalkBack pass" item
 * (issue #27's own explicit acceptance criterion), not a JVM test.
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

    // ---- Semantics attachment (issue #27 ACs 1 & 2) -------------------------
    //
    // Applies applyCaliperReadOutSemantics/applyCaliperInputSemantics to a
    // real SemanticsConfiguration() — the same object CaliperScale's
    // .semantics { } block populates — and reads back exactly what a screen
    // reader would see/invoke. See the class KDoc for why this is possible
    // without Robolectric or an instrumented test.

    // SemanticsConfiguration deliberately forbids reading a property back
    // via the same `receiver.property` syntax used to SET it (that syntax
    // is the builder-only side of the API) — `getOrNull` is the documented
    // readback path (androidx.compose.ui.semantics.SemanticsConfigurationKt).

    @Test
    fun readOutSemanticsExposeAContentDescriptionNamingTheControl() {
        val config = SemanticsConfiguration()
        config.applyCaliperReadOutSemantics(204f)
        assertEquals(
            listOf(CALIPER_READOUT_CONTENT_DESCRIPTION),
            config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }

    @Test
    fun readOutSemanticsExposeAStateDescriptionContainingTheSettledMsValue() {
        val config = SemanticsConfiguration()
        config.applyCaliperReadOutSemantics(204f)
        assertEquals("204 ms", config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun readOutSemanticsStateDescriptionIsNotCalibratedWhenNoProfileExists() {
        val config = SemanticsConfiguration()
        config.applyCaliperReadOutSemantics(null)
        assertEquals("Not calibrated", config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun inputSemanticsExposeADistinctContentDescriptionAndTheLiveStateDescription() {
        val config = SemanticsConfiguration()
        config.applyCaliperInputSemantics(CaliperMode.Input(cursorMs = 260f, onCursorChange = {}))
        assertEquals(
            listOf(CALIPER_INPUT_CONTENT_DESCRIPTION),
            config.getOrNull(SemanticsProperties.ContentDescription),
        )
        assertEquals("260 ms", config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun inputSemanticsExposeAProgressBarRangeInfoSpanningTheFullAxis() {
        val config = SemanticsConfiguration()
        config.applyCaliperInputSemantics(CaliperMode.Input(cursorMs = 260f, onCursorChange = {}))
        val rangeInfo = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertNotNull(rangeInfo)
        assertEquals(260f, rangeInfo!!.current)
        assertEquals(0f, rangeInfo.range.start)
        assertEquals(DT.Calibration.scaleRangeMs, rangeInfo.range.endInclusive)
    }

    @Test
    fun inputSemanticsSetProgressActionInvokesOnCursorChangeOffsetByOneStepIncrementing() {
        // AC: "a CaliperScale in Input mode exposes custom accessibility
        // actions whose invocation calls onCursorChange with cursorMs offset
        // by exactly one defined step (increment)... invokable directly as
        // a semantics-action lambda in a unit test."
        var captured: Float? = null
        val config = SemanticsConfiguration()
        config.applyCaliperInputSemantics(
            CaliperMode.Input(cursorMs = 300f, onCursorChange = { captured = it }),
        )
        val setProgressAction = config.getOrNull(SemanticsActions.SetProgress)?.action
        assertNotNull("setProgress action must be attached to Input mode's semantics", setProgressAction)
        // The framework computes its own raw target from
        // progressBarRangeInfo/steps; this exercises the same shape by
        // offering a target one step above the current value.
        setProgressAction!!.invoke(300f + CALIPER_ADJUST_STEP_MS)
        assertEquals(330f, captured)
    }

    @Test
    fun inputSemanticsSetProgressActionInvokesOnCursorChangeOffsetByOneStepDecrementing() {
        // AC: "...and the inverse (decrement)."
        var captured: Float? = null
        val config = SemanticsConfiguration()
        config.applyCaliperInputSemantics(
            CaliperMode.Input(cursorMs = 300f, onCursorChange = { captured = it }),
        )
        val setProgressAction = config.getOrNull(SemanticsActions.SetProgress)?.action
        assertNotNull("setProgress action must be attached to Input mode's semantics", setProgressAction)
        setProgressAction!!.invoke(300f - CALIPER_ADJUST_STEP_MS)
        assertEquals(270f, captured)
    }

    @Test
    fun inputSemanticsSetProgressActionSnapsAnUnalignedTargetOntoTheNearestStepBeforeCommitting() {
        // Guards against a regression where the semantics action passes the
        // raw accessibility-framework value straight through instead of
        // routing it through caliperSnapToStep.
        var captured: Float? = null
        val config = SemanticsConfiguration()
        config.applyCaliperInputSemantics(
            CaliperMode.Input(cursorMs = 200f, onCursorChange = { captured = it }),
        )
        val setProgressAction = config.getOrNull(SemanticsActions.SetProgress)?.action
        setProgressAction!!.invoke(223f)
        assertEquals(210f, captured)
    }

    @Test
    fun readOutModeDoesNotExposeTheInputOnlySetProgressAction() {
        // ReadOut is read-only (ui-ux §6.5: "ReadOut instances... expose
        // their settled value the same way, read-only") — it must not
        // accidentally offer a commit path that has nothing to commit to.
        val config = SemanticsConfiguration()
        config.applyCaliperReadOutSemantics(204f)
        assertEquals(null, config.getOrNull(SemanticsActions.SetProgress))
    }
}
