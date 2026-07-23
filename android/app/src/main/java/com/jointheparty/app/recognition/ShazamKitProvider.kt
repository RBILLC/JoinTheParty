package com.jointheparty.app.recognition

import com.jointheparty.app.backend.BackendClient
import com.jointheparty.app.backend.ShazamTokenResult
import com.shazam.shazamkit.AudioSampleRateInHz
import com.shazam.shazamkit.Catalog
import com.shazam.shazamkit.DeveloperToken
import com.shazam.shazamkit.DeveloperTokenProvider
import com.shazam.shazamkit.MatchResult
import com.shazam.shazamkit.ShazamKit
import com.shazam.shazamkit.ShazamKitResult
import com.shazam.shazamkit.StreamingSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val RECOGNITION_TIMEOUT_MS = 12_000L
private const val READ_BUFFER_SIZE = 4096

// ShazamKit doesn't expose a granular per-match confidence score (unlike
// ACRCloud's normalized value) — every accepted Match is reported at this
// fixed prior. SyncCore's Kalman filter and deadband policy
// (architecture-spec.md §6.2) don't depend on fine-grained confidence
// differences between fixes, so a constant is an acceptable stand-in, not a
// gap that needs closing before launch.
private const val MATCH_CONFIDENCE = 0.9f

/**
 * NAT-06: [RecognitionProvider] over the ShazamKit stubs
 * (android/app/src/main/java/com/shazam/shazamkit/ — see their headers for
 * the stub-to-real swap procedure). Every call site below is written
 * exactly as it will be against the real Apple AAR; nothing here needs to
 * change when it lands.
 *
 * Quota discipline (technical-requirements.md §3.2: "recognizer must reuse
 * one session per sync session"): the [Catalog] and the single
 * [StreamingSession] are created lazily on first use and reused across
 * every [recognizeOnce] pass for the life of this provider — never one
 * session per pass. Passes themselves are driven one at a time by the
 * caller (`SessionViewModel.runRecognitionPass`), itself gated by
 * `SC_EVT_REQUEST_FIX`; this class runs no timer or loop of its own.
 */
class ShazamKitProvider(
    private val backend: BackendClient,
) : RecognitionProvider {

    private val initMutex = Mutex()
    private var catalog: Catalog? = null
    private var session: StreamingSession? = null

    // Bridges BackendClient.fetchShazamToken() (suspend, backed by the
    // network/mock) to DeveloperTokenProvider.provideDeveloperToken() (the
    // ShazamKit SDK's callback is synchronous — it's invoked from
    // ShazamKit's own internal thread, not a coroutine, so it can't suspend
    // to fetch a token itself). ensureSession() always suspends to call
    // fetchShazamToken() — which is itself a cache hit whenever
    // HttpBackendClient's own <1h-to-expiry cache is warm — immediately
    // before touching the catalog, so by the time ShazamKit might invoke
    // this provider synchronously, [lastFetchedToken] is never more than
    // one ensureSession() call stale.
    @Volatile
    private var lastFetchedToken: String? = null

    private val tokenProvider = DeveloperTokenProvider {
        DeveloperToken(lastFetchedToken ?: "")
    }

    override suspend fun recognizeOnce(): RecognitionProvider.RecognitionFixResult? {
        val activeSession = ensureSession() ?: return null
        val captureMonoNs = System.nanoTime()

        // Timeout, not a hang: the stub's recognitionResults() never emits
        // (see StubStreamingSession), so this always resolves via the
        // timeout path today — an honest "no match this pass," not a
        // fabricated one. Against the real AAR the same timeout guards
        // against a pass that never completes.
        val result = withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
            activeSession.recognitionResults().first()
        } ?: return null

        return when (result) {
            is MatchResult.Match -> {
                val item = result.matchedMediaItems.firstOrNull() ?: return null
                RecognitionProvider.RecognitionFixResult(
                    matchOffsetMs = (item.predictedCurrentMatchOffsetInSeconds * 1000).toLong(),
                    captureMonoNs = captureMonoNs,
                    frequencySkew = item.frequencySkew?.toDouble() ?: 0.0,
                    confidence = MATCH_CONFIDENCE,
                    title = item.title,
                    artist = item.artist,
                    isrc = item.isrc,
                )
            }
            MatchResult.NoMatch -> null
            is MatchResult.Error -> null
        }
    }

    // TODO(NAT-06b): AUDIO FEED — with the stubs there is no real PCM path.
    // When the real AAR lands, call this during active recognition passes
    // ONLY (never continuously — technical-requirements.md §3.2's
    // no-free-running-recognition rule) with 48 kHz PCM16 mono, tee'd from
    // the existing Oboe capture stream (an AudioRecord tee alongside
    // `SyncCore.pushCapture`, or a future C++ PCM tap that mirrors
    // `sc_push_capture`'s buffer out to this provider). Until then this
    // method forwards to the stub's no-op `matchStream` and has no
    // observable effect.
    fun feedAudio(pcm: ByteArray, timestampMs: Long) {
        session?.matchStream(pcm, pcm.size, timestampMs)
    }

    override fun close() {
        // The stub Catalog/StreamingSession hold no real resources to
        // release. The real AAR's StreamingSession likely needs an
        // explicit teardown call — add it here when the swap happens; no
        // such API exists on the stub to call today.
        session = null
        catalog = null
    }

    private suspend fun ensureSession(): StreamingSession? = initMutex.withLock {
        val existing = session
        if (existing != null) return@withLock existing

        val tokenResult = backend.fetchShazamToken()
        if (tokenResult !is ShazamTokenResult.Success) return@withLock null
        lastFetchedToken = tokenResult.token

        val activeCatalog = catalog ?: ShazamKit.createShazamCatalog(tokenProvider).also { catalog = it }
        val created = ShazamKit.createStreamingSession(
            catalog = activeCatalog,
            audioSampleRateInHz = AudioSampleRateInHz.SAMPLE_RATE_48000,
            readBufferSize = READ_BUFFER_SIZE,
        )
        (created as? ShazamKitResult.Success)?.data?.also { session = it }
    }
}
