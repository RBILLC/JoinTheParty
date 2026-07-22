package com.spotify.protocol.types

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.protocol.types.PlayerState` — the payload delivered
 * to `PlayerApi.subscribeToPlayerState()` subscribers and consumed by
 * [com.jointheparty.app.spotify.AppRemoteSpotifyController] to drive
 * `SyncEngine.submitPlayerState`.
 */
data class PlayerState(
    val track: Track?,
    val isPaused: Boolean,
    val playbackPosition: Long,
    val playbackSpeed: Float,
)
