package com.jointheparty.app.ui.session

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.ui.components.CalibrationSheet
import com.jointheparty.app.ui.components.FirstContactGateSheet
import com.jointheparty.app.ui.components.NudgeWheel
import com.jointheparty.app.ui.components.SyncMeter
import com.jointheparty.app.ui.model.MeterFrame
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT
import com.jointheparty.app.ui.theme.isReducedMotionEnabled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * UI-05: session screen assembly (ui-ux-design-system.md §4, §6.1–6.3;
 * technical-requirements.md §2.4).
 *
 * Negative space is the layout (§4): exactly four elements ever appear —
 * track identity, the meter, its readout (drawn by [SyncMeter] itself), and
 * the wheel — separated by whitespace ≥ [DT.Space.sectionGap], never
 * dividers. Settings/calibration/A-B live behind a quiet entry point that
 * isn't part of this ticket.
 *
 * One warm accent at a time (§4): the brass "Join the party" pill is the
 * *only* saturated element on screen, and only in [SessionPhase.IDLE] — the
 * instant a meter or wheel exists, brass appears nowhere here except what
 * [SyncMeter]/[NudgeWheel] render internally at lock.
 *
 * Stateless by design: the Activity owns [SessionViewModel] and passes
 * projections in, so phase transitions, the engine, and the two-stream rule
 * (technical-requirements.md §2.3 — [meterFrames] and [inputLevel] are both
 * bare pass-throughs, never folded into [state]) all stay out of this file
 * entirely.
 */
