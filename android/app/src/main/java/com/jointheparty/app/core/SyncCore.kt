package com.jointheparty.app.core

import androidx.annotation.Keep
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterIsInstance

/**
 * NAT-04: Kotlin face of the SyncCore C ABI (core/include/synccore/synccore.h).
 *
 * Threading: native events arrive on SyncCore's worker thread and are
 * published with a non-suspending [MutableSharedFlow.tryEmit] into [events].
 * Collect on whatever dispatcher you like; the UI meter should collect
 * [meterFrames] (conflated — always renders the freshest estimate, per the
 * two-stream rule in technical-requirements.md §2.1).
 *
 * Note on the spec's `callbackFlow`: the engine has a single callback slot
 * but two mandated consumers (session store + meter). A per-collector
 * `callbackFlow` would re-register the native callback per collection, so
 * the bridge uses one SharedFlow instead; the contract (typed events, no
 * blocking of the native thread) is unchanged.
 *
 * Lifecycle: [close] is mandatory and idempotent; the native side joins its
 * worker before freeing anything, so no event is ever delivered to a closed
 * instance.
 */
class SyncCore(
    sampleRateHz: Int = 48_000,
    channels: Int = 1,
    initialRoute: Route = Route.SPEAKER,
    outputLatencyPriorMs: Int = -1,
    commandLatencyPriorMs: Int = -1,
) : AutoCloseable, SyncEngine {

    enum class Route { SPEAKER, WIRED, BLUETOOTH }
    enum class AecMode { OFF, PLATFORM_ONLY, FULL }
    enum class FixSource { SHAZAMKIT, ACRCLOUD }
    enum class RejectReason { SELF_HEARING, LOW_CONFIDENCE, SETTLING }

    sealed interface Event {
        data class SyncEstimate(
            val errorMs: Double,
            val driftPpm: Double,
            val confidence: Float,
            val converged: Boolean,
            val lastFixMonoNs: Long,
        ) : Event

        data class Correction(val seekToMs: Long) : Event

        data object RequestFix : Event

        data class FixRejected(val reason: RejectReason) : Event

        data object TrackLost : Event

        data class CalibrationResult(val latencyMs: Int, val valid: Boolean) : Event
    }

    private val eventFlow = MutableSharedFlow<Event>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every engine event, in order, fan-out to any number of collectors. */
    override val events: SharedFlow<Event> = eventFlow.asSharedFlow()

    /** ≤15 Hz estimate stream for the sync meter; conflated per collector. */
    override val meterFrames: Flow<Event.SyncEstimate> =
        events.filterIsInstance<Event.SyncEstimate>().conflate()

    private var handle: Long

    init {
        handle = nativeCreate(
            sampleRateHz, channels, initialRoute.ordinal,
            outputLatencyPriorMs, commandLatencyPriorMs,
        )
        require(handle != 0L) {
            "SyncCore rejected config (rate=$sampleRateHz ch=$channels): " +
                "only 48000 Hz mono is supported"
        }
    }

    fun pushCapture(samples: FloatArray, frames: Int, captureMonoNs: Long) =
        nativePushCapture(handle, samples, frames, captureMonoNs)

    // NOTE (UI-02): Kotlin forbids default parameter values on overriding
    // functions, so implementing SyncEngine.submitRecognitionFix required
    // dropping this method's `frequencySkew = 0.0` default. Call sites that
    // relied on it (SyncCoreBridgeTest) now pass 0.0 explicitly.
    override fun submitRecognitionFix(
        source: FixSource,
        matchOffsetMs: Long,
        captureMonoNs: Long,
        frequencySkew: Double,
        confidence: Float,
    ): Boolean = nativeSubmitRecognitionFix(
        handle, source.ordinal, matchOffsetMs, captureMonoNs, frequencySkew,
        confidence,
    ) == 0

    override fun submitPlayerState(positionMs: Long, isPaused: Boolean, receivedMonoNs: Long): Boolean =
        nativeSubmitPlayerState(handle, positionMs, isPaused, receivedMonoNs) == 0

    override fun setUserNudgeMs(nudgeMs: Int): Boolean = nativeSetUserNudgeMs(handle, nudgeMs) == 0

    override fun setOutputRoute(route: Route, latencyPriorMs: Int): Boolean =
        nativeSetOutputRoute(handle, route.ordinal, latencyPriorMs) == 0

    fun setAecMode(mode: AecMode): Boolean = nativeSetAecMode(handle, mode.ordinal) == 0

    override fun notifySeekIssued(targetMs: Long, issuedMonoNs: Long): Boolean =
        nativeNotifySeekIssued(handle, targetMs, issuedMonoNs) == 0

    override fun notifyLocalPlayback(commandedPositionMs: Long): Boolean =
        nativeNotifyLocalPlayback(handle, commandedPositionMs) == 0

    fun pushReference(samples: FloatArray, frames: Int, trackPositionMs: Long): Boolean =
        nativePushReference(handle, samples, frames, trackPositionMs) == 0

    fun beginCalibration(): Boolean = nativeBeginCalibration(handle) == 0

    fun cancelCalibration(): Boolean = nativeCancelCalibration(handle) == 0

    /**
     * Current (possibly learned) Spotify command latency. Persist per
     * device/route and pass back as [commandLatencyPriorMs] next session —
     * PM decision 2026-07-21: learning survives cold starts.
     */
    override fun commandLatencyMs(): Int = nativeGetCommandLatencyMs(handle)

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    /** Called from the native worker thread. Signature is ABI: (IDDDIIJ)V. */
    @Keep
    private fun onNativeEvent(
        type: Int, d0: Double, d1: Double, d2: Double, i0: Int, i1: Int, l0: Long,
    ) {
        val event = when (type) {
            0 -> Event.SyncEstimate(d0, d1, d2.toFloat(), i0 != 0, l0)
            1 -> Event.Correction(l0)
            2 -> Event.RequestFix
            3 -> Event.FixRejected(RejectReason.entries.getOrElse(i0) { RejectReason.LOW_CONFIDENCE })
            4 -> Event.TrackLost
            5 -> Event.CalibrationResult(i0, i1 != 0)
            else -> return
        }
        eventFlow.tryEmit(event)
    }

    private external fun nativeCreate(
        sampleRate: Int, channels: Int, route: Int,
        outputLatencyPriorMs: Int, commandLatencyPriorMs: Int,
    ): Long

    private external fun nativeDestroy(handle: Long)
    private external fun nativePushCapture(
        handle: Long, samples: FloatArray, frames: Int, captureMonoNs: Long,
    )

    private external fun nativeSubmitRecognitionFix(
        handle: Long, source: Int, matchOffsetMs: Long, captureMonoNs: Long,
        frequencySkew: Double, confidence: Float,
    ): Int

    private external fun nativeSubmitPlayerState(
        handle: Long, positionMs: Long, isPaused: Boolean, receivedMonoNs: Long,
    ): Int

    private external fun nativeSetUserNudgeMs(handle: Long, nudgeMs: Int): Int
    private external fun nativeSetOutputRoute(handle: Long, route: Int, latencyPriorMs: Int): Int
    private external fun nativeSetAecMode(handle: Long, mode: Int): Int
    private external fun nativeNotifySeekIssued(handle: Long, targetMs: Long, issuedMonoNs: Long): Int
    private external fun nativeNotifyLocalPlayback(handle: Long, commandedPositionMs: Long): Int
    private external fun nativePushReference(
        handle: Long, samples: FloatArray, frames: Int, trackPositionMs: Long,
    ): Int

    private external fun nativeBeginCalibration(handle: Long): Int
    private external fun nativeCancelCalibration(handle: Long): Int
    private external fun nativeGetCommandLatencyMs(handle: Long): Int

    companion object {
        init {
            System.loadLibrary("synccore_jni")
        }
    }
}
