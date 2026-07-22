package com.jointheparty.app.ui.session

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
) {
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
                PhaseGroup.IDLE -> IdleContent(onJoinTap = onJoinTap)
                PhaseGroup.WAITING -> WaitingContent(phase = state.phase)
                PhaseGroup.ACTIVE -> ActiveContent(
                    state = state,
                    meterFrames = meterFrames,
                    onTrimChange = onTrimChange,
                    onTrimCommit = onTrimCommit,
                )
                PhaseGroup.LOST -> QuietMessage("Lost the room — listening again…")
                PhaseGroup.CONCIERGE -> ConciergeContent(phase = state.phase, onJoinTap = onJoinTap)
            }
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

/** IDLE (§4): the invitation IS the screen — nothing else renders. */
@Composable
private fun IdleContent(onJoinTap: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        JoinButton(onClick = onJoinTap)
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TrackIdentity(track = state.track, phase = state.phase)
        Spacer(modifier = Modifier.height(DT.Space.sectionGap))
        SyncMeter(frames = meterFrames)
        // Flexible gap, floored at sectionGap, pushes the wheel to the
        // bottom third of the screen without a fixed offset.
        Spacer(modifier = Modifier.weight(1f).heightIn(min = DT.Space.sectionGap))
        NudgeWheel(
            trimMs = state.nudgeMs,
            routeName = state.routeName,
            onTrimChange = onTrimChange,
            onTrimCommit = onTrimCommit,
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
 * NEEDS_SPOTIFY / NEEDS_PREMIUM / ERROR: quiet-text placeholders standing in
 * for the real concierge screens (ui-ux-design-system.md §6.4).
 *
 * TODO(UI-06): replace with the full concierge treatment — title/body copy,
 * "See Premium plans" / "Get Spotify" primary actions, and the "Keep
 * identifying songs" graceful-degradation path.
 */
@Composable
private fun ConciergeContent(phase: SessionPhase, onJoinTap: () -> Unit) {
    when (phase) {
        // Tap = the §6.4 recognition-only degradation until UI-06's real
        // concierge lands ("Keep identifying songs").
        SessionPhase.NEEDS_SPOTIFY ->
            QuietMessage("Spotify not installed — tap to listen anyway", onTap = onJoinTap)
        SessionPhase.NEEDS_PREMIUM -> QuietMessage("Syncing needs Spotify Premium")
        SessionPhase.ERROR -> QuietMessage("Something broke — tap to retry", onTap = onJoinTap)
        else -> Unit
    }
}

/** Shared quiet-text centerpiece used by LOST, WAITING, and the concierge placeholders. */
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
        )
    }
}
