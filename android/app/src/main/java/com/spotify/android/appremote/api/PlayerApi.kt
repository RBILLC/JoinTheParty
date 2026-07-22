package com.spotify.android.appremote.api

import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Empty
import com.spotify.protocol.types.PlayerState

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.android.appremote.api.PlayerApi`. In this stub every
 * call returns an inert [CallResult]/[Subscription] whose callbacks never
 * fire — there is no real App Remote process on the other end to respond.
 * [AppRemoteSpotifyController] only reaches this class through a
 * [SpotifyAppRemote] instance, and the stubbed [SpotifyAppRemote.connect]
 * never succeeds, so in practice these methods are unreachable until the
 * real AAR lands; they exist purely so the call sites compile unchanged.
 */
class PlayerApi {
    fun play(uri: String): CallResult<Empty> = CallResult()

    fun pause(): CallResult<Empty> = CallResult()

    fun resume(): CallResult<Empty> = CallResult()

    fun seekTo(positionMs: Long): CallResult<Empty> = CallResult()

    fun subscribeToPlayerState(): Subscription<PlayerState> = Subscription()
}
