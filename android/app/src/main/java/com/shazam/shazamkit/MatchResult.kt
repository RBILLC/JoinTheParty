package com.shazam.shazamkit

/**
 * STUB — compile-time stand-in for the Apple ShazamKit Android AAR (NAT-06).
 * Delete when the real dependency is vendored; call sites must not change.
 *
 * Mirrors `com.shazam.shazamkit.MatchResult`: the payload
 * [StreamingSession.recognitionResults] emits per match attempt.
 */
sealed class MatchResult {
    data class Match(val matchedMediaItems: List<MatchedMediaItem>) : MatchResult()
    data object NoMatch : MatchResult()
    data class Error(val exception: ShazamKitException) : MatchResult()
}

/**
 * Mirrors `com.shazam.shazamkit.MatchedMediaItem` — the fields
 * architecture-spec.md §3 calls out specifically:
 * [predictedCurrentMatchOffsetInSeconds] (the primary timestamp source,
 * `matchOffset` extrapolated to "now") and [frequencySkew] (non-negligible
 * skew means seek-only correction can't achieve perfect lock). [isrc] is
 * what backs the Shazam → Spotify catalog mapping (technical-requirements.md
 * §3.1's `GET /v1/track-map?isrc=`).
 */
class MatchedMediaItem(
    val title: String?,
    val artist: String?,
    val isrc: String?,
    val matchOffsetInSeconds: Double,
    val predictedCurrentMatchOffsetInSeconds: Double,
    val frequencySkew: Float?,
)
