package com.spotify.android.appremote.api.error

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.android.appremote.api.error.UserNotAuthorizedException`
 * — reported when the logged-in user hasn't authorized this app (or lacks
 * the entitlement the requested action needs, e.g. Premium-gated seek per
 * technical-requirements.md §3.1 step 6). Maps to
 * [com.jointheparty.app.spotify.SpotifyController.ConnectionResult.AuthFailed].
 */
class UserNotAuthorizedException : RuntimeException("User not authorized")
