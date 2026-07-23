package com.shazam.shazamkit

/**
 * STUB — compile-time stand-in for the Apple ShazamKit Android AAR (NAT-06).
 * Delete when the real dependency is vendored; call sites must not change.
 *
 * Mirrors `com.shazam.shazamkit.ShazamKitResult<T>`: the result/error
 * wrapper returned by session-creation calls (distinct from [MatchResult],
 * which wraps the outcome of an individual match, not the session setup).
 */
sealed class ShazamKitResult<T> {
    data class Success<T>(val data: T) : ShazamKitResult<T>()
    data class Failure<T>(val reason: ShazamKitException) : ShazamKitResult<T>()
}

/**
 * Mirrors `com.shazam.shazamkit.ShazamKitException`: the base type for every
 * ShazamKit-originated failure (session creation, matching). `open` so the
 * real AAR's richer taxonomy (e.g. invalid-token, network) can subclass it
 * the same way it will once vendored.
 */
open class ShazamKitException(message: String) : Exception(message)
