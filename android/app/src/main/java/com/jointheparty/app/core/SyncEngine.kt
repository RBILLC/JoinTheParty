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
 */
interface SyncEngine {

    /** Every engine event, in order, fan-out to any number of collectors. */
    val events: SharedFlow<SyncCore.Event>

    /** ≤15 Hz estimate stream for the sync meter; conflated per collector. */
    val meterFrames: Flow<SyncCore.Event.SyncEstimate>

    fun setUserNudgeMs(nudgeMs: Int): Boolean

    fun setOutputRoute(route: SyncCore.Route, latencyPriorMs: Int): Boolean

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

    fun close()
}
