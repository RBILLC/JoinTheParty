package com.jointheparty.app.spotify.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
private const val SCOPES = "app-remote-control user-read-playback-state user-modify-playback-state"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000
private const val REFRESH_THRESHOLD_MS = 5 * 60 * 1000L
private const val PKCE_PREFS_FILE_NAME = "spotify_auth_pkce_prefs"
private const val KEY_PENDING_VERIFIER = "pending_verifier"

/**
 * AUTH-02: Spotify OAuth 2.0 Authorization Code + PKCE
 * (technical-requirements.md §3.1, steps 1–5). Owns the whole user-token
 * lifecycle — launching the authorize page, exchanging the redirect for
 * tokens, and refreshing ahead of expiry. Does *not* own App Remote connect
 * (§3.1 step 6); that's a separate concern layered on top of
 * [validAccessToken].
 *
 * Usage: an Activity/ViewModel calls [beginAuth] to kick off the flow, then
 * collects [PendingCallback.redirect] (fed by [AuthCallbackActivity], which
 * owns no logic of its own) and forwards each emission to [handleCallback].
 * Everywhere else in the app that needs an API token calls
 * [validAccessToken].
 */
class SpotifyAuthManager(
    context: Context,
    private val tokenStore: TokenStore,
    // TODO(AUTH-02b: inject real client id): wire to BuildConfig once the
    // Spotify Developer Dashboard app registration lands. A constructor
    // parameter keeps this class buildable/testable ahead of that.
    private val clientId: String,
    private val redirectUri: String = "jointheparty://callback",
) {
    private val appContext = context.applicationContext

    private val pkcePrefs: SharedPreferences by lazy {
        createEncryptedPrefs(appContext, PKCE_PREFS_FILE_NAME)
    }

    /**
     * Generates a fresh PKCE verifier/challenge pair and launches the
     * Spotify authorize page in a Custom Tab (technical-requirements.md
     * §3.1 steps 1–2).
     */
    fun beginAuth() {
        val verifier = generateCodeVerifier()
        // Written synchronously, before the Custom Tab takes focus: the OS
        // can kill this process the moment it's backgrounded, and process
        // death between launch and callback is routine here — not an edge
        // case — so the verifier must already be durable before control
        // passes to the browser.
        pkcePrefs.edit().putString(KEY_PENDING_VERIFIER, verifier).commit()

        val challenge = codeChallengeS256(verifier)
        val authorizeUri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .build()

        val customTabsIntent = CustomTabsIntent.Builder().build()
        // appContext may not be an Activity context; starting an activity
        // from a non-Activity context requires this flag.
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(appContext, authorizeUri)
    }

    /**
     * Exchanges the `jointheparty://callback` redirect for tokens
     * (technical-requirements.md §3.1 step 3) and persists them via
     * [TokenStore]. Always clears the pending verifier, win or lose — the
     * flow it belonged to is over either way.
     */
    suspend fun handleCallback(redirect: Uri): AuthResult = withContext(Dispatchers.IO) {
        try {
            val error = redirect.getQueryParameter("error")
            if (error != null) {
                return@withContext if (error == "access_denied") {
                    AuthResult.UserDeclined
                } else {
                    AuthResult.Failure(error)
                }
            }

            val code = redirect.getQueryParameter("code")
                ?: return@withContext AuthResult.Failure("missing_code")
            val verifier = pendingVerifier()
                ?: return@withContext AuthResult.Failure("missing_verifier")

            val response = postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                ),
            )
            if (response.status !in 200..299) {
                return@withContext AuthResult.Failure(tokenErrorReason(response.body))
            }

            val json = JSONObject(response.body)
            tokenStore.save(
                SpotifyTokens(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.getString("refresh_token"),
                    expiresAtEpochMs = System.currentTimeMillis() + json.getInt("expires_in") * 1000L,
                ),
            )
            AuthResult.Success
        } catch (e: IOException) {
            AuthResult.Failure("network_error")
        } catch (e: JSONException) {
            AuthResult.Failure("malformed_token_response")
        } finally {
            clearPendingVerifier()
        }
    }

    /**
     * The current access token, refreshing first if fewer than
     * [REFRESH_THRESHOLD_MS] (5 min) remain on it
     * (technical-requirements.md §3.1 step 5). Returns null when there's no
     * stored session, or when refresh failed in a way that requires the
     * user to re-authenticate.
     */
    suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = tokenStore.tokens() ?: return@withContext null
        val remainingMs = stored.expiresAtEpochMs - System.currentTimeMillis()
        if (remainingMs >= REFRESH_THRESHOLD_MS) return@withContext stored.accessToken
        refresh(stored)
    }

    private suspend fun refresh(stored: SpotifyTokens): String? {
        val response = try {
            postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to stored.refreshToken,
                    "client_id" to clientId,
                ),
            )
        } catch (e: IOException) {
            // Network failure, not a rejection: keep serving the stale
            // token until it's actually expired rather than forcing a
            // re-auth over a flaky connection.
            return staleOrNull(stored)
        }

        if (response.status == 400 || response.status == 401) {
            // Spotify rejected the refresh token outright (revoked/
            // invalid) — retrying won't help; the user must re-auth.
            tokenStore.clear()
            return null
        }
        if (response.status !in 200..299) {
            // Other transient server-side failure: same fallback as a
            // network error.
            return staleOrNull(stored)
        }

        return try {
            val json = JSONObject(response.body)
            // PKCE refresh responses ROTATE the refresh_token, but Spotify
            // doesn't always include a new one in the response body — both
            // behaviors are observed in the wild. Overwrite when present,
            // keep the previous one when absent (technical-requirements.md
            // §3.1 step 5).
            val rotatedRefreshToken = if (json.has("refresh_token")) {
                json.getString("refresh_token")
            } else {
                stored.refreshToken
            }
            val updated = SpotifyTokens(
                accessToken = json.getString("access_token"),
                refreshToken = rotatedRefreshToken,
                expiresAtEpochMs = System.currentTimeMillis() + json.getInt("expires_in") * 1000L,
            )
            tokenStore.save(updated)
            updated.accessToken
        } catch (e: JSONException) {
            staleOrNull(stored)
        }
    }

    private fun staleOrNull(stored: SpotifyTokens): String? =
        if (System.currentTimeMillis() < stored.expiresAtEpochMs) stored.accessToken else null

    private suspend fun pendingVerifier(): String? = withContext(Dispatchers.IO) {
        pkcePrefs.getString(KEY_PENDING_VERIFIER, null)
    }

    private suspend fun clearPendingVerifier(): Unit = withContext(Dispatchers.IO) {
        pkcePrefs.edit().remove(KEY_PENDING_VERIFIER).apply()
    }

    private fun tokenErrorReason(body: String): String = try {
        JSONObject(body).optString("error", "token_endpoint_error")
    } catch (e: JSONException) {
        "token_endpoint_error"
    }

    private fun postForm(urlString: String, params: Map<String, String>): HttpFormResponse {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = params.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""
            HttpFormResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpFormResponse(val status: Int, val body: String)

    /** Outcome of [handleCallback]. */
    sealed interface AuthResult {
        data object Success : AuthResult
        data object UserDeclined : AuthResult
        data class Failure(val reason: String) : AuthResult
    }

    /**
     * Bridges [AuthCallbackActivity] (which owns no business logic of its
     * own) to whichever app-layer component is driving the auth flow: that
     * component should collect [redirect], call
     * [SpotifyAuthManager.handleCallback] with the emitted [Uri], then
     * [consume] it so a later recomposition or config change doesn't
     * replay the same callback.
     */
    companion object PendingCallback {
        private val _redirect = MutableStateFlow<Uri?>(null)
        val redirect: StateFlow<Uri?> = _redirect.asStateFlow()

        /** Called by [AuthCallbackActivity] when the deep link arrives. */
        fun deliver(uri: Uri) {
            _redirect.value = uri
        }

        /** Clears the pending redirect once handled. */
        fun consume() {
            _redirect.value = null
        }
    }
}
