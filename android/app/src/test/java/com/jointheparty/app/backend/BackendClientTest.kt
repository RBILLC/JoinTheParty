package com.jointheparty.app.backend

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTH-03/04 mock-path acceptance: no backend is deployed yet, so
 * [HttpBackendClient] is exercised with `baseUrl = null` — the canned
 * responses that unblock INT-02 wiring ahead of the real server (see
 * [HttpBackendClient]'s class doc for the swap procedure).
 */
class BackendClientTest {

    @Test
    fun fetchShazamTokenReturnsSuccessWithFutureExpiryAndIsCached() = runTest {
        val client = HttpBackendClient(baseUrl = null)
        val now = System.currentTimeMillis()

        val first = client.fetchShazamToken()
        require(first is ShazamTokenResult.Success)
        assertTrue("expiresAtEpochMs should be in the future", first.expiresAtEpochMs > now)

        // technical-requirements.md §3.2 step 3: cache in memory, refresh
        // only when < 1h of TTL remains. A 24h-TTL token minted moments ago
        // has ~24h remaining, so the second call must be served from cache
        // rather than minting a new one.
        val second = client.fetchShazamToken()
        require(second is ShazamTokenResult.Success)
        assertEquals(first.token, second.token)
        assertEquals(first.expiresAtEpochMs, second.expiresAtEpochMs)
    }

    @Test
    fun resolveIsrcToSpotifyUriReturnsResolvedWithoutLooseSync() = runTest {
        val client = HttpBackendClient(baseUrl = null)

        val result = client.resolveIsrcToSpotifyUri("USABC1234567")

        require(result is TrackResolution.Resolved)
        assertEquals(false, result.looseSync)
    }
}
