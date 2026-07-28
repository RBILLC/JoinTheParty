package com.jointheparty.app.data

import com.google.gson.Gson
import com.google.gson.JsonParseException

/** Single Gson instance backing [CalibrationProfile.fromJson]/[CalibrationProfile.toJson]. */
private val gson = Gson()

/**
 * CAL-04 (technical-requirements.md §2.6, "Profile record"): the persisted
 * per-route calibration record. One JSON blob per route, held by
 * [com.jointheparty.app.data.NudgeStore] under
 * `stringPreferencesKey("calibration_profile:<routeId>")` — replacing
 * INT-03's flat `outlatency:<routeId>` Int key (left orphaned; see
 * [NudgeStore]'s doc comment for why, same `setpoint2` precedent as the
 * engine-setpoint key).
 *
 * **Why a blob and not more flat Int/Boolean keys.** [NudgeStore]'s
 * existing fields (trim/latency/setpoint2/outlatency) each commit in their
 * OWN `edit{}` call — there is no atomicity across keys. A profile update
 * touches five-plus fields at once (e.g. a fresh MEASURED result stamps
 * `latencyMs`, `confidence`, `acousticallyReachable`, and `updatedAtMs` in
 * the same instant); if those were separate keys, a reader landing between
 * two of the writes would see a self-inconsistent profile — new timestamps
 * paired with a stale `sampleCount`, `confidence` for a `latencyMs` that
 * hasn't landed yet — and that state is indistinguishable from a valid one.
 * One `stringPreferencesKey` holding one JSON blob makes every update a
 * single DataStore write, so DataStore's own transactional `edit{}`
 * guarantees a concurrent reader only ever observes a complete profile or
 * the previous complete profile — never a half-written one.
 *
 * `schemaVersion` mismatches and unparseable blobs both resolve to `null`
 * ("no profile") via [fromJson] — never an exception. There is no
 * migration path between schema versions, by the same "leave it orphaned"
 * precedent: a version bump abandons the old blob exactly like a key
 * rename would.
 */
data class CalibrationProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val routeId: String,
    val routeClass: String,
    val deviceName: String,
    val method: Method,
    val latencyMs: Int,
    val confidence: Float,
    val sampleCount: Int,
    // Cached optimisation only (tech-req §2.6): true once a chirp has ever
    // been detected on this routeId, letting the shell skip referee
    // sampling on headphone routes early. This is NOT the safety mechanism
    // — that's sc_sample_latency_residual's own peak_ratio > 4.0 gate
    // inside SyncCore, which this field never overrides or bypasses.
    val acousticallyReachable: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    // Bounded ring, cap [MAX_REFEREE_SAMPLES]; oldest evicted on overflow.
    val refereeSamples: List<RefereeSample> = emptyList(),
    val drifted: Boolean = false,
) {
    enum class Method { MEASURED, BY_EAR, ESTIMATED }

    data class RefereeSample(val residualMs: Int, val atMs: Long)

    /**
     * Appends one referee-committed residual sample (CAL-04's aggregation
     * rule lives in `SessionViewModel.onLatencyResidual` — this method only
     * applies an already-agreed-upon sample), capping the ring at
     * [MAX_REFEREE_SAMPLES] with the oldest evicted first, bumping
     * [sampleCount], and recomputing [drifted] against the profile's
     * CURRENT [latencyMs].
     *
     * This method never touches [latencyMs] itself. The referee verifies,
     * it never steers (technical-requirements.md §2.6) — the only fields
     * that change here are bookkeeping: the ring, the count, the drift
     * flag, and the update timestamp.
     */
    fun withRefereeSample(residualMs: Int, atMs: Long): CalibrationProfile {
        val ring = (refereeSamples + RefereeSample(residualMs, atMs)).takeLast(MAX_REFEREE_SAMPLES)
        return copy(
            refereeSamples = ring,
            sampleCount = sampleCount + 1,
            drifted = kotlin.math.abs(residualMs - latencyMs) > DRIFT_THRESHOLD_MS,
            updatedAtMs = atMs,
        )
    }

    fun toJson(): String = gson.toJson(this)

    /**
     * Gson's reflective decoder allocates the instance without ever calling
     * the Kotlin constructor, so it bypasses the compiler-inserted
     * null-checks on these "non-null" fields — a corrupt or truncated blob
     * can silently produce an instance whose reference fields are actually
     * null at the JVM level. [fromJson] checks this explicitly rather than
     * let a stray NPE surface far from its cause.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun isWellFormed(): Boolean =
        schemaVersion == SCHEMA_VERSION &&
            routeId != null &&
            routeClass != null &&
            deviceName != null &&
            method != null &&
            refereeSamples != null

    companion object {
        const val SCHEMA_VERSION = 1

        /** Ring cap for [refereeSamples] (technical-requirements.md §2.6). */
        const val MAX_REFEREE_SAMPLES = 20

        /**
         * Referee drift threshold (technical-requirements.md §2.6): a
         * committed residual more than this far from the profile's current
         * [latencyMs] flags [drifted] so the UI (CAL-08) can prompt a redo.
         */
        const val DRIFT_THRESHOLD_MS = 50

        /**
         * Parses a persisted blob. Returns null — never throws — for
         * anything short of a complete, current-schema profile: a null/
         * blank string, garbage JSON, or a `schemaVersion` mismatch. A
         * corrupt blob must read exactly like "no profile," because
         * throwing here would crash the session that next routes through
         * whatever device wrote it.
         */
        fun fromJson(json: String?): CalibrationProfile? {
            if (json.isNullOrBlank()) return null
            val profile = try {
                gson.fromJson(json, CalibrationProfile::class.java)
            } catch (e: JsonParseException) {
                null
            } catch (e: IllegalStateException) {
                // Gson throws this (not JsonParseException) for some
                // malformed-primitive/malformed-array shapes.
                null
            } ?: return null
            return profile.takeIf { it.isWellFormed() }
        }
    }
}
