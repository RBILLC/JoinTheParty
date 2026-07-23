package com.jointheparty.app.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000

// technical-requirements.md §3.2 step 2: backend vends the token with a 24h
// TTL (Apple allows up to 6 months; we vend short).
private const val SHAZAM_TOKEN_TTL_MS = 24 * 60 * 60 * 1000L

// technical-requirements.md §3.2 step 3: refresh on expiry-1h (or
// InvalidToken, handled by the caller re-fetching after a failed session).
private const val REFRESH_THRESHOLD_MS = 60 * 60 * 1000L

private const val MOCK_PASS_DELAY_MS = 50L

/**
 * AUTH-03/04: the app-facing seam over the backend service
 * (architecture-spec.md §10's `backend/` — "thin service: token vending,
 * ISRC→URI cache; nothing latency-critical"). Two endpoints
 * (technical-requirements.md §3.3):
 *
 * - `POST /v1/tokens/shazam` — vends the ShazamKit Android developer token
 *   (§3.2); consumed by [com.jointheparty.app.recognition.ShazamKitProvider].
 * - `GET /v1/track-map?isrc=` — resolves a Shazam ISRC to a Spotify URI
 *   (§3.1); consumed by `SessionViewModel.runRecognitionPass`.
 *
 * Extracted as an interface, same rationale as `SyncEngine`/
 * `SpotifyController`: lets session/recognition code substitute a fake
 * without touching HTTP.
 */
interface BackendClient {
    suspend fun fetchShazamToken(): ShazamTokenResult
    suspend fun resolveIsrcToSpotifyUri(isrc: String): TrackResolution
}

sealed interface ShazamTokenResult {
    data class Success(val token: String, val expiresAtEpochMs: Long) : ShazamTokenResult
    data class Failure(val reason: String) : ShazamTokenResult
}

sealed interface TrackResolution {
    /**
     * [looseSync]: architecture-spec.md §3's "fall back to title+artist
     * search and flag the session as loose sync" — true when the backend
     * couldn't resolve by ISRC and fell back to a title/artist match
     * (lower initial seek confidence; the iterative correction loop still
     * converges).
     */
    data class Resolved(val spotifyUri: String, val looseSync: Boolean) : TrackResolution
    data object NotFound : TrackResolution
    data class Failure(val reason: String) : TrackResolution
}

/**
 * [BackendClient] implemented against `HttpURLConnection` + `org.json`,
 * mirroring `SpotifyAuthManager`'s plumbing style (10s timeouts, typed
 * error mapping, `Dispatchers.IO`).
 *
 * MOCK MODE: [baseUrl] is null in the current state — no backend is
 * deployed yet (architecture-spec.md §10's `backend/` directory has no
 * service running). With a null [baseUrl], every method short-circuits to
 * a canned success (below the `MOCK constants` marker) after a small
 * `delay(50)` so downstream wiring — [com.jointheparty.app.recognition
 * .ShazamKitProvider], `SessionViewModel`'s recognition pass — can be
 * built, wired, and unit-tested end-to-end today (unblocks INT-02) without
 * waiting on the AUTH-03/04 server work. Swap procedure: once the backend
 * is deployed, construct `HttpBackendClient(realBaseUrl)` — the one call
 * site (`SessionViewModel.Companion.Factory`) is the only place that needs
 * to change; [HttpBackendClient] itself, [ShazamKitProvider], and every
 * other caller are already written against the real HTTP path below.
 */
class HttpBackendClient(private val baseUrl: String?) : BackendClient {

    // In-memory Shazam token cache (technical-requirements.md §3.2 step 3:
    // "App caches in memory + EncryptedSharedPreferences, refreshes on
    // 401/InvalidToken or expiry-1h"). The EncryptedSharedPreferences layer
    // is a follow-up (TODO(AUTH-03b)); in-memory alone already satisfies
    // the "reuse one session per sync session" quota discipline (§3.2) for
    // this process's lifetime, and this client is recreated per process
    // anyway.
    @Volatile
    private var cachedToken: ShazamTokenResult.Success? = null

    override suspend fun fetchShazamToken(): ShazamTokenResult {
        cachedToken?.let { cached ->
            val remainingMs = cached.expiresAtEpochMs - System.currentTimeMillis()
            if (remainingMs >= REFRESH_THRESHOLD_MS) return cached
        }

        val result = if (baseUrl == null) mockFetchShazamToken() else httpFetchShazamToken()
        if (result is ShazamTokenResult.Success) cachedToken = result
        return result
    }

    override suspend fun resolveIsrcToSpotifyUri(isrc: String): TrackResolution =
        if (baseUrl == null) mockResolveIsrc(isrc) else httpResolveIsrc(isrc)

    // ---- Mock path (no backend deployed yet) -------------------------------

    private suspend fun mockFetchShazamToken(): ShazamTokenResult {
        delay(MOCK_PASS_DELAY_MS)
        return ShazamTokenResult.Success(
            token = MOCK_SHAZAM_TOKEN,
            expiresAtEpochMs = System.currentTimeMillis() + SHAZAM_TOKEN_TTL_MS,
        )
    }

    private suspend fun mockResolveIsrc(isrc: String): TrackResolution {
        delay(MOCK_PASS_DELAY_MS)
        return TrackResolution.Resolved(spotifyUri = MOCK_SPOTIFY_URI, looseSync = false)
    }

    // ---- Real HTTP path (AUTH-03/04 server work replaces the mock above) --

    private suspend fun httpFetchShazamToken(): ShazamTokenResult = withContext(Dispatchers.IO) {
        try {
            // TODO(AUTH-03b): attach the Play Integrity / App Attest
            // attestation header the backend requires
            // (technical-requirements.md §3.2 step 1); no attestation SDK
            // is wired yet.
            val (status, body) = postJson("$baseUrl/v1/tokens/shazam", JSONObject())
            if (status !in 200..299) return@withContext ShazamTokenResult.Failure("http_$status")
            val json = JSONObject(body)
            ShazamTokenResult.Success(
                token = json.getString("token"),
                expiresAtEpochMs = System.currentTimeMillis() + json.getLong("expires_in_ms"),
            )
        } catch (e: IOException) {
            ShazamTokenResult.Failure("network_error")
        } catch (e: JSONException) {
            ShazamTokenResult.Failure("malformed_response")
        }
    }

    private suspend fun httpResolveIsrc(isrc: String): TrackResolution = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(isrc, "UTF-8")
            val (status, body) = getJson("$baseUrl/v1/track-map?isrc=$encoded")
            when (status) {
                in 200..299 -> {
                    val json = JSONObject(body)
                    TrackResolution.Resolved(
                        spotifyUri = json.getString("spotify_uri"),
                        looseSync = json.optBoolean("loose_sync", false),
                    )
                }
                404 -> TrackResolution.NotFound
                else -> TrackResolution.Failure("http_$status")
            }
        } catch (e: IOException) {
            TrackResolution.Failure("network_error")
        } catch (e: JSONException) {
            TrackResolution.Failure("malformed_response")
        }
    }

    private fun postJson(urlString: String, payload: JSONObject): Pair<Int, String> {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(urlString: String): Pair<Int, String> {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): Pair<Int, String> {
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: ""
        return status to text
    }

    private companion object {
        // MOCK constants — unblock INT-02 wiring ahead of AUTH-03/04's
        // server work; replaced automatically the moment baseUrl is
        // non-null (see class doc's swap procedure).
        const val MOCK_SHAZAM_TOKEN = "mock-shazam-token"
        const val MOCK_SPOTIFY_URI = "spotify:track:mock"
    }
}
