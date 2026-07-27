package com.jointheparty.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

    // INT-03: chirp-calibrated output-chain latency per route (arch §6.4).
    // Distinct from the *command* latency above: output latency is how long
    // sound takes to become audible on this route (DAC/BT buffering) and
    // feeds sc_set_output_route's prior; command latency is Spotify's
    // seek-in-flight time and seeds sc_create. -1 = never calibrated.
    suspend fun outputLatencyFor(routeId: String): Int
    suspend fun saveOutputLatency(routeId: String, ms: Int)
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

    override suspend fun outputLatencyFor(routeId: String): Int =
        context.nudgeDataStore.data.map { it[outputLatencyKey(routeId)] ?: -1 }.first()

    override suspend fun saveOutputLatency(routeId: String, ms: Int) {
        context.nudgeDataStore.edit { it[outputLatencyKey(routeId)] = ms }
    }

    private fun trimKey(routeId: String) = intPreferencesKey("trim:$routeId")
    private fun latencyKey(routeId: String) = intPreferencesKey("latency:$routeId")
    private fun outputLatencyKey(routeId: String) = intPreferencesKey("outlatency:$routeId")
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
    }
}
