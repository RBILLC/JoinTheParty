package com.jointheparty.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAL-04 (technical-requirements.md §2.6 "Profile record"): gson
 * serialize/deserialize round trip, and the "a corrupt or absent blob is
 * `null`, never a thrown exception" contract that lets [NudgeStore] treat a
 * bad write exactly like "no profile" (see [CalibrationProfile.fromJson]).
 */
class CalibrationProfileTest {

    @Test
    fun profileRoundTripsThroughJsonFieldForField() {
        val profile = CalibrationProfile(
            routeId = "bluetooth:AirPods Pro",
            routeClass = "BLUETOOTH",
            deviceName = "AirPods Pro",
            method = CalibrationProfile.Method.MEASURED,
            latencyMs = 182,
            confidence = 0.9f,
            sampleCount = 3,
            acousticallyReachable = true,
            createdAtMs = 1_000L,
            updatedAtMs = 5_000L,
            refereeSamples = listOf(
                CalibrationProfile.RefereeSample(residualMs = 180, atMs = 2_000L),
                CalibrationProfile.RefereeSample(residualMs = 185, atMs = 4_000L),
            ),
            drifted = false,
        )

        val restored = CalibrationProfile.fromJson(profile.toJson())

        assertEquals(profile, restored)
    }

    @Test
    fun nullBlobReadsAsNullWithoutThrowing() {
        assertNull(CalibrationProfile.fromJson(null))
    }

    @Test
    fun blankBlobReadsAsNullWithoutThrowing() {
        assertNull(CalibrationProfile.fromJson(""))
        assertNull(CalibrationProfile.fromJson("   "))
    }

    @Test
    fun garbageJsonReadsAsNullWithoutThrowing() {
        assertNull(CalibrationProfile.fromJson("not json at all {{{"))
    }

    @Test
    fun schemaVersionMismatchReadsAsNull() {
        // Structurally valid, but schemaVersion=2 — no migration path
        // (setpoint2 precedent); a version bump orphans the blob.
        val json = """
            {"schemaVersion":2,"routeId":"speaker","routeClass":"SPEAKER",
            "deviceName":"speaker","method":"MEASURED","latencyMs":100,
            "confidence":1.0,"sampleCount":1,"acousticallyReachable":true,
            "createdAtMs":1,"updatedAtMs":1,"refereeSamples":[],"drifted":false}
        """.trimIndent()

        assertNull(CalibrationProfile.fromJson(json))
    }

    @Test
    fun truncatedProfileMissingRequiredFieldsReadsAsNull() {
        // Gson's reflective decoder never runs the Kotlin constructor, so
        // this parses "successfully" into an instance whose non-null
        // fields are actually null at the JVM level — isWellFormed() must
        // catch it rather than let that null escape as a routeId/method/etc.
        assertNull(CalibrationProfile.fromJson("""{"schemaVersion":1}"""))
    }

    @Test
    fun withRefereeSampleCapsRingAt20AndEvictsOldest() {
        var profile = CalibrationProfile(
            routeId = "speaker",
            routeClass = "SPEAKER",
            deviceName = "speaker",
            method = CalibrationProfile.Method.MEASURED,
            latencyMs = 200,
            confidence = 1.0f,
            sampleCount = 0,
            acousticallyReachable = true,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

        repeat(21) { i ->
            profile = profile.withRefereeSample(residualMs = 200, atMs = i.toLong())
        }

        assertEquals(CalibrationProfile.MAX_REFEREE_SAMPLES, profile.refereeSamples.size)
        // The 0th append (atMs=0) was evicted; the ring now starts at atMs=1.
        assertEquals(1L, profile.refereeSamples.first().atMs)
        assertEquals(20L, profile.refereeSamples.last().atMs)
        assertEquals(21, profile.sampleCount)
    }

    @Test
    fun withRefereeSampleSetsDriftedBeyondThresholdAndClearsItWithin() {
        val base = CalibrationProfile(
            routeId = "speaker",
            routeClass = "SPEAKER",
            deviceName = "speaker",
            method = CalibrationProfile.Method.MEASURED,
            latencyMs = 200,
            confidence = 1.0f,
            sampleCount = 0,
            acousticallyReachable = true,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

        // Exactly at the 50 ms threshold: not drifted.
        assertFalse(base.withRefereeSample(residualMs = 250, atMs = 1L).drifted)
        // Beyond it: drifted.
        assertTrue(base.withRefereeSample(residualMs = 251, atMs = 1L).drifted)
        assertTrue(base.withRefereeSample(residualMs = 149, atMs = 1L).drifted)
    }
}
