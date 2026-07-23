package com.jointheparty.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appPrefsDataStore by preferencesDataStore(name = "app_prefs")

/**
 * UI-06: tiny app-wide preference store — currently just "has onboarding
 * been seen" (ui-ux-design-system.md §6.4: three screens, shown once).
 * Deliberately separate from [NudgeStore]'s `nudge_store` DataStore: this is
 * app-lifecycle state, not per-route sync state.
 *
 * An interface, matching [NudgeStore]'s pattern, so callers can be tested
 * against an in-memory fake without a [Context]/DataStore.
 */
interface AppPrefs {
    suspend fun onboardingSeen(): Boolean
    suspend fun setOnboardingSeen()
}

class DataStoreAppPrefs(private val context: Context) : AppPrefs {

    override suspend fun onboardingSeen(): Boolean =
        context.appPrefsDataStore.data.map { it[ONBOARDING_SEEN_KEY] ?: false }.first()

    override suspend fun setOnboardingSeen() {
        context.appPrefsDataStore.edit { it[ONBOARDING_SEEN_KEY] = true }
    }

    private companion object {
        val ONBOARDING_SEEN_KEY = booleanPreferencesKey("onboarding_seen")
    }
}
