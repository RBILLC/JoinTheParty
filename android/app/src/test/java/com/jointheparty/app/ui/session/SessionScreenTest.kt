package com.jointheparty.app.ui.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CFX-04 (technical-requirements.md §2.6 "Sheet lifetime & precedence" /
 * ui-ux §6.5 "Sheet lifetime") and CFX-05 (tech-req §2.6 "Entry points"):
 * JVM coverage for [shouldShowCalibrationSheet] and [openDeviceShelfAction]
 * — the two pieces of `SessionScreen.kt`'s sheet-visibility/entry-point
 * wiring that were extracted to plain functions so they're testable without
 * composing anything, same convention as
 * [shouldOpenGuidedCalibrationPaneAfterRecalibrateRequest] (CFX-02).
 *
 * What this file CANNOT cover, and why: whether the actual `ModalBottomSheet`
 * never visually double-renders during a live phase transition (no
 * overlay-flicker artifact) requires a real composition — this project has
 * no `androidx.compose.ui:ui-test*`/Robolectric (see `CaliperScaleTest.kt`'s
 * doc comment for the same constraint), so that half of CFX-04's own
 * acceptance criteria is explicitly a "needs a device pass" item, not a JVM
 * test.
 */
class SessionScreenTest {

    // ---- CFX-04: sheet visibility -------------------------------------------

    @Test
    fun sheetIsVisibleWhenRequestedDuringAnActivePhaseWithNoGate() {
        assertTrue(shouldShowCalibrationSheet(true, SessionPhase.LOCKED, firstContactGate = null))
        assertTrue(shouldShowCalibrationSheet(true, SessionPhase.AIMING, firstContactGate = null))
        assertTrue(shouldShowCalibrationSheet(true, SessionPhase.CONVERGING, firstContactGate = null))
        assertTrue(shouldShowCalibrationSheet(true, SessionPhase.DRIFTING, firstContactGate = null))
    }

    @Test
    fun sheetIsNeverVisibleWhenNotRequested() {
        assertFalse(shouldShowCalibrationSheet(false, SessionPhase.LOCKED, firstContactGate = null))
    }

    @Test
    fun sheetClosesWhenThePhaseLeavesActiveForLost() {
        assertFalse(shouldShowCalibrationSheet(true, SessionPhase.LOST, firstContactGate = null))
    }

    @Test
    fun sheetClosesWhenThePhaseLeavesActiveForAConciergeGate() {
        assertFalse(shouldShowCalibrationSheet(true, SessionPhase.NEEDS_SPOTIFY, firstContactGate = null))
        assertFalse(shouldShowCalibrationSheet(true, SessionPhase.NEEDS_PREMIUM, firstContactGate = null))
        assertFalse(shouldShowCalibrationSheet(true, SessionPhase.ERROR, firstContactGate = null))
    }

    @Test
    fun sheetClosesWhenThePhaseIsIdleOrWaiting() {
        assertFalse(shouldShowCalibrationSheet(true, SessionPhase.IDLE, firstContactGate = null))
        assertFalse(shouldShowCalibrationSheet(true, SessionPhase.LISTENING, firstContactGate = null))
        assertFalse(shouldShowCalibrationSheet(true, SessionPhase.MATCHING, firstContactGate = null))
    }

    @Test
    fun sheetIsHiddenWhileTheFirstContactGateIsPending_gateWins() {
        val gate = FirstContactGateState(routeId = "speaker", deviceName = "Living room speaker")

        assertFalse(shouldShowCalibrationSheet(true, SessionPhase.LOCKED, firstContactGate = gate))
    }

    @Test
    fun sheetBecomesVisibleOnceTheGateResolves() {
        val gate = FirstContactGateState(routeId = "speaker", deviceName = "Living room speaker")

        // A tap that "would otherwise open the sheet" landed while the gate
        // was still pending (sheetRequested = true throughout) — the sheet
        // stays hidden until the gate clears (accept/decline → null), at
        // which point the SAME request is honored without needing a second
        // tap.
        assertFalse(shouldShowCalibrationSheet(true, SessionPhase.LOCKED, firstContactGate = gate))
        assertTrue(shouldShowCalibrationSheet(true, SessionPhase.LOCKED, firstContactGate = null))
    }

    // ---- CFX-05: IDLE device-shelf entry point wiring -----------------------

    @Test
    fun openDeviceShelfActionSetsShowDeviceReviewAndInvokesTheCallback() {
        var shown = false
        var callbackFired = false
        val action = openDeviceShelfAction(
            setShowDeviceReview = { shown = it },
            onOpenDeviceShelf = { callbackFired = true },
        )

        action()

        assertTrue(shown)
        assertTrue(callbackFired)
    }
}
