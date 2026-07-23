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

    override suspend fun outputLatencyFor(routeId: String): Int =
        context.nudgeDataStore.data.map { it[outputLatencyKey(routeId)] ?: -1 }.first()

    override suspend fun saveOutputLatency(routeId: String, ms: Int) {
        context.nudgeDataStore.edit { it[outputLatencyKey(routeId)] = ms }
    }

    private fun trimKey(routeId: String) = intPreferencesKey("trim:$routeId")
    private fun latencyKey(routeId: String) = intPreferencesKey("latency:$routeId")
    private fun outputLatencyKey(routeId: String) = intPreferencesKey("outlatency:$routeId")
}