@Composable
fun SessionScreen(
    state: SyncState,
    meterFrames: Flow<MeterFrame>,
    onJoinTap: () -> Unit,
    onTrimChange: (Int) -> Unit,
    onTrimCommit: (Int) -> Unit,
    onGetSpotify: () -> Unit,
    onSeePremiumPlans: () -> Unit,
    modifier: Modifier = Modifier,
    // INT-03: calibration intents (defaults keep previews/simple hosts terse).
    onStartCalibration: () -> Unit = {},
    onCancelCalibration: () -> Unit = {},
    onDismissCalibration: () -> Unit = {},
    // CAL-07: by-ear (tone-match) calibration intents.
    onTryByEar: () -> Unit = {},
    onStartByEar: () -> Unit = {},
    onCancelByEar: () -> Unit = {},
    onCommitByEar: (Int) -> Unit = {},
    onConnectSpotify: () -> Unit = {},
    spotifyLinked: Boolean = false,
    playbackPositionMs: Flow<Long> = MutableStateFlow(-1L),
    // UX audit #1: every in-session phase needs an exit back to IDLE.
    onLeaveSession: () -> Unit = {},
    // CAL-06: CAL-05's smoothed mic level (0..1, high-frequency stream
    // family — see meterFrames' doc comment above; default keeps
    // previews/simple hosts terse).
    inputLevel: Flow<Float> = MutableStateFlow(0f),
    // CAL-08: device shelf/detail review intents (defaults keep previews/
    // simple hosts terse, same convention as the calibration intents above).
    onOpenDeviceShelf: () -> Unit = {},
    onSelectDevice: (String) -> Unit = {},
    onBackToDeviceShelf: () -> Unit = {},
    onDismissDeviceReview: () -> Unit = {},
    // CFX-02 (tech-req §2.6 "Recalibration targeting"): mirrors
    // [com.jointheparty.app.ui.session.SessionViewModel.requestRecalibrate]'s
    // return — true exactly when a measurement actually started. See
    // [shouldOpenGuidedCalibrationPaneAfterRecalibrateRequest]'s doc
    // comment for why the guided-calibration pane's visibility is gated on
    // this instead of opening unconditionally.
    onRequestRecalibrate: () -> Boolean = { false },
    // CAL-09: first-contact gate intents (defaults keep previews/simple
    // hosts terse, same convention as the calibration intents above).
    onAcceptFirstContactGate: () -> Unit = {},
    onDeclineFirstContactGate: () -> Unit = {},
    // CAL-10: trim-promotion intents, threaded down through CalibrationSheet
    // to DeviceDetail's seam.
    onAcceptTrimPromotion: (routeId: String, medianMs: Int) -> Unit = { _, _ -> },
    onDeclineTrimPromotion: (routeId: String) -> Unit = {},
    // CFX-08: drift banner's "Later" — dismisses in place, threaded down to
    // CalibrationSheet exactly like the trim-promotion intents above.
    onDismissDriftBanner: (routeId: String) -> Unit = {},
) {
    var showCalibration by remember { mutableStateOf(false) }
    // CAL-08: separate from [showCalibration] so the sheet appears the
    // instant "Devices" is tapped, not only once the ViewModel's async
    // profile fetch lands in [state.deviceReview] — see onOpenDeviceShelf
    // below.
    var showDeviceReview by remember { mutableStateOf(false) }

    val phaseGroup = state.phase.toPhaseGroup()

    // CFX-04 (tech-req §2.6 "Sheet lifetime"): a phase transition OUT of
    // ACTIVE (into "Lost the room…" or a concierge gate) closes any open
    // calibration/review sheet outright — resetting the local flags here,
    // not merely hiding the sheet behind shouldShowCalibrationSheet below,
    // which alone would let the very same sheet silently resurrect the
    // moment the phase returns to ACTIVE. Mirrors the sheet's own onDismiss
    // wiring (below) so the ViewModel's calibration/deviceReview state is
    // cleared the same way a manual dismiss would clear it.
    LaunchedEffect(phaseGroup) {
        if (phaseGroup != PhaseGroup.ACTIVE && (showCalibration || showDeviceReview)) {
            showCalibration = false
            showDeviceReview = false
            onDismissDeviceReview()
            onDismissCalibration()
        }
    }

    // CFX-05 (tech-req §2.6 "Entry points"): the exact same open-shelf
    // action ActiveContent's "Devices" already used, extracted so IDLE's
    // entry point below wires to the identical closure shape — one
    // definition, reused, rather than two composables independently
    // deciding how "open the shelf" behaves.
    val handleOpenDeviceShelf = openDeviceShelfAction(
        setShowDeviceReview = { showDeviceReview = it },
        onOpenDeviceShelf = onOpenDeviceShelf,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DT.Colors.void)
            .padding(DT.Space.gutter),
    ) {
        // Crossfade, not a spring — layout-level phase changes get the
        // restrained ~200ms fade (§5 Reduced Motion token, applied here
        // unconditionally since this is a content swap, not a physical
        // gesture). No loops, no bounce.
        Crossfade(
            targetState = phaseGroup,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(DT.Motion.reducedMotionCrossfadeMs.toInt()),
            label = "session-phase",
        ) { group ->
            when (group) {
                // CFX-05: the shelf/detail entry point is reachable here
                // too — calibrating a device before joining a party, and
                // reviewing known devices between sessions, are both
                // legitimate uses (ui-ux §6.5's empty-state copy is written
                // for exactly this moment). A single "Devices" label, not
                // "Devices" + "Calibrate" as ActiveContent has — see
                // IdleContent's own doc comment for why one, not two.
                PhaseGroup.IDLE -> IdleContent(
                    onJoinTap = onJoinTap,
                    onConnectSpotify = onConnectSpotify,
                    spotifyLinked = spotifyLinked,
                    onOpenDeviceShelf = handleOpenDeviceShelf,
                )
                PhaseGroup.WAITING -> WaitingContent(
                    phase = state.phase,
                    level = inputLevel,
                    onCancel = onLeaveSession,
                )
                PhaseGroup.ACTIVE -> ActiveContent(
                    state = state,
                    meterFrames = meterFrames,
                    onTrimChange = onTrimChange,
                    onTrimCommit = onTrimCommit,
                    onOpenCalibration = { showCalibration = true },
                    onOpenDeviceShelf = handleOpenDeviceShelf,
                    playbackPositionMs = playbackPositionMs,
                    onLeaveSession = onLeaveSession,
                )
                PhaseGroup.LOST -> QuietMessage("Lost the room — listening again…")
                PhaseGroup.CONCIERGE -> ConciergeContent(
                    phase = state.phase,
                    onJoinTap = onJoinTap,
                    onGetSpotify = onGetSpotify,
                    onSeePremiumPlans = onSeePremiumPlans,
                )
            }
        }

        // FIELD DEBUG (2026-07-24): live diagnostic overlay — remove before
        // release. Shows why recognition/playback is (not) progressing.
        DebugOverlay(modifier = Modifier.align(Alignment.TopStart))

        // CFX-04 (tech-req §2.6 "Sheet lifetime & precedence"): visibility is
        // no longer just `showCalibration || showDeviceReview` — it also
        // requires the ACTIVE phase group and a clear first-contact gate.
        // See shouldShowCalibrationSheet's doc comment for the full rule;
        // the gate below is rendered completely independently, so this
        // condition excluding gate!=null is what makes the two sheets
        // structurally exclusive rather than a coincidence of layout.
        if (shouldShowCalibrationSheet(showCalibration || showDeviceReview, state.phase, state.firstContactGate)) {
            CalibrationSheet(
                routeName = state.routeName,
                calibration = state.calibration,
                onStart = onStartCalibration,
                onCancel = onCancelCalibration,
                onDismiss = {
                    showCalibration = false
                    showDeviceReview = false
                    onDismissDeviceReview()
                    onDismissCalibration()
                },
                onTryByEar = onTryByEar,
                onStartByEar = onStartByEar,
                onCancelByEar = onCancelByEar,
                onCommitByEar = onCommitByEar,
                // CAL-08: the shelf/detail panes only ever render while
                // showDeviceReview is true — once it's false this stays
                // DeviceReviewPane.Hidden regardless of what the ViewModel
                // is still holding, so the guided-calibration content below
                // is what a bare showCalibration=true entry sees.
                deviceReview = if (showDeviceReview) state.deviceReview else DeviceReviewPane.Hidden,
                connectedRouteId = state.routeId,
                onSelectDevice = onSelectDevice,
                onBackToShelf = onBackToDeviceShelf,
                onRequestRecalibrate = {
                    // Detail's "Calibrate again": the review pane closes
                    // itself (the ViewModel sets deviceReview = Hidden).
                    // CFX-02: the sheet only stays open into the guided
                    // flow when requestRecalibrate() actually started one
                    // (the viewed device IS the connected route) — it used
                    // to swap in unconditionally, showing a guided flow
                    // titled with whatever device happened to be connected
                    // even when nothing was started against it.
                    showDeviceReview = false
                    showCalibration = shouldOpenGuidedCalibrationPaneAfterRecalibrateRequest(onRequestRecalibrate)
                },
                onCalibratePhoneSpeaker = {
                    // CFX-02: DEVICE_SHELF_EMPTY_PRIMARY is now labelled
                    // honestly ("Calibrate this device," not "Calibrate
                    // phone speaker") precisely because this still starts
                    // guided calibration on whatever route is already
                    // active rather than switching to the phone's built-in
                    // speaker — route-switching is out of scope here (see
                    // SessionViewModel.requestRecalibrate's doc comment for
                    // the matching limitation on "Calibrate again"). Always
                    // starts something (unlike onRequestRecalibrate above),
                    // so the pane opens unconditionally.
                    showDeviceReview = false
                    showCalibration = true
                    onDismissDeviceReview()
                    onStartCalibration()
                },
                onAcceptTrimPromotion = onAcceptTrimPromotion,
                onDeclineTrimPromotion = onDeclineTrimPromotion,
                onDismissDriftBanner = onDismissDriftBanner,
            )
        }

        // CAL-09: independent of the calibration sheet above — the gate is
        // raised by SessionViewModel.onRouteChanged, not a quiet-entry-point
        // tap, and can appear the instant a route connects regardless of
        // whether the sheet is open. CFX-04: this independence, combined
        // with shouldShowCalibrationSheet's gate!=null exclusion above, is
        // what makes the gate and the sheet mutually exclusive — the gate
        // wins, since a non-null gate here always forces the sheet's own
        // condition false.
        state.firstContactGate?.let { gate ->
            FirstContactGateSheet(
                gate = gate,
                onAccept = onAcceptFirstContactGate,
                onDecline = onDeclineFirstContactGate,
            )
        }
    }
}

