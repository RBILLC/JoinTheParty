package com.jointheparty.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CAL-09 copy audit (ui-ux-design-system.md §6.5 "First-contact gate"):
 * both device-class variants' title/body/primary/quiet/fine-caption,
 * pinned verbatim — same string-diff convention as [ProvenanceTest].
 */
class FirstContactGateTest {

    @Test
    fun titleIsTemplatedWithTheDeviceNameVerbatim() {
        assertEquals("New here: Living room speaker", firstContactGateTitle("Living room speaker"))
    }

    @Test
    fun acousticCapableCopyMatchesTheDeckVerbatim() {
        assertEquals(
            "A quick calibration keeps everyone in sync on this speaker. Takes about ten seconds.",
            GATE_ACOUSTIC_BODY,
        )
        assertEquals("Calibrate now", GATE_ACOUSTIC_PRIMARY)
    }

    @Test
    fun headphoneClassCopyMatchesTheDeckVerbatim() {
        assertEquals(
            "Headphones can't be heard by the phone's mic — so you're the instrument here. " +
                "We'll play a tone and you match it by ear. Takes about fifteen seconds.",
            GATE_HEADPHONE_BODY,
        )
        assertEquals("Calibrate by ear", GATE_HEADPHONE_PRIMARY)
    }

    @Test
    fun quietAndFineCaptionAreSharedByBothVariants() {
        assertEquals("Not now", GATE_QUIET)
        assertEquals("We'll use a generic default until you do.", GATE_FINE_CAPTION)
    }
}
