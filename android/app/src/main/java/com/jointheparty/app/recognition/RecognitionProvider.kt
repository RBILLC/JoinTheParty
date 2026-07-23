package com.jointheparty.app.recognition

/**
 * NAT-06: abstracts the recognition engine behind the (offset, timestamp,
 * ISRC) tuple `SyncEngine.submitRecognitionFix` actually needs
 * (architecture-spec.md §3 — "Abstracted behind a `RecognitionProvider`
 * interface so the sync engine doesn't care which engine produced the fix").
 * [ShazamKitProvider] is the v1 implementation; an ACRCloud fallback
 * (architecture-spec.md §3's "Fallback path (Android or ShazamKit outage)")
 * is a future implementation of this same interface.
 */
interface RecognitionProvider {

    /**
     * One accepted match, already shaped for `SyncEngine.submitRecognitionFix`
     * (`matchOffsetMs`/`captureMonoNs`/`frequencySkew`/`confidence`) plus the
     * metadata (`title`/`artist`/`isrc`) the caller needs to resolve a
     * Spotify URI and populate `TrackInfo`.
     */
    data class RecognitionFixResult(
        val matchOffsetMs: Long,
        val captureMonoNs: Long,
        val frequencySkew: Double,
        val confidence: Float,
        val title: String?,
        val artist: String?,
        val isrc: String?,
    )

    /**
     * Runs exactly one recognition pass and suspends until it resolves —
     * callers (SessionViewModel.runRecognitionPass) are responsible for not
     * overlapping calls and for driving cadence off
     * `SC_EVT_REQUEST_FIX` (technical-requirements.md §3.2: "no
     * free-running recognition loops"). Null means no match this pass
     * (timeout or an honest no-match) — not an error; the caller simply
     * waits for the next pass.
     */
    suspend fun recognizeOnce(): RecognitionFixResult?

    /** Releases any held session/catalog resources. Idempotent. */
    fun close()
}