/**
 * CFX-04 (tech-req §2.6 "Sheet lifetime & precedence" / ui-ux §6.5 "Sheet
 * lifetime"): whether the calibration/device-review sheet should be visible
 * right now. Two independent rules, both must hold:
 *  1. Scoped to the ACTIVE phase group — a transition into LOST or a
 *     concierge gate (NEEDS_SPOTIFY/NEEDS_PREMIUM/ERROR) must not leave the
 *     sheet open and interactive over that state.
 *  2. The gate wins — the sheet can never be visible while
 *     [FirstContactGateState] is non-null, whether that's because the gate
 *     just became eligible while the sheet was already open, or because a
 *     tap that would open the sheet lands while the gate is still pending
 *     (the sheet only becomes visible once accept/decline clears it).
 *
 * `internal`, not `private`: lets a JVM test drive this directly against a
 * scripted phase/gate combination without composing anything — same
 * "extract for testability" convention as
 * [shouldOpenGuidedCalibrationPaneAfterRecalibrateRequest]. What this
 * function alone does NOT cover — actually resetting [SessionScreen]'s
 * local `showCalibration`/`showDeviceReview` flags so a sheet closed by
 * rule 1 doesn't resurrect itself the moment the phase returns to ACTIVE —
 * is the `LaunchedEffect` in [SessionScreen] above; that's Compose wiring,
 * not decision logic, and is called out as the one "needs a device pass"
 * item (no overlay-flicker artifact during the transition) in this
 * ticket's own acceptance criteria.
 */
internal fun shouldShowCalibrationSheet(
    sheetRequested: Boolean,
    phase: SessionPhase,
    firstContactGate: FirstContactGateState?,
): Boolean = sheetRequested && firstContactGate == null && phase.toPhaseGroup() == PhaseGroup.ACTIVE

