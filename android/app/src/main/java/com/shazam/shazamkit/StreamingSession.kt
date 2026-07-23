package com.shazam.shazamkit

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * STUB — compile-time stand-in for the Apple ShazamKit Android AAR (NAT-06).
 * Delete when the real dependency is vendored; call sites must not change.
 *
 * Mirrors `com.shazam.shazamkit.StreamingSession`: fed PCM via [matchStream]
 * on the capture path, and matches surface asynchronously through
 * [recognitionResults].
 */
interface StreamingSession {
    fun matchStream(inputBuffer: ByteArray, numberOfBytesToProcess: Int, timestampInMs: Long)
    fun recognitionResults(): Flow<MatchResult>
}

/**
 * Honest-but-inert, like the Spotify stubs: there is no real matching
 * engine behind [matchStream], so [recognitionResults] is a flow that
 * suspends forever without ever emitting or completing — it does not
 * fabricate a match, and it does not throw. Callers (see
 * [com.jointheparty.app.recognition.ShazamKitProvider.recognizeOnce]) are
 * expected to race it against a timeout, which is exactly the code path
 * this exercises today.
 */
class StubStreamingSession : StreamingSession {
    override fun matchStream(inputBuffer: ByteArray, numberOfBytesToProcess: Int, timestampInMs: Long) {
        // No-op: no real matcher to feed. The real AAR streams PCM into its
        // on-device matching pipeline here.
    }

    override fun recognitionResults(): Flow<MatchResult> = flow { awaitCancellation() }
}
