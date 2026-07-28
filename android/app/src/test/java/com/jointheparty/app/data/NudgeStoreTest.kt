package com.jointheparty.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CFX-09 (technical-requirements.md §2.6 "Shelf ordering"): JVM coverage for
 * [sortedByUpdatedAtDescending] — the pure ordering rule
 * [DataStoreNudgeStore.allCalibrationProfiles] applies to every read. Tested
 * as a plain function on `List<CalibrationProfile>` rather than through
 * [DataStoreNudgeStore] itself, which needs a real `Context`/DataStore this
 * project's JVM-only test suite doesn't have (no Robolectric, no
 * instrumentation — see `CaliperScaleTest.kt`'s doc comment for the same
 * constraint) — the extraction is exactly what makes this testable at all,
 * same "extract for testability" convention as
 * [com.jointheparty.app.ui.session.trimPromotionMedian].
 */
class NudgeStoreTest {

    private fun profile(routeId: String, updatedAtMs: Long) = CalibrationProfile(
        routeId = routeId,
        routeClass = "SPEAKER",
        deviceName = routeId,
        method = CalibrationProfile.Method.MEASURED,
        latencyMs = 100,
        confidence = 1.0f,
        sampleCount = 1,
        acousticallyReachable = true,
        createdAtMs = 0L,
        updatedAtMs = updatedAtMs,
    )

    @Test
    fun ordersByUpdatedAtDescending() {
        val profiles = listOf(
            profile("a", updatedAtMs = 1_000L),
            profile("b", updatedAtMs = 3_000L),
            profile("c", updatedAtMs = 2_000L),
        )

        val ordered = profiles.sortedByUpdatedAtDescending()

        assertEquals(listOf("b", "c", "a"), ordered.map { it.routeId })
    }

    @Test
    fun repeatedCallsWithNoInterveningWritesReturnTheSameOrder() {
        // Determinism, not incidental to map/collection iteration.
        val profiles = listOf(
            profile("a", updatedAtMs = 1_000L),
            profile("b", updatedAtMs = 3_000L),
            profile("c", updatedAtMs = 2_000L),
        )

        val first = profiles.sortedByUpdatedAtDescending().map { it.routeId }
        val second = profiles.sortedByUpdatedAtDescending().map { it.routeId }

        assertEquals(first, second)
    }

    @Test
    fun emptyListStaysEmpty() {
        assertEquals(emptyList<CalibrationProfile>(), emptyList<CalibrationProfile>().sortedByUpdatedAtDescending())
    }
}