/**
 * CFX-05 (tech-req §2.6 "Entry points"): the open-shelf action shared by
 * IdleContent's and ActiveContent's "Devices" entry points — flips the
 * screen-local `showDeviceReview` flag and fires the ViewModel's
 * [com.jointheparty.app.ui.session.SessionViewModel.openDeviceShelf] call,
 * exactly as the pre-CFX-05 inline lambda on ActiveContent's entry point
 * did. Extracted to a plain function (not a private inline lambda per call
 * site) for two reasons: it lets a JVM test assert the wiring directly —
 * same "extract for testability" convention as
 * [shouldOpenGuidedCalibrationPaneAfterRecalibrateRequest] — and it
 * guarantees IDLE's and ACTIVE's entry points can never drift into
 * behaving differently from each other over time.
 */
internal fun openDeviceShelfAction(
    setShowDeviceReview: (Boolean) -> Unit,
    onOpenDeviceShelf: () -> Unit,
): () -> Unit = {
    setShowDeviceReview(true)
    onOpenDeviceShelf()
}

/**
 * CFX-02 (tech-req §2.6 "Recalibration targeting"): [SessionScreen]'s
 * "Calibrate again" pane-swap decision, extracted as a plain function so
 * it's unit-testable without composing anything — this file's sheet/pane
 * `remember`ed booleans aren't otherwise reachable from a JVM test.
 * [requestRecalibrate] mirrors [SessionViewModel.requestRecalibrate]'s
 * return value: true exactly when a measurement actually started. The
 * guided-calibration pane must open if and only if that's true — CFX-02's
 * bug was this happening unconditionally, which swapped the sheet into a
 * guided flow titled with whatever device happened to be connected even
 * when [SessionViewModel.requestRecalibrate] silently declined (mismatched
 * routeId) to start anything against it.
 */
internal fun shouldOpenGuidedCalibrationPaneAfterRecalibrateRequest(
    requestRecalibrate: () -> Boolean,
): Boolean = requestRecalibrate()

/**
 * Field request: the song's current position, quiet fine print under the
 * track identity. 1 Hz collection isolated to this leaf (meter-style).
 */
