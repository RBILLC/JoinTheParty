package com.jointheparty.app.ui.session

import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.core.SyncEngine
import com.jointheparty.app.data.NudgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * UI-02 acceptance: every legal §2.4 transition, the lost-track
 * auto-restart (max 3 → error, counter reset at lock), illegal transitions
 * being silently ignored, and nudge/route persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        engine: FakeSyncEngine = FakeSyncEngine(),
        nudgeStore: FakeNudgeStore = FakeNudgeStore(),
    ) = SessionViewModel(engine, nudgeStore, testDispatcher)

    @Test
    fun happyPathIdleToLocked() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)

        assertEquals(SessionPhase.IDLE, vm.syncState.value.phase)

        vm.startListening()
        assertEquals(SessionPhase.LISTENING, vm.syncState.value.phase)

        vm.onMatchInFlight()
        assertEquals(SessionPhase.MATCHING, vm.syncState.value.phase)

        vm.onTrackResolved(track())
        assertEquals(SessionPhase.AIMING, vm.syncState.value.phase)
        assertEquals("spotify:track:abc", vm.syncState.value.track?.spotifyUri)

        vm.onPlaybackStarted()
        assertEquals(SessionPhase.CONVERGING, vm.syncState.value.phase)

        engine.emit(estimate(converged = true))
        advanceUntilIdle()
        assertEquals(SessionPhase.LOCKED, vm.syncState.value.phase)
    }

    @Test
    fun lockedDriftsAndReconverges() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)
        driveToLocked(vm, engine)

        engine.emit(estimate(converged = false))
        advanceUntilIdle()
        assertEquals(SessionPhase.DRIFTING, vm.syncState.value.phase)

        engine.emit(estimate(converged = true))
        advanceUntilIdle()
        assertEquals(SessionPhase.LOCKED, vm.syncState.value.phase)
    }

    @Test
    fun trackLostAutoRestartsTwiceThenErrors() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)
        vm.startListening()

        engine.emit(SyncCore.Event.TrackLost)
        advanceUntilIdle()
        assertEquals(SessionPhase.LISTENING, vm.syncState.value.phase) // restart #1

        engine.emit(SyncCore.Event.TrackLost)
        advanceUntilIdle()
        assertEquals(SessionPhase.LISTENING, vm.syncState.value.phase) // restart #2

        engine.emit(SyncCore.Event.TrackLost)
        advanceUntilIdle()
        assertEquals(SessionPhase.ERROR, vm.syncState.value.phase) // 3rd loss → error
    }

    @Test
    fun lossCounterResetsAfterLock() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)

        vm.startListening()
        engine.emit(SyncCore.Event.TrackLost)
        advanceUntilIdle()
        engine.emit(SyncCore.Event.TrackLost)
        advanceUntilIdle()
        assertEquals(SessionPhase.LISTENING, vm.syncState.value.phase) // 2 losses so far

        // Drive all the way to LOCKED — this must reset the loss counter.
        vm.onMatchInFlight()
        vm.onTrackResolved(track())
        vm.onPlaybackStarted()
        engine.emit(estimate(converged = true))
        advanceUntilIdle()
        assertEquals(SessionPhase.LOCKED, vm.syncState.value.phase)

        // Two more losses should NOT trip ERROR — the counter was reset.
        engine.emit(SyncCore.Event.TrackLost)
        advanceUntilIdle()
        assertEquals(SessionPhase.LISTENING, vm.syncState.value.phase)
        engine.emit(SyncCore.Event.TrackLost)
        advanceUntilIdle()
        assertEquals(SessionPhase.LISTENING, vm.syncState.value.phase)
    }

    @Test
    fun illegalTransitionIsIgnored() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onPlaybackStarted() // requires AIMING; illegal straight from IDLE
        assertEquals(SessionPhase.IDLE, vm.syncState.value.phase)
    }

    @Test
    fun fixRejectedRecordsReasonWithoutChangingPhase() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)
        vm.startListening()

        assertNull(vm.syncState.value.lastRejectReason)
        engine.emit(SyncCore.Event.FixRejected(SyncCore.RejectReason.SELF_HEARING))
        advanceUntilIdle()

        assertEquals(SessionPhase.LISTENING, vm.syncState.value.phase) // unchanged
        assertEquals(SyncCore.RejectReason.SELF_HEARING, vm.syncState.value.lastRejectReason)
    }

    @Test
    fun nudgeCommitPersistsAndAppliesToEngine() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore()
        val vm = viewModel(engine, nudgeStore)

        vm.onNudgeCommitted(35)
        advanceUntilIdle()

        assertEquals(listOf(35), engine.nudgeCalls)
        assertEquals(35, nudgeStore.trims["speaker"])
        assertEquals(35, vm.syncState.value.nudgeMs)
    }

    @Test
    fun routeChangeLoadsPersistedTrimAndLatency() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            trims["bluetooth:AirPods Pro"] = -60
            latencies["bluetooth:AirPods Pro"] = 310
        }
        val vm = viewModel(engine, nudgeStore)

        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()

        assertEquals(listOf(-60), engine.nudgeCalls)
        assertEquals(listOf(SyncCore.Route.BLUETOOTH to 310), engine.routeCalls)
        assertEquals(-60, vm.syncState.value.nudgeMs)
        assertEquals("bluetooth:AirPods Pro", vm.syncState.value.routeId)
        assertEquals("AirPods Pro", vm.syncState.value.routeName)
    }

    private suspend fun TestScope.driveToLocked(vm: SessionViewModel, engine: FakeSyncEngine) {
        vm.startListening()
        vm.onMatchInFlight()
        vm.onTrackResolved(track())
        vm.onPlaybackStarted()
        engine.emit(estimate(converged = true))
        advanceUntilIdle()
    }

    private fun estimate(converged: Boolean) = SyncCore.Event.SyncEstimate(
        errorMs = 0.0,
        driftPpm = 0.0,
        confidence = 0.9f,
        converged = converged,
        lastFixMonoNs = 0L,
    )

    private fun track() = TrackInfo(
        spotifyUri = "spotify:track:abc",
        isrc = "USABC1234567",
        title = "Song",
        artist = "Artist",
        durationMs = 200_000L,
    )
}

