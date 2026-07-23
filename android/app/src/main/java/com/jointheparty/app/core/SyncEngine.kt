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

    fun notifySeekIssued(targetMs: Long, issuedMonoNs: Long): Boolean

    fun notifyLocalPlayback(commandedPositionMs: Long): Boolean

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