@Composable
private fun PlaybackClock(positionMs: Flow<Long>) {
    val pos by positionMs.collectAsState(initial = -1L)
    if (pos < 0) return
    val totalSec = pos / 1000
    Text(
        text = "%d:%02d".format(totalSec / 60, totalSec % 60),
        style = BilletType.fine,
        color = DT.Colors.ink3,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** FIELD DEBUG overlay — reads [DebugLog]; recomposition-cost irrelevant here. */
@Composable
private fun DebugOverlay(modifier: Modifier = Modifier) {
    val lines by com.jointheparty.app.debug.DebugLog.lines.collectAsState()
    // UX audit #4: field listeners shouldn't have to watch logs scroll.
    var dismissed by remember { mutableStateOf(false) }
    if (lines.isEmpty() || dismissed) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { dismissed = true },
    ) {
        lines.forEach {
            Text(
                text = it,
                color = DT.Colors.ink2,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Coarser than [SessionPhase]: only the buckets that change this screen's shape. */
private enum class PhaseGroup { IDLE, WAITING, ACTIVE, LOST, CONCIERGE }

private fun SessionPhase.toPhaseGroup(): PhaseGroup = when (this) {
    SessionPhase.IDLE -> PhaseGroup.IDLE
    SessionPhase.LISTENING, SessionPhase.MATCHING -> PhaseGroup.WAITING
    SessionPhase.AIMING, SessionPhase.CONVERGING, SessionPhase.LOCKED, SessionPhase.DRIFTING -> PhaseGroup.ACTIVE
    SessionPhase.LOST -> PhaseGroup.LOST
    SessionPhase.NEEDS_SPOTIFY, SessionPhase.NEEDS_PREMIUM, SessionPhase.ERROR -> PhaseGroup.CONCIERGE
}

/**
 * IDLE (§4): the invitation IS the screen. One whisper-quiet secondary
 * action beneath it — the PKCE account link (AUTH-02), needed before the
 * Web API can be called on the user's behalf.
 *
 * CFX-05 (tech-req §2.6 "Entry points" / ui-ux §6.5): also carries the
 * shelf/device-review entry point — calibrating a device before joining a
 * party, and reviewing known devices between sessions, are both legitimate
 * uses ui-ux §6.5's empty-state copy is written for exactly this moment
 * ("Play something through a speaker or headphones and JoinTheParty will
 * get to know it").
 *
 * A single "Devices" label, not "Devices" + "Calibrate" as [ActiveContent]
 * has: [onOpenDeviceShelf] here opens the SAME sheet, and the device
 * shelf's own empty state ("Calibrate this device," `DeviceShelf.kt`)
 * already offers a calibration action once inside it. Outside a session
 * there is also no meaningfully "active route" to calibrate ahead of time
 * — [com.jointheparty.app.ui.session.SyncState.routeId] defaults to
 * `"speaker"` with no real connection behind it yet — so a bare standalone
 * "Calibrate" here would just duplicate the shelf's own empty-state entry
 * under a second, redundant idiom. One quiet label keeps IDLE's "the
 * invitation IS the screen" restraint (§4) intact: this is a second
 * quiet-register action alongside Connect Spotify, not a doubling of the
 * "single quiet entry point" ui-ux §4 already promises.
 */
@Composable
private fun IdleContent(
    onJoinTap: () -> Unit,
    onConnectSpotify: () -> Unit = {},
    spotifyLinked: Boolean = false,
    onOpenDeviceShelf: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            JoinButton(onClick = onJoinTap)
            // Field feedback: don't invite a connection that already exists.
            if (spotifyLinked) {
                Text(
                    text = "Spotify linked",
                    style = BilletType.fine,
                    color = DT.Colors.ink3,
                    modifier = Modifier.padding(top = DT.Space.sectionGap),
                )
            } else {
                Text(
                    text = "Connect Spotify",
                    style = BilletType.label,
                    color = DT.Colors.ink3,
                    modifier = Modifier
                        .padding(top = DT.Space.sectionGap)
                        .clickable(onClick = onConnectSpotify),
                )
            }
            // CFX-05: same register as "Devices" in ActiveContent's own
            // quiet settings-tier row (§4) — label/ink3, no icon chrome, no
            // separate settings screen.
            Text(
                text = "Devices",
                style = BilletType.label,
                color = DT.Colors.ink3,
                modifier = Modifier
                    .padding(top = DT.Space.grid)
                    .clickable(onClick = onOpenDeviceShelf),
            )
        }
    }
}

/**
 * LISTENING / MATCHING: quiet phase text only — no meter, no track block, no
 * spinner or progress bar (Billet rejects gamified "working…" UI wholesale).
 * [level] drives the phase word's opacity (CAL-06, §6.1) — collected inside
 * [PhaseWord] alone, never here.
 */
@Composable
private fun WaitingContent(phase: SessionPhase, level: Flow<Float>, onCancel: () -> Unit = {}) {
    val word = when (phase) {
        SessionPhase.LISTENING -> "Listening…"
        SessionPhase.MATCHING -> "Matching…"
        else -> ""
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PhaseWord(word = word, level = level)
            // UX audit #1: the waiting states were inescapable.
            Text(
                text = "Cancel",
                style = BilletType.label,
                color = DT.Colors.ink3,
                modifier = Modifier
                    .padding(top = DT.Space.sectionGap)
                    .clickable(onClick = onCancel),
            )
        }
    }
}

/** Below this level the input is treated as "quiet" for Reduced Motion's two-value quantization. */
private const val REDUCED_MOTION_LEVEL_THRESHOLD = 0.5f

/** Opacity floor (silence) and the per-level multiplier, per §6.1 "Before the meter": `0.55 + 0.45 × level`. */
private const val PHASE_WORD_OPACITY_FLOOR = 0.55f
private const val PHASE_WORD_OPACITY_RANGE = 0.45f

private fun phaseWordOpacity(level: Float): Float =
    PHASE_WORD_OPACITY_FLOOR + PHASE_WORD_OPACITY_RANGE * level.coerceIn(0f, 1f)

/**
 * CAL-06: LISTENING/MATCHING's mic-reactive phase word (ui-ux §6.1 "Before
 * the meter"). Text color stays a constant [DT.Colors.ink] — the "ink2 at
 * rest, brightening toward ink" effect the spec describes is produced
 * entirely by alpha compositing that fixed ink value over [DT.Colors.void]
 * (never by swapping color tokens), which is also how the "no color other
 * than ink/ink2/ink3" constraint stays trivially true: only `ink` is ever
 * used here.
 *
 * Two-stream rule (technical-requirements.md §2.1/§2.3), same idiom as
 * [SyncMeter]: [level] is collected HERE, in this leaf alone, written into
 * an [Animatable] driven by the `settle` spring (ω=[DT.Motion.settleOmega]),
 * and that animated value is read only inside [Modifier.graphicsLayer]'s
 * lambda — a draw/layout-phase read, so a new sample invalidates this
 * text's layer alone. Nothing above this composable (WaitingContent,
 * SessionScreen's root) ever recomposes because the level ticked.
 */
@Composable
private fun PhaseWord(word: String, level: Flow<Float>) {
    val context = LocalContext.current
    val reducedMotion = remember { isReducedMotionEnabled(context) }
    val opacity = remember { Animatable(PHASE_WORD_OPACITY_FLOOR) }
    var quantizedBright by remember { mutableStateOf(false) }

    LaunchedEffect(level, reducedMotion) {
        level.collect { raw ->
            val clamped = raw.coerceIn(0f, 1f)
            if (reducedMotion) {
                // Reduced Motion (§5): no continuous tracking — the level
                // quantizes to dim/bright and only crossfades (200ms, tween)
                // when that quantized state actually flips.
                val bright = clamped >= REDUCED_MOTION_LEVEL_THRESHOLD
                if (bright != quantizedBright) {
                    quantizedBright = bright
                    launch {
                        opacity.animateTo(
                            targetValue = phaseWordOpacity(if (bright) 1f else 0f),
                            animationSpec = tween(DT.Motion.reducedMotionCrossfadeMs.toInt()),
                        )
                    }
                }
            } else {
                // Full motion: eased continuously through `settle` so a
                // stray syllable doesn't flicker it (§6.1).
                launch {
                    opacity.animateTo(
                        targetValue = phaseWordOpacity(clamped),
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = DT.Motion.settleOmega * DT.Motion.settleOmega,
                        ),
                    )
                }
            }
        }
    }

    Text(
        text = word,
        style = BilletType.title,
        color = DT.Colors.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier.graphicsLayer { alpha = opacity.value },
    )
}

/**
 * AIMING / CONVERGING / LOCKED / DRIFTING: the full four-element layout —
 * track identity, then the meter (which draws its own ms readout), then the
 * wheel pinned toward the bottom via the flexible spacer between them.
 */
@Composable
private fun ActiveContent(
    state: SyncState,
    meterFrames: Flow<MeterFrame>,
    onTrimChange: (Int) -> Unit,
    onTrimCommit: (Int) -> Unit,
    onOpenCalibration: () -> Unit = {},
    onOpenDeviceShelf: () -> Unit = {},
    playbackPositionMs: Flow<Long> = MutableStateFlow(-1L),
    onLeaveSession: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TrackIdentity(track = state.track, phase = state.phase)
        PlaybackClock(playbackPositionMs)
        Spacer(modifier = Modifier.height(DT.Space.sectionGap))
        SyncMeter(frames = meterFrames)
        // INT-04: transient self-hearing hint (speaker mode heard our own
        // playback; the guard discarded the fix). Cleared by the ViewModel
        // on the next accepted estimate. Quiet fine print, never a warning
        // color — this is expected behavior, not an error.
        if (state.lastRejectReason == SyncCore.RejectReason.SELF_HEARING) {
            Text(
                text = "Hearing our own speaker — listening past it…",
                style = BilletType.fine,
                color = DT.Colors.ink3,
                modifier = Modifier.padding(top = DT.Space.grid),
            )
        }
        // Flexible gap, floored at sectionGap, pushes the wheel to the
        // bottom third of the screen without a fixed offset.
        Spacer(modifier = Modifier.weight(1f).heightIn(min = DT.Space.sectionGap))
        NudgeWheel(
            trimMs = state.nudgeMs,
            routeName = state.routeName,
            onTrimChange = onTrimChange,
            onTrimCommit = onTrimCommit,
        )
        // Quiet settings-tier actions (§4): calibration entry and — UX
        // audit #1 — the session's exit.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DT.Space.grid),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Leave the party",
                style = BilletType.label,
                color = DT.Colors.ink3,
                modifier = Modifier.clickable(onClick = onLeaveSession),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // CAL-08: the shelf's quiet entry — same register as
                // "Calibrate" beside it (ui-ux §4's "single quiet entry
                // point"), no icon chrome, no separate settings screen.
                Text(
                    text = "Devices",
                    style = BilletType.label,
                    color = DT.Colors.ink3,
                    modifier = Modifier.clickable(onClick = onOpenDeviceShelf),
                )
                Text(
                    text = "Calibrate",
                    style = BilletType.label,
                    color = DT.Colors.ink3,
                    modifier = Modifier.clickable(onClick = onOpenCalibration),
                )
            }
        }
    }
}

