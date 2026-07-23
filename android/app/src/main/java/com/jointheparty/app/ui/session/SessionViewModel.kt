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
) : ViewModel() {

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /**
     * Pass-through meter stream — bypasses [syncState] entirely (two-stream
     * rule). Collect only inside the meter composable, per UI-03.
     */
    val meterFrames: Flow<MeterFrame> = engine.meterFrames.map { it.toMeterFrame() }

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
        if (!engine.startCapture()) return
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
            runRecognitionPass()
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
            _syncState.update {
                SyncState(routeId = it.routeId, routeName = it.routeName, nudgeMs = it.nudgeMs)
            }
        }
    }

    /** Wheel commit: push to the engine, persist per-route, reflect in state. */
    fun onNudgeCommitted(trimMs: Int) {
        val routeId = _syncState.value.routeId
        engine.setUserNudgeMs(trimMs)
        _syncState.update { it.copy(nudgeMs = trimMs) }
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
            is SyncCore.Event.FixRejected ->
                _syncState.update { it.copy(lastRejectReason = event.reason) }
            SyncCore.Event.TrackLost -> onTrackLost()
            // NAT-06: the ONLY recurring recognition trigger, per the
            // no-free-running-recognition-loops rule (technical-
            // requirements.md §3.2). runRecognitionPass() itself no-ops
            // when `recognition` is null.
            SyncCore.Event.RequestFix -> runRecognitionPass()
            is SyncCore.Event.CalibrationResult -> onCalibrationResult(event)
            is SyncCore.Event.Correction,
            -> Unit // not phase-relevant to the session state machine
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
    private fun runRecognitionPass() {
        val recognizer = recognition ?: return
        val backendClient = backend ?: return
        if (!recognitionInFlight.compareAndSet(false, true)) return

        viewModelScope.launch(dispatcher) {
            try {
                val fix = recognizer.recognizeOnce() ?: return@launch
                engine.submitRecognitionFix(
                    SyncCore.FixSource.SHAZAMKIT,
                    fix.matchOffsetMs,
                    fix.captureMonoNs,
                    fix.frequencySkew,
                    fix.confidence,
                )

                // ISRC→URI resolution only matters while we're still aiming
                // to lock the track (matching → aiming, §2.4); a fix that
                // arrives after AIMING is still a useful sync-error
                // observation for SyncCore (submitted above) but shouldn't
                // re-resolve or re-transition.
                if (_syncState.value.phase != SessionPhase.MATCHING) return@launch
                val isrc = fix.isrc ?: return@launch

                when (val resolution = backendClient.resolveIsrcToSpotifyUri(isrc)) {
                    is TrackResolution.Resolved ->
                        onTrackResolved(
                            TrackInfo(
                                spotifyUri = resolution.spotifyUri,
                                isrc = isrc,
                                title = fix.title ?: "Unknown",
                                artist = fix.artist ?: "",
                                durationMs = 0L,
                            ),
                        )
                    TrackResolution.NotFound,
                    is TrackResolution.Failure,
                    -> Unit // stay in MATCHING; the next pass may resolve
                }
            } finally {
                recognitionInFlight.set(false)
            }
        }
    }

    private fun onSyncEstimate(event: SyncCore.Event.SyncEstimate) {
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
                val engine = SyncCore()
                // ══════════════════════════════════════════════════════════
                // PASTE YOUR ACRCLOUD TRIAL CREDENTIALS HERE (from
                // console.acrcloud.com → your project → Access). Until all
                // three placeholders are replaced, recognition stays
                // safely inert (the guard below passes config = null).
                // Debug-build convenience only — production proxies these
                // through the backend (see ACRCloudProvider's KDoc).
                // ══════════════════════════════════════════════════════════
                val acrConfig = ACRCloudProvider.Config(
                    host = "YOUR_ACR_HOST",        // e.g. identify-eu-west-1.acrcloud.com
                    accessKey = "YOUR_ACR_KEY",
                    accessSecret = "YOUR_ACR_SECRET",
                )
                return SessionViewModel(
                    engine = engine,
                    nudgeStore = DataStoreNudgeStore(context.applicationContext),
                    recognition = ACRCloudProvider(
                        config = acrConfig.takeIf { it.accessKey != "YOUR_ACR_KEY" },
                        source = EnginePcmWindowSource(engine),
                    ),
                    backend = backendClient,
                    chirp = AudioTrackChirpPlayer(),
                ) as T
            }
        }
    }
}
