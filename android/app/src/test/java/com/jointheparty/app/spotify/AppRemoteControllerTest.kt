package com.jointheparty.app.spotify

import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.core.SyncEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NAT-08 acceptance slice (JVM, no Robolectric — see
 * [AppRemoteSpotifyController]'s doc comment for why [context] is
 * nullable): the two behaviors that don't require an emulator to verify —
 * disconnected seekTo doesn't echo a phantom settle window, and connect()
 * resolves to SpotifyMissing via the honest-but-inert stub taxonomy.
 */
class AppRemoteControllerTest {

    @Test
    fun seekToReturnsFalseWhenDisconnectedAndDoesNotEchoNotifySeekIssued() {
        val engine = FakeSyncEngine()
        val controller = AppRemoteSpotifyController(context = null, engine = engine)

        val result = controller.seekTo(12_345L)

        assertFalse(result)
        // No phantom settle window: a suppressed-but-never-issued seek must
        // not echo sc_notify_seek_issued, or the estimator would suppress
        // measurements for a correction that never actually happened.
        assertTrue(engine.notifySeekIssuedCalls.isEmpty())
    }

    @Test
    fun playReturnsFalseWhenDisconnected() {
        val engine = FakeSyncEngine()
        val controller = AppRemoteSpotifyController(context = null, engine = engine)

        val result = controller.play("spotify:track:abc")

        assertFalse(result)
        assertTrue(engine.notifyLocalPlaybackCalls.isEmpty())
    }

    @Test
    fun connectWithoutContextResolvesToSpotifyMissing() = runTest {
        // No Context is available in a plain JVM test; AppRemoteSpotifyController
        // documents that a null context short-circuits to SpotifyMissing —
        // the same terminal state the stubbed SpotifyAppRemote.connect()
        // would report anyway (it always fails with CouldNotFindSpotifyApp).
        val controller = AppRemoteSpotifyController(context = null, engine = FakeSyncEngine())

        val result = controller.connect()

        assertEquals(SpotifyController.ConnectionResult.SpotifyMissing, result)
    }

    @Test
    fun isConnectedFalseBeforeConnect() {
        val controller = AppRemoteSpotifyController(context = null, engine = FakeSyncEngine())

        assertFalse(controller.isConnected)
    }
}

/** Records calls; minimal SyncEngine fake — pattern copied from ui/session/SessionViewModelTest.kt's FakeSyncEngine. */
private class FakeSyncEngine : SyncEngine {
    private val eventFlow = MutableSharedFlow<SyncCore.Event>(extraBufferCapacity = 64)

    override val events: SharedFlow<SyncCore.Event> = eventFlow.asSharedFlow()
    override val meterFrames: Flow<SyncCore.Event.SyncEstimate> =
        events.filterIsInstance<SyncCore.Event.SyncEstimate>().conflate()

    val notifySeekIssuedCalls = mutableListOf<Pair<Long, Long>>()
    val notifyLocalPlaybackCalls = mutableListOf<Long>()
    val submitPlayerStateCalls = mutableListOf<Triple<Long, Boolean, Long>>()

    override fun startCapture(): Boolean = true

    override fun stopCapture() = Unit

    override fun setUserNudgeMs(nudgeMs: Int): Boolean = true

    override fun setOutputRoute(route: SyncCore.Route, latencyPriorMs: Int): Boolean = true

    override fun setAecMode(mode: SyncCore.AecMode): Boolean = true

    override fun beginCalibration(): Boolean = true

    override fun cancelCalibration(): Boolean = true

    override fun notifySeekIssued(targetMs: Long, issuedMonoNs: Long): Boolean {
        notifySeekIssuedCalls += targetMs to issuedMonoNs
        return true
    }

    override fun notifyLocalPlayback(commandedPositionMs: Long): Boolean {
        notifyLocalPlaybackCalls += commandedPositionMs
        return true
    }

    override fun submitPlayerState(positionMs: Long, isPaused: Boolean, receivedMonoNs: Long): Boolean {
        submitPlayerStateCalls += Triple(positionMs, isPaused, receivedMonoNs)
        return true
    }

    override fun submitRecognitionFix(
        source: SyncCore.FixSource,
        matchOffsetMs: Long,
        captureMonoNs: Long,
        frequencySkew: Double,
        confidence: Float,
    ): Boolean = true

    override fun commandLatencyMs(): Int = 250

    override fun close() = Unit
}
