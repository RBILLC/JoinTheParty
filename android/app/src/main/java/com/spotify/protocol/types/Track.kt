package com.spotify.protocol.types

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.protocol.types.Track`.
 */
data class Track(
    val uri: String,
    val name: String,
    val artist: Artist,
    val duration: Long,
)