/** Records calls; lets tests emit engine events without native code. */
private class FakeSyncEngine : SyncEngine {
    private val eventFlow = MutableSharedFlow<SyncCore.Event>(extraBufferCapacity = 64)

    override val events: SharedFlow<SyncCore.Event> = eventFlow.asSharedFlow()
    override val meterFrames: Flow<SyncCore.Event.SyncEstimate> =
        events.filterIsInstance<SyncCore.Event.SyncEstimate>().conflate()

    val nudgeCalls = mutableListOf<Int>()
    val routeCalls = mutableListOf<Pair<SyncCore.Route, Int>>()
    var closed = false
        private set

    suspend fun emit(event: SyncCore.Event) = eventFlow.emit(event)

    override fun setUserNudgeMs(nudgeMs: Int): Boolean {
        nudgeCalls += nudgeMs
        return true
    }

    override fun setOutputRoute(route: SyncCore.Route, latencyPriorMs: Int): Boolean {
        routeCalls += route to latencyPriorMs
        return true
    }

    override fun notifySeekIssued(targetMs: Long, issuedMonoNs: Long) = true

    override fun notifyLocalPlayback(commandedPositionMs: Long) = true

    override fun submitPlayerState(positionMs: Long, isPaused: Boolean, receivedMonoNs: Long) = true

    override fun submitRecognitionFix(
        source: SyncCore.FixSource,
        matchOffsetMs: Long,
        captureMonoNs: Long,
        frequencySkew: Double,
        confidence: Float,
    ) = true

    override fun commandLatencyMs(): Int = 250

    override fun close() {
        closed = true
    }
}

/** In-memory stand-in for the real DataStore-backed [NudgeStore] — no Context needed. */
private class FakeNudgeStore : NudgeStore {
    val trims = mutableMapOf<String, Int>()
    val latencies = mutableMapOf<String, Int>()

    override suspend fun trimFor(routeId: String): Int = trims[routeId] ?: 0

    override suspend fun saveTrim(routeId: String, trimMs: Int) {
        trims[routeId] = trimMs
    }

    override suspend fun commandLatencyFor(routeId: String): Int = latencies[routeId] ?: -1

    override suspend fun saveCommandLatency(routeId: String, ms: Int) {
        latencies[routeId] = ms
    }
}
