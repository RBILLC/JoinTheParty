package com.jointheparty.app.ui.session

import com.jointheparty.app.backend.BackendClient
import com.jointheparty.app.backend.TrackResolution
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.core.SyncEngine
import com.jointheparty.app.audio.ChirpPlayer
import com.jointheparty.app.audio.StreamVolumeController
import com.jointheparty.app.audio.TonePlayer
import com.jointheparty.app.data.CalibrationProfile
import com.jointheparty.app.data.NudgeStore
import com.jointheparty.app.recognition.RecognitionProvider
import com.jointheparty.app.spotify.AppRemoteSpotifyController
import com.jointheparty.app.spotify.SpotifyController
import com.jointheparty.app.ui.theme.DT
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.jointheparty.app.ui.model.MeterFrame
import com.jointheparty.app.ui.model.toMeterFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // CAL-08: which device-review pane (if any) the calibration sheet is
    // showing. Plain state, not a stream — see [DeviceReviewPane]'s doc
    // comment for the two-stream-rule reasoning.
    val deviceReview: DeviceReviewPane = DeviceReviewPane.Hidden,
    // CAL-09: non-null exactly while an unknown route's first-contact gate
    // is up. See [FirstContactGateState]'s doc comment for the load-bearing
    // "gates playback's aim, never recognition" distinction.
    val firstContactGate: FirstContactGateState? = null,
)

/** First pass waits for the PCM window to fill (source needs ≥3 s). */
private const val INITIAL_CAPTURE_FILL_MS = 4_000L

/** Pre-first-fix retry cadence while MATCHING (post-fix cadence is engine-driven). */
private const val RECOGNITION_RETRY_MS = 6_000L

/** ~2 minutes of shell-driven sampling before giving up (quota guard). */
private const val MAX_SAMPLING_ATTEMPTS = 20

/**
 * Below this confidence a wheel commit skips the error rebase (§4.4).
 *
 * FULL-LOOP TEST (2026-07-26): 0.2 caused a runaway — each commit seeks,
 * seek inflates estimator variance (settling), and the next commit rebased
 * against the settling transient (conf ~0.3): setpoint −176 → −3133 in five
 * spins → forced LOST. Settled estimates log conf 0.79–0.83, settling
 * transients 0.28–0.32; 0.6 splits the two populations cleanly.
 */
private const val REBASE_MIN_CONFIDENCE = 0.6f

/**
 * Pause this far before our track's end. Enough margin that Spotify never
 * reaches the auto-advance, small enough that no audible music is lost —
 * the last fraction of a second of a fading outro.
 */
private const val END_OF_TRACK_LEAD_MS = 400L

/** First identify attempt after losing the track (§ re-acquire speed). */
private const val REACQUIRE_FIRST_PASS_MS = 1_000L

/** Settle time after pausing our own output before the mic is worth sampling. */
private const val AUTO_ADVANCE_QUIET_MS = 700L

/** Backstop: one commit may absorb at most this much measured error. */
private const val REBASE_MAX_MS = 600.0

/** Aim-verification loop (arch §6.2 coarse aim, made deterministic). */
private const val MAX_AIM_ATTEMPTS = 4
private const val AIM_VERIFY_DELAY_MS = 900L
private const val AIM_TOLERANCE_MS = 3_000L

/**
 * GRD-01 (technical-requirements.md §2.13): expected-URI self-play latch.
 * Every `controller.play(uri)` call latches that uri for this long — a
 * bounded set-membership test, not a blanket "ignore auto-advance for N ms"
 * timer (see [SessionViewModel.consumeSelfPlayLatch]) — so a late
 * player-state confirmation of a URI we ourselves just commanded is never
 * mistaken for a genuine Spotify auto-advance, even once a newer
 * re-resolution has already moved [SyncState.track] on. FT9's own churn
 * measured three self-issued restarts inside 2.8 s; 5 s covers that with
 * margin. Named per policy.h's convention, materialized as a Kotlin
 * SCREAMING_SNAKE_CASE constant — mirrors the existing `ENGINE_DEADBAND_MS`
 * precedent (SessionGraph.kt).
 */
private const val SELF_PLAY_LATCH_WINDOW_MS = 5000L

/**
 * GRD-01: bounded ring capacity for the self-play latch, oldest evicted
 * first — sized off FT9's observed churn rate (bursts of 3 restarts in
 * under 3 s), generous enough for a burst, still finite.
 */
private const val SELF_PLAY_LATCH_MAX_ENTRIES = 4

/**
 * IDC-01 (technical-requirements.md §2.14): identity corroboration gate.
 * Mirrors §2.7's `confirm_min_fixes` (3) exactly.
 */
private const val IDENT_CONFIRM_MIN_FIXES = 3

/**
 * IDC-01: reuses `kRoomContinuityGateMs` (synccore.cpp's CORE-06 tolerance)
 * rather than inventing a new figure for "advancing ~wall-clock."
 */
private const val IDENT_CONFIRM_OFFSET_AGREE_MS = 500L

/**
 * IDC-01: matches §2.8's `large_pending_max_age_ns` (30 s) precedent for
 * "how long unconfirmed evidence may sit before it expires."
 */
private const val IDENT_CORROB_MAX_AGE_MS = 30_000L

/**
 * CAL-04: cadence for [SessionViewModel.maybeSampleReferee]. The referee
 * (`sc_sample_latency_residual`) autocorrelates a 12 s post-AEC capture
 * history (technical-requirements.md §2.6) — sampling much faster than that
 * window mostly re-analyzes the same audio for no new information, and each
 * call also flips AEC off and back on for the sampled window (CAL-03), so a
 * tight cadence adds churn without adding signal. 20 s sits in the
 * spec-suggested 15–30 s band: fresh enough that 3 agreeing windows (the
 * agreement rule below) land within a normal few-minute LOCKED stretch,
 * without meaningfully taxing the AEC toggle or CPU.
 */
private const val REFEREE_SAMPLE_INTERVAL_MS = 20_000L

/**
 * CAL-04 aggregation rule (technical-requirements.md §2.6): a residual is
 * only committed to a profile once this many consecutive valid windows
 * agree. `peak_ratio > 4.0` alone is an extreme-value statistic over the
 * ~118k-lag search range `sc_sample_latency_residual` scans (40..2500 ms at
 * 48 kHz) — across that many candidate lags a spurious peak clears a fixed
 * ratio threshold on noise often enough that a single pass isn't
 * trustworthy evidence. A spurious peak lands at a DIFFERENT lag on each
 * independent 12 s window (it's tracking whatever transient happened to
 * correlate that window), while a genuine echo of our own output sits at
 * the same acoustic lag every time — so agreement across windows, not
 * peak_ratio by itself, is what actually protects the profile from a
 * one-off false positive.
 */
private const val REFEREE_AGREEMENT_COUNT = 3

/**
 * Tolerance for two referee residuals to count as "the same measurement"
 * (CAL-04). Matches the 25 ms trim-promotion tolerance (tech-req §2.6,
 * CAL-10) and the engine's own correction deadband — the resolution below
 * which the estimator itself no longer distinguishes error.
 */
private const val REFEREE_AGREEMENT_TOLERANCE_MS = 25

/**
 * DSP-03b (technical-requirements.md §2.12): nominal duck depth. Target
 * index selection looks for the largest volume index whose dB is at most
 * this far below the original — see [SessionViewModel.onActiveDuck].
 */
private const val DUCK_TARGET_DB = 6f

/** Seed confidence for a fresh chirp-measured profile (CAL-04): a GCC-PHAT
 * round-trip is a direct correlator measurement, the strongest evidence
 * source calibration has — as opposed to BY_EAR (human alignment, ±30 ms
 * stated accuracy) or ESTIMATED (no measurement at all). */
private const val MEASURED_CALIBRATION_CONFIDENCE = 1.0f

/**
 * CAL-07: seed confidence for a fresh by-ear profile — weaker evidence than
 * a correlator round-trip (above), but a real human judgment against a
 * reference tone, not a placeholder. Stronger than ESTIMATED's "no
 * measurement at all," weaker than MEASURED's ground truth; the caliper
 * itself doesn't care (both render as solid, real ticks — ui-ux §6.5), this
 * only seeds the stored profile's own bookkeeping field.
 */
private const val BY_EAR_CALIBRATION_CONFIDENCE = 0.7f

/**
 * CAL-09 (technical-requirements.md §2.6 "ESTIMATED default"): "Centered on
 * the common case (Bluetooth SBC/AAC and the deep-buffer speaker path), one
 * value for every route class — v1 does not attempt per-codec detection."
 * Written when the first-contact gate is declined.
 */
private const val ESTIMATED_DEFAULT_LATENCY_MS = 150

/**
 * CAL-10 cooling-off period (technical-requirements.md §2.6 "Trim
 * promotion"): 7 days, compared against a stored decline timestamp — see
 * [SessionViewModel.declineTrimPromotion]'s doc comment for why this is
 * never a running timer.
 */
private const val TRIM_PROMOTION_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

/**
 * CAL-10 promotion floor (technical-requirements.md §2.6 / ui-ux §6.5's
 * `DT.Calibration` table): `|median|` must clear this to promote — above
 * the engine's own [REFEREE_AGREEMENT_TOLERANCE_MS]-style 25 ms correction
 * deadband, so a promotion is never offered for scatter the estimator
 * wouldn't even act on in the first place.
 */
private const val TRIM_PROMOTION_FLOOR_MS = 30

/**
 * CAL-10 detection rule (technical-requirements.md §2.6 "Trim promotion"):
 * the most recent [DT.Calibration.trimPromotionSampleCount] (3) wheel
 * commits must ALL fall within [DT.Calibration.trimPromotionToleranceMs]
 * (25 ms) of their own median, AND that median's magnitude must clear
 * [TRIM_PROMOTION_FLOOR_MS] (30 ms) — a strict `>`, not `>=`: a median
 * sitting exactly at the floor is exactly as unpromotable as one below it.
 *
 * The 25 ms tolerance is deliberately not tighter than the engine's own
 * correction deadband: the trim comes from a human ear, whose own
 * repeatability is only ±[DT.Calibration.byEarAccuracyMs] (30 ms) —
 * demanding the wheel agree with itself more precisely than the very
 * instrument doing the judging would mean this rule could never fire
 * (`DT.Calibration.trimPromotionToleranceMs`'s own token doc, ui-ux §6.5).
 *
 * `internal`, not `private`, and a plain function rather than a method —
 * same reasoning as [com.jointheparty.app.ui.components.provenanceLabel]:
 * lets the threshold math be pinned directly by a JVM test, no
 * ViewModel/store/coroutine scaffolding required.
 */
/**
 * CFX-09 (technical-requirements.md §2.6 "Shelf ordering"): the shell-side
 * half of the ordering contract — [NudgeStore.allCalibrationProfiles]
 * already returns a deterministic updatedAtMs-descending base order (the
 * store's own half, since it has no notion of "connected"); this moves the
 * profile matching [connectedRouteId], if any, to the front of that list
 * without disturbing the relative order of the rest. A no-op (returns
 * `this` unchanged) when nothing matches, or the match is already first.
 *
 * Index-based rather than a list-remove-by-value — [CalibrationProfile] is
 * a data class, so removing "by value" would risk touching the wrong
 * element if two entries were ever structurally equal.
 *
 * `internal`, not `private` — same "extract for testability" convention as
 * [trimPromotionMedian]: a JVM test can pin this reordering directly, no
 * ViewModel/store/coroutine scaffolding required.
 */
