package com.spotify.android.appremote.api.error

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp`
 * — thrown/reported when the Spotify app isn't installed or can't be
 * reached. Maps to [com.jointheparty.app.spotify.SpotifyController.ConnectionResult.SpotifyMissing]
 * per technical-requirements.md §3.1 step 6.
 */
class CouldNotFindSpotifyApp : RuntimeException("Could not find Spotify app")
