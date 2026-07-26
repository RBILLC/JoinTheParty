package com.jointheparty.app.ui.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jointheparty.app.backend.BackendClient
import com.jointheparty.app.backend.HttpBackendClient
import com.jointheparty.app.backend.TrackResolution
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.core.SyncEngine
import com.jointheparty.app.data.DataStoreNudgeStore
import com.jointheparty.app.audio.AudioTrackChirpPlayer
import com.jointheparty.app.audio.ChirpPlayer
import com.jointheparty.app.data.NudgeStore
import com.jointheparty.app.recognition.ACRCloudProvider
import com.jointheparty.app.recognition.EnginePcmWindowSource
import com.jointheparty.app.recognition.RecognitionProvider
import com.jointheparty.app.spotify.AppRemoteSpotifyController
import com.jointheparty.app.spotify.SpotifyController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.jointheparty.app.ui.model.MeterFrame
import com.jointheparty.app.ui.model.toMeterFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI-02: shared low-frequency session model (technical-requirements.md
 * §2.1). Distinct from [MeterFrame] — see the two-stream rule enforced by
 * [SessionViewModel.meterFrames].
 */
data class SyncState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val track: TrackInfo? = null,
    val nudgeMs: Int = 0,
    val routeId: String = "speaker",
    val routeName: String? = null,
    val lastRejectReason: SyncCore.RejectReason? = null,
    val calibration: CalibrationState = CalibrationState.Idle,
)

/** First pass waits for the PCM window to fill (source needs ≥3 s). */
private const val INITIAL_CAPTURE_FILL_MS = 4_000L

/** Pre-first-fix retry cadence while MATCHING (post-fix cadence is engine-driven). */
private const val RECOGNITION_RETRY_MS = 6_000L

/** ~2 minutes of shell-driven sampling before giving up (quota guard). */
private const val MAX_SAMPLING_ATTEMPTS = 20

/**
 * Engine correction deadband for this shell (sc_config_t.deadband_ms):
 * above ACR's fix noise so corrections fire only on audibly-wrong error;
 * an audible skip every few seconds annoys more than sub-400 ms offset
 * between separated sources.
 */
private const val ENGINE_DEADBAND_MS = 350

/** Aim-verification loop (arch §6.2 coarse aim, made deterministic). */
private const val MAX_AIM_ATTEMPTS = 4
private const val AIM_VERIFY_DELAY_MS = 900L
private const val AIM_TOLERANCE_MS = 3_000L

/** INT-03: chirp-calibration lifecycle for the active route (arch §6.4). */
sealed interface CalibrationState {
    data object Idle : CalibrationState
    data object Running : CalibrationState
    data class Success(val latencyMs: Int) : CalibrationState
    data object Failed : CalibrationState
}

