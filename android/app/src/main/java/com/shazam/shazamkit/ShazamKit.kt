package com.shazam.shazamkit

/**
 * STUB — compile-time stand-in for the Apple ShazamKit Android AAR (NAT-06).
 * Delete when the real dependency is vendored; call sites must not change.
 *
 * Mirrors the top-level `ShazamKit` object: the entry point for creating a
 * [Catalog] (bound to a developer token) and, from it, a [StreamingSession]
 * to feed PCM into for matching.
 *
 * Honest-but-inert runtime behavior: there is no real matcher behind this
 * build, so [createShazamCatalog] always succeeds (it never actually talks
 * to Shazam's servers to validate the token) and every
 * [createStreamingSession] call succeeds with a session whose
 * `recognitionResults()` flow never emits — see [StubStreamingSession].
 * Nothing here pretends to recognize audio; it exists purely so
 * [com.jointheparty.app.recognition.ShazamKitProvider] compiles and runs
 * against the real shape of the SDK ahead of the AAR being vendored.
 */
object ShazamKit {

    suspend fun createShazamCatalog(developerTokenProvider: DeveloperTokenProvider): Catalog {
        // Real AAR: exchanges the developer token for a catalog handle,
        // validating it server-side. Stub: exercise the provider seam (so a
        // broken DeveloperTokenProvider implementation still surfaces
        // exceptions the same way it eventually will) but otherwise just
        // hand back an inert catalog.
        developerTokenProvider.provideDeveloperToken()
        return StubCatalog
    }

    suspend fun createStreamingSession(
        catalog: Catalog,
        audioSampleRateInHz: AudioSampleRateInHz,
        readBufferSize: Int,
    ): ShazamKitResult<StreamingSession> {
        // Real AAR: opens a matching session against `catalog` at the given
        // sample rate. Stub: always succeeds — the honest failure mode here
        // is a session that never produces a match, not a session that
        // fails to open.
        return ShazamKitResult.Success(StubStreamingSession())
    }

    private object StubCatalog : Catalog
}
