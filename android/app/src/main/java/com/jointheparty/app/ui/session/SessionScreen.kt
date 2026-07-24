package com.jointheparty.app.ui.session

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.ui.components.CalibrationSheet
import com.jointheparty.app.ui.components.NudgeWheel
import com.jointheparty.app.ui.components.SyncMeter
import com.jointheparty.app.ui.model.MeterFrame
import com.jointheparty.app.ui.theme.BilletTheme
import com.jointheparty.app.ui.theme.BilletType
import com.jointheparty.app.ui.theme.DT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
 * (technical-requirements.md §2.3 — [meterFrames] is a bare pass-through,
 * never folded into [state]) all stay out of this file entirely.
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
    onConnectSpotify: () -> Unit = {},
) {
    var showCalibration by remember { mutableStateOf(false) }
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
            targetState = state.phase.toPhaseGroup(),
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(DT.Motion.reducedMotionCrossfadeMs.toInt()),
            label = "session-phase",
        ) { group ->
            when (group) {
                PhaseGroup.IDLE -> IdleContent(
                    onJoinTap = onJoinTap,
                    onConnectSpotify = onConnectSpotify,
                )
                PhaseGroup.WAITING -> WaitingContent(phase = state.phase)
                PhaseGroup.ACTIVE -> ActiveContent(
                    state = state,
                    meterFrames = meterFrames,
                    onTrimChange = onTrimChange,
                    onTrimCommit = onTrimCommit,
                    onOpenCalibration = { showCalibration = true },
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

        if (showCalibration) {
            CalibrationSheet(
                routeName = state.routeName,
                calibration = state.calibration,
                onStart = onStartCalibration,
                onCancel = onCancelCalibration,
                onDismiss = {
                    showCalibration = false
                    onDismissCalibration()
                },
            )
        }
    }
}

/** FIELD DEBUG overlay — reads [DebugLog]; recomposition-cost irrelevant here. */
@Composable
private fun DebugOverlay(modifier: Modifier = Modifier) {
    val lines by com.jointheparty.app.debug.DebugLog.lines.collectAsState()
    if (lines.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
 */
@Composable
private fun IdleContent(onJoinTap: () -> Unit, onConnectSpotify: () -> Unit = {}) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            JoinButton(onClick = onJoinTap)
            Text(
                text = "Connect Spotify",
                style = BilletType.label,
                color = DT.Colors.ink3,
                modifier = Modifier
                    .padding(top = DT.Space.sectionGap)
                    .clickable(onClick = onConnectSpotify),
            )
        }
    }
}

/**
 * LISTENING / MATCHING: quiet phase text only — no meter, no track block, no
 * spinner or progress bar (Billet rejects gamified "working…" UI wholesale).
 */
@Composable
private fun WaitingContent(phase: SessionPhase) {
    val word = when (phase) {
        SessionPhase.LISTENING -> "Listening…"
        SessionPhase.MATCHING -> "Matching…"
        else -> ""
    }
    QuietMessage(word)
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TrackIdentity(track = state.track, phase = state.phase)
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
        // INT-03: the quiet calibration entry point — a label, not a button
        // chrome (§4: settings-tier actions stay whisper-quiet).
        Text(
            text = "Calibrate",
            style = BilletType.label,
            color = DT.Colors.ink3,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = DT.Space.grid)
                .clickable(onClick = onOpenCalibration),
        )
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
        SessionPhase.NEEDS_PREMIUM -> ConciergeGate(
            title = "Syncing needs Spotify Premium",
            body = "JoinTheParty drives your Spotify app and seeks to the exact beat — " +
                "Spotify only allows seeking on Premium accounts.",
            primaryLabel = "See Premium plans",
            onPrimary = onSeePremiumPlans,
            onSecondary = onJoinTap,
        )
        SessionPhase.ERROR -> QuietMessage("Something broke — tap to retry", onTap = onJoinTap)
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

@Preview(name = "Listening — quiet text only", showBackground = true, backgroundColor = 0xFF131110)
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
