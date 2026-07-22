package com.spotify.protocol.client.error

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.protocol.client.error.RemoteClientException` — the
 * base type for transport-level failures reported through
 * [com.spotify.protocol.client.ErrorCallback]. Never thrown by this stub
 * (nothing round-trips to a remote process), but present so call sites
 * that catch/inspect it compile unchanged.
 */
open class RemoteClientException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)
