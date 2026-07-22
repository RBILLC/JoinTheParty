package com.jointheparty.app.spotify.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AUTH-02: the tokens returned by Spotify's Authorization Code + PKCE flow
 * (technical-requirements.md §3.1 step 3). [expiresAtEpochMs] is the
 * wall-clock deadline computed from the token response's `expires_in` at
 * the moment it was issued/refreshed — see
 * [SpotifyAuthManager.validAccessToken] for the proactive-refresh
 * threshold applied against it.
 *
 * [toString] is overridden to redact both tokens: crash reports, `Log.d`,
 * and test failure messages all eventually route a value through
 * `toString`, and these must never end up in one.
 */
data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
) {
    override fun toString(): String =
        "SpotifyTokens(accessToken=<redacted>, refreshToken=<redacted>, " +
            "expiresAtEpochMs=$expiresAtEpochMs)"
}

/**
 * AUTH-02: persistence for [SpotifyTokens] (technical-requirements.md §3.1
 * step 4 — "Android Keystore-backed EncryptedSharedPreferences").
 *
 * An interface — not [EncryptedTokenStore] directly — so
 * [SpotifyAuthManager] can be unit tested against an in-memory fake without
 * a [Context] or the Keystore, matching the pattern in
 * [com.jointheparty.app.data.NudgeStore].
 */
interface TokenStore {
    suspend fun tokens(): SpotifyTokens?
    suspend fun save(tokens: SpotifyTokens)
    suspend fun clear()
}

/**
 * [TokenStore] backed by [EncryptedSharedPreferences]: AES256_GCM Keystore
 * master key, AES256_SIV key encryption, AES256_GCM value encryption
 * (technical-requirements.md §3.1 step 4). All disk I/O runs on
 * [Dispatchers.IO].
 */
class EncryptedTokenStore(context: Context) : TokenStore {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        createEncryptedPrefs(appContext, PREFS_FILE_NAME)
    }

    override suspend fun tokens(): SpotifyTokens? = withContext(Dispatchers.IO) {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return@withContext null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return@withContext null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, -1L)
        if (expiresAt < 0L) return@withContext null
        SpotifyTokens(accessToken, refreshToken, expiresAt)
    }

    override suspend fun save(tokens: SpotifyTokens): Unit = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochMs)
            .apply()
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "spotify_auth_secure_prefs"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_epoch_ms"
    }
}

/**
 * Shared [EncryptedSharedPreferences] factory (Keystore-backed AES256_GCM
 * master key; AES256_SIV key / AES256_GCM value schemes —
 * technical-requirements.md §3.1 step 4). Used by [EncryptedTokenStore]
 * and by [SpotifyAuthManager] for pending-PKCE-verifier persistence, which
 * needs the same at-rest guarantees but — unlike the tokens themselves —
 * isn't part of the [TokenStore] contract, since it's an implementation
 * detail of the auth *flow* rather than the resulting session.
 */
internal fun createEncryptedPrefs(context: Context, fileName: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
