package com.spotify.protocol.client

/**
 * STUB — compile-time stand-in for the Spotify App Remote AAR (NAT-08).
 * Delete this file when the real dependency is vendored; call sites must
 * not change.
 *
 * Mirrors `com.spotify.protocol.client.CallResult<T>`: the fluent
 * result/error callback registration returned by every [PlayerApi]
 * command. Stubbed callbacks are recorded nowhere and never fire — there
 * is no remote process to reply — so call sites written against this API
 * (`.setResultCallback { }.setErrorCallback { }`) compile and run
 * harmlessly, just without ever completing.
 */
class CallResult<T> {
    fun setResultCallback(callback: ResultCallback<T>): CallResult<T> = this

    fun setErrorCallback(callback: ErrorCallback): CallResult<T> = this
}

fun interface ResultCallback<T> {
    fun onResult(data: T)
}

fun interface ErrorCallback {
    fun onError(throwable: Throwable)
}
