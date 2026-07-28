package com.jointheparty.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CFX-06 copy audit (ui-ux-design-system.md §6.5 "First-contact gate",
 * "Corrected to a single, route-neutral variant"): one copy set, for every
 * route — pinned verbatim, same string-diff convention as [ProvenanceTest].
 * Formerly two variants (ACOUSTIC/HEADPHONE branched on route class); this
 * file's own assertions ARE the "the gate's copy is identical regardless of
 * the connected route's class" audit CFX-06 calls for — there is exactly
 * one constant set left to assert against.
 */
class FirstContactGateTest {

    @Test
    fun titleIsTemplatedWithTheDeviceNameVerbatim() {
        assertEquals("New here: Living room speaker", firstContactGateTitle("Living room speaker"))
    }

    @Test
    fun bodyAndPrimaryMatchTheDeckVerbatim() {
        assertEquals(
            "A quick calibration keeps everyone in sync. Takes about ten seconds.",
            GATE_BODY,
        )
        assertEquals("Calibrate now", GATE_PRIMARY)
    }

    @Test
    fun quietAndFineCaptionMatchTheDeckVerbatim() {
        assertEquals("Not now", GATE_QUIET)
        assertEquals("We'll use a generic default until you do.", GATE_FINE_CAPTION)
    }
}