data class TrackInfo(
    val spotifyUri: String,
    val isrc: String?,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

/**
 * UI-02: sole writer of [SyncState], driving the §2.4 phase machine from
 * both shell intents (user actions, resolver results) and SyncCore engine
 * events. [transition] is the single gate every phase change passes
 * through — see its allowlist for the authoritative table.
 *
 * Two-stream rule (technical-requirements.md §2.1/§2.3): [meterFrames] is a
 * bare pass-through of [SyncEngine.meterFrames]. It is NEVER collected into
 * [syncState] — only the raw [SyncEngine.events] stream (collected
 * separately in [init]) feeds phase transitions, and only by reading the
 * `converged` flag off [SyncCore.Event.SyncEstimate]. High-frequency meter
 * values (errorMs/driftPpm/confidence) never touch this ViewModel's state.
 */
class SessionViewModel(
    private val engine: SyncEngine,
    private val nudgeStore: NudgeStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    // NAT-06: both default null so every existing call site (and every
    // existing test's FakeSyncEngine-only construction) compiles and
    // behaves unchanged — runRecognitionPass() and the startListening()
    // bootstrap both no-op when recognition is null.
    private val recognition: RecognitionProvider? = null,
    private val backend: BackendClient? = null,
    private val chirp: ChirpPlayer? = null,
    // INT-02: the playback half of the loop. Null in unit tests.
    private val spotify: SpotifyController? = null,
) : ViewModel() {

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /**
     * Pass-through meter stream — bypasses [syncState] entirely (two-stream
     * rule). Collect only inside the meter composable, per UI-03.
     */
    val meterFrames: Flow<MeterFrame> = engine.meterFrames.map { it.toMeterFrame() }

    /**
     * Field request: the song's current position, 1 Hz, projected between
     * player-state events. −1 while unknown. Collected only by the small
     * clock composable (same isolation idea as the meter stream).
     */
    val playbackPositionMs: Flow<Long> = kotlinx.coroutines.flow.flow {
        while (true) {
            val ps = spotify?.lastKnownPlayerState
            emit(
                when {
                    ps == null -> -1L
                    ps.isPaused -> ps.positionMs
                    else -> ps.positionMs +
                        (System.nanoTime() - ps.receivedMonoNs) / 1_000_000
                },
            )
            delay(1_000)
        }
    }

    /** Consecutive SC_EVT_TRACK_LOST count; reset whenever LOCKED is reached. */
    private var consecutiveLosses = 0

    /**
     * NAT-06: guards [runRecognitionPass] against overlapping passes —
     * ShazamKit quota discipline (technical-requirements.md §3.2) requires
     * one session, one pass at a time. Two triggers can race to start a
     * pass (the startListening() bootstrap and every SC_EVT_REQUEST_FIX
     * after it); this flag is what keeps them from overlapping.
     */
    private val recognitionInFlight = AtomicBoolean(false)

    init {
        // UNDISPATCHED: SyncCore.events is a hot SharedFlow with no replay
        // (see SyncCore.kt's threading note) — a collector that only starts
        // once `dispatcher` gets around to running it could miss an event
        // emitted between construction and that first dispatch. Starting
        // undispatched registers the collector synchronously, before this
        // constructor returns; every hop after its first suspension point
        // still runs on `dispatcher` as normal.
        viewModelScope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { event -> onEngineEvent(event) }
        }
    }

    // ---- Shell-driven intents ---------------------------------------------

    /**
     * idle/lost/error → listening (user taps Join, or manual retry). Starts
     * the native Oboe capture stream first — LISTENING with a dead mic would
     * be a lie. Caller must hold RECORD_AUDIO before invoking.
     */
    fun startListening() {
        val from = _syncState.value.phase
        if (from == SessionPhase.NEEDS_SPOTIFY || from == SessionPhase.NEEDS_PREMIUM) {
            // Proceeding past a gate IS its dismissal (once per session).
            gateDismissedThisSession = true
        }
        val capStarted = engine.startCapture()
        com.jointheparty.app.debug.DebugLog.log(
            "join → startCapture=${if (capStarted) "ok" else "FAILED (mic/format)"}; " +
                "recognizer=${if (recognition != null) "ACRCloud" else "none"}",
        )
        if (!capStarted) return
        transition(SessionPhase.LISTENING)

        // NAT-06 bootstrap: SC_EVT_REQUEST_FIX is the only *recurring*
        // recognition trigger (technical-requirements.md §3.2 — no
        // free-running recognition loops), but SyncCore can't request a fix
        // before it has ever received one, so nothing would ever kick off
        // the first pass. Fire it manually, once, right after capture
        // starts. Guarded on `recognition` so ViewModels built without
        // NAT-06 wiring (every existing unit test's FakeSyncEngine-only
        // construction) see no behavior change.
        if (recognition != null) {
            onMatchInFlight()
            // FIELD FIX (2026-07-24): the first pass must WAIT for the
            // capture window to fill (EnginePcmWindowSource needs ≥3 s of
            // audio); firing instantly returned null and nothing retried —
            // the app sat in MATCHING forever. First pass at +4 s; misses
            // retry on a 6 s cadence while MATCHING (see runRecognitionPass).
            viewModelScope.launch(dispatcher) {
                delay(INITIAL_CAPTURE_FILL_MS)
                runRecognitionPass()
            }
        }
    }

    /** listening → matching: first audio buffered to the recognizer. */
    fun onMatchInFlight() {
        transition(SessionPhase.MATCHING)
    }

    /** matching → aiming: fix accepted + ISRC→URI resolved, play+seek issued. */
    fun onTrackResolved(track: TrackInfo) {
        if (transition(SessionPhase.AIMING)) {
            _syncState.update { it.copy(track = track) }
            startPlayback(track.spotifyUri, aimOffsetMs = null, aimCaptureMonoNs = null)
        }
    }

    /**
     * INT-02: connect App Remote and start the matched track. SyncCore's
     * first recognition fix is already in; once player states flow
     * (subscription inside the controller feeds submitPlayerState), the
     * estimator has both timelines and corrections begin.
     */
    private fun startPlayback(
        uri: String,
        aimOffsetMs: Long?,
        aimCaptureMonoNs: Long?,
    ) {
        val controller = spotify ?: return
        viewModelScope.launch(dispatcher) {
            when (val r = controller.connect()) {
                SpotifyController.ConnectionResult.Connected -> {
                    com.jointheparty.app.debug.DebugLog.log("Spotify connected → play $uri")
                    controller.play(uri)
                    // FIELD TEST 2 FIX (round 1): play(uri) starts from 0:00
                    // — without the arch §6.2 coarse aim the estimator
                    // immediately measures the full song-position error
                    // (observed: −17.3 s → track-lost → restart loop).
                    // FIELD TEST 2 FIX (round 2, superseded): waiting for a
                    // player state carrying our track URI is NOT enough —
                    // Spotify reports the new track's metadata within ~10 ms
                    // while playback is still buffering, and a seek in that
                    // window is silently dropped (observed twice).
                    // FIELD TEST 2 FIX (round 3): stop guessing when Spotify
                    // is seekable — VERIFY each aim landed via the reported
                    // position and re-issue until it does (bounded).
                    if (aimOffsetMs != null && aimCaptureMonoNs != null) {
                        aimUntilLanded(controller, aimOffsetMs, aimCaptureMonoNs)
                    }
                    // AIMING → CONVERGING on the first player state.
                    playerStateWatcher()
                }
                SpotifyController.ConnectionResult.SpotifyMissing -> {
                    com.jointheparty.app.debug.DebugLog.log("Spotify: app not found / not running")
                    onSpotifyMissing()
                }
                SpotifyController.ConnectionResult.AuthFailed -> {
                    // NOTE: App Remote reports "not authorized" for BOTH a
                    // missing user grant and a genuine Premium gate. Cost us
                    // real debugging time on 2026-07-24 (the account simply
                    // wasn't allowlisted); the copy is now honest about it.
                    com.jointheparty.app.debug.DebugLog.log(
                        "Spotify: not authorized (grant needed, or Premium)",
                    )
                    onPremiumRequired()
                }
                is SpotifyController.ConnectionResult.Failed -> {
                    com.jointheparty.app.debug.DebugLog.log(
                        "Spotify connect failed: ${r.cause.message}",
                    )
                }
            }
        }
    }

    /**
     * The coarse aim (arch §6.2 step 1), made deterministic: issue the seek,
     * wait, compare the reported position against where the room should be,
     * and re-issue if the seek was swallowed by the track-load transition.
     * Success within ~3 s of room-time is plenty — the estimator's
     * micro-corrections own everything below that.
     */
    private suspend fun aimUntilLanded(
        controller: SpotifyController,
        aimOffsetMs: Long,
        aimCaptureMonoNs: Long,
    ) {
        repeat(MAX_AIM_ATTEMPTS) { attempt ->
            val elapsedMs = (System.nanoTime() - aimCaptureMonoNs) / 1_000_000
            val target = aimOffsetMs + elapsedMs + engine.commandLatencyMs()
            controller.seekTo(target)
            delay(AIM_VERIFY_DELAY_MS)

            val state = controller.lastKnownPlayerState
            val roomNowMs =
                aimOffsetMs + (System.nanoTime() - aimCaptureMonoNs) / 1_000_000
            val landed = state != null &&
                kotlin.math.abs(state.positionMs - roomNowMs) < AIM_TOLERANCE_MS
            com.jointheparty.app.debug.DebugLog.log(
                "aim #${attempt + 1} → seek ${target}ms; player=" +
                    "${state?.positionMs ?: "?"}ms room≈${roomNowMs}ms " +
                    if (landed) "LANDED" else "missed",
            )
            if (landed) return
        }
        com.jointheparty.app.debug.DebugLog.log(
            "aim gave up after $MAX_AIM_ATTEMPTS attempts — estimator will report the error",
        )
    }

    private fun playerStateWatcher() {
        val controller = spotify ?: return
        viewModelScope.launch(dispatcher) {
            controller.playerStates.collect {
                if (_syncState.value.phase == SessionPhase.AIMING) {
                    onPlaybackStarted()
                }
            }
        }
    }

    /**
     * Prefers the provider-supplied Spotify URI (ACRCloud external_metadata
     * — real, playable) over the backend ISRC resolver (which is MOCKED
     * until AUTH-03's server deploys and would hand playback a fake URI).
     */
    private suspend fun resolveTrack(fix: RecognitionProvider.RecognitionFixResult) {
        val direct = fix.spotifyUri
        if (direct != null) {
            resolvedWithAim(
                TrackInfo(
                    spotifyUri = direct,
                    isrc = fix.isrc,
                    title = fix.title ?: "Unknown",
                    artist = fix.artist ?: "",
                    durationMs = 0L,
                ),
                fix,
            )
            return
        }
        val backendClient = backend ?: return
        val isrc = fix.isrc ?: return
        when (val resolution = backendClient.resolveIsrcToSpotifyUri(isrc)) {
            is TrackResolution.Resolved ->
                resolvedWithAim(
                    TrackInfo(
                        spotifyUri = resolution.spotifyUri,
                        isrc = isrc,
                        title = fix.title ?: "Unknown",
                        artist = fix.artist ?: "",
                        durationMs = 0L,
                    ),
                    fix,
                )
            TrackResolution.NotFound,
            is TrackResolution.Failure,
            -> Unit // stay in MATCHING; the next pass may resolve
        }
    }

    /** Like [onTrackResolved], but carries the fix so playback can aim. */
    private fun resolvedWithAim(
        track: TrackInfo,
        fix: RecognitionProvider.RecognitionFixResult,
    ) {
        if (transition(SessionPhase.AIMING)) {
            _syncState.update { it.copy(track = track) }
            startPlayback(
                track.spotifyUri,
                aimOffsetMs = fix.matchOffsetMs,
                aimCaptureMonoNs = fix.captureMonoNs,
            )
        }
    }

    /** aiming → converging: first post-seek player state received. */
    fun onPlaybackStarted() {
        transition(SessionPhase.CONVERGING)
    }

    /**
     * UI-06 once-per-session rule (ui-ux §6.4): after the user dismisses a
     * concierge gate (by proceeding recognition-only), it must not re-raise
     * within the session. Cleared by [reset].
     */
    private var gateDismissedThisSession = false

    /**
     * any → needsSpotify (App Remote couldn't find the Spotify app).
     * Returns whether the gate was raised — false once dismissed this
     * session, so callers proceed (recognition-only) instead.
     */
    fun onSpotifyMissing(): Boolean {
        if (gateDismissedThisSession) return false
        return transition(SessionPhase.NEEDS_SPOTIFY)
    }

    /** any → needsPremium (seek rejected for a non-Premium account). */
    fun onPremiumRequired(): Boolean {
        if (gateDismissedThisSession) return false
        return transition(SessionPhase.NEEDS_PREMIUM)
    }

    /** any → idle: user-initiated escape hatch, e.g. leaving the session. */
    fun reset() {
        if (transition(SessionPhase.IDLE)) {
            engine.stopCapture()
            consecutiveLosses = 0
            gateDismissedThisSession = false
            firstEstimateSeen = false
            samplingAttempts = 0
            _syncState.update {
                SyncState(routeId = it.routeId, routeName = it.routeName, nudgeMs = it.nudgeMs)
            }
        }
    }

    /** Wheel commit: push to the engine, persist per-route, reflect in state. */
    fun onNudgeCommitted(trimMs: Int) {
        val routeId = _syncState.value.routeId
        val deltaMs = trimMs - _syncState.value.nudgeMs
        // FIELD FIX (2026-07-26, "fighting the auto correction"): the EAR is
        // ground truth. A commit (1) moves playback by the delta right now,
        // and (2) REBASES the engine setpoint by its own currently-measured
        // error — declaring the user's alignment to be zero. Without the
        // rebase, the engine's residual (biased) measurement survives the
        // commit and the next correction seeks the user's tuning back off.
        val rebase = lastEstimateErrorMs
        lastEstimateErrorMs = 0.0
        engineNudgeMs += deltaMs + rebase
        engine.setUserNudgeMs(engineNudgeMs.toInt())
        _syncState.update { it.copy(nudgeMs = trimMs) }
        val ps = spotify?.lastKnownPlayerState
        if (deltaMs != 0 && ps != null) {
            val projected =
                ps.positionMs + (System.nanoTime() - ps.receivedMonoNs) / 1_000_000
            com.jointheparty.app.debug.DebugLog.log(
                "nudge Δ${deltaMs}ms rebase=${"%.0f".format(rebase)}ms " +
                    "engineSetpoint=${engineNudgeMs.toInt()}ms → seek ${projected + deltaMs}ms",
            )
            spotify?.seekTo(projected + deltaMs)
        }
        viewModelScope.launch(dispatcher) {
            nudgeStore.saveTrim(routeId, trimMs)
        }
    }

    /** Route reconnect: load persisted trim + command-latency prior, apply both. */
    fun onRouteChanged(routeId: String, routeName: String?, route: SyncCore.Route) {
        viewModelScope.launch(dispatcher) {
            val trim = nudgeStore.trimFor(routeId)
            // INT-03 fix: setOutputRoute's prior is the chirp-calibrated
            // OUTPUT-chain latency, not Spotify's command latency (which
            // seeds sc_create instead — see NudgeStore's doc note).
            val outputLatencyPrior = nudgeStore.outputLatencyFor(routeId)
            engineNudgeMs = trim.toDouble()  // fresh route: no rebase history
            engine.setUserNudgeMs(trim)
            engine.setOutputRoute(route, outputLatencyPrior)
            // INT-04 (arch §7): phone-speaker playback means the mic hears
            // us — full AEC + self-hearing guard. Headphone routes are the
            // clean case: AEC off entirely.
            engine.setAecMode(
                if (route == SyncCore.Route.SPEAKER) SyncCore.AecMode.FULL
                else SyncCore.AecMode.OFF,
            )
            _syncState.update { it.copy(routeId = routeId, routeName = routeName, nudgeMs = trim) }
        }
    }

    // ---- Calibration (INT-03) ---------------------------------------------

    /**
     * Arms the engine's chirp detector. The shell-side chirp *playback*
     * (through the active output route) is TODO(INT-03b) — until it exists,
     * a run with nothing audible ends in the engine's 8 s timeout →
     * [CalibrationState.Failed], which is the honest outcome.
     */
    fun startCalibration() {
        if (_syncState.value.calibration == CalibrationState.Running) return
        if (!engine.beginCalibration()) return
        // INT-03b: begin arms the detector (t0 = capture-now), THEN the
        // chirp is rendered through the active output route — the measured
        // delta is exactly the route's output-chain latency.
        chirp?.play()
        _syncState.update { it.copy(calibration = CalibrationState.Running) }
    }

    /**
     * INT-02: App Remote needs an Activity to present its consent UI (see
     * AppRemoteSpotifyController.activityContext). MainActivity attaches
     * itself while alive and detaches in onDestroy.
     */
    fun attachActivity(activity: android.app.Activity?) {
        (spotify as? AppRemoteSpotifyController)?.activityContext = activity
    }

    fun cancelCalibration() {
        engine.cancelCalibration()
        _syncState.update { it.copy(calibration = CalibrationState.Idle) }
    }

    /** Sheet dismissed: clear any terminal result so reopening starts fresh. */
    fun acknowledgeCalibration() {
        _syncState.update { it.copy(calibration = CalibrationState.Idle) }
    }

    private fun onCalibrationResult(event: SyncCore.Event.CalibrationResult) {
        if (event.valid) {
            val routeId = _syncState.value.routeId
            _syncState.update {
                it.copy(calibration = CalibrationState.Success(event.latencyMs))
            }
            viewModelScope.launch(dispatcher) {
                // Persisted beside the route's trim; replayed into
                // sc_set_output_route on every reconnect (onRouteChanged).
                nudgeStore.saveOutputLatency(routeId, event.latencyMs)
            }
            engine.setOutputRoute(currentRoute(), event.latencyMs)
        } else {
            _syncState.update { it.copy(calibration = CalibrationState.Failed) }
        }
    }

    private fun currentRoute(): SyncCore.Route = when {
        _syncState.value.routeId.startsWith("bluetooth") -> SyncCore.Route.BLUETOOTH
        _syncState.value.routeId == "wired" -> SyncCore.Route.WIRED
        else -> SyncCore.Route.SPEAKER
    }

    // ---- Engine-driven transitions -----------------------------------------

    private fun onEngineEvent(event: SyncCore.Event) {
        when (event) {
            is SyncCore.Event.SyncEstimate -> onSyncEstimate(event)
            is SyncCore.Event.FixRejected -> {
                com.jointheparty.app.debug.DebugLog.log("fix rejected: ${event.reason}")
                _syncState.update { it.copy(lastRejectReason = event.reason) }
            }
            SyncCore.Event.TrackLost -> onTrackLost()
            // NAT-06: the ONLY recurring recognition trigger, per the
            // no-free-running-recognition-loops rule (technical-
            // requirements.md §3.2). runRecognitionPass() itself no-ops
            // when `recognition` is null.
            SyncCore.Event.RequestFix -> runRecognitionPass()
            is SyncCore.Event.CalibrationResult -> onCalibrationResult(event)
            // INT-02: execute the engine's micro-seek; the controller echoes
            // notifySeekIssued (settle window + latency learning).
            is SyncCore.Event.Correction -> {
                // FIELD FIX (2026-07-25): shell-side damping is BANNED —
                // silently dropping an emitted correction corrupted the
                // engine's command-latency learning (each unexecuted seek
                // read as landing bias → learned latency ballooned →
                // overshoots → spurious track-lost → song restarts). The
                // deadband now lives in the engine (sc_config_t.deadband_ms
                // = 350 from the Factory); every emitted correction MUST be
                // executed and echoed.
                val ps = spotify?.lastKnownPlayerState
                val jumpMs = ps?.let {
                    event.seekToMs -
                        (it.positionMs + (System.nanoTime() - it.receivedMonoNs) / 1_000_000)
                }
                com.jointheparty.app.debug.DebugLog.log(
                    "CORRECTION → seek ${event.seekToMs}ms (jump ${jumpMs ?: "?"}ms)",
                )
                spotify?.seekTo(event.seekToMs)
            }
        }
    }

    /**
     * NAT-06: runs one recognition pass end-to-end — [RecognitionProvider
     * .recognizeOnce], submit the resulting fix to [SyncEngine
     * .submitRecognitionFix], and — only while still in MATCHING — resolve
     * the fix's ISRC to a Spotify URI via [BackendClient
     * .resolveIsrcToSpotifyUri] and advance to AIMING via [onTrackResolved].
     * A fix with no ISRC, or a resolution that comes back NotFound/Failure,
     * simply leaves the session in MATCHING for the next pass to try again
     * — never a phase change, never an exception.
     *
     * [recognitionInFlight] rejects a second concurrent call outright (see
     * its declaration for why two triggers can race here).
     */
    /**
     * The shell samples on its own cadence only until SyncCore accepts a
     * fix and takes over scheduling via SC_EVT_REQUEST_FIX. Covers the
     * AIMING/CONVERGING window where the first fix was discarded for want
     * of a player timeline.
     */
    /** True when the fix's position is >5 s from our playback — a genuinely
     * different song, not an alternate release of the current one. */
    private fun isOffsetWildlyOff(fix: RecognitionProvider.RecognitionFixResult): Boolean {
        val ps = spotify?.lastKnownPlayerState ?: return true
        val projected =
            ps.positionMs + (fix.captureMonoNs - ps.receivedMonoNs) / 1_000_000
        return kotlin.math.abs(projected - fix.matchOffsetMs) > 5_000
    }

    private fun shouldKeepSampling(): Boolean {
        if (firstEstimateSeen) return false
        // Bounded: every pass is a paid recognition request, so a session
        // that never converges must not bill forever (caught by the unit
        // suite as 9.2M virtual-time calls).
        if (samplingAttempts >= MAX_SAMPLING_ATTEMPTS) return false
        return _syncState.value.phase in setOf(
            SessionPhase.MATCHING,
            SessionPhase.AIMING,
            SessionPhase.CONVERGING,
        )
    }

    private fun runRecognitionPass() {
        val recognizer = recognition ?: return
        if (!recognitionInFlight.compareAndSet(false, true)) return
        samplingAttempts += 1

        viewModelScope.launch(dispatcher) {
            var retry = false
            try {
                val fix = recognizer.recognizeOnce()
                if (fix == null) {
                    retry = shouldKeepSampling()
                    // UX audit #2: hitting the sampling cap used to leave a
                    // zombie MATCHING screen — alive-looking, permanently
                    // deaf. Escalate honestly instead.
                    if (!retry && !firstEstimateSeen &&
                        samplingAttempts >= MAX_SAMPLING_ATTEMPTS &&
                        _syncState.value.phase == SessionPhase.MATCHING
                    ) {
                        com.jointheparty.app.debug.DebugLog.log(
                            "sampling cap reached with no match → error state",
                        )
                        transition(SessionPhase.LOST)
                        transition(SessionPhase.ERROR)
                    }
                    return@launch
                }
                // FIELD DEBUG: shell-side replica of the engine's sync
                // measurement (z = projected_local − match_offset). If this
                // disagrees with the engine's estimate line, the bias lives
                // in the engine's inputs/bookkeeping; if they agree, the
                // bias is real and lives in the audio path (ACR reference
                // point / window staleness / leakage).
                val ps = spotify?.lastKnownPlayerState
                if (ps != null) {
                    val shellProj =
                        ps.positionMs + (fix.captureMonoNs - ps.receivedMonoNs) / 1_000_000
                    com.jointheparty.app.debug.DebugLog.log(
                        "fixdbg: offset=${fix.matchOffsetMs} " +
                            "projLocal=$shellProj shellZ=${shellProj - fix.matchOffsetMs} " +
                            "(ps=${ps.positionMs}@-${(System.nanoTime() - ps.receivedMonoNs) / 1_000_000}ms " +
                            "capAge=${(System.nanoTime() - fix.captureMonoNs) / 1_000_000}ms)",
                    )
                }
                engine.submitRecognitionFix(
                    SyncCore.FixSource.SHAZAMKIT,
                    fix.matchOffsetMs,
                    fix.captureMonoNs,
                    fix.frequencySkew,
                    fix.confidence,
                )

                // Track resolution only matters while we're still aiming to
                // lock the track (matching → aiming, §2.4); a fix that
                // arrives after AIMING is still a useful sync-error
                // observation for SyncCore (submitted above) but shouldn't
                // re-resolve or re-transition — UNLESS it names a different
                // track: the room moved on. Fast-switch instead of waiting
                // for the engine's 2 s track-lost threshold.
                val currentUri = _syncState.value.track?.spotifyUri
                if (_syncState.value.phase == SessionPhase.MATCHING) {
                    resolveTrack(fix)
                } else if (fix.spotifyUri != null && currentUri != null &&
                    fix.spotifyUri != currentUri &&
                    isOffsetWildlyOff(fix)
                ) {
                    // Different URI alone is NOT a song change: ACR maps the
                    // same recording to different Spotify releases across
                    // fixes (observed: 'Blinding Lights' under 3 ids in one
                    // evening). Only switch when the offset ALSO disagrees
                    // — a real new song has an arbitrary position; an
                    // alternate release of the current song matches ours.
                    com.jointheparty.app.debug.DebugLog.log(
                        "room changed songs → re-aim '${fix.title}'",
                    )
                    transition(SessionPhase.LOST)
                    transition(SessionPhase.LISTENING)
                    onMatchInFlight()
                    resolveTrack(fix)
                }
                retry = shouldKeepSampling()
            } finally {
                recognitionInFlight.set(false)
                if (retry) {
                    viewModelScope.launch(dispatcher) {
                        delay(RECOGNITION_RETRY_MS)
                        if (shouldKeepSampling()) runRecognitionPass()
                    }
                }
            }
        }
    }

    /** Throttle for the field overlay: estimates arrive at ≤15 Hz. */
    private var lastEstimateLogMs = 0L

    /** Latest engine-measured error; consumed by wheel-commit rebasing. */
    @Volatile
    private var lastEstimateErrorMs: Double = 0.0

    /**
     * The engine-side setpoint, which can diverge from the user's wheel
     * value: each wheel commit REBASES it by the engine's own measured
     * error, declaring the user's by-ear alignment to be zero. Without this
     * the wheel and the correction loop fight (field, 2026-07-26): the ear
     * corrects audible error, the engine still measures its biased error
     * and seeks the user's tuning right back off.
     */
    private var engineNudgeMs: Double = 0.0

    /**
     * FIELD FIX (2026-07-24): true once SyncCore has ACCEPTED a fix and
     * emitted an estimate. Until then the engine has no schedule of its own
     * (SC_EVT_REQUEST_FIX is only armed by an accepted fix), so the shell
     * must keep sampling.
     *
     * Why the first fix is always discarded: recognition necessarily runs
     * BEFORE playback starts, so the estimator has no local timeline to
     * compare against and drops it. Observed in the field as a session
     * frozen in CONVERGING with no estimates at all.
     */
    private var firstEstimateSeen = false

    /** Shell-driven passes taken this session (see [MAX_SAMPLING_ATTEMPTS]). */
    private var samplingAttempts = 0

    private fun onSyncEstimate(event: SyncCore.Event.SyncEstimate) {
        // INT-02 field instrumentation: without this a real-speaker test
        // can't show whether the loop actually converges.
        firstEstimateSeen = true
        lastEstimateErrorMs = event.errorMs
        val now = System.currentTimeMillis()
        if (now - lastEstimateLogMs > 1000) {
            lastEstimateLogMs = now
            com.jointheparty.app.debug.DebugLog.log(
                "sync err=${"%.0f".format(event.errorMs)}ms " +
                    "drift=${"%.0f".format(event.driftPpm)}ppm " +
                    "conf=${"%.2f".format(event.confidence)}" +
                    if (event.converged) " LOCKED" else "",
            )
        }
        onSyncEstimateInternal(event)
    }

    private fun onSyncEstimateInternal(event: SyncCore.Event.SyncEstimate) {
        // An accepted estimate clears any transient reject hint (INT-04:
        // the self-hearing banner disappears once real fixes flow again).
        if (_syncState.value.lastRejectReason != null)
            _syncState.update { it.copy(lastRejectReason = null) }
        val phase = _syncState.value.phase
        when {
            // converging/drifting → locked
            event.converged && (phase == SessionPhase.CONVERGING || phase == SessionPhase.DRIFTING) ->
                transition(SessionPhase.LOCKED)
            // locked → drifting (estimate leaves the deadband)
            !event.converged && phase == SessionPhase.LOCKED ->
                transition(SessionPhase.DRIFTING)
        }
    }

    /**
     * SC_EVT_TRACK_LOST: any → lost, then auto-restart to listening — unless
     * this is the 3rd consecutive loss, in which case → error
     * (technical-requirements.md §2.4).
     */
    private fun onTrackLost() {
        transition(SessionPhase.LOST)
        consecutiveLosses += 1
        if (consecutiveLosses < 3) {
            transition(SessionPhase.LISTENING)
            // FIELD FIX (2026-07-25, "does not handle the next song"): the
            // auto-restart changed phase but never re-armed recognition —
            // the bootstrap only ran from the Join tap, so after the room
            // moved to the next track the app sat deaf in LISTENING
            // forever. Re-bootstrap exactly like a fresh Join.
            if (recognition != null) {
                firstEstimateSeen = false
                samplingAttempts = 0
                onMatchInFlight()
                viewModelScope.launch(dispatcher) {
                    delay(RECOGNITION_RETRY_MS / 2)
                    runRecognitionPass()
                }
            }
        } else {
            transition(SessionPhase.ERROR)
        }
    }

    // ---- Transition allowlist (technical-requirements.md §2.4) ------------

    /**
     * The single gate every phase change passes through. Illegal
     * transitions are ignored silently — no exception, no log — per UI-02.
     * Returns whether the transition was applied.
     */
    private fun transition(to: SessionPhase): Boolean {
        val from = _syncState.value.phase
        if (!isLegalTransition(from, to)) return false
        if (to == SessionPhase.LOCKED) consecutiveLosses = 0
        _syncState.update { it.copy(phase = to) }
        com.jointheparty.app.debug.DebugLog.log("phase: $from → $to")
        return true
    }

    private fun isLegalTransition(from: SessionPhase, to: SessionPhase): Boolean {
        if (from == to) return false
        return when (to) {
            // startListening(): idle/lost/error → listening; this is also
            // the lost-track auto-restart target (lost → listening), and the
            // gates may proceed too — the §6.4 "keep identifying songs"
            // recognition-only degradation (AUTH-05).
            SessionPhase.LISTENING ->
                from == SessionPhase.IDLE || from == SessionPhase.LOST ||
                    from == SessionPhase.ERROR || from == SessionPhase.NEEDS_SPOTIFY ||
                    from == SessionPhase.NEEDS_PREMIUM
            SessionPhase.MATCHING -> from == SessionPhase.LISTENING
            SessionPhase.AIMING -> from == SessionPhase.MATCHING
            SessionPhase.CONVERGING -> from == SessionPhase.AIMING
            SessionPhase.LOCKED -> from == SessionPhase.CONVERGING || from == SessionPhase.DRIFTING
            SessionPhase.DRIFTING -> from == SessionPhase.LOCKED
            // any → lost / needsSpotify / needsPremium (§2.4); reset() is an
            // any → idle escape hatch not spelled out in the table but
            // implied by "user taps Join" needing somewhere to start from.
            SessionPhase.LOST,
            SessionPhase.NEEDS_SPOTIFY,
            SessionPhase.NEEDS_PREMIUM,
            SessionPhase.IDLE,
            -> true
            // Only reachable via the exhausted lost-track auto-restart (3rd
            // consecutive TrackLost while already lost) — never a direct
            // shell intent.
            SessionPhase.ERROR -> from == SessionPhase.LOST
        }
    }

    override fun onCleared() {
        engine.close()
    }

    companion object {
        /** Builds a [SessionViewModel] wired to a real [SyncCore] + DataStore. */
        class Factory(private val context: Context) : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                // AUTH-03/04: no backend is deployed yet, so this is the
                // mock-mode HttpBackendClient(baseUrl = null) — see its
                // class doc for the swap procedure once one exists.
                val backendClient = HttpBackendClient(baseUrl = null)
                val engine = SyncCore(deadbandMs = ENGINE_DEADBAND_MS)
                // ACRCloud credentials come from the gitignored
                // android/local.properties via BuildConfig (acr.host /
                // acr.key / acr.secret) — see app/build.gradle.kts. Empty
                // (unconfigured) keeps recognition safely inert.
                val acrConfig = ACRCloudProvider.Config(
                    host = com.jointheparty.app.BuildConfig.ACR_HOST,
                    accessKey = com.jointheparty.app.BuildConfig.ACR_KEY,
                    accessSecret = com.jointheparty.app.BuildConfig.ACR_SECRET,
                )
                return SessionViewModel(
                    engine = engine,
                    nudgeStore = DataStoreNudgeStore(context.applicationContext),
                    recognition = ACRCloudProvider(
                        config = acrConfig.takeIf { it.accessKey.isNotEmpty() },
                        source = EnginePcmWindowSource(engine),
                    ),
                    backend = backendClient,
                    chirp = AudioTrackChirpPlayer(),
                    spotify = AppRemoteSpotifyController(
                        context = context.applicationContext,
                        engine = engine,
                    ),
                ) as T
            }
        }
    }
}
