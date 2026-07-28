package com.jointheparty.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.nudgeDataStore by preferencesDataStore(name = "nudge_store")

/**
 * UI-02: per-route nudge trim persistence (technical-requirements.md §2.2/
 * §2.3 — "Nudge wheel ... persist to UserDefaults/DataStore keyed by route
 * ID"). Also persists SyncCore's learned command-latency prior per route
 * (PM decision 2026-07-21, recorded on [com.jointheparty.app.core.SyncCore
 * .commandLatencyMs]: that learning should survive cold starts).
 *
 * Route id is a stable string, e.g. "bluetooth:AirPods Pro" or "speaker".
 *
 * An interface — not a concrete DataStore-backed class directly — so
 * `SessionViewModel` can be unit tested against an in-memory fake without a
 * [Context]/DataStore.
 */
interface NudgeStore {
    suspend fun trimFor(routeId: String): Int
    suspend fun saveTrim(routeId: String, trimMs: Int)
    suspend fun commandLatencyFor(routeId: String): Int
    suspend fun saveCommandLatency(routeId: String, ms: Int)

    // Convergence-audit §4.2: the ENGINE setpoint (wheel trim + rebased
    // measurement bias) persisted separately from the wheel's display
    // value, so sessions start already-aligned instead of re-fighting the
    // bias until the first wheel touch. Null = never rebased on this route.
    suspend fun engineSetpointFor(routeId: String): Int?
    suspend fun saveEngineSetpoint(routeId: String, ms: Int)

    // CAL-04 (technical-requirements.md §2.6): the per-route calibration
    // record, superseding INT-03's flat `outlatency:<routeId>` Int key
    // (output-chain latency — how long sound takes to become audible on
    // this route — now lives at [CalibrationProfile.latencyMs] instead).
    // The old key is left orphaned, same precedent as `setpoint2` above: no
    // migration, its bytes are simply never read again.
    //
    // Null means "no profile for this route yet" — deliberately covers
    // both "never calibrated" and "the stored blob didn't parse" the same
    // way, so a corrupt write degrades to the same fallback path a fresh
    // route takes (see [CalibrationProfile.fromJson]).
    suspend fun calibrationProfileFor(routeId: String): CalibrationProfile?
    suspend fun saveCalibrationProfile(profile: CalibrationProfile)

    // CAL-08's device shelf needs "every known device"; no consumer yet in
    // CAL-04, but the store's shape shouldn't need revisiting to add one.
    suspend fun allCalibrationProfiles(): List<CalibrationProfile>
}

class DataStoreNudgeStore(private val context: Context) : NudgeStore {

    override suspend fun trimFor(routeId: String): Int =
        context.nudgeDataStore.data.map { it[trimKey(routeId)] ?: 0 }.first()

    override suspend fun saveTrim(routeId: String, trimMs: Int) {
        context.nudgeDataStore.edit { it[trimKey(routeId)] = trimMs }
    }

    override suspend fun commandLatencyFor(routeId: String): Int =
        context.nudgeDataStore.data.map { it[latencyKey(routeId)] ?: -1 }.first()

    override suspend fun saveCommandLatency(routeId: String, ms: Int) {
        context.nudgeDataStore.edit { it[latencyKey(routeId)] = ms }
    }

    override suspend fun engineSetpointFor(routeId: String): Int? =
        context.nudgeDataStore.data
            .map { it[setpointKey(routeId)]?.coerceIn(-MAX_SETPOINT_MS, MAX_SETPOINT_MS) }
            .first()

    override suspend fun saveEngineSetpoint(routeId: String, ms: Int) {
        context.nudgeDataStore.edit {
            it[setpointKey(routeId)] = ms.coerceIn(-MAX_SETPOINT_MS, MAX_SETPOINT_MS)
        }
    }

    override suspend fun calibrationProfileFor(routeId: String): CalibrationProfile? =
        context.nudgeDataStore.data
            .map { CalibrationProfile.fromJson(it[calibrationProfileKey(routeId)]) }
            .first()

    override suspend fun saveCalibrationProfile(profile: CalibrationProfile) {
        // One `edit{}` call writing one key — the atomic-write guarantee
        // [CalibrationProfile]'s doc comment relies on.
        context.nudgeDataStore.edit { it[calibrationProfileKey(profile.routeId)] = profile.toJson() }
    }

    override suspend fun allCalibrationProfiles(): List<CalibrationProfile> =
        context.nudgeDataStore.data
            .map { prefs ->
                prefs.asMap().entries.mapNotNull { (key, value) ->
                    if (!key.name.startsWith(CALIBRATION_PROFILE_KEY_PREFIX)) return@mapNotNull null
                    CalibrationProfile.fromJson(value as? String)
                }
            }
            .first()

    private fun trimKey(routeId: String) = intPreferencesKey("trim:$routeId")
    private fun latencyKey(routeId: String) = intPreferencesKey("latency:$routeId")
    private fun calibrationProfileKey(routeId: String) =
        stringPreferencesKey("$CALIBRATION_PROFILE_KEY_PREFIX$routeId")
    // "setpoint2": Field Test 4 found a stored value of −2007 ms on the
    // speaker route, written by the wheel-rebase runaway before that bug was
    // fixed. The app restored it every session and dutifully played two
    // seconds behind the room, which looked exactly like a broken sync
    // engine. Old keys are left unread rather than migrated — the values
    // under them are known garbage.
    private fun setpointKey(routeId: String) = intPreferencesKey("setpoint2:$routeId")

    private companion object {
        // An auto-absorbed ear correction beyond the wheel's own range is not
        // a correction, it is a runaway. Clamped on both save and restore so
        // a bad write can never outlive the session that made it.
        const val MAX_SETPOINT_MS = 1500

        const val CALIBRATION_PROFILE_KEY_PREFIX = "calibration_profile:"
    }
}
