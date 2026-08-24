package com.jointheparty.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CTL-06/W8 (wayfinder #41): [ACRCloudProvider.formatAcrTimeLine] surfaces
 * the raw ACRCloud timing/skew fields the app otherwise ignores, so a
 * control run can judge the play_offset_ms/sample-end pairing and the
 * frequency_skew/time_skew field-name question. Purely observational —
 * these tests only pin down the line's format, not any change to
 * [RecognitionProvider.RecognitionFixResult].
 *
 * [ACRCloudProvider.acrTimeLine] (the JSONObject-reading wrapper actually
 * called from the match path) delegates its formatting to
 * [ACRCloudProvider.formatAcrTimeLine] and is exercised here instead of the
 * wrapper: org.json.JSONObject isn't usable under this module's plain JVM
 * `testDebugUnitTest` suite — there's no Robolectric dependency, and
 * android.jar's org.json methods throw "not mocked" (confirmed: adding a
 * JSONObject-driven variant of these tests fails every case at the first
 * `optLong` call with exactly that RuntimeException). The wrapper itself is
 * a thin, one-line-per-field extraction with nothing left to verify once
 * the formatting it delegates to is covered.
 */
class ACRCloudProviderTest {

    @Test
    fun formatAcrTimeLineReportsEveryFieldWhenTheResponseCarriedThem() {
        // ACRCloud's own docs example (audit 2026-07-22): play_offset_ms
        // (9040) pairs with the sample window's END (9280), not its begin.
        val line = ACRCloudProvider.formatAcrTimeLine(
            offsetMs = 9040L,
            sampleBeginMs = 1000L,
            sampleEndMs = 9280L,
            dbBeginMs = 20000L,
            dbEndMs = 28240L,
            timeSkew = "0.998",
            frequencySkew = "1.0021",
            durationMs = 210000L,
        )

        assertEquals(
            "acrtime: off=9040 sBeg=1000 sEnd=9280 dbBeg=20000 dbEnd=28240 " +
                "tskew=0.998 fskew=1.0021 dur=210000",
            line,
        )
    }

    @Test
    fun formatAcrTimeLinePrintsMinusOneForMissingNumericFieldsAndAbsentForMissingSkewKeys() {
        // acrTimeLine's contract: a missing numeric field is defaulted to
        // -1L (org.json's optLong default) before reaching the formatter;
        // a missing skew key is rendered as the literal "absent" string.
        val line = ACRCloudProvider.formatAcrTimeLine(
            offsetMs = 9040L,
            sampleBeginMs = -1L,
            sampleEndMs = -1L,
            dbBeginMs = -1L,
            dbEndMs = -1L,
            timeSkew = "absent",
            frequencySkew = "absent",
            durationMs = -1L,
        )

        assertEquals(
            "acrtime: off=9040 sBeg=-1 sEnd=-1 dbBeg=-1 dbEnd=-1 " +
                "tskew=absent fskew=absent dur=-1",
            line,
        )
    }

    @Test
    fun formatAcrTimeLinePrintsAPresentSkewValueEvenWhenItIsZero() {
        // Root-cause candidate from the audit: every field fix has logged
        // skew=0.0 — this pins down that a present-but-zero raw value must
        // still print, never fall through to "absent".
        val line = ACRCloudProvider.formatAcrTimeLine(
            offsetMs = 9040L,
            sampleBeginMs = -1L,
            sampleEndMs = -1L,
            dbBeginMs = -1L,
            dbEndMs = -1L,
            timeSkew = "0.0",
            frequencySkew = "0.0",
            durationMs = -1L,
        )

        assertEquals(
            "acrtime: off=9040 sBeg=-1 sEnd=-1 dbBeg=-1 dbEnd=-1 " +
                "tskew=0.0 fskew=0.0 dur=-1",
            line,
        )
    }

    @Test
    fun formatAcrTimeLineUsesTheOffsetPassedInVerbatim() {
        // The MATCH ✓ line and the acrtime line must always pair on the
        // same `off` value — this just pins down that formatAcrTimeLine
        // never recomputes or overrides the offset it's given.
        val line = ACRCloudProvider.formatAcrTimeLine(
            offsetMs = 12_345L,
            sampleBeginMs = -1L,
            sampleEndMs = -1L,
            dbBeginMs = -1L,
            dbEndMs = -1L,
            timeSkew = "absent",
            frequencySkew = "absent",
            durationMs = -1L,
        )

        assertEquals("off=12345", line.substringAfter("acrtime: ").substringBefore(" sBeg"))
    }
}
