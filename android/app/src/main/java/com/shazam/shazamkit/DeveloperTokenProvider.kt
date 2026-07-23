package com.shazam.shazamkit

/**
 * STUB — compile-time stand-in for the Apple ShazamKit Android AAR (NAT-06).
 * Delete when the real dependency is vendored; call sites must not change.
 *
 * Mirrors `com.shazam.shazamkit.DeveloperTokenProvider`: the callback
 * ShazamKit invokes (synchronously, on its own thread) whenever it needs a
 * fresh Apple developer token (technical-requirements.md §3.2) — e.g. on
 * catalog creation or after an `InvalidToken` failure. `fun interface` so
 * callers can hand it a lambda, same pattern as the Spotify stubs'
 * `ResultCallback`/`EventCallback`.
 */
fun interface DeveloperTokenProvider {
    fun provideDeveloperToken(): DeveloperToken
}

/** A minted ES256 JWT (technical-requirements.md §3.2), opaque to ShazamKit's caller. */
data class DeveloperToken(val token: String)