internal fun List<CalibrationProfile>.withConnectedFirst(connectedRouteId: String): List<CalibrationProfile> {
    val index = indexOfFirst { it.routeId == connectedRouteId }
    if (index <= 0) return this
    val reordered = toMutableList()
    val connected = reordered.removeAt(index)
    reordered.add(0, connected)
    return reordered
}

internal fun trimPromotionMedian(commits: List<Int>): Int? {
    val sampleCount = DT.Calibration.trimPromotionSampleCount.roundToInt()
    if (commits.size < sampleCount) return null
    val window = commits.takeLast(sampleCount)
    val median = window.sorted()[window.size / 2]
    val tolerance = DT.Calibration.trimPromotionToleranceMs.roundToInt()
    if (window.any { kotlin.math.abs(it - median) > tolerance }) return null
    if (kotlin.math.abs(median) <= TRIM_PROMOTION_FLOOR_MS) return null
    return median
}

/** INT-03/CAL-07: calibration lifecycle for the active route (arch §6.4, ui-ux §6.5). */
sealed interface CalibrationState {
    data object Idle : CalibrationState
    data object Running : CalibrationState
    data class Success(val latencyMs: Int) : CalibrationState
    data object Failed : CalibrationState

    // CFX-01 (tech-req §2.6 "Route attribution"): the connected route
    // changed while a chirp was armed-and-playing or a tone-match was
    // Running. The in-flight measurement is invalidated, not relabelled
    // against the new route — this is a distinct terminal from [Idle] so
    // the sheet can name what happened ("Device changed — calibration
    // cancelled.") instead of silently resetting, which the spec calls
    // out as reading like a stuck button. [CalibrationSheet] offers
    // "Start calibration" from here, which (re-)starts against whatever
    // route is now connected — the sheet is already re-scoped to it by
    // the time this state is visible.
    data object Cancelled : CalibrationState

    // CAL-07: tone-match (by ear). Reached automatically whenever the
    // chirp's 8 s arm timeout elapses with no detection — that's the
    // existing [Failed] state above (SyncCore.Event.CalibrationResult's
    // valid=false path, unchanged), which now unconditionally offers the
    // Quiet "Try by ear instead" exit into [ByEarIdle] (ui-ux §6.5's
    // "Quiet exit on Failed is new: By ear is a fallback on every route,
    // not just headphones" — no device-class branch anywhere in that
    // wiring). [tryByEarInstead] is also callable directly, from any
    // state, satisfying "available on any route" without a Failed
    // attempt first.
    //
    // No ByEarFailed: "every completed attempt produces a usable value; a
    // bad result gets fixed via 'Calibrate again,' not a retry state."
    data object ByEarIdle : CalibrationState
    data object ByEarRunning : CalibrationState
    data class ByEarSuccess(val latencyMs: Int) : CalibrationState
}

/**
 * CAL-08 (ui-ux §6.5): the device shelf/detail review panes, layered
 * independently of [CalibrationState] — browsing known devices never
 * implies a measurement is in flight. [CalibrationSheet] renders whichever
 * of these isn't [Hidden] IN PLACE of the guided-calibration content; both
 * live in the same `ModalBottomSheet` instance (ui-ux §6.5: "shelf/detail/
 * guided-calibration are panes the existing calibration sheet swaps, not
 * separate pushed screens/routes").
 *
 * Two-stream rule (technical-requirements.md §2.1/§2.3): this is
 * low-frequency, deliberately-opened state — a plain [NudgeStore] read on
 * open/back, never a stream — so it belongs on [SyncState] exactly like
 * [CalibrationState] does, nowhere near [SessionViewModel.meterFrames]/
 * [SessionViewModel.inputLevel].
 */
sealed interface DeviceReviewPane {
    data object Hidden : DeviceReviewPane
    data class Shelf(val profiles: List<CalibrationProfile>) : DeviceReviewPane

    /**
     * @param trimPromotionMedianMs CAL-10 seam: non-null exactly when this
     * device's recent wheel-commit history qualifies for promotion,
     * computed once on open (see [SessionViewModel.selectDevice]) — never a
     * live recompute while the pane sits open (two-stream rule, matching
     * [openDeviceShelf]'s own "a plain one-shot suspend read, not a
     * stream"). Rendered through [com.jointheparty.app.ui.components
     * .DeviceDetail]'s existing `TrimPromotionBannerState` seam.
     * @param trimPromotionAccepted mirrors `TrimPromotionBannerState
     * .accepted` — true once "Use this offset" is tapped, so the "Folded
     * into the calibration" confirmation replaces the accept/decline
     * actions IN PLACE rather than needing a fresh shelf/detail reload.
     * @param driftDismissed CFX-08 (ui-ux §6.5 "Both Quiet actions dismiss
     * in place"): true once the drift banner's "Later" has been tapped for
     * THIS view of the pane — mirrors [trimPromotionAccepted]'s shape
     * exactly. [DeviceDetail] shows the drift banner only while
     * `profile.drifted && !driftDismissed`; dismissing never edits
     * `profile.drifted` itself (the referee's finding is still true), it
     * only stops re-showing the banner in this already-open pane. A fresh
     * [SessionViewModel.selectDevice] always starts a new `Detail` with this
     * defaulted back to `false`, so the banner is offered again on the next
     * deliberate visit — matching drift's own "visited deliberately, no
     * push/toast/badge" restraint.
     */
    data class Detail(
        val profile: CalibrationProfile,
        val trimPromotionMedianMs: Int? = null,
        val trimPromotionAccepted: Boolean = false,
        val driftDismissed: Boolean = false,
    ) : DeviceReviewPane
}

/**
 * CAL-09 (ui-ux §6.5 "First-contact gate" / tech-req §2.6 "Scope"): raised
 * for an unknown routeId.
 *
 * THE LOAD-BEARING DISTINCTION (tech-req §2.6's own "Scope" paragraph):
 * this gates PLAYBACK'S AIM ONLY, never recognition. Recognition reads the
 * mic, not the speaker — an unknown/uncalibrated device identifies the
 * room's song exactly as well as a calibrated one; it just can't aim the
 * initial seek at the right spot yet, and that's the ONLY thing declining
 * costs. [SessionViewModel.startListening]/[SessionViewModel
 * .runRecognitionPass] never read [SyncState.firstContactGate] anywhere —
 * that omission (not a guard that skips it) IS the enforcement.
 *
 * CFX-06 (tech-req §2.6 "Gate copy must not pre-commit to a route class"):
 * formerly carried a `variant: FirstContactVariant` (ACOUSTIC/HEADPHONE)
 * branched on [SyncCore.Route] — removed along with `FirstContactVariant`
 * and `firstContactVariant()` entirely. The gate cannot know
 * acoustic-capability in advance (only running the chirp can), so there is
 * now exactly one route-neutral copy set for every route (ui-ux §6.5's
 * corrected "First-contact gate" section) — no field left to branch on.
 */
