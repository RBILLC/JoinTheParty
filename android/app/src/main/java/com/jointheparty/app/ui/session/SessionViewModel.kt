package com.jointheparty.app.ui.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.core.SyncEngine
import com.jointheparty.app.data.DataStoreNudgeStore
import com.jointheparty.app.data.NudgeStore
import com.jointheparty.app.ui.model.MeterFrame
import com.jointheparty.app.ui.model.toMeterFrame
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
)

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

    /** idle/lost/error → listening (user taps Join, or manual retry). */
    fun startListening() {
        transition(SessionPhase.LISTENING)
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

    /** any → needsSpotify (App Remote couldn't find the Spotify app). */
    fun onSpotifyMissing() {
        transition(SessionPhase.NEEDS_SPOTIFY)
    }

    /** any → needsPremium (seek rejected for a non-Premium account). */
    fun onPremiumRequired() {
        transition(SessionPhase.NEEDS_PREMIUM)
    }

    /** any → idle: user-initiated escape hatch, e.g. leaving the session. */
    fun reset() {
        if (transition(SessionPhase.IDLE)) {
            consecutiveLosses = 0
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
            val latencyPrior = nudgeStore.commandLatencyFor(routeId)
            engine.setUserNudgeMs(trim)
            engine.setOutputRoute(route, latencyPrior)
            _syncState.update { it.copy(routeId = routeId, routeName = routeName, nudgeMs = trim) }
        }
    }

    // ---- Engine-driven transitions -----------------------------------------

    private fun onEngineEvent(event: SyncCore.Event) {
        when (event) {
            is SyncCore.Event.SyncEstimate -> onSyncEstimate(event)
            is SyncCore.Event.FixRejected ->
                _syncState.update { it.copy(lastRejectReason = event.reason) }
            SyncCore.Event.TrackLost -> onTrackLost()
            is SyncCore.Event.Correction,
            SyncCore.Event.RequestFix,
            is SyncCore.Event.CalibrationResult,
            -> Unit // not phase-relevant to the session state machine
        }
    }

    private fun onSyncEstimate(event: SyncCore.Event.SyncEstimate) {
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
            // the lost-track auto-restart target (lost → listening).
            SessionPhase.LISTENING ->
                from == SessionPhase.IDLE || from == SessionPhase.LOST || from == SessionPhase.ERROR
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
                return SessionViewModel(
                    engine = SyncCore(),
                    nudgeStore = DataStoreNudgeStore(context.applicationContext),
                ) as T
            }
        }
    }
}
