package com.spotify.android.appremote.api.error

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.android.appremote.api.error.NotLoggedInException` —
 * reported when the Spotify app has no logged-in user. Maps to
 * [com.jointheparty.app.spotify.SpotifyController.ConnectionResult.AuthFailed].
 */
class NotLoggedInException : RuntimeException("Spotify app has no logged-in user")