data class FirstContactGateState(
    val routeId: String,
    val deviceName: String,
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
    // NAT-06: both default null so every existing call site (and every
    // existing test's FakeSyncEngine-only construction) compiles and
    // behaves unchanged — runRecognitionPass() and the startListening()
    // bootstrap both no-op when recognition is null.
    private val recognition: RecognitionProvider? = null,
    private val backend: BackendClient? = null,
    private val chirp: ChirpPlayer? = null,
    // CAL-07: the by-ear tone-match reference tone. Null in unit tests,
    // same convention as [chirp] — the by-ear functions below no-op the
    // audio call and only drive [SyncState.calibration].
    private val tonePlayer: TonePlayer? = null,
    // INT-02: the playback half of the loop. Null in unit tests.
    private val spotify: SpotifyController? = null,
    // DSP-03b (technical-requirements.md §2.12): the volume-duck actuator's
    // AudioManager seam. Null in unit tests (StreamVolumeController isn't
    // JVM-testable against a real AudioManager) and on pre-API-28 devices
    // (see AudioManagerStreamVolumeController's doc comment) — same
    // defaults-to-null convention as [spotify]/[chirp]/[tonePlayer]; onActiveDuck
    // no-ops whenever this is null.
    private val volumeController: StreamVolumeController? = null,
    // INT-06a (technical-requirements.md §2.5): the session's lifetime
    // anchor, owned by SessionGraph — replaces the former `viewModelScope`.
    // Defaults from `dispatcher` (not a bare Dispatchers.Default) so the JVM
    // unit tests, which construct positionally with a shared
    // StandardTestDispatcher and never pass this parameter, get a scope
    // driven by that same test scheduler.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
) {

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /**
     * Pass-through meter stream — bypasses [syncState] entirely (two-stream
     * rule). Collect only inside the meter composable, per UI-03.
     */
    val meterFrames: Flow<MeterFrame> = engine.meterFrames.map { it.toMeterFrame() }

    /**
     * CAL-06: pass-through of [SyncEngine.inputLevel] — same high-frequency
     * stream family as [meterFrames] (technical-requirements.md §2.1), so it
     * is bypassed here exactly the same way: never collected into
     * [syncState], never observed by the session screen root. Collect only
     * inside the phase-word composable that drives its opacity in
     * LISTENING/MATCHING (ui-ux §6.1 "Before the meter").
     */
    val inputLevel: Flow<Float> = engine.inputLevel()

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
     * GRD-01 concurrency fix (GitHub #32, reopened by field-test-10: FT10
     * hit a FATAL `IndexOutOfBoundsException` in [consumeSelfPlayLatch] plus
     * a double guardian fire in [onSpotifyAutoAdvanced], both real-world-only
     * races invisible to the JVM suite's single-threaded scheduler).
     * [dispatcher] defaults to [Dispatchers.Default] — a genuinely
     * multi-threaded pool — and this class launches many coroutines on it
     * ([playerStateWatcher]'s collector, [latchSelfPlay]'s per-entry expiry
     * job, [runRecognitionPass]'s fast-switch branch, the single
     * `engine.events` collector, [aimUntilLanded]'s give-up→[onTrackLost]
     * path). Any of them can land on a different worker thread, so a plain
     * read-then-write of one of this class's shared mutable session fields
     * — [selfPlayLatch], [autoAdvanceHandled], the IDC-01 corroboration
     * fields ([identCorrobArmed]/[identStreakCount]/[identStreakUri]/etc.),
     * [consecutiveLosses], [endOfTrackJob], and [transition]'s own
     * from→to legality check — is a genuine TOCTOU race, not a style nit.
     * Every function that touches one of those synchronizes on this
     * monitor for its critical section.
     *
     * Plain JVM `synchronized`, not a coroutine [kotlinx.coroutines.sync
     * .Mutex]: most guarded call sites ([transition], every shell-driven
     * intent — [startListening], [onTrackResolved], [reset], ... —
     * [onSpotifyAutoAdvanced]) are ordinary non-suspend functions called
     * synchronously off the UI thread; a coroutine `Mutex` would force
     * every one of them to become `suspend`, rippling into Compose's
     * calling convention for no benefit, since none of these critical
     * sections themselves suspend or block on I/O. `synchronized` is
     * reentrant per thread, so the nesting this file already has
     * ([onTrackLost] → [transition] / [armIdentCorroboration],
     * [onSpotifyAutoAdvanced] → [stopFollowingAndRelisten] → [transition])
     * is free. No guarded block ever suspends inside the monitor (a
     * `scope.launch(...)` call is fine — launching is not suspending), so
     * there is no suspend-while-holding-the-monitor deadlock risk either.
     * Under the JVM unit suite's single-threaded
     * [kotlinx.coroutines.test.StandardTestDispatcher] the monitor is never
     * contended, so virtual-time scheduling (`advanceUntilIdle`/
     * `runCurrent`) is completely unaffected — this is purely a
     * real-multi-thread-only guard (verified by the concurrent stress
     * tests in `SessionViewModelTest.kt`).
     */
    private val sessionLock = Any()

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
        scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
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
        // The session owns the mic from here; a calibration started later is
        // a guest and must not close it (see releaseCalibrationCapture).
        if (capStarted) {
            captureRunning = true
            calibrationOwnsCapture = false
        }
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
            scope.launch(dispatcher) {
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
        scope.launch(dispatcher) {
            // CAL-09: calibration gates PLAYBACK on a device we have never
            // measured — never recognition, which reads the mic and is
            // unaffected by output latency. So we keep listening and keep
            // identifying the room; we just don't aim through an output whose
            // delay is unknown, because that plays audibly wrong and then
            // corrects, which is worse than starting a beat late.
            //
            // Waiting for the gate to clear rather than checking once: the
            // scenario this exists for is a speaker paired MID-session, where
            // route detection lands after the track is already resolved.
            // Declining is one tap and writes the ESTIMATED default
            // immediately, so this resumes as soon as the user answers either
            // way — it cannot strand playback behind an unanswered prompt any
            // longer than the user leaves it open.
            if (_syncState.value.firstContactGate != null) {
                com.jointheparty.app.debug.DebugLog.log("playback held: ${_syncState.value.routeId} not calibrated yet")
                syncState.first { it.firstContactGate == null }
                com.jointheparty.app.debug.DebugLog.log("gate resolved → aiming")
            }
            when (val r = controller.connect()) {
                SpotifyController.ConnectionResult.Connected -> {
                    // Re-acquiring the track we are ALREADY on must not call
                    // play(uri): that restarts it from 0:00, which is plainly
                    // audible for the ~1 s before the aim drags it back. It
                    // happens on every recovery — a track-lost, a room gap, a
                    // Spotify auto-advance we paused out of — because the
                    // MATCHING branch re-resolves without comparing to what is
                    // already loaded. Resume and aim instead; the aim below is
                    // what puts us at the room's position either way.
                    val loaded = controller.lastKnownPlayerState
                    if (loaded?.trackUri == uri) {
                        com.jointheparty.app.debug.DebugLog.log(
                            "Spotify connected → $uri already loaded; " +
                                "resume+aim (no restart)",
                        )
                        if (loaded.isPaused) controller.resume()
                    } else {
                        com.jointheparty.app.debug.DebugLog.log("Spotify connected → play $uri")
                        // GRD-01 (tech-req §2.13): latch BEFORE issuing the
                        // call — the confirming player-state event can arrive
                        // before this coroutine's next line does.
                        latchSelfPlay(uri)
                        controller.play(uri)
                    }
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
        // IDC-01 (tech-req §2.14): signed-off behavior change. An unresolved
        // aim used to silently let playerStateWatcher() below proceed to
        // CONVERGING against a garbage/stale estimate (FT9's Test 3: an aim
        // gave up, the estimator reported a 763715 ms reading two seconds
        // later, and the recognizer went on to mis-lock). Force the SAME
        // LOST→LISTENING→MATCHING re-bootstrap onTrackLost() already
        // performs — which also arms this section's corroboration gate —
        // rather than trusting an aim nobody ever confirmed landed.
        onTrackLost()
    }

    private fun playerStateWatcher() {
        val controller = spotify ?: return
        scope.launch(dispatcher) {
            // FIELD TEST 7: player states are EVENT-driven and this watcher
            // starts only after the aim settles — when the first aim lands
            // clean (no correction ever fires), the state announcing
            // playback was emitted BEFORE this collector subscribed
            // (SharedFlow, no replay) and steady playback emits nothing
            // more. The session then sits in AIMING forever and the
            // end-of-track pause is never armed. Seed with the last known
            // state so "the first player state" exists even when it
            // predates us; double-handling one state is harmless (the
            // transition is phase-guarded, the pause timer re-arms).
            controller.lastKnownPlayerState?.let { handlePlayerState(controller, it) }
            controller.playerStates.collect { state ->
                handlePlayerState(controller, state)
            }
        }
    }

    private fun handlePlayerState(
        controller: SpotifyController,
        state: SpotifyController.RemotePlayerState,
    ) {
        if (_syncState.value.phase == SessionPhase.AIMING) {
            onPlaybackStarted()
        }
        val commanded = _syncState.value.track?.spotifyUri
        // GRD-01 (tech-req §2.13): checked BEFORE the state.trackUri !=
        // commanded test below — a hit means this confirmation is a URI WE
        // issued, regardless of what _syncState.track currently holds (a
        // newer re-resolution may have already moved it on). A miss falls
        // through to the ordinary check, unchanged.
        val selfIssued = state.trackUri != null && consumeSelfPlayLatch(state.trackUri)
        if (!selfIssued && commanded != null && state.trackUri != null &&
            state.trackUri != commanded && !state.isPaused
        ) {
            onSpotifyAutoAdvanced(controller, state.trackUri)
        } else {
            scheduleEndOfTrackPause(controller, state)
        }
    }

    /**
     * GRD-01 (tech-req §2.13): bounded, time-boxed set of self-issued
     * `play(uri)` calls not yet confirmed by a player-state event. Each
     * entry expires via its OWN scheduled job — virtual-time-friendly,
     * matching this file's delay()-based scheduling convention, rather than
     * a raw System.nanoTime() comparison a JVM test could never advance past
     * without a real wall-clock sleep.
     */
    private val selfPlayLatch = mutableListOf<Pair<String, kotlinx.coroutines.Job>>()

    private fun latchSelfPlay(uri: String) {
        lateinit var job: kotlinx.coroutines.Job
        // GRD-01 concurrency fix (#32): the eviction + append below must be
        // atomic with every other selfPlayLatch access — see [sessionLock].
        synchronized(sessionLock) {
            if (selfPlayLatch.size >= SELF_PLAY_LATCH_MAX_ENTRIES) {
                selfPlayLatch.removeAt(0).second.cancel()  // oldest evicted first
            }
            job = scope.launch(dispatcher) {
                delay(SELF_PLAY_LATCH_WINDOW_MS)
                // This expiry job can run on a different Dispatchers.Default
                // worker than whatever thread is concurrently consuming or
                // latching — the exact race FT10 crashed on (a bare
                // `removeAll` racing consumeSelfPlayLatch's indexOfFirst→
                // removeAt window).
                synchronized(sessionLock) { selfPlayLatch.removeAll { it.second === job } }
            }
            selfPlayLatch += uri to job
        }
    }

    /**
     * Set-membership test, not a blanket "ignore auto-advance for N ms"
     * timer (tech-req §2.13's own load-bearing distinction) — the latch
     * only ever contains URIs we issued, so a real auto-advance to any URI
     * we did not just command is never in the set and still trips the
     * guardian exactly as today. A hit consumes the matching entry.
     *
     * GRD-01 concurrency fix (#32): the indexOfFirst→removeAt window below
     * is exactly what FT10 crashed on (`IndexOutOfBoundsException`,
     * `SessionViewModel.kt:795` at the time) — a concurrent expiry
     * `removeAll` or another consumer emptied the list between the two
     * calls. Synchronized on [sessionLock] with every other selfPlayLatch
     * access.
     */
    private fun consumeSelfPlayLatch(uri: String): Boolean = synchronized(sessionLock) {
        val idx = selfPlayLatch.indexOfFirst { it.first == uri }
        if (idx < 0) return@synchronized false
        selfPlayLatch.removeAt(idx).second.cancel()
        true
    }

    /**
     * Prefers the provider-supplied Spotify URI (ACRCloud external_metadata
     * — real, playable) over the backend ISRC resolver (which is MOCKED
     * until AUTH-03's server deploys and would hand playback a fake URI).
     *
     * IDC-01 (tech-req §2.14): while the identity corroboration gate is
     * armed (see [armIdentCorroboration]), a resolved identity is recorded
     * into the streak instead of being acted on immediately — only once
     * [IDENT_CONFIRM_MIN_FIXES] agreeing entries accumulate does the shell
     * proceed to [resolvedWithAim], using THIS (the newest) fix's data. When
     * NOT armed (ordinary cold-start MATCHING), behavior is unchanged: the
     * first resolved identity resolves immediately.
     */
    private suspend fun resolveTrack(fix: RecognitionProvider.RecognitionFixResult) {
        val track = resolveTrackInfo(fix) ?: return
        // GRD-01 concurrency fix (#32): the armed-check and the streak
        // mutation must land as one atomic unit — see [sessionLock] —
        // otherwise two fixes racing this function on different
        // Dispatchers.Default workers (e.g. the fast-switch branch and a
        // track-lost re-bootstrap) could both read identCorrobArmed before
        // either updates the streak. [resolveTrackInfo] above (network/
        // backend I/O) deliberately stays outside the lock.
        val corroborated = synchronized(sessionLock) {
            !identCorrobArmed || identCorroborate(track.spotifyUri, fix)
        }
        if (!corroborated) return  // not yet corroborated; stay quietly in MATCHING
        resolvedWithAim(track, fix)
    }

    private suspend fun resolveTrackInfo(
        fix: RecognitionProvider.RecognitionFixResult,
    ): TrackInfo? {
        val direct = fix.spotifyUri
        if (direct != null) {
            return TrackInfo(
                spotifyUri = direct,
                isrc = fix.isrc,
                title = fix.title ?: "Unknown",
                artist = fix.artist ?: "",
                durationMs = 0L,
            )
        }
        val backendClient = backend ?: return null
        val isrc = fix.isrc ?: return null
        return when (val resolution = backendClient.resolveIsrcToSpotifyUri(isrc)) {
            is TrackResolution.Resolved ->
                TrackInfo(
                    spotifyUri = resolution.spotifyUri,
                    isrc = isrc,
                    title = fix.title ?: "Unknown",
                    artist = fix.artist ?: "",
                    durationMs = 0L,
                )
            TrackResolution.NotFound,
            is TrackResolution.Failure,
            -> null // stay in MATCHING; the next pass may resolve
        }
    }

    /**
     * IDC-01 (tech-req §2.14): true once `MATCHING` is (re-)armed via
     * [onTrackLost]'s re-bootstrap. [aimUntilLanded]'s give-up path arms it
     * only INDIRECTLY, by calling [onTrackLost] itself (the same
     * re-bootstrap any other track-lost takes) — it has no arming call of
     * its own (issue #37: this doc previously implied it did; corrected).
     * While armed, every resolved identity — including one CORE-06
     * independently rejected as SELF_HEARING/LOW_CONFIDENCE downstream —
     * still passes through [resolveTrack]'s existing unconditional call (it
     * never gated on that verdict to begin with), so a rejected fix is
     * recorded here exactly like any other.
     */
    private var identCorrobArmed = false
    private var identStreakCount = 0
    private var identStreakUri: String? = null
    private var identStreakLastOffsetMs: Long = 0L
    private var identStreakLastCaptureMonoNs: Long = 0L

    /**
     * Capture-mono timestamp of the current streak's OWN first entry —
     * [IDENT_CORROB_MAX_AGE_MS] is measured from here, not from a separate
     * coroutine timer. A scheduled `delay()`-based expiry job would be
     * drained early by ANY subsequent `advanceUntilIdle()` call in a JVM
     * test (`maybeSampleReferee`'s own doc comment records exactly this
     * "free-running timer" pitfall for a `while(true) { delay() }` loop);
     * `captureMonoNs` is already the real per-fix clock this mechanism
     * reasons about (the offset-vs-wall-clock agreement check below reads
     * it too), so reusing it here needs no timer at all — expiry is simply
     * checked reactively against whatever fix arrives next.
     */
    private var identStreakStartCaptureMonoNs: Long = 0L

    /** Arms (or re-arms) the gate: epoch rule — the ring/streak never
     * carries across a prior arming or a resolved track.
     *
     * GRD-01 concurrency fix (#32): synchronized on [sessionLock] with
     * every other identCorrob* access — this can be called from
     * [onTrackLost], which itself runs on either the engine-event collector
     * or [aimUntilLanded]'s give-up path, two different coroutines that can
     * land on different threads. */
    private fun armIdentCorroboration() = synchronized(sessionLock) {
        identCorrobArmed = true
        identStreakCount = 0
        identStreakUri = null
    }

    /** GRD-01 concurrency fix (#32): see [armIdentCorroboration]. */
    private fun clearIdentCorroboration() = synchronized(sessionLock) {
        identCorrobArmed = false
        identStreakCount = 0
        identStreakUri = null
    }

    /**
     * Extends the streak (same uri as the streak's most recent entry, and
     * its offset delta agrees with the elapsed wall-clock delta within
     * [IDENT_CONFIRM_OFFSET_AGREE_MS]) or restarts it at just this entry —
     * the same "restart, not accumulate" rule §2.8's pending-large-
     * correction record already established. Returns true once
     * [IDENT_CONFIRM_MIN_FIXES] agreeing entries are reached (corroborated:
     * the caller proceeds using THIS — the newest — entry's data).
     *
     * A streak whose OWN first entry is more than [IDENT_CORROB_MAX_AGE_MS]
     * older than this fix expires silently first — cleared, no escalation —
     * and this fix becomes entry 1 of a fresh streak instead.
     */
    // GRD-01 concurrency fix (#32): full body synchronized on [sessionLock]
    // — see [resolveTrack]'s call site, which reads identCorrobArmed and
    // calls this as one atomic unit; [sessionLock] is reentrant so this
    // function's own lock acquisition nests safely inside that one.
    private fun identCorroborate(
        uri: String,
        fix: RecognitionProvider.RecognitionFixResult,
    ): Boolean = synchronized(sessionLock) {
        if (identStreakCount > 0 &&
            fix.captureMonoNs - identStreakStartCaptureMonoNs >
            IDENT_CORROB_MAX_AGE_MS * 1_000_000L
        ) {
            // tech-req §2.14: no escalation to error — the streak simply
            // clears and the session keeps quietly sampling in MATCHING.
            identStreakCount = 0
            identStreakUri = null
        }
        val extends = identStreakUri == uri &&
            kotlin.math.abs(
                (fix.matchOffsetMs - identStreakLastOffsetMs) -
                    (fix.captureMonoNs - identStreakLastCaptureMonoNs) / 1_000_000,
            ) <= IDENT_CONFIRM_OFFSET_AGREE_MS
        if (!extends) identStreakStartCaptureMonoNs = fix.captureMonoNs
        identStreakCount = if (extends) identStreakCount + 1 else 1
        identStreakUri = uri
        identStreakLastOffsetMs = fix.matchOffsetMs
        identStreakLastCaptureMonoNs = fix.captureMonoNs
        // #37 instrumentation: streak progress was invisible in logcat —
        // FT10's IDC-01 verdict was nearly inconclusive purely for want of
        // this line (field-test-10-results.md's IDC-01 section had to
        // reconstruct streak state from source reading instead).
        com.jointheparty.app.debug.DebugLog.log(
            "identCorrob: streak $identStreakCount/$IDENT_CONFIRM_MIN_FIXES ($uri)",
        )
        if (identStreakCount >= IDENT_CONFIRM_MIN_FIXES) {
            clearIdentCorroboration()
            return@synchronized true
        }
        false
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
            // FIELD TEST 8: leaving the party must stop OUR music. This
            // cancelled the end-of-track guardian below but left Spotify
            // playing, so the track ran out unattended and Spotify
            // auto-advanced to a song nobody asked for — the exact failure
            // the guardian exists to prevent, recreated by the exit path
            // that disarmed it.
            spotify?.pause()
            engine.stopCapture()
            captureRunning = false
            calibrationOwnsCapture = false
            gateDismissedThisSession = false
            capturedCalibrationRoute = null // CFX-01
            refereePendingRouteId = null
            refereePendingResidualsMs.clear()
            // GRD-01 concurrency fix (#32): reset() runs on the caller's
            // thread (a UI-driven action) concurrently with any in-flight
            // session coroutine on `dispatcher` — guard the same shared
            // fields those coroutines guard. See [sessionLock].
            synchronized(sessionLock) {
                consecutiveLosses = 0
                firstEstimateSeen = false
                samplingAttempts = 0
                autoAdvanceHandled = null
                endOfTrackJob?.cancel()
                // GRD-01 (tech-req §2.13): epoch rule — a fresh session
                // must never carry a self-issued latch forward from before.
                selfPlayLatch.forEach { it.second.cancel() }
                selfPlayLatch.clear()
            }
            // IDC-01 (tech-req §2.14): epoch rule — the corroboration
            // ring/streak never carries across sessions.
            clearIdentCorroboration()
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
        // Audit §4.4: only absorb the measured error when the estimate is
        // fresh enough to mean something; a decayed estimate rebases noise.
        val rebase = if (lastEstimateConfidence >= REBASE_MIN_CONFIDENCE) {
            lastEstimateErrorMs.coerceIn(-REBASE_MAX_MS, REBASE_MAX_MS)
        } else {
            0.0
        }
        lastEstimateErrorMs = 0.0
        if (trimMs == 0) {
            // Zeroing the wheel means "remove my adjustment" — including the
            // bias absorbed into the setpoint. Without this the absorbed part
            // is invisible and unclearable from the UI, which is how a −2 s
            // setpoint survived a user who had deliberately zeroed the trim.
            engineNudgeMs = 0.0
        } else {
            engineNudgeMs += deltaMs + rebase
        }
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
        scope.launch(dispatcher) {
            nudgeStore.saveTrim(routeId, trimMs)
            // Audit §4.2: the rebased setpoint is what makes the NEXT
            // session start aligned — persist it beside the wheel value.
            nudgeStore.saveEngineSetpoint(routeId, engineNudgeMs.toInt())
            // CAL-10: feeds trim-promotion detection, checked lazily when
            // Device detail opens (selectDevice) — never live during the
            // session, matching ui-ux §6.5's "no push, no toast, no badge
            // count" restraint. A user-driven reset to 0 is appended like
            // any other commit, which is fine: it naturally breaks an
            // in-progress "same offset three times" streak rather than
            // silently not counting. [acceptTrimPromotion]'s OWN wheel
            // reset bypasses this function entirely (see its doc comment)
            // and clears the history outright instead.
            nudgeStore.appendTrimCommit(routeId, trimMs)
        }
    }

    /** Route reconnect: load persisted trim + command-latency prior, apply both. */
    fun onRouteChanged(routeId: String, routeName: String?, route: SyncCore.Route) {
        // CFX-01: a route change invalidates any calibration/tone-match
        // measurement captured against the PREVIOUS route — see
        // invalidateInFlightCalibrationIfRouteChanged's doc comment.
        invalidateInFlightCalibrationIfRouteChanged(routeId)
        // CAL-04: a fresh route also starts a fresh referee agreement
        // window — residuals accumulated against the PREVIOUS route must
        // never be attributed to this one.
        refereePendingRouteId = null
        refereePendingResidualsMs.clear()
        scope.launch(dispatcher) {
            val trim = nudgeStore.trimFor(routeId)
            // INT-03 fix: setOutputRoute's prior is the chirp-calibrated
            // OUTPUT-chain latency, not Spotify's command latency (which
            // seeds sc_create instead — see NudgeStore's doc note). CAL-04:
            // that latency now lives on the route's CalibrationProfile;
            // -1 (engine default) when the route has never been calibrated
            // — the same fallback the old flat key produced.
            val profile = nudgeStore.calibrationProfileFor(routeId)
            val outputLatencyPrior = profile?.latencyMs ?: -1
            // Audit §4.2: restore the rebased setpoint when one exists —
            // the session starts already-aligned; the wheel still displays
            // the plain trim.
            val setpoint = nudgeStore.engineSetpointFor(routeId) ?: trim
            engineNudgeMs = setpoint.toDouble()
            engine.setUserNudgeMs(setpoint)
            engine.setOutputRoute(route, outputLatencyPrior)
            // INT-04 (arch §7): phone-speaker playback means the mic hears
            // us — full AEC + self-hearing guard. Headphone routes are the
            // clean case: AEC off entirely.
            engine.setAecMode(
                if (route == SyncCore.Route.SPEAKER) SyncCore.AecMode.FULL
                else SyncCore.AecMode.OFF,
            )
            // CAL-09: trigger is "no stored profile" — extended to
            // "sampleCount==0" so a previously-declined ESTIMATED profile
            // (see declineFirstContactGate) re-offers next time this route
            // becomes active too ("handled" only ever means a real
            // MEASURED/BY_EAR sample landed). This never delays or guards
            // anything above — recognition's bootstrap lives entirely in
            // startListening(), which doesn't read this field.
            val gate = if (profile == null || profile.sampleCount == 0) {
                FirstContactGateState(routeId, routeName ?: routeId)
            } else {
                null
            }
            _syncState.update {
                it.copy(routeId = routeId, routeName = routeName, nudgeMs = trim, firstContactGate = gate)
            }
        }
    }

    // ---- Calibration (INT-03) ---------------------------------------------

    /**
     * CFX-01 (tech-req §2.6 "Route attribution — captured at measurement
     * start, not completion"): the route identity a chirp or tone-match
     * measurement is running against. Snapshotted by [captureCalibrationRoute]
     * when the measurement starts ([startCalibration]/[startByEarCalibration]),
     * held for its duration, and consumed by [onCalibrationResult]/
     * [commitByEar] at completion — those functions write against THIS,
     * never a re-read of [SyncState.routeId]. Null whenever nothing is in
     * flight; cleared on every exit path (cancel, dismiss, completion,
     * session reset) so a stale snapshot can never survive into the next
     * measurement.
     */
    private data class CapturedCalibrationRoute(
        val routeId: String,
        val routeName: String?,
        val routeClass: SyncCore.Route,
    )

    private var capturedCalibrationRoute: CapturedCalibrationRoute? = null

    /** Mirrors the engine's capture state so calibration can tell whether it must open the mic itself. */
    private var captureRunning = false

    /** True when THIS calibration started capture, so only it may stop it again. */
    private var calibrationOwnsCapture = false

    /**
     * Hands the mic back if this measurement was the one that opened it.
     * A session already listening keeps its capture untouched — calibration
     * is a guest there, not the owner.
     */
    private fun releaseCalibrationCapture() {
        if (!calibrationOwnsCapture) return
        calibrationOwnsCapture = false
        captureRunning = false
        engine.stopCapture()
    }

    private fun captureCalibrationRoute() {
        capturedCalibrationRoute = CapturedCalibrationRoute(
            routeId = _syncState.value.routeId,
            routeName = _syncState.value.routeName,
            routeClass = currentRoute(),
        )
    }

    /**
     * CFX-01: called from [onRouteChanged] on every route change. A route
     * change observed while a measurement is in flight invalidates it
     * outright (tech-req §2.6) — same effect as [cancelCalibration]/
     * [cancelByEarCalibration], but landing on [CalibrationState.Cancelled]
     * instead of [CalibrationState.Idle]/[CalibrationState.ByEarIdle] so the
     * sheet can name what happened. A no-op whenever nothing is in flight,
     * or the "new" route is the same one the in-flight measurement already
     * captured (e.g. a redundant reconnect callback for the same device).
     */
    private fun invalidateInFlightCalibrationIfRouteChanged(newRouteId: String) {
        val captured = capturedCalibrationRoute ?: return
        if (captured.routeId == newRouteId) return
        when (_syncState.value.calibration) {
            CalibrationState.Running -> engine.cancelCalibration()
            CalibrationState.ByEarRunning -> tonePlayer?.stop()
            else -> Unit // stale snapshot with nothing actually running; just clear it below
        }
        capturedCalibrationRoute = null
        _syncState.update { it.copy(calibration = CalibrationState.Cancelled) }
    }

    /**
     * Arms the engine's chirp detector, then plays the calibration chirp
     * through the active output route.
     *
     * CFX-07: "Start calibration" must never be a dead tap (ui-ux §6.5). If
     * the engine refuses to arm calibration — a bad session state, distinct
     * from a chirp that arms fine but times out undetected — this routes
     * straight into the same [CalibrationState.Failed] that timeout already
     * reaches, reusing its existing "Try again"/"Try by ear instead"
     * recovery rather than leaving Idle with no visible change at all.
     */
    fun startCalibration() {
        if (_syncState.value.calibration == CalibrationState.Running) return
        // FIELD FIX (device test, 2026-07-28): the chirp detector only polls
        // while the worker is draining capture, so with the mic stopped it
        // neither hears the chirp NOR ever reaches its own 8 s timeout — the
        // sheet sat on "Listening for the chirp…" indefinitely. Capture was
        // previously started only by startListening() (the Join tap), which
        // made calibrating before joining a party — the entire point of the
        // idle entry point — impossible in practice.
        //
        // Start it here when it isn't already running, and remember that we
        // did so this measurement can hand the mic back afterwards rather
        // than leaving it open on an idle screen.
        if (!captureRunning) {
            if (!engine.startCapture()) {
                com.jointheparty.app.debug.DebugLog.log("calibrate → startCapture FAILED (mic/format)")
                _syncState.update { it.copy(calibration = CalibrationState.Failed) }
                return
            }
            captureRunning = true
            calibrationOwnsCapture = true
        }
        if (!engine.beginCalibration()) {
            releaseCalibrationCapture()
            _syncState.update { it.copy(calibration = CalibrationState.Failed) }
            return
        }
        // CFX-01: snapshot the route NOW, at measurement start — completion
        // (onCalibrationResult) writes against this snapshot, never a
        // re-read of the live route.
        captureCalibrationRoute()
        // INT-03b: begin arms the detector (t0 = capture-now), THEN the
        // chirp is rendered through the active output route — the measured
        // delta is exactly the route's output-chain latency.
        chirp?.play()
        _syncState.update { it.copy(calibration = CalibrationState.Running) }
    }

    /**
     * INT-02: App Remote needs an Activity to present its consent UI (see
     * AppRemoteSpotifyController.activityContext). INT-06c: MainActivity
     * attaches itself in onStart and detaches in onStop — set only while
     * the Activity is started, the window where App Remote's consent UI
     * could actually render (tech-req §2.5).
     */
    fun attachActivity(activity: android.app.Activity?) {
        (spotify as? AppRemoteSpotifyController)?.activityContext = activity
    }

    fun cancelCalibration() {
        engine.cancelCalibration()
        releaseCalibrationCapture()
        capturedCalibrationRoute = null // CFX-01: cancelling clears the captured route
        _syncState.update { it.copy(calibration = CalibrationState.Idle) }
    }

    /**
     * Sheet dismissed: clear any terminal result so reopening starts fresh.
     * Also stops the tone loop unconditionally (CAL-07) — a scrim tap or
     * back-press can dismiss the sheet mid-[CalibrationState.ByEarRunning],
     * and [TonePlayer.stop] is a harmless no-op if the tone was never
     * started.
     */
    fun acknowledgeCalibration() {
        tonePlayer?.stop()
        releaseCalibrationCapture()
        capturedCalibrationRoute = null // CFX-01: dismissing clears the captured route
        _syncState.update { it.copy(calibration = CalibrationState.Idle) }
    }

    // ---- Calibration by ear (CAL-07) ---------------------------------------

    /**
     * Enters the tone-match flow's Idle state. Reachable two ways, per
     * ui-ux §6.5: automatically available once the chirp times out (the
     * existing [CalibrationState.Failed] path, which now renders a Quiet
     * "Try by ear instead" exit calling this), and directly — this function
     * itself has no precondition on the current [CalibrationState] or the
     * active route, matching CAL-01/07's shared "no device-class check"
     * rule and the ticket's "available on any route."
     */
    fun tryByEarInstead() {
        // No defensive tonePlayer?.stop() here: the tone only ever starts
        // from [startByEarCalibration] below, and every path out of
        // [CalibrationState.ByEarRunning] (cancel/commit/dismiss) already
        // stops it — by the time this function is reachable again, the
        // tone is never playing.
        _syncState.update { it.copy(calibration = CalibrationState.ByEarIdle) }
    }

    /**
     * "Start": begins the tone loop and enters Running. The Running
     * screen's visual strike + `abClick` haptic beat is driven entirely by
     * the composable (a UI-side `LaunchedEffect`, never this class's
     * [scope]) — this function only owns the audio side, mirroring
     * [startCalibration]'s split with [ChirpPlayer].
     */
    fun startByEarCalibration() {
        if (_syncState.value.calibration == CalibrationState.ByEarRunning) return
        // CFX-01: snapshot the route NOW, at measurement start — completion
        // (commitByEar) writes against this snapshot, never a re-read of
        // the live route.
        captureCalibrationRoute()
        tonePlayer?.start()
        _syncState.update { it.copy(calibration = CalibrationState.ByEarRunning) }
    }

    /** Quiet "Cancel" on Running: stop the tone, back to By-ear Idle. */
    fun cancelByEarCalibration() {
        tonePlayer?.stop()
        capturedCalibrationRoute = null // CFX-01: cancelling clears the captured route
        _syncState.update { it.copy(calibration = CalibrationState.ByEarIdle) }
    }

    /**
     * "That's it": the dragged caliper value the user judged as aligned
     * becomes the route's [CalibrationProfile.latencyMs], `method = BY_EAR`
     * (ui-ux §6.5). Mirrors [onCalibrationResult]'s MEASURED persistence
     * shape — preserve an existing profile's creation time / referee
     * history / acoustic-reachability across re-calibration, apply the new
     * latency to the engine immediately, not just persist it.
     */
    fun commitByEar(latencyMs: Int) {
        tonePlayer?.stop()
        // CFX-01: consume the snapshot captured at startByEarCalibration()
        // — never re-read the live route here. A null/mismatched snapshot
        // means the route moved on since the tone-match started (normally
        // already caught by invalidateInFlightCalibrationIfRouteChanged,
        // which would have landed on Cancelled already; this is the
        // completion-time backstop for the race where the commit was
        // already in flight). Either way: discard, no profile write, no
        // engine apply.
        val captured = capturedCalibrationRoute
        capturedCalibrationRoute = null
        if (captured == null || captured.routeId != _syncState.value.routeId) {
            _syncState.update { it.copy(calibration = CalibrationState.Cancelled) }
            return
        }
        _syncState.update { it.copy(calibration = CalibrationState.ByEarSuccess(latencyMs)) }
        scope.launch(dispatcher) {
            val existing = nudgeStore.calibrationProfileFor(captured.routeId)
            val now = System.currentTimeMillis()
            val profile = CalibrationProfile(
                routeId = captured.routeId,
                routeClass = captured.routeClass.name,
                deviceName = captured.routeName ?: captured.routeId,
                method = CalibrationProfile.Method.BY_EAR,
                latencyMs = latencyMs,
                confidence = BY_EAR_CALIBRATION_CONFIDENCE,
                sampleCount = (existing?.sampleCount ?: 0) + 1,
                acousticallyReachable = existing?.acousticallyReachable ?: false,
                createdAtMs = existing?.createdAtMs ?: now,
                updatedAtMs = now,
                refereeSamples = existing?.refereeSamples ?: emptyList(),
                drifted = false,
            )
            nudgeStore.saveCalibrationProfile(profile)
        }
        engine.setOutputRoute(captured.routeClass, latencyMs)
    }

    private fun onCalibrationResult(event: SyncCore.Event.CalibrationResult) {
        // Measurement over either way — hand the mic back if we opened it.
        releaseCalibrationCapture()
        // CFX-01: consume the snapshot captured at startCalibration() —
        // never re-read the live route here. See commitByEar's matching
        // comment for why both the proactive (onRouteChanged) and this
        // completion-time check exist together.
        val captured = capturedCalibrationRoute
        capturedCalibrationRoute = null
        if (captured == null || captured.routeId != _syncState.value.routeId) {
            if (_syncState.value.calibration != CalibrationState.Cancelled) {
                _syncState.update { it.copy(calibration = CalibrationState.Cancelled) }
            }
            return
        }
        if (event.valid) {
            _syncState.update {
                it.copy(calibration = CalibrationState.Success(event.latencyMs))
            }
            scope.launch(dispatcher) {
                // CAL-04: a successful chirp round-trip is a MEASURED
                // profile, not just the old flat output-latency key — it
                // also marks the route acoustically reachable (letting the
                // referee sample it later) and stamps timestamps. Preserve
                // an existing profile's creation time / referee history
                // across re-calibration; a fresh chirp result supersedes
                // any prior drift flag.
                val existing = nudgeStore.calibrationProfileFor(captured.routeId)
                val now = System.currentTimeMillis()
                val profile = CalibrationProfile(
                    routeId = captured.routeId,
                    routeClass = captured.routeClass.name,
                    deviceName = captured.routeName ?: captured.routeId,
                    method = CalibrationProfile.Method.MEASURED,
                    latencyMs = event.latencyMs,
                    confidence = MEASURED_CALIBRATION_CONFIDENCE,
                    sampleCount = (existing?.sampleCount ?: 0) + 1,
                    acousticallyReachable = true,
                    createdAtMs = existing?.createdAtMs ?: now,
                    updatedAtMs = now,
                    refereeSamples = existing?.refereeSamples ?: emptyList(),
                    drifted = false,
                )
                // Persisted beside the route's trim; replayed into
                // sc_set_output_route on every reconnect (onRouteChanged).
                nudgeStore.saveCalibrationProfile(profile)
            }
            engine.setOutputRoute(captured.routeClass, event.latencyMs)
        } else {
            _syncState.update { it.copy(calibration = CalibrationState.Failed) }
        }
    }

    /** Classifies an arbitrary routeId string — the same prefix rule [currentRoute] uses for the active one. */
    private fun routeClassFor(routeId: String): SyncCore.Route = when {
        routeId.startsWith("bluetooth") -> SyncCore.Route.BLUETOOTH
        routeId == "wired" -> SyncCore.Route.WIRED
        else -> SyncCore.Route.SPEAKER
    }

    private fun currentRoute(): SyncCore.Route = routeClassFor(_syncState.value.routeId)

    // ---- First-contact gate (CAL-09) ---------------------------------------

    /**
     * "Calibrate now": dismiss the gate and enter the guided acoustic flow
     * CAL-01 already built. This ticket adds no new calibration mechanism —
     * only the prompt that offers one.
     *
     * CFX-06 (tech-req §2.6 "Gate copy must not pre-commit to a route
     * class"): always [startCalibration] — never [startByEarCalibration]
     * directly. The gate cannot know acoustic-capability in advance, so it
     * always attempts the acoustic flow unconditionally, per the Method
     * taxonomy's existing no-device-class-lookup rule; a route that can't be
     * heard acoustically reaches By ear via [startCalibration]'s own
     * existing chirp-timeout → [CalibrationState.Failed] → "Try by ear
     * instead" path, not by this function pre-emptively skipping to it.
     *
     * CFX-01 (tech-req §2.6 "Route attribution"): staleness-guarded the
     * same way [declineFirstContactGate] already was — a route change
     * between the gate raising and the user tapping "Calibrate now" must
     * dismiss the gate as stale rather than starting a measurement against
     * whatever now-unrelated device happens to be connected.
     */
    fun acceptFirstContactGate() {
        val gate = _syncState.value.firstContactGate ?: return
        _syncState.update { it.copy(firstContactGate = null) }
        if (gate.routeId != _syncState.value.routeId) return
        startCalibration()
    }

    /**
     * "Not now" (ui-ux §6.5): declining must not read as failure. Writes an
     * ESTIMATED profile at the generic default (tech-req §2.6) with
     * `sampleCount=0` so [onRouteChanged] re-offers the gate next time this
     * routeId becomes active — "handled" only ever means a real measurement
     * landed, never a decline. Applies the same default to the LIVE engine
     * right away too, mirroring [onCalibrationResult]/[commitByEar]'s
     * "apply to the engine immediately, not just persist it" — the device
     * must be usable THIS session, not just after a future recalibration.
     */
    fun declineFirstContactGate() {
        val gate = _syncState.value.firstContactGate ?: return
        _syncState.update { it.copy(firstContactGate = null) }
        val routeClass = routeClassFor(gate.routeId)
        scope.launch(dispatcher) {
            val existing = nudgeStore.calibrationProfileFor(gate.routeId)
            val now = System.currentTimeMillis()
            nudgeStore.saveCalibrationProfile(
                CalibrationProfile(
                    routeId = gate.routeId,
                    routeClass = routeClass.name,
                    deviceName = gate.deviceName,
                    method = CalibrationProfile.Method.ESTIMATED,
                    latencyMs = ESTIMATED_DEFAULT_LATENCY_MS,
                    confidence = 0f,
                    sampleCount = 0,
                    acousticallyReachable = existing?.acousticallyReachable ?: false,
                    createdAtMs = existing?.createdAtMs ?: now,
                    updatedAtMs = now,
                    refereeSamples = existing?.refereeSamples ?: emptyList(),
                    drifted = false,
                ),
            )
        }
        // Only steer the LIVE engine if this route is still the one
        // actually connected — a gate answered after the route already
        // moved on must not misapply a stale prior to whatever plays now.
        if (gate.routeId == _syncState.value.routeId) {
            engine.setOutputRoute(routeClass, ESTIMATED_DEFAULT_LATENCY_MS)
        }
    }

    // ---- Device review: shelf/detail (CAL-08) ------------------------------

    /**
     * Opens the device shelf: loads every known route's profile from
     * [nudgeStore] (CAL-04's list-all accessor). A plain one-shot suspend
     * read, not a stream — this is low-frequency, deliberately-opened state
     * (technical-requirements.md §2.1/§2.3's two-stream rule), so refetching
     * on open/back is correct; nothing here needs to react live to a profile
     * changing while the pane sits open.
     */
    fun openDeviceShelf() {
        scope.launch(dispatcher) {
            val profiles = nudgeStore.allCalibrationProfiles()
            // CFX-09 (tech-req §2.6 "Shelf ordering"): the store already
            // returns a deterministic updatedAtMs-descending base order (see
            // NudgeStore.sortedByUpdatedAtDescending); this is the ONLY
            // place that knows connectedRouteId, so connected-first is
            // layered on here rather than in the store.
            val ordered = profiles.withConnectedFirst(_syncState.value.routeId)
            _syncState.update { it.copy(deviceReview = DeviceReviewPane.Shelf(ordered)) }
        }
    }

    /**
     * Shelf row tapped: look the routeId up in the shelf's already-loaded
     * list and open its detail pane.
     *
     * CAL-10: trim-promotion eligibility is computed once, right here, on
     * open — a second suspend read alongside the synchronous profile
     * lookup above, same "plain one-shot read, not a stream" shape
     * [openDeviceShelf] already uses. [nowMs] defaults to the wall clock
     * like [com.jointheparty.app.ui.components.DeviceDetail]'s own
     * parameter of the same name, letting a test drive the 7-day
     * cooling-off check without sleeping.
     */
    fun selectDevice(routeId: String, nowMs: Long = System.currentTimeMillis()) {
        val shelf = _syncState.value.deviceReview as? DeviceReviewPane.Shelf ?: return
        val profile = shelf.profiles.firstOrNull { it.routeId == routeId } ?: return
        _syncState.update { it.copy(deviceReview = DeviceReviewPane.Detail(profile)) }
        scope.launch(dispatcher) {
            val declinedAtMs = nudgeStore.trimPromotionDeclinedAtMs(routeId)
            val suppressed = declinedAtMs != null && nowMs - declinedAtMs < TRIM_PROMOTION_COOLDOWN_MS
            val median = if (suppressed) null else trimPromotionMedian(nudgeStore.trimCommitHistoryFor(routeId))
            if (median == null) return@launch
            _syncState.update { state ->
                val detail = state.deviceReview as? DeviceReviewPane.Detail
                if (detail == null || detail.profile.routeId != routeId) return@update state
                state.copy(deviceReview = detail.copy(trimPromotionMedianMs = median))
            }
        }
    }

    /**
     * "Use this offset" (ui-ux §6.5 Device detail / tech-req §2.6 "Trim
     * promotion"). Folds [medianMs] the same way [commitByEar] folds a
     * fresh tone-match result (method=BY_EAR, latencyMs=medianMs, pushed to
     * the engine's output-route prior immediately), plus two things unique
     * to a promotion: the median is ALSO appended to the caliper's own tick
     * ring via [CalibrationProfile.withRefereeSample] — "stored as a By ear
     * tick, same as any tone-match result... the caliper doesn't care how a
     * tick was produced, only whether it agrees with its neighbors"
     * (ui-ux §6.5) — and the WHEEL itself resets to zero (tech-req §2.6:
     * "keeps the wheel's centre meaningful").
     *
     * The wheel reset is a DIRECT store+engine write, not a replay of
     * [onNudgeCommitted]'s rebase/seek logic: that logic exists to correct
     * audible error the ear just heard, and the SAME correction is what's
     * being folded into the profile here, so replaying it would double-
     * apply it as an audible jump back toward zero.
     */
    fun acceptTrimPromotion(routeId: String, medianMs: Int) {
        val routeClass = routeClassFor(routeId)
        scope.launch(dispatcher) {
            val existing = nudgeStore.calibrationProfileFor(routeId) ?: return@launch
            val now = System.currentTimeMillis()
            // FIELD FIX (field test 8): the promoted trim is a LATENCY
            // value, not a residual — under corrected drift semantics
            // (CalibrationProfile.withRefereeSample) recording it as a
            // referee sample would both poison the error history and
            // instantly flag drift on a number the user just chose. The
            // fold touches the latency fields only; the referee ring stays
            // what it is: measured residual errors.
            val folded = existing.copy(
                method = CalibrationProfile.Method.BY_EAR,
                latencyMs = medianMs,
                confidence = BY_EAR_CALIBRATION_CONFIDENCE,
                updatedAtMs = now,
                drifted = false,
            )
            nudgeStore.saveCalibrationProfile(folded)
            // The streak that triggered this promotion has been consumed —
            // clear it so re-opening the pane doesn't immediately offer the
            // very same offset again (unlike a decline, which deliberately
            // leaves the history alone; see declineTrimPromotion).
            nudgeStore.clearTrimCommitHistory(routeId)
            nudgeStore.saveTrim(routeId, 0)
            nudgeStore.saveEngineSetpoint(routeId, 0)
            _syncState.update { state ->
                val detail = state.deviceReview as? DeviceReviewPane.Detail
                if (detail == null || detail.profile.routeId != routeId) return@update state
                state.copy(deviceReview = detail.copy(profile = folded, trimPromotionAccepted = true))
            }
        }
        // Only steer the LIVE wheel/engine if this route is still the one
        // actually connected — mirrors declineFirstContactGate's same
        // staleness guard.
        if (routeId == _syncState.value.routeId) {
            engineNudgeMs = 0.0
            engine.setUserNudgeMs(0)
            engine.setOutputRoute(routeClass, medianMs)
            _syncState.update { it.copy(nudgeMs = 0) }
        }
    }

    /**
     * "Keep as is": never adopt silently — this is the alternative that
     * keeps it that way. Suppresses the banner for
     * [TRIM_PROMOTION_COOLDOWN_MS] (7 days) by writing ONE timestamp,
     * compared on every future [selectDevice] read — no running timer (the
     * CAL-04 hang precedent — see [maybeSampleReferee]'s doc comment).
     * Deliberately does NOT clear the commit history: unlike
     * [acceptTrimPromotion], which consumes the streak into the profile,
     * a decline just postpones the ask — "re-appears after cooldown if the
     * trigger condition still holds" needs that history to still be there.
     */
    fun declineTrimPromotion(routeId: String, nowMs: Long = System.currentTimeMillis()) {
        scope.launch(dispatcher) {
            nudgeStore.saveTrimPromotionDeclinedAtMs(routeId, nowMs)
        }
        _syncState.update { state ->
            val detail = state.deviceReview as? DeviceReviewPane.Detail
            if (detail == null || detail.profile.routeId != routeId) return@update state
            // "Keep as is" just closes the banner — the plain provenance
            // line takes its place, same as dismissDeviceReview does for
            // drift's "Later" (ui-ux: neither decline reads as failure).
            state.copy(deviceReview = detail.copy(trimPromotionMedianMs = null))
        }
    }

    /**
     * Drift banner's "Later" (CFX-08, ui-ux §6.5 "Both Quiet actions dismiss
     * in place"): closes the banner on the SAME `DeviceReviewPane.Detail`
     * the user is already looking at — never [backToDeviceShelf]. Mirrors
     * [declineTrimPromotion]'s exact shape (a no-persistence, in-memory
     * flag flip on the currently-open `Detail`, ignored if the pane has
     * since navigated elsewhere or moved to a different device).
     */
    fun dismissDriftBanner(routeId: String) {
        _syncState.update { state ->
            val detail = state.deviceReview as? DeviceReviewPane.Detail
            if (detail == null || detail.profile.routeId != routeId) return@update state
            state.copy(deviceReview = detail.copy(driftDismissed = true))
        }
    }

    /** Detail's back affordance: re-opens the shelf (a fresh read, same as [openDeviceShelf]). */
    fun backToDeviceShelf() = openDeviceShelf()

    /** Closes the review pane entirely — the sheet's dismiss, or a banner's "Later"/"Keep as is" exit. */
    fun dismissDeviceReview() {
        _syncState.update { it.copy(deviceReview = DeviceReviewPane.Hidden) }
    }

    /**
     * Detail's "Calibrate again": closes the review pane and, only when the
     * selected device IS the currently connected route, starts the existing
     * guided-calibration flow ([startCalibration]). The acoustic chirp can
     * only play through whatever route is actually active right now, so
     * recalibrating a DIFFERENT known device from here would first need to
     * switch the active output — that's route-selection machinery this
     * ticket doesn't own (CAL-01/INT-02's territory); browsing a
     * non-connected device's history is still useful on its own. Silently
     * doing nothing on a mismatched routeId (rather than starting a
     * measurement against the wrong device) is the honest choice — CAL-09/
     * CAL-10 own the rest of this seam.
     *
     * CFX-02 (tech-req §2.6 "Recalibration targeting"): returns whether a
     * measurement actually started. The caller (SessionScreen's
     * `onRequestRecalibrate` wiring) uses this to decide whether to swap
     * the sheet into the guided-calibration pane — the bug this fixes is
     * that wiring swapping in UNCONDITIONALLY, which showed a guided flow
     * titled with whatever device happened to be connected even when this
     * function silently declined to touch it.
     */
    fun requestRecalibrate(): Boolean {
        val detail = _syncState.value.deviceReview as? DeviceReviewPane.Detail ?: return false
        dismissDeviceReview()
        if (detail.profile.routeId != _syncState.value.routeId) return false
        startCalibration()
        return true
    }

    // ---- Acoustic referee (CAL-03/CAL-04) ----------------------------------

    /** [refereePendingResidualsMs] accumulates for this routeId only — see [onRouteChanged]. */
    private var refereePendingRouteId: String? = null

    /** Recent valid residuals awaiting [REFEREE_AGREEMENT_COUNT]-way agreement. */
    private val refereePendingResidualsMs = mutableListOf<Int>()

    /** Monotonic stamp of the last referee request; see [maybeSampleReferee]. */
    private var lastRefereeSampleNs = 0L

    /**
     * CAL-04: while the session is LOCKED, periodically asks the engine for
     * one acoustic-referee residual ([SyncEngine.sampleLatencyResidual] →
     * [SyncCore.Event.LatencyResidual]). Gated on the current route's
     * profile being [CalibrationProfile.acousticallyReachable] — the same
     * "cached optimisation to skip headphone routes early" the field
     * exists for (technical-requirements.md §2.6); a route with no profile
     * at all has nothing to compare a residual against either, so it's
     * skipped the same way.
     *
     * Driven by the estimate stream rather than its own timer. An earlier
     * version launched `while (true) { delay(interval) }` on entering
     * LOCKED, which hung every JVM test: the scope runs on the tests'
     * `StandardTestDispatcher`, so `advanceUntilIdle()` kept advancing
     * virtual time into an always-pending delay and never returned.
     * (`playbackPositionMs` has the same loop shape and is harmless only
     * because it is a COLD flow — nothing runs unless something collects
     * it.) Deriving the cadence from estimates that already arrive means
     * there is no free-running timer to spin on, and tests advance it
     * exactly as far as the estimates they emit.
     */
    private fun maybeSampleReferee() {
        val nowNs = System.nanoTime()
        if (nowNs - lastRefereeSampleNs < REFEREE_SAMPLE_INTERVAL_MS * 1_000_000L) return
        lastRefereeSampleNs = nowNs
        scope.launch(dispatcher) {
            val routeId = _syncState.value.routeId
            if (nudgeStore.calibrationProfileFor(routeId)?.acousticallyReachable == true) {
                engine.sampleLatencyResidual()
            }
        }
    }

    /**
     * CAL-04 shell-side referee aggregation (technical-requirements.md
     * §2.6). See [REFEREE_AGREEMENT_COUNT]'s doc comment for WHY agreement
     * across windows — not `peak_ratio` alone — is the actual protection
     * against a spurious reading.
     *
     * The pending-residuals bookkeeping below runs synchronously on the
     * single events-collector coroutine (same threading assumption
     * [onSyncEstimate]'s plain-var updates already rely on), so no lock is
     * needed for [refereePendingResidualsMs]; only the DataStore read/write
     * once a sample commits needs a coroutine.
     *
     * The referee never changes the live output-latency prior: this
     * function only ever calls [CalibrationProfile.withRefereeSample],
     * which touches bookkeeping (the ring, `sampleCount`, `drifted`,
     * `updatedAtMs`) and never `latencyMs`. Nothing here calls
     * `engine.setOutputRoute` or touches the wheel/nudge state.
     */
    private fun onLatencyResidual(event: SyncCore.Event.LatencyResidual) {
        if (!event.valid) return

        val routeId = _syncState.value.routeId
        if (refereePendingRouteId != routeId) {
            refereePendingRouteId = routeId
            refereePendingResidualsMs.clear()
        }
        refereePendingResidualsMs += event.residualMs
        if (refereePendingResidualsMs.size < REFEREE_AGREEMENT_COUNT) return

        val window = refereePendingResidualsMs.toList()
        val agrees = (window.max() - window.min()) <= REFEREE_AGREEMENT_TOLERANCE_MS
        if (!agrees) {
            // "resets the agreement count instead of writing" (CAL-04): the
            // disagreeing sample starts the NEXT window rather than being
            // discarded along with the ones it disagreed with.
            refereePendingResidualsMs.clear()
            refereePendingResidualsMs += window.last()
            return
        }

        val committedMs = window.sorted()[window.size / 2] // median of the agreeing window
        refereePendingResidualsMs.clear()
        scope.launch(dispatcher) {
            // Only a route we've actually measured has a latencyMs worth
            // comparing a residual against — and only a reachable route
            // could have produced a valid residual for real (see
            // [maybeSampleReferee]'s doc comment); this re-check guards
            // against a race where the route changed between the sample
            // request and this event landing.
            val profile = nudgeStore.calibrationProfileFor(routeId) ?: return@launch
            if (!profile.acousticallyReachable) return@launch
            val updated = profile.withRefereeSample(committedMs, System.currentTimeMillis())
            nudgeStore.saveCalibrationProfile(updated)
            com.jointheparty.app.debug.DebugLog.log(
                "referee: committed ${committedMs}ms residual on $routeId" +
                    if (updated.drifted) " — DRIFTED (residual above threshold)" else "",
            )
        }
    }

    // ---- Active probe (CTL-01b, technical-requirements.md §2.9) -----------

    /**
     * Executes [SyncCore.Event.ActiveProbe]: pause -> delay(pauseMs) ->
     * resume -> [SyncEngine.notifyProbeExecuted], exactly the sequence the
     * engine's self-match verdict measures against. Two preconditions must
     * both hold or this does nothing and never echoes — an inconclusive
     * probe is inconclusive BY DESIGN (§2.9), not a bug to route around:
     *  - playback must already be live ([SpotifyController
     *    .lastKnownPlayerState]`.isPaused == false`) — pausing something
     *    already paused can't produce the perturbation the verdict needs;
     *  - no calibration may be running ([CalibrationState.Running]/
     *    [CalibrationState.ByEarRunning]) — calibration already owns
     *    playback/capture for its own measurement.
     *
     * Launched on the existing session [scope]/[dispatcher], same as every
     * other suspend call in this file — deliberately NOT a free-running
     * timer loop. [maybeSampleReferee]'s doc comment records the JVM-test
     * hang a `while (true) { delay(...) }` caused earlier in this file: the
     * fix there was to never start an unbounded loop against the tests'
     * `StandardTestDispatcher` in the first place. This coroutine is
     * bounded — it runs the fixed pause/delay/resume/echo sequence once and
     * completes — so `advanceUntilIdle()` finishes normally.
     */
    private fun onActiveProbe(event: SyncCore.Event.ActiveProbe) {
        val playbackLive = spotify?.lastKnownPlayerState?.isPaused == false
        val calibrating = _syncState.value.calibration == CalibrationState.Running ||
            _syncState.value.calibration == CalibrationState.ByEarRunning
        if (!playbackLive || calibrating) return

        val controller = spotify ?: return
        scope.launch(dispatcher) {
            controller.pause()
            delay(event.pauseMs.toLong())
            controller.resume()
            engine.notifyProbeExecuted()
        }
    }

    // ---- Active duck (DSP-03b, technical-requirements.md §2.12) -----------

    /**
     * Executes [SyncCore.Event.ActiveDuck]: duck `STREAM_MUSIC` by ~6 dB for
     * [SyncCore.Event.ActiveDuck.duckMs], restore it, then
     * [SyncEngine.notifyDuckExecuted] with the depth ACTUALLY achieved
     * (deci-dB) — never a hardcoded 60, since volume-index quantization
     * means -6.0 dB exactly is rarely reachable (§2.12's own caveat).
     *
     * Gates mirror [onActiveProbe] exactly — playback must already be live
     * ([SpotifyController.lastKnownPlayerState]`.isPaused == false`), no
     * calibration may be running ([CalibrationState.Running]/
     * [CalibrationState.ByEarRunning]) — plus two duck-specific ones:
     *  - [volumeController] must be non-null (unavailable on pre-API-28
     *    devices; see [AudioManagerStreamVolumeController]'s doc comment —
     *    `SessionGraph` never constructs one there, so this is the only
     *    gate that needs to fire for that case);
     *  - the current volume must not already be 0 — you cannot duck
     *    silence, and per DSP-03a's echo contract (docs/dsp03a-review.md) a
     *    shell that cannot execute the duck must stay silent: no echo, the
     *    core's own 20 s duck-request expiry and 60 s cooldown handle it.
     *
     * Target selection per §2.12: the largest index whose dB is
     * <= original dB − [DUCK_TARGET_DB]. [firstOrNull] on
     * `(original downTo 0)` because a small volume range may not reach a
     * full 6 dB dip at ANY index — falling back to index 0 (the deepest
     * duck this device can produce) rather than no duck at all.
     *
     * Cancellation safety — the one place this handler differs
     * structurally from [onActiveProbe]: if the session [scope] dies
     * mid-`delay` (e.g. teardown), the user's volume must not stay ducked.
     * The `try`/`finally` below restores the original volume on EVERY
     * path — normal completion or cancellation — exactly once, wrapped in
     * `withContext(NonCancellable)` because a plain suspend call inside a
     * `finally` of an already-cancelled coroutine would itself be
     * cancelled immediately otherwise. The echo is deliberately NOT sent
     * on the cancelled path: after `finally` runs, the original
     * `CancellationException` continues propagating and the statement
     * below the `try` block never executes — the episode never completed,
     * so DSP-03a's `on_duck_result` must never see a verdict for it; the
     * core's own duck-request expiry already covers a duck that never
     * echoes.
     */
    private fun onActiveDuck(event: SyncCore.Event.ActiveDuck) {
        val playbackLive = spotify?.lastKnownPlayerState?.isPaused == false
        val calibrating = _syncState.value.calibration == CalibrationState.Running ||
            _syncState.value.calibration == CalibrationState.ByEarRunning
        val controller = volumeController
        if (!playbackLive || calibrating || controller == null) return

        val original = controller.getStreamVolume()
        if (original == 0) return // can't duck silence; stay silent per §2.12

        val originalDb = controller.getStreamVolumeDb(original)
        val targetIdx = (original downTo 0).firstOrNull { idx ->
            controller.getStreamVolumeDb(idx) <= originalDb - DUCK_TARGET_DB
        } ?: 0
        val achievedDb = originalDb - controller.getStreamVolumeDb(targetIdx)
        val achievedDeciDb = (achievedDb * 10).roundToInt()

        scope.launch(dispatcher) {
            controller.setStreamVolume(targetIdx)
            try {
                delay(event.duckMs.toLong())
            } finally {
                withContext(NonCancellable) {
                    controller.setStreamVolume(original)
                }
            }
            engine.notifyDuckExecuted(achievedDeciDb)
        }
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
            is SyncCore.Event.LatencyResidual -> onLatencyResidual(event)
            is SyncCore.Event.ActiveProbe -> onActiveProbe(event)
            is SyncCore.Event.ActiveDuck -> onActiveDuck(event)
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
                // e= and conf= are the engine state the correction was
                // computed from. Field Test 4 burned a whole run because the
                // trace showed only the jump: −2.6 s seeks that matched
                // neither the raw observation nor the reported sync error,
                // and there was no way to tell which input was lying.
                com.jointheparty.app.debug.DebugLog.log(
                    "CORRECTION → seek ${event.seekToMs}ms (jump ${jumpMs ?: "?"}ms) " +
                        "e=${"%.0f".format(lastEstimateErrorMs)} " +
                        "conf=${"%.2f".format(lastEstimateConfidence)}",
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

        scope.launch(dispatcher) {
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
                        // GRD-01 concurrency fix (#32): the two-step
                        // transition sequence is atomic w.r.t. any other
                        // thread mutating phase/session state — see
                        // [sessionLock].
                        synchronized(sessionLock) {
                            transition(SessionPhase.LOST)
                            transition(SessionPhase.ERROR)
                        }
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
                    val capAge = (System.nanoTime() - fix.captureMonoNs) / 1_000_000
                    // BIAS ISOLATION (audit §4.1): zEnd pairs ACR's offset
                    // with the sample-END timestamp (current engine
                    // behavior); zResp pairs it with response time (the
                    // hypothesis that ACR extrapolates play_offset_ms to
                    // "now"). Whichever column sits near zero across a
                    // clean run names the bias source.
                    val zEnd = shellProj - fix.matchOffsetMs
                    com.jointheparty.app.debug.DebugLog.log(
                        "fixdbg: offset=${fix.matchOffsetMs} zEnd=$zEnd " +
                            "zResp=${zEnd + capAge} capAge=${capAge}ms " +
                            "(ps=${ps.positionMs}@-${(System.nanoTime() - ps.receivedMonoNs) / 1_000_000}ms)",
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
                    // GRD-01 concurrency fix (#32): the three-step
                    // transition sequence is atomic w.r.t. any other thread
                    // mutating phase/session state (e.g. a concurrent
                    // onTrackLost() re-bootstrap) — see [sessionLock].
                    // resolveTrack(fix) deliberately stays OUTSIDE the lock:
                    // it suspends (backend I/O), and a suspend call must
                    // never happen while holding a plain JVM monitor.
                    synchronized(sessionLock) {
                        transition(SessionPhase.LOST)
                        transition(SessionPhase.LISTENING)
                        onMatchInFlight()
                    }
                    resolveTrack(fix)
                }
                retry = shouldKeepSampling()
            } finally {
                recognitionInFlight.set(false)
                if (retry) {
                    scope.launch(dispatcher) {
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
    /** Track URI we have already reacted to auto-advancing onto (§ above). */
    private var autoAdvanceHandled: String? = null

    /** Pending end-of-track pause; re-armed by every fresh player state. */
    private var endOfTrackJob: kotlinx.coroutines.Job? = null

    private var lastEstimateErrorMs: Double = 0.0

    /** Freshness guard for the rebase (audit §4.4): a stale, low-confidence
     * estimate must not be absorbed into the setpoint. */
    @Volatile
    private var lastEstimateConfidence: Float = 0f

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
        lastEstimateConfidence = event.confidence
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
        // CAL-04: the referee only has anything meaningful to measure while
        // the seek target is known-accurate (tech-req §2.6's attribution
        // argument) — i.e. still LOCKED after the transitions above.
        if (event.converged && _syncState.value.phase == SessionPhase.LOCKED) {
            maybeSampleReferee()
        }
    }

    /**
     * Stop just before OUR track ends, so Spotify never gets the chance to
     * pick the next one.
     *
     * `track.duration` is Spotify's own length for the track we are playing —
     * exact, and the only duration this needs. It is deliberately NOT a guess
     * about the room's version: if the room is playing a longer master we have
     * no audio left for those extra seconds anyway, so pausing at our own end
     * costs nothing that we could have played.
     *
     * This has to be a timer rather than a check on incoming player states,
     * because App Remote only emits those on events (play/pause/seek/track
     * change) — during steady playback nothing arrives for tens of seconds, so
     * there would be no event near the end to react to. Every fresh state
     * re-arms it, which also absorbs drift and our own corrections.
     */
    private fun scheduleEndOfTrackPause(
        controller: SpotifyController,
        state: SpotifyController.RemotePlayerState,
    ) {
        // GRD-01 concurrency fix (#32): endOfTrackJob is read/cancelled/
        // reassigned from every playerStateWatcher() collector — genuinely
        // concurrent whenever more than one is alive at once (e.g. a fresh
        // re-resolution's watcher racing a still-live older one, both on
        // Dispatchers.Default). See [sessionLock].
        synchronized(sessionLock) {
            endOfTrackJob?.cancel()
            if (state.isPaused || state.durationMs <= 0) return
            val remaining = state.durationMs - state.positionMs - END_OF_TRACK_LEAD_MS
            endOfTrackJob = scope.launch(dispatcher) {
                delay(remaining.coerceAtLeast(0L))
                val uri = state.trackUri ?: return@launch
                if (_syncState.value.track?.spotifyUri != uri) return@launch
                com.jointheparty.app.debug.DebugLog.log(
                    "track ending — pausing before Spotify picks the next one",
                )
                stopFollowingAndRelisten(controller, uri)
            }
        }
    }

    /**
     * Spotify reached the end of the track we asked for and moved on by
     * itself, to something the room is not playing.
     *
     * Field Test 4 caught what this costs: the room finished "My Life" while
     * Spotify auto-advanced to "Summer, Highland Falls". Our own speaker sits
     * inches from our own microphone, so from then on the recognizer only
     * ever heard US. The self-match guard did its job and refused every one
     * of those fixes, but that left the session with no information at all —
     * nominally LOCKED, confidence decayed to 0.00, playing the wrong song
     * and unable to discover the right one.
     *
     * The only way to hear the room again is to stop competing with it, so
     * pause first, drop the track, and re-listen exactly like a fresh join.
     *
     * This is the BACKSTOP. [scheduleEndOfTrackPause] should normally stop us
     * before Spotify ever gets to choose, so reaching here means the timer was
     * missed — no duration reported, or the track changed for some other
     * reason (the user hit next in Spotify).
     */
    // GRD-01 concurrency fix (#32): full body synchronized on [sessionLock].
    // FT10 caught this exact check-then-act (`autoAdvanceHandled ==
    // actualUri` here, `autoAdvanceHandled = uri` inside
    // [stopFollowingAndRelisten]) double-firing from two threads — both
    // passed the check before either wrote. Reentrant with
    // [stopFollowingAndRelisten]'s own synchronized body below.
    private fun onSpotifyAutoAdvanced(controller: SpotifyController, actualUri: String) {
        synchronized(sessionLock) {
            if (autoAdvanceHandled == actualUri) return@synchronized  // one response per track
            com.jointheparty.app.debug.DebugLog.log(
                "Spotify auto-advanced to $actualUri — pausing to hear the room",
            )
            stopFollowingAndRelisten(controller, actualUri)
        }
    }

    /**
     * Stop playing, forget the track, and go back to listening for the room —
     * the shared tail of both the end-of-track timer and the auto-advance
     * backstop. Our own speaker drowns out the room, so the pause has to land
     * before the microphone is worth sampling again.
     *
     * GRD-01 concurrency fix (#32): full body synchronized on [sessionLock]
     * — this is the OTHER caller of [scheduleEndOfTrackPause]'s natural-end
     * path in addition to [onSpotifyAutoAdvanced] above, so both routes to
     * this shared tail serialize against each other and against every other
     * guarded field ([autoAdvanceHandled], [endOfTrackJob], the phase
     * transitions below).
     */
    private fun stopFollowingAndRelisten(controller: SpotifyController, uri: String) {
        synchronized(sessionLock) {
            autoAdvanceHandled = uri
            endOfTrackJob?.cancel()
            controller.pause()
            _syncState.update { it.copy(track = null) }
            transition(SessionPhase.LOST)
            if (!transition(SessionPhase.LISTENING)) return@synchronized
            if (recognition == null) return@synchronized
            firstEstimateSeen = false
            samplingAttempts = 0
            onMatchInFlight()
            scope.launch(dispatcher) {
                // The pause has to actually take effect before the mic is
                // worth sampling, otherwise the first window still contains
                // our audio.
                delay(AUTO_ADVANCE_QUIET_MS)
                runRecognitionPass()
            }
        }
    }

    /**
     * SC_EVT_TRACK_LOST: any → lost, then auto-restart to listening — unless
     * this is the 3rd consecutive loss, in which case → error
     * (technical-requirements.md §2.4).
     */
    private fun onTrackLost() {
        // GRD-01 concurrency fix (#32): full body synchronized on
        // [sessionLock] — [onTrackLost] itself is reachable from two
        // different coroutines (the single engine-event collector, and
        // [aimUntilLanded]'s give-up path on its own `startPlayback`
        // coroutine), which can land on different Dispatchers.Default
        // workers. Guards consecutiveLosses, the phase transitions below,
        // and the IDC-01 arming as one atomic sequence.
        synchronized(sessionLock) {
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
                    // IDC-01 (tech-req §2.14): arm the identity
                    // corroboration gate for this re-bootstrap. This is the
                    // gate's ONE direct arming call site — aimUntilLanded's
                    // give-up path reaches it only indirectly, by calling
                    // onTrackLost() itself (issue #37: this comment
                    // previously implied aimUntilLanded armed it directly).
                    armIdentCorroboration()
                    onMatchInFlight()
                    scope.launch(dispatcher) {
                        // Field Test 5: this was RECOGNITION_RETRY_MS / 2 (3 s)
                        // of dead time added to an already slow re-acquire. The
                        // capture window gates how soon a match is possible, so
                        // there is nothing to gain by also waiting here.
                        delay(REACQUIRE_FIRST_PASS_MS)
                        runRecognitionPass()
                    }
                }
            } else {
                transition(SessionPhase.ERROR)
            }
        }
    }

    // ---- Transition allowlist (technical-requirements.md §2.4) ------------

    /**
     * The single gate every phase change passes through. Illegal
     * transitions are ignored silently — no exception, no log — per UI-02.
     * Returns whether the transition was applied.
     *
     * GRD-01 concurrency fix (#32): the from-read + legality check + state
     * write below is synchronized on [sessionLock] as one atomic unit. FT10
     * caught this exact check-then-act letting two threads both pass
     * `isLegalTransition` for the same duplicated LOST→LISTENING→MATCHING
     * chain. Reentrant — every caller of [transition] that itself already
     * holds [sessionLock] (e.g. [onTrackLost], [stopFollowingAndRelisten])
     * nests into this same monitor for free.
     */
    private fun transition(to: SessionPhase): Boolean = synchronized(sessionLock) {
        val from = _syncState.value.phase
        if (!isLegalTransition(from, to)) return@synchronized false
        if (to == SessionPhase.LOCKED) {
            consecutiveLosses = 0
            // CAL-04: start the referee's interval from the moment we lock,
            // so the first request comes one full interval into a settled
            // session rather than immediately on arrival.
            lastRefereeSampleNs = System.nanoTime()
        }
        _syncState.update { it.copy(phase = to) }
        com.jointheparty.app.debug.DebugLog.log("phase: $from → $to")
        true
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

}