/**
 * Title + artist (§4/§6): title in [BilletType.title]/`ink`, artist in
 * [BilletType.subtitle]/`ink2`. Falls back to the phase word for the brief
 * window a phase transition lands before [SyncState.track] is populated
 * (e.g. the AIMING frame emitted by [SessionViewModel.transition] just
 * ahead of its track-copy update).
 */
@Composable
private fun TrackIdentity(track: TrackInfo?, phase: SessionPhase) {
    if (track != null) {
        Column {
            // TODO(UI-05b): artwork — needs image loading, out of scope here.
            Text(text = track.title, style = BilletType.title, color = DT.Colors.ink)
            Text(text = track.artist, style = BilletType.subtitle, color = DT.Colors.ink2)
        }
    } else {
        Text(text = activePhaseWord(phase), style = BilletType.title, color = DT.Colors.ink2)
    }
}

private fun activePhaseWord(phase: SessionPhase): String = when (phase) {
    SessionPhase.AIMING -> "Aiming…"
    SessionPhase.CONVERGING -> "Converging…"
    SessionPhase.LOCKED -> "Locked"
    SessionPhase.DRIFTING -> "Drifting…"
    else -> ""
}

/**
 * NEEDS_SPOTIFY / NEEDS_PREMIUM: the graceful concierge moment (§6.4) — never
 * a dead end, never a dark pattern. Explains what happened and why, offers
 * one primary action that clearly leaves the app (Spotify's install page or
 * its Premium plans page — both opened by the Activity), and one honest
 * secondary action that keeps the recognition-only experience already
 * running. This composable is purely a renderer of whatever phase it's
 * given; it holds no dismissal memory itself.
 *
 * TODO(UI-06 follow-up): §6.4 point 4 ("never repeat the gate more than once
 * per session after dismissal") is NOT yet enforced — [SessionViewModel]'s
 * transition table currently allows any phase → NEEDS_SPOTIFY/NEEDS_PREMIUM
 * unconditionally (see its `isLegalTransition`), so the gate can currently
 * reappear more than once in a session. That's state-machine-owned and out
 * of this ticket's file list (a concurrent change owns SessionViewModel.kt).
 *
 * ERROR keeps UI-05's quiet-text/tap-to-retry treatment; it isn't part of
 * the §6.4 gate copy.
 */
