package com.spotify.protocol.client

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.protocol.client.Subscription<T>`: the handle
 * returned by `PlayerApi.subscribeToPlayerState()`. Stubbed: [cancel] is a
 * no-op and the registered [EventCallback] never fires — there is no
 * remote process emitting player-state events.
 */
class Subscription<T> {
    fun setEventCallback(callback: EventCallback<T>): Subscription<T> = this

    fun cancel() {
        // No-op: nothing subscribed in the stub.
    }
}

fun interface EventCallback<T> {
    fun onEvent(data: T)
}
