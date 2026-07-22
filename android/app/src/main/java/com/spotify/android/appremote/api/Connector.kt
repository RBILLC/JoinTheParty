package com.spotify.android.appremote.api

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.android.appremote.api.Connector`: the connect
 * lifecycle callback surface passed to [SpotifyAppRemote.connect].
 */
interface Connector {
    interface ConnectionListener {
        fun onConnected(spotifyAppRemote: SpotifyAppRemote)
        fun onFailure(throwable: Throwable)
    }
}
