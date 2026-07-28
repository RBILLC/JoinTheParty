package com.jointheparty.app.ui.components

import com.jointheparty.app.data.CalibrationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * CAL-08 copy audit (ui-ux-design-system.md §6.5 "Provenance" / "Device
 * shelf" / "Device detail"): [provenanceLabel]/[provenanceQualifier] are
 * plain functions specifically so this string-diff check can run without
 * Compose (acceptance criteria: "shelf qualifiers... match ui-ux §6.5's
 * copy deck verbatim").
 */
class ProvenanceTest {

    private fun profile(
        method: CalibrationProfile.Method,
        updatedAtMs: Long = 0L,
        drifted: Boolean = false,
    ) = CalibrationProfile(
        routeId = "speaker",
        routeClass = "SPEAKER",
        deviceName = "Living room speaker",
        method = method,
        latencyMs = 204,
        confidence = 1.0f,
        sampleCount = 1,
        acousticallyReachable = true,
        createdAtMs = 0L,
        updatedAtMs = updatedAtMs,
        drifted = drifted,
    )

    @Test
    fun labelsMatchTheEngravedWordsVerbatim() {
        assertEquals("MEASURED", provenanceLabel(CalibrationProfile.Method.MEASURED))
        assertEquals("BY EAR", provenanceLabel(CalibrationProfile.Method.BY_EAR))
        assertEquals("ESTIMATED", provenanceLabel(CalibrationProfile.Method.ESTIMATED))
    }

    @Test
    fun measuredQualifierIsMeasuredRelativeTime() {
        val twoDaysMs = 2 * 86_400_000L
        val nowMs = 10 * 86_400_000L
        val qualifier = provenanceQualifier(
            profile(CalibrationProfile.Method.MEASURED, updatedAtMs = nowMs - twoDaysMs),
            nowMs,
        )
        assertEquals("measured 2 days ago", qualifier)
    }

    @Test
    fun byEarQualifierIsSetByEarRelativeTime() {
        val sixDaysMs = 6 * 86_400_000L
        val nowMs = 10 * 86_400_000L
        val qualifier = provenanceQualifier(
            profile(CalibrationProfile.Method.BY_EAR, updatedAtMs = nowMs - sixDaysMs),
            nowMs,
        )
        assertEquals("set by ear, 6 days ago", qualifier)
    }

    @Test
    fun estimatedQualifierIsNotMeasuredYetRegardlessOfTimestamp() {
        val qualifier = provenanceQualifier(
            profile(CalibrationProfile.Method.ESTIMATED, updatedAtMs = 0L),
            nowMs = 999_999_999L,
        )
        assertEquals("not measured yet", qualifier)
    }

    @Test
    fun driftedQualifierReplacesTheMeasuredTextOnAnyMethod() {
        val nowMs = 10 * 86_400_000L
        val measuredDrifted = profile(CalibrationProfile.Method.MEASURED, updatedAtMs = 0L, drifted = true)
        assertEquals("timing's drifted, worth a redo", provenanceQualifier(measuredDrifted, nowMs))
    }

    @Test
    fun relativeTimeStepsThroughMinutesHoursAndDays() {
        assertEquals("just now", relativeTimeAgo(nowMs = 30_000L, thenMs = 0L))
        assertEquals("1 minute ago", relativeTimeAgo(nowMs = 60_000L, thenMs = 0L))
        assertEquals("5 minutes ago", relativeTimeAgo(nowMs = 5 * 60_000L, thenMs = 0L))
        assertEquals("1 hour ago", relativeTimeAgo(nowMs = 3_600_000L, thenMs = 0L))
        assertEquals("3 hours ago", relativeTimeAgo(nowMs = 3 * 3_600_000L, thenMs = 0L))
        assertEquals("1 day ago", relativeTimeAgo(nowMs = 86_400_000L, thenMs = 0L))
        assertEquals("2 days ago", relativeTimeAgo(nowMs = 2 * 86_400_000L, thenMs = 0L))
    }

    // ---- ui-ux §6.5's empty-state copy (shared constants, asserted here so
    // a future edit to DeviceShelf.kt's literal strings fails a test) -------

    @Test
    fun emptyStateCopyMatchesTheDeckVerbatim() {
        assertEquals(
            "No devices calibrated yet. Play something through a speaker " +
                "or headphones and JoinTheParty will get to know it.",
            DEVICE_SHELF_EMPTY_BODY,
        )
        // CFX-02: relabelled from "Calibrate phone speaker" — this action
        // never switches the active route, so the label must describe what
        // it actually calibrates (tech-req §2.6 "Recalibration targeting").
        assertEquals("Calibrate this device", DEVICE_SHELF_EMPTY_PRIMARY)
    }

    // ---- ui-ux §6.5 Device detail: banner copy verbatim --------------------

    @Test
    fun driftBannerCopyMatchesTheDeckVerbatim() {
        assertEquals(
            "This one's drifted from where we measured it. A quick " +
                "recalibration will tighten it back up.",
            DRIFT_BANNER_BODY,
        )
        assertEquals("Recalibrate", DRIFT_BANNER_PRIMARY)
        assertEquals("Later", DRIFT_BANNER_QUIET)
    }

    @Test
    fun trimPromotionBannerCopyMatchesTheDeckVerbatim() {
        assertEquals("Use this offset", TRIM_PROMOTION_PRIMARY)
        assertEquals("Keep as is", TRIM_PROMOTION_QUIET)
        assertEquals(
            "Folded into the calibration — the wheel's back at zero.",
            TRIM_PROMOTION_CONFIRMATION,
        )
    }

    @Test
    fun detailRecalibrateLabelMatchesTheDeckVerbatim() {
        assertEquals("Calibrate again", DETAIL_RECALIBRATE_LABEL)
    }

    // ---- CFX-02: "Recalibration targeting" copy verbatim -------------------

    @Test
    fun recalibrateDisabledReasonMatchesTheDeckVerbatim() {
        assertEquals(
            "Reconnect this device to recalibrate it",
            DETAIL_RECALIBRATE_DISABLED_REASON,
        )
    }

    // ---- CFX-01: "Route attribution" cancelled-measurement copy verbatim --

    @Test
    fun calibrationCancelledCopyMatchesTheSpecVerbatim() {
        assertEquals(
            "Device changed — calibration cancelled.",
            CALIBRATION_CANCELLED_BODY,
        )
    }

    // ---- CFX-03: "Connected-state encoding" — connected != colour-only ----
    // tech-req §2.6 / ui-ux §6.5 "Settled line": "wherever a brass line
    // appears, the row/pane also carries a plain-text 'Connected' qualifier
    // ... color is a reinforcing signal, not the only one." Verified here as
    // a copy/string-diff check, same convention as the rest of this file.

    @Test
    fun connectedQualifierSuffixMatchesTheDeckVerbatim() {
        assertEquals("· Connected", CONNECTED_QUALIFIER_SUFFIX)
    }

    @Test
    fun measuredQualifierAppendsConnectedSuffixWhenConnectedIsTrue() {
        val twoDaysMs = 2 * 86_400_000L
        val nowMs = 10 * 86_400_000L
        val qualifier = provenanceQualifier(
            profile(CalibrationProfile.Method.MEASURED, updatedAtMs = nowMs - twoDaysMs),
            nowMs,
            connected = true,
        )
        // ui-ux §6.5's own worked example, verbatim.
        assertEquals("measured 2 days ago · Connected", qualifier)
    }

    @Test
    fun qualifierOmitsConnectedSuffixByDefaultAndWhenExplicitlyFalse() {
        val nowMs = 10 * 86_400_000L
        val profile = profile(CalibrationProfile.Method.ESTIMATED, updatedAtMs = 0L)
        assertEquals("not measured yet", provenanceQualifier(profile, nowMs))
        assertEquals("not measured yet", provenanceQualifier(profile, nowMs, connected = false))
    }

    @Test
    fun byEarQualifierAppendsConnectedSuffixWhenConnected() {
        val sixDaysMs = 6 * 86_400_000L
        val nowMs = 10 * 86_400_000L
        val qualifier = provenanceQualifier(
            profile(CalibrationProfile.Method.BY_EAR, updatedAtMs = nowMs - sixDaysMs),
            nowMs,
            connected = true,
        )
        assertEquals("set by ear, 6 days ago · Connected", qualifier)
    }

    @Test
    fun driftedQualifierAlsoAppendsConnectedSuffixWhenConnected() {
        // The drift banner replaces WHAT HAPPENED, not WHETHER this is the
        // connected device right now — §6.5's connection-tell rule carries
        // no drift-state exception.
        val nowMs = 10 * 86_400_000L
        val measuredDrifted = profile(CalibrationProfile.Method.MEASURED, updatedAtMs = 0L, drifted = true)
        assertEquals(
            "timing's drifted, worth a redo · Connected",
            provenanceQualifier(measuredDrifted, nowMs, connected = true),
        )
    }

    // ---- CFX-06 (additional fix): route-neutral Failed-state copy ---------
    // The old "turn the volume up and try again" assumed a speaker; with
    // the gate now route-neutral (CFX-06), headphone users reach this state
    // too, where no volume ever reaches the phone's mic. The rewrite hands
    // off evenhandedly to both recovery paths already offered below it
    // (Try again / Try by ear instead) without asserting which situation
    // the user is in.

    @Test
    fun calibrationFailedCopyIsRouteNeutralAndNamesBothRecoveryPaths() {
        assertEquals(
            "Couldn't hear the chirp. Try again, or set it by ear instead.",
            CALIBRATION_FAILED_BODY,
        )
        // What CFX-06 removes: no speaker-assuming "volume" advice.
        assertFalse(CALIBRATION_FAILED_BODY.contains("volume", ignoreCase = true))
    }
}