@Composable
private fun ConciergeContent(
    phase: SessionPhase,
    onJoinTap: () -> Unit,
    onGetSpotify: () -> Unit,
    onSeePremiumPlans: () -> Unit,
) {
    when (phase) {
        SessionPhase.NEEDS_SPOTIFY -> ConciergeGate(
            title = "Spotify isn't installed",
            body = "JoinTheParty drives your Spotify app to play the room in sync — " +
                "you'll need Spotify installed on this device for that.",
            primaryLabel = "Get Spotify",
            onPrimary = onGetSpotify,
            onSecondary = onJoinTap,
        )
        // TODO(UI-06c): App Remote cannot distinguish "user hasn't granted
        // this app access" from "account lacks Premium" — both arrive as
        // UserNotAuthorizedException. Copy now covers both honestly rather
        // than asserting a Premium problem the user may not have.
        SessionPhase.NEEDS_PREMIUM -> ConciergeGate(
            title = "Syncing needs Spotify Premium",
            body = "JoinTheParty drives your Spotify app and seeks to the exact beat — " +
                "Spotify only allows seeking on Premium accounts.",
            primaryLabel = "See Premium plans",
            onPrimary = onSeePremiumPlans,
            onSecondary = onJoinTap,
        )
        // Reached via lost-track exhaustion OR the recognition sampling cap
        // (UX audit #2) — either way the honest message is the same.
        SessionPhase.ERROR ->
            QuietMessage("Couldn't find the song — tap to try again", onTap = onJoinTap)
        else -> Unit
    }
}

/**
 * Shared concierge layout: title ([BilletType.title]/`ink`), body
 * ([BilletType.body]/`ink2`, capped near 65 characters per line via
 * [widthIn]), a `brass`-fill primary pill (§6.3 — the screen's only warm
 * accent, fine here since no meter is visible in this phase), and an
 * outlined secondary pill for the "Keep identifying songs" degradation path
 * both gates share.
 */
@Composable
private fun ConciergeGate(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(DT.Space.gutter), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = BilletType.title, color = DT.Colors.ink, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(DT.Space.grid))
            Text(
                text = body,
                style = BilletType.body,
                color = DT.Colors.ink2,
                textAlign = TextAlign.Center,
                // ~65ch at BilletType.body's 15sp.
                modifier = Modifier.widthIn(max = 300.dp),
            )
            // TODO(UI-06b): Spotify brand attribution (their logo, their
            // green, untouched, per Spotify's brand guidelines) — assets
            // aren't available yet, so these stay text-only pills for now.
            Spacer(modifier = Modifier.height(DT.Space.sectionGap))
            PrimaryPill(label = primaryLabel, onClick = onPrimary)
            Spacer(modifier = Modifier.height(DT.Space.grid))
            SecondaryPill(label = "Keep identifying songs", onClick = onSecondary)
        }
    }
}

/** Primary pill (§6.3): `brass` fill, `void` text, [BilletType.label]. */
@Composable
private fun PrimaryPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(DT.Colors.brass)
            .clickable(onClick = onClick)
            .padding(horizontal = DT.Space.gutter, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = BilletType.label, color = DT.Colors.void)
    }
}

