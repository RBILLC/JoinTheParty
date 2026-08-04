package com.jointheparty.app.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * UI-02: the public surface of [SyncCore] extracted as an interface so
 * `SessionViewModel` is JVM-unit-testable without loading the native
 * library (`System.loadLibrary` runs in [SyncCore]'s companion object,
 * which a plain JVM test can't satisfy). [SyncCore] implements this
 * directly; tests substitute a fake.
 *
 * Deliberately excludes the real-time/audio-thread and reference/
 * calibration surface (`pushCapture`, `pushReference`, `setAecMode`,
 * `beginCalibration`, `cancelCalibration`) — none of that is
 * ViewModel-driven per technical-requirements.md §2.3.
 *
 * `startCapture`/`stopCapture` (NAT-02) are the exception: they don't touch
 * the audio thread themselves, only start/stop the native Oboe capture
 * stream that runs on it, so a ViewModel can drive them off session
 * lifecycle (join → startCapture, leave/background → stopCapture) same as
 * every other control-plane call here.
 */
interface SyncEngine {

    /** Every engine event, in order, fan-out to any number of collectors. */
    val events: SharedFlow<SyncCore.Event>

    /** ≤15 Hz estimate stream for the sync meter; conflated per collector. */
    val meterFrames: Flow<SyncCore.Event.SyncEstimate>

    /**
     * CAL-05 (technical-requirements.md §2.1): smoothed capture input
     * level, normalized 0..1, attack ~10 ms / release ~300 ms envelope —
     * computed on SyncCore's worker thread from `sc_get_input_level`,
     * independent of calibration and the estimator. Unlike [meterFrames]
     * it's live before the first recognition fix and during calibration
     * (`SC_EVT_SYNC_ESTIMATE` doesn't exist until a fix lands, which would
     * leave LISTENING/MATCHING dark — docs/ux-audit-2026-07.md #8), and it
     * reports ~0 for free whenever capture is idle or was never started.
     *
     * A polled getter wrapped in a cold [Flow], not a new event type — same
     * high-frequency stream family as [meterFrames]: polled at ≤15 Hz,
     * never folded into `SyncState`, never observed by the session screen
     * root. Polling starts when a collector subscribes and stops the
     * instant collection stops, same idiom as
     * `SessionViewModel.playbackPositionMs`. (The mic-reactive UI
     * treatment that actually consumes this is a separate ticket, CAL-06 —
     * this seam is deliberately unwired from any UI.)
     */
    fun inputLevel(): Flow<Float>

    /**
     * Opens and starts the Oboe input stream, pushing capture audio
     * straight into SyncCore (no JNI on the audio thread — see
     * android/app/src/main/cpp/audio_capture.h). Returns false if the
     * stream couldn't be opened/started or the device negotiated a format
     * other than 48 kHz mono.
     */
    fun startCapture(): Boolean

    /** Stops the Oboe capture stream. Idempotent; safe if never started. */
    fun stopCapture()

    fun setUserNudgeMs(nudgeMs: Int): Boolean

    fun setOutputRoute(route: SyncCore.Route, latencyPriorMs: Int): Boolean

    /** INT-04: speaker route → FULL, headphone routes → OFF (arch §7). */
    fun setAecMode(mode: SyncCore.AecMode): Boolean

    /**
     * INT-03: chirp calibration (arch §6.4). Call at the instant chirp
     * playback is commanded, with capture running; the engine answers with
     * [SyncCore.Event.CalibrationResult] (valid=false on the 8 s timeout).
     */
    fun beginCalibration(): Boolean
    fun cancelCalibration(): Boolean

    /**
     * CAL-03: requests one acoustic-referee measurement (technical-
     * requirements.md §2.6) — fire-and-forget, the result arrives as
     * [SyncCore.Event.LatencyResidual] on [events]. Aggregating repeated
     * samples into a calibration profile is shell-side (CAL-04).
     */
    fun sampleLatencyResidual(): Boolean

    fun notifySeekIssued(targetMs: Long, issuedMonoNs: Long): Boolean

    fun notifyLocalPlayback(commandedPositionMs: Long): Boolean

    /**
     * CTL-01b (technical-requirements.md §2.9): echoes an executed
     * [SyncCore.Event.ActiveProbe] — the shell must call this only after
     * actually pausing playback, waiting `pauseMs`, and resuming; mirrors
     * [notifySeekIssued]'s echo shape over the new `sc_notify_probe_executed`
     * ABI call.
     */
    fun notifyProbeExecuted(): Boolean

    /**
     * DSP-03b (technical-requirements.md §2.12): echoes an executed
     * [SyncCore.Event.ActiveDuck] — the shell must call this only after
     * actually ducking `STREAM_MUSIC`, waiting `duckMs`, and restoring the
     * original volume; mirrors [notifyProbeExecuted]'s echo shape over the
     * new `sc_notify_duck_executed` ABI call. [achievedDeciDb] is the depth
     * ACTUALLY commanded (tenths of a dB) — never the nominal 60, since
     * volume-index quantization means -6.0 dB exactly is rarely reachable.
     */
    fun notifyDuckExecuted(achievedDeciDb: Int): Boolean

    fun submitPlayerState(positionMs: Long, isPaused: Boolean, receivedMonoNs: Long): Boolean

    fun submitRecognitionFix(
        source: SyncCore.FixSource,
        matchOffsetMs: Long,
        captureMonoNs: Long,
        frequencySkew: Double,
        confidence: Float,
    ): Boolean

    fun commandLatencyMs(): Int

    /**
     * NAT-06b: copies the newest post-AEC capture (mono 48 kHz float,
     * chronological) into [out]; null until any audio has been captured.
     * [CaptureWindow.endMonoNs] timestamps the LAST copied frame — the
     * pairing recognition needs (a match offset references sample end).
     */
    fun copyRecentCapture(out: FloatArray): CaptureWindow?

    data class CaptureWindow(val frames: Int, val endMonoNs: Long)

    fun close()
}
