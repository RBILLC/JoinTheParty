package com.jointheparty.app.ui.session

import com.jointheparty.app.backend.BackendClient
import com.jointheparty.app.backend.ShazamTokenResult
import com.jointheparty.app.backend.TrackResolution
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.core.SyncEngine
import com.jointheparty.app.data.NudgeStore
import com.jointheparty.app.recognition.RecognitionProvider
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
    fun calibrationLifecyclePersistsMeasuredOutputLatency() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore()
        val vm = viewModel(engine, nudgeStore)
        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()

        vm.startCalibration()
        assertEquals(1, engine.calibrationBegun)
        assertEquals(CalibrationState.Running, vm.syncState.value.calibration)

        engine.emit(SyncCore.Event.CalibrationResult(latencyMs = 182, valid = true))
        advanceUntilIdle()
        assertEquals(CalibrationState.Success(182), vm.syncState.value.calibration)
        assertEquals(182, nudgeStore.outputLatencies["bluetooth:AirPods Pro"])
        // Applied to the engine immediately, not just persisted.
        assertEquals(SyncCore.Route.BLUETOOTH to 182, engine.routeCalls.last())

        vm.acknowledgeCalibration()
        assertEquals(CalibrationState.Idle, vm.syncState.value.calibration)

        // Timeout path → Failed.
        vm.startCalibration()
        engine.emit(SyncCore.Event.CalibrationResult(latencyMs = 0, valid = false))
        advanceUntilIdle()
        assertEquals(CalibrationState.Failed, vm.syncState.value.calibration)
    }

    @Test
    fun routeChangeTogglesAecMode() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)

        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()
        assertEquals(listOf(SyncCore.AecMode.FULL), engine.aecCalls)

        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()
        assertEquals(
            listOf(SyncCore.AecMode.FULL, SyncCore.AecMode.OFF),
            engine.aecCalls,
        )
    }

    @Test
    fun routeChangeLoadsPersistedTrimAndLatency() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            trims["bluetooth:AirPods Pro"] = -60
            // INT-03: setOutputRoute's prior is the calibrated OUTPUT
            // latency, not the Spotify command latency.
            outputLatencies["bluetooth:AirPods Pro"] = 310
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

    @Test
    fun startListeningBootstrapsRecognitionSubmitsFixAndResolvesTrack() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val fix = RecognitionProvider.RecognitionFixResult(
            matchOffsetMs = 12_345L,
            captureMonoNs = 999L,
            frequencySkew = 0.0,
            confidence = 0.9f,
            title = "Song",
            artist = "Artist",
            isrc = "USABC1234567",
        )
        val recognition = FakeRecognitionProvider(fix)
        val backend = FakeBackendClient(
            TrackResolution.Resolved(spotifyUri = "spotify:track:xyz", looseSync = false),
        )
        val vm = SessionViewModel(engine, FakeNudgeStore(), testDispatcher, recognition, backend)

        vm.startListening()
        // The bootstrap transition (listening -> matching) and the
        // dispatch of the recognition pass both happen synchronously
        // inside startListening(); the pass's own body only actually runs
        // once the test dispatcher is advanced below.
        assertEquals(SessionPhase.MATCHING, vm.syncState.value.phase)

        advanceUntilIdle()

        assertEquals(1, recognition.callCount)
        assertEquals(1, engine.submittedFixes.size)
        assertEquals(12_345L, engine.submittedFixes[0].matchOffsetMs)
        assertEquals(SessionPhase.AIMING, vm.syncState.value.phase)
        assertEquals("spotify:track:xyz", vm.syncState.value.track?.spotifyUri)
        assertEquals("USABC1234567", vm.syncState.value.track?.isrc)
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

    // CAL-05: inert stand-in — nothing under test drives the session screen
    // off this stream (that's CAL-06); a single-value flow satisfies the
    // interface without a fake poll loop to keep alive/cancel.
    override fun inputLevel(): Flow<Float> = kotlinx.coroutines.flow.flowOf(0f)

    val nudgeCalls = mutableListOf<Int>()
    val routeCalls = mutableListOf<Pair<SyncCore.Route, Int>>()
    val aecCalls = mutableListOf<SyncCore.AecMode>()

    override fun setAecMode(mode: SyncCore.AecMode): Boolean {
        aecCalls += mode
        return true
    }
    val submittedFixes = mutableListOf<SubmittedFix>()
    var closed = false
        private set
    var capturing = false
        private set

    override fun startCapture(): Boolean {
        capturing = true
        return true
    }

    override fun stopCapture() {
        capturing = false
    }

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

    /**
     * Mirrors the real engine: an accepted fix produces an estimate. Without
     * this the ViewModel's shell-driven sampling (which stops at the first
     * estimate) would keep retrying to its cap.
     */
    override fun submitRecognitionFix(
        source: SyncCore.FixSource,
        matchOffsetMs: Long,
        captureMonoNs: Long,
        frequencySkew: Double,
        confidence: Float,
    ): Boolean {
        submittedFixes += SubmittedFix(source, matchOffsetMs, captureMonoNs, frequencySkew, confidence)
        eventFlow.tryEmit(
            SyncCore.Event.SyncEstimate(
                errorMs = 0.0,
                driftPpm = 0.0,
                confidence = confidence,
                converged = false,
                lastFixMonoNs = captureMonoNs,
            ),
        )
        return true
    }

    override fun commandLatencyMs(): Int = 250

    override fun copyRecentCapture(out: FloatArray): SyncEngine.CaptureWindow? = null

    var calibrationBegun = 0
        private set
    var calibrationCancelled = 0
        private set

    override fun beginCalibration(): Boolean {
        calibrationBegun += 1
        return true
    }

    override fun cancelCalibration(): Boolean {
        calibrationCancelled += 1
        return true
    }

    override fun close() {
        closed = true
    }
}

private data class SubmittedFix(
    val source: SyncCore.FixSource,
    val matchOffsetMs: Long,
    val captureMonoNs: Long,
    val frequencySkew: Double,
    val confidence: Float,
)

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

    val outputLatencies = mutableMapOf<String, Int>()

    override suspend fun outputLatencyFor(routeId: String): Int =
        outputLatencies[routeId] ?: -1

    override suspend fun saveOutputLatency(routeId: String, ms: Int) {
        outputLatencies[routeId] = ms
    }

    val setpoints = mutableMapOf<String, Int>()

    override suspend fun engineSetpointFor(routeId: String): Int? = setpoints[routeId]

    override suspend fun saveEngineSetpoint(routeId: String, ms: Int) {
        setpoints[routeId] = ms
    }
}

/** NAT-06: records calls; returns a fixed fix (or null) without touching ShazamKit. */
private class FakeRecognitionProvider(
    private val result: RecognitionProvider.RecognitionFixResult?,
) : RecognitionProvider {
    var callCount = 0
        private set

    override suspend fun recognizeOnce(): RecognitionProvider.RecognitionFixResult? {
        callCount++
        return result
    }

    override fun close() = Unit
}

/** AUTH-03/04: records calls; returns a fixed resolution without touching HTTP. */
private class FakeBackendClient(
    private val resolution: TrackResolution,
) : BackendClient {
    override suspend fun fetchShazamToken(): ShazamTokenResult =
        ShazamTokenResult.Success(token = "fake-token", expiresAtEpochMs = Long.MAX_VALUE)

    override suspend fun resolveIsrcToSpotifyUri(isrc: String): TrackResolution = resolution
}
