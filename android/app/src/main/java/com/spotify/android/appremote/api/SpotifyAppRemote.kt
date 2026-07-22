package com.spotify.android.appremote.api

import android.content.Context
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.android.appremote.api.SpotifyAppRemote`: the
 * connection handle vended by [connect] and torn down via [disconnect].
 *
 * Honest-but-inert runtime behavior: since there is no real Spotify app to
 * wake on this build, [connect] immediately reports
 * [CouldNotFindSpotifyApp] on the caller-supplied listener instead of
 * silently hanging or fabricating a fake success. This keeps
 * [AppRemoteSpotifyController]'s error-taxonomy mapping exercised
 * end-to-end even before the real AAR is vendored.
 */
class SpotifyAppRemote private constructor(
    val playerApi: PlayerApi,
) {
    val isConnected: Boolean = true

    companion object {
        @JvmStatic
        fun connect(
            context: Context,
            connectionParams: ConnectionParams,
            connectionListener: Connector.ConnectionListener,
        ) {
            // Real AAR: attempts to bind the Spotify app's App Remote
            // service, retrying briefly before failing. Stub: fails fast
            // and honestly — there is no service to bind to.
            connectionListener.onFailure(CouldNotFindSpotifyApp())
        }

        @JvmStatic
        fun disconnect(spotifyAppRemote: SpotifyAppRemote) {
            // No-op: no live connection exists in the stub.
        }
    }
}