/** Secondary pill (§6.3): no fill, 1px `hairline` outline, `ink` text. */
@Composable
private fun SecondaryPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .border(width = 1.dp, color = DT.Colors.hairline, shape = RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = DT.Space.gutter, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = BilletType.label, color = DT.Colors.ink)
    }
}

/** Shared quiet-text centerpiece used by LOST, WAITING, and the ERROR concierge phase. */
@Composable
private fun QuietMessage(
    text: String,
    style: TextStyle = BilletType.title,
    color: Color = DT.Colors.ink2,
    onTap: (() -> Unit)? = null,
) {
    val tappable = if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier
    Box(
        modifier = Modifier.fillMaxSize().then(tappable),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = style, color = color, textAlign = TextAlign.Center)
    }
}

/**
 * Primary pill (§6.3): `brass` fill, `void` text, [BilletType.label]. The
 * screen's one-and-only warm accent — see the [SessionScreen] doc comment.
 */
@Composable
private fun JoinButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(DT.Colors.brass)
            .clickable(onClick = onClick)
            .padding(horizontal = DT.Space.gutter, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Join the party", style = BilletType.label, color = DT.Colors.void)
    }
}

// ---- Previews ---------------------------------------------------------------

@Preview(name = "Idle — the invitation", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun SessionScreenIdlePreview() {
    BilletTheme {
        SessionScreen(
            state = SyncState(phase = SessionPhase.IDLE),
            meterFrames = MutableStateFlow(MeterFrame.Initial),
            onJoinTap = {},
            onTrimChange = {},
            onTrimCommit = {},
            onGetSpotify = {},
            onSeePremiumPlans = {},
        )
    }
}

@Preview(name = "Listening — silence (0.55 floor)", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun SessionScreenListeningPreview() {
    BilletTheme {
        SessionScreen(
            state = SyncState(phase = SessionPhase.LISTENING),
            meterFrames = MutableStateFlow(MeterFrame.Initial),
            onJoinTap = {},
            onTrimChange = {},
            onTrimCommit = {},
            onGetSpotify = {},
            onSeePremiumPlans = {},
            inputLevel = MutableStateFlow(0f),
        )
    }
}

/** CAL-06: level pinned near its ceiling — phase word brightening toward `ink`. */
@Preview(name = "Listening — mic-bright", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun SessionScreenListeningBrightPreview() {
    BilletTheme {
        SessionScreen(
            state = SyncState(phase = SessionPhase.LISTENING),
            meterFrames = MutableStateFlow(MeterFrame.Initial),
            onJoinTap = {},
            onTrimChange = {},
            onTrimCommit = {},
            onGetSpotify = {},
            onSeePremiumPlans = {},
            inputLevel = MutableStateFlow(1f),
        )
    }
}

@Preview(name = "Locked — track, meter, wheel", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun SessionScreenLockedPreview() {
    BilletTheme {
        SessionScreen(
            state = SyncState(
                phase = SessionPhase.LOCKED,
                track = TrackInfo(
                    spotifyUri = "spotify:track:preview",
                    isrc = "USRC12345678",
                    title = "Nightcall",
                    artist = "Kavinsky",
                    durationMs = 246_000L,
                ),
                nudgeMs = -35,
                routeId = "bluetooth",
                routeName = "AirPods Pro",
            ),
            meterFrames = MutableStateFlow(
                MeterFrame(errorMs = 2.0, driftPpm = 10.0, confidence = 0.97f, converged = true),
            ),
            onJoinTap = {},
            onTrimChange = {},
            onTrimCommit = {},
            onGetSpotify = {},
            onSeePremiumPlans = {},
        )
    }
}

@Preview(name = "Concierge — needs Spotify", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun SessionScreenNeedsSpotifyPreview() {
    BilletTheme {
        SessionScreen(
            state = SyncState(phase = SessionPhase.NEEDS_SPOTIFY),
            meterFrames = MutableStateFlow(MeterFrame.Initial),
            onJoinTap = {},
            onTrimChange = {},
            onTrimCommit = {},
            onGetSpotify = {},
            onSeePremiumPlans = {},
        )
    }
}

@Preview(name = "Concierge — needs Premium", showBackground = true, backgroundColor = 0xFF131110)
@Composable
private fun SessionScreenNeedsPremiumPreview() {
    BilletTheme {
        SessionScreen(
            state = SyncState(phase = SessionPhase.NEEDS_PREMIUM),
            meterFrames = MutableStateFlow(MeterFrame.Initial),
            onJoinTap = {},
            onTrimChange = {},
            onTrimCommit = {},
            onGetSpotify = {},
            onSeePremiumPlans = {},
        )
    }
}
