package com.jointheparty.app.ui.session

import com.jointheparty.app.audio.TonePlayer
import com.jointheparty.app.backend.BackendClient
import com.jointheparty.app.backend.ShazamTokenResult
import com.jointheparty.app.backend.TrackResolution
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.core.SyncEngine
import com.jointheparty.app.data.CalibrationProfile
import com.jointheparty.app.data.NudgeStore
import com.jointheparty.app.data.sortedByUpdatedAtDescending
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
import com.jointheparty.app.spotify.SpotifyController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        tonePlayer: TonePlayer? = null,
    ) = SessionViewModel(
        engine = engine,
        nudgeStore = nudgeStore,
        dispatcher = testDispatcher,
        tonePlayer = tonePlayer,
    )

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
        val profile = nudgeStore.calibrationProfiles["bluetooth:AirPods Pro"]
        assertEquals(182, profile?.latencyMs)
        assertEquals(CalibrationProfile.Method.MEASURED, profile?.method)
        assertEquals(true, profile?.acousticallyReachable)
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

    // CFX-07: "Start calibration" must never be a dead tap — a refusal to
    // arm surfaces into the same Failed state chirp-timeout already uses.
    @Test
    fun startCalibrationRoutesAnEngineRefusalIntoFailedWithoutPlayingTheChirp() = runTest(testDispatcher) {
        val engine = FakeSyncEngine().apply { beginCalibrationResult = false }
        val vm = viewModel(engine)

        vm.startCalibration()

        assertEquals(CalibrationState.Failed, vm.syncState.value.calibration)
        assertEquals(1, engine.calibrationBegun)
    }

    @Test
    fun startCalibrationStillRunsNormallyWhenTheEngineArms() = runTest(testDispatcher) {
        // Regression: beginCalibration() == true still transitions to
        // Running exactly as before CFX-07.
        val engine = FakeSyncEngine().apply { beginCalibrationResult = true }
        val vm = viewModel(engine)

        vm.startCalibration()

        assertEquals(CalibrationState.Running, vm.syncState.value.calibration)
        assertEquals(1, engine.calibrationBegun)
    }

    // ---- CAL-07: by-ear (tone-match) calibration ---------------------------

    @Test
    fun chirpTimeoutReachesFailedOnAnyRouteType_noDeviceClassBranch() = runTest(testDispatcher) {
        // The by-ear entry point (tryByEarInstead, below) hangs off this
        // Failed state, unconditionally — this pins down that Failed
        // itself is still reached automatically on the chirp's 8s timeout
        // (SyncCore.Event.CalibrationResult valid=false), on an ordinary
        // speaker route, not just headphones.
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()

        vm.startCalibration()
        engine.emit(SyncCore.Event.CalibrationResult(latencyMs = 0, valid = false))
        advanceUntilIdle()

        assertEquals(CalibrationState.Failed, vm.syncState.value.calibration)
    }

    @Test
    fun tryByEarInsteadEntersByEarIdleFromFailed() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)
        vm.startCalibration()
        engine.emit(SyncCore.Event.CalibrationResult(latencyMs = 0, valid = false))
        advanceUntilIdle()
        assertEquals(CalibrationState.Failed, vm.syncState.value.calibration)

        vm.tryByEarInstead()

        assertEquals(CalibrationState.ByEarIdle, vm.syncState.value.calibration)
    }

    @Test
    fun tryByEarInsteadIsOfferedDirectlyWithoutFailingFirst() = runTest(testDispatcher) {
        val vm = viewModel()
        assertEquals(CalibrationState.Idle, vm.syncState.value.calibration)

        vm.tryByEarInstead()

        assertEquals(CalibrationState.ByEarIdle, vm.syncState.value.calibration)
    }

    @Test
    fun startByEarCalibrationStartsToneAndEntersByEarRunning() = runTest(testDispatcher) {
        val tonePlayer = FakeTonePlayer()
        val vm = viewModel(tonePlayer = tonePlayer)
        vm.tryByEarInstead()

        vm.startByEarCalibration()

        assertEquals(CalibrationState.ByEarRunning, vm.syncState.value.calibration)
        assertEquals(1, tonePlayer.startCount)
    }

    @Test
    fun cancelByEarCalibrationStopsToneAndReturnsToByEarIdle() = runTest(testDispatcher) {
        val tonePlayer = FakeTonePlayer()
        val vm = viewModel(tonePlayer = tonePlayer)
        vm.tryByEarInstead()
        vm.startByEarCalibration()

        vm.cancelByEarCalibration()

        assertEquals(CalibrationState.ByEarIdle, vm.syncState.value.calibration)
        assertEquals(1, tonePlayer.stopCount)
    }

    @Test
    fun commitByEarStopsToneSavesByEarProfileAndEntersByEarSuccess() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore()
        val tonePlayer = FakeTonePlayer()
        val vm = viewModel(engine, nudgeStore, tonePlayer)
        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()
        vm.tryByEarInstead()
        vm.startByEarCalibration()

        vm.commitByEar(214)
        advanceUntilIdle()

        assertEquals(CalibrationState.ByEarSuccess(214), vm.syncState.value.calibration)
        assertEquals(1, tonePlayer.stopCount)
        val profile = nudgeStore.calibrationProfiles["bluetooth:AirPods Pro"]
        assertEquals(214, profile?.latencyMs)
        assertEquals(CalibrationProfile.Method.BY_EAR, profile?.method)
        // Applied to the engine immediately, matching the MEASURED path.
        assertEquals(SyncCore.Route.BLUETOOTH to 214, engine.routeCalls.last())
    }

    @Test
    fun acknowledgeCalibrationStopsToneWhenDismissedMidByEarRunning() = runTest(testDispatcher) {
        val tonePlayer = FakeTonePlayer()
        val vm = viewModel(tonePlayer = tonePlayer)
        vm.tryByEarInstead()
        vm.startByEarCalibration()

        vm.acknowledgeCalibration()

        assertEquals(CalibrationState.Idle, vm.syncState.value.calibration)
        assertEquals(1, tonePlayer.stopCount)
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
            // INT-03/CAL-04: setOutputRoute's prior is the calibrated
            // OUTPUT latency (now on the route's CalibrationProfile), not
            // the Spotify command latency.
            calibrationProfiles["bluetooth:AirPods Pro"] = calibrationProfile(
                routeId = "bluetooth:AirPods Pro",
                latencyMs = 310,
            )
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

    // ---- CAL-04: acoustic referee aggregation ------------------------------

    @Test
    fun invalidResidualEventsAreIgnored() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        val vm = viewModel(engine, nudgeStore)

        repeat(5) {
            engine.emit(SyncCore.Event.LatencyResidual(residualMs = 200, peakRatio = 1f, valid = false))
        }
        advanceUntilIdle()

        val profile = nudgeStore.calibrationProfiles.getValue("speaker")
        assertTrue(profile.refereeSamples.isEmpty())
        assertEquals(1, profile.sampleCount) // unchanged from the seeded profile
        assertEquals(SessionPhase.IDLE, vm.syncState.value.phase) // phase untouched, too
    }

    @Test
    fun fewerThanThreeAgreeingResidualsRecordNothing() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        viewModel(engine, nudgeStore)

        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 200, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 205, peakRatio = 5f, valid = true))
        advanceUntilIdle()

        val profile = nudgeStore.calibrationProfiles.getValue("speaker")
        assertTrue(profile.refereeSamples.isEmpty())
        assertEquals(1, profile.sampleCount)
    }

    @Test
    fun threeAgreeingResidualsCommitOneSample() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        viewModel(engine, nudgeStore)

        // FIELD FIX (field test 8): a healthy session's residual sits near
        // the reverb floor — it is the ERROR, not the latency re-measured.
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 40, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 45, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 50, peakRatio = 5f, valid = true))
        advanceUntilIdle()

        val profile = nudgeStore.calibrationProfiles.getValue("speaker")
        assertEquals(1, profile.refereeSamples.size)
        assertEquals(45, profile.refereeSamples.single().residualMs) // median of 40/45/50
        assertEquals(2, profile.sampleCount) // 1 seeded + 1 committed
        assertFalse(profile.drifted)
    }

    @Test
    fun disagreeingThirdResidualResetsAgreementCountInsteadOfCommitting() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        viewModel(engine, nudgeStore)

        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 200, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 205, peakRatio = 5f, valid = true))
        // >25ms from the pending pair — resets the agreement count instead
        // of committing a spurious median.
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 500, peakRatio = 5f, valid = true))
        advanceUntilIdle()
        assertTrue(nudgeStore.calibrationProfiles.getValue("speaker").refereeSamples.isEmpty())

        // The disagreeing sample became the new window's first entry — two
        // more agreeing with IT commit.
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 505, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 510, peakRatio = 5f, valid = true))
        advanceUntilIdle()

        val profile = nudgeStore.calibrationProfiles.getValue("speaker")
        assertEquals(1, profile.refereeSamples.size)
        assertEquals(505, profile.refereeSamples.single().residualMs) // median of 500/505/510
    }

    @Test
    fun agreeingResidualsBeyondDriftThresholdSetDrifted() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        viewModel(engine, nudgeStore)

        // Committed median (300) is 100ms from the stored latencyMs (200)
        // — over the 50ms drift threshold.
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 295, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 300, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 305, peakRatio = 5f, valid = true))
        advanceUntilIdle()

        val profile = nudgeStore.calibrationProfiles.getValue("speaker")
        assertTrue(profile.drifted)
        // The referee only ever appends/flags — it must NEVER move latencyMs.
        assertEquals(200, profile.latencyMs)
    }

    @Test
    fun agreeingResidualsWithinDriftThresholdLeaveDriftedFalse() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        viewModel(engine, nudgeStore)

        // FIELD FIX (field test 8): healthy = near-floor residual, and the
        // profile's latencyMs is irrelevant to the comparison.
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 38, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 43, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 48, peakRatio = 5f, valid = true))
        advanceUntilIdle()

        assertFalse(nudgeStore.calibrationProfiles.getValue("speaker").drifted)
    }

    @Test
    fun residualsNeverCommitOnARouteThatIsNotAcousticallyReachable() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["wired"] =
                calibrationProfile("wired", latencyMs = 150, acousticallyReachable = false)
        }
        val vm = viewModel(engine, nudgeStore)
        vm.onRouteChanged("wired", null, SyncCore.Route.WIRED)
        advanceUntilIdle()

        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 150, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 152, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 148, peakRatio = 5f, valid = true))
        advanceUntilIdle()

        assertTrue(nudgeStore.calibrationProfiles.getValue("wired").refereeSamples.isEmpty())
    }

    // ---- CAL-08: device shelf/detail review --------------------------------

    @Test
    fun openDeviceShelfLoadsEveryKnownProfileFromTheStore() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204)
            calibrationProfiles["bluetooth:AirPods Pro"] =
                calibrationProfile("bluetooth:AirPods Pro", latencyMs = 182, method = CalibrationProfile.Method.ESTIMATED)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        assertEquals(DeviceReviewPane.Hidden, vm.syncState.value.deviceReview)

        vm.openDeviceShelf()
        advanceUntilIdle()

        val shelf = vm.syncState.value.deviceReview as DeviceReviewPane.Shelf
        assertEquals(2, shelf.profiles.size)
        assertTrue(shelf.profiles.any { it.routeId == "speaker" })
        assertTrue(shelf.profiles.any { it.routeId == "bluetooth:AirPods Pro" })
    }

    // ---- CFX-09: deterministic shelf order, connected device first --------

    @Test
    fun withConnectedFirstMovesTheMatchingProfileToTheFrontOnly() {
        val a = calibrationProfile("a", latencyMs = 1, updatedAtMs = 3_000L)
        val b = calibrationProfile("b", latencyMs = 2, updatedAtMs = 2_000L)
        val c = calibrationProfile("c", latencyMs = 3, updatedAtMs = 1_000L)
        val baseOrder = listOf(a, b, c) // already updatedAtMs-descending

        assertEquals(listOf("c", "a", "b"), baseOrder.withConnectedFirst("c").map { it.routeId })
        // Already first: unchanged.
        assertEquals(listOf("a", "b", "c"), baseOrder.withConnectedFirst("a").map { it.routeId })
    }

    @Test
    fun withConnectedFirstFallsBackToPlainOrderWhenNothingMatches() {
        val a = calibrationProfile("a", latencyMs = 1, updatedAtMs = 2_000L)
        val b = calibrationProfile("b", latencyMs = 2, updatedAtMs = 1_000L)
        val baseOrder = listOf(a, b)

        assertEquals(listOf("a", "b"), baseOrder.withConnectedFirst("bluetooth:unknown").map { it.routeId })
    }

    @Test
    fun openDeviceShelfPlacesTheConnectedDeviceFirstAheadOfUpdatedAtOrder() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            // Deliberately inserted out of updatedAtMs order — proves the
            // shelf isn't just reflecting map/insertion order.
            calibrationProfiles["bluetooth:AirPods Pro"] =
                calibrationProfile("bluetooth:AirPods Pro", latencyMs = 182, updatedAtMs = 3_000L)
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204, updatedAtMs = 1_000L)
            calibrationProfiles["bluetooth:Kitchen speaker"] =
                calibrationProfile("bluetooth:Kitchen speaker", latencyMs = 96, updatedAtMs = 2_000L)
        }
        // Default routeId is "speaker" — the OLDEST-updated profile, so a
        // plain updatedAtMs-descending read alone would put it LAST.
        val vm = viewModel(nudgeStore = nudgeStore)

        vm.openDeviceShelf()
        advanceUntilIdle()

        val shelf = vm.syncState.value.deviceReview as DeviceReviewPane.Shelf
        assertEquals(
            listOf("speaker", "bluetooth:AirPods Pro", "bluetooth:Kitchen speaker"),
            shelf.profiles.map { it.routeId },
        )
    }

    @Test
    fun openDeviceShelfFallsBackToPlainUpdatedAtOrderWhenTheConnectedRouteIsUnknown() =
        runTest(testDispatcher) {
            val nudgeStore = FakeNudgeStore().apply {
                calibrationProfiles["bluetooth:AirPods Pro"] =
                    calibrationProfile("bluetooth:AirPods Pro", latencyMs = 182, updatedAtMs = 2_000L)
                calibrationProfiles["bluetooth:Kitchen speaker"] =
                    calibrationProfile("bluetooth:Kitchen speaker", latencyMs = 96, updatedAtMs = 1_000L)
            }
            // Default routeId "speaker" matches nothing in the store.
            val vm = viewModel(nudgeStore = nudgeStore)

            vm.openDeviceShelf()
            advanceUntilIdle()

            val shelf = vm.syncState.value.deviceReview as DeviceReviewPane.Shelf
            assertEquals(
                listOf("bluetooth:AirPods Pro", "bluetooth:Kitchen speaker"),
                shelf.profiles.map { it.routeId },
            )
        }

    @Test
    fun selectDeviceOpensDetailForAKnownShelfRow() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.openDeviceShelf()
        advanceUntilIdle()

        vm.selectDevice("speaker")

        val detail = vm.syncState.value.deviceReview as DeviceReviewPane.Detail
        assertEquals("speaker", detail.profile.routeId)
        assertEquals(204, detail.profile.latencyMs)
    }

    @Test
    fun selectDeviceIgnoresARouteIdNotOnTheLoadedShelf() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.openDeviceShelf()
        advanceUntilIdle()

        vm.selectDevice("bluetooth:unknown")

        // Unchanged — still the shelf, not a Detail pane for a device that
        // was never in the loaded list.
        assertTrue(vm.syncState.value.deviceReview is DeviceReviewPane.Shelf)
    }

    @Test
    fun backToDeviceShelfReturnsFromDetailToTheShelf() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.openDeviceShelf()
        advanceUntilIdle()
        vm.selectDevice("speaker")
        assertTrue(vm.syncState.value.deviceReview is DeviceReviewPane.Detail)

        vm.backToDeviceShelf()
        advanceUntilIdle()

        assertTrue(vm.syncState.value.deviceReview is DeviceReviewPane.Shelf)
    }

    @Test
    fun dismissDeviceReviewHidesWhicheverPaneWasShowing() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.openDeviceShelf()
        advanceUntilIdle()
        vm.selectDevice("speaker")

        vm.dismissDeviceReview()

        assertEquals(DeviceReviewPane.Hidden, vm.syncState.value.deviceReview)
    }

    @Test
    fun requestRecalibrateOnTheConnectedDeviceClosesReviewAndStartsGuidedCalibration() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204)
        }
        val vm = viewModel(engine, nudgeStore)
        // Default routeId is "speaker" — the profile below IS the connected device.
        vm.openDeviceShelf()
        advanceUntilIdle()
        vm.selectDevice("speaker")

        // CFX-02: the return value is what SessionScreen's wiring uses to
        // decide whether the guided-calibration pane opens — true here
        // because a measurement genuinely started.
        assertTrue(vm.requestRecalibrate())

        assertEquals(DeviceReviewPane.Hidden, vm.syncState.value.deviceReview)
        assertEquals(CalibrationState.Running, vm.syncState.value.calibration)
        assertEquals(1, engine.calibrationBegun)
    }

    @Test
    fun requestRecalibrateOnANonConnectedDeviceOnlyClosesReview() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["bluetooth:AirPods Pro"] =
                calibrationProfile("bluetooth:AirPods Pro", latencyMs = 182)
        }
        val vm = viewModel(engine, nudgeStore)
        // Default routeId is "speaker" — the selected device is NOT connected.
        vm.openDeviceShelf()
        advanceUntilIdle()
        vm.selectDevice("bluetooth:AirPods Pro")

        // CFX-02: false — SessionScreen's wiring must NOT open the
        // guided-calibration pane off this, since nothing started.
        assertFalse(vm.requestRecalibrate())

        assertEquals(DeviceReviewPane.Hidden, vm.syncState.value.deviceReview)
        // No measurement was started against the wrong device.
        assertEquals(CalibrationState.Idle, vm.syncState.value.calibration)
        assertEquals(0, engine.calibrationBegun)
    }

    // ---- CAL-09: first-contact gate ----------------------------------------

    @Test
    fun firstContactGateFiresForAnUnknownRouteButNotForAKnownOne() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204)
        }
        val vm = viewModel(nudgeStore = nudgeStore)

        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()
        val gate = vm.syncState.value.firstContactGate
        assertEquals("bluetooth:AirPods Pro", gate?.routeId)

        // "speaker" already has a real (sampleCount=1) profile — handled.
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()
        assertNull(vm.syncState.value.firstContactGate)
    }

    // CFX-06 (tech-req §2.6 "Gate copy must not pre-commit to a route
    // class"): the gate fires identically for WIRED as for any other route
    // — [FirstContactGateState] no longer carries a route-class-derived
    // variant field at all (formerly [FirstContactVariant], removed).
    @Test
    fun firstContactGateFiresTheSameWayForWiredRoutes() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onRouteChanged("wired", "Wired headphones", SyncCore.Route.WIRED)
        advanceUntilIdle()

        val gate = vm.syncState.value.firstContactGate
        assertEquals("wired", gate?.routeId)
        assertEquals("Wired headphones", gate?.deviceName)
    }

    @Test
    fun decliningFirstContactGateWritesAnEstimatedProfileAppliedToTheEngineImmediately() =
        runTest(testDispatcher) {
            val engine = FakeSyncEngine()
            val nudgeStore = FakeNudgeStore()
            val vm = viewModel(engine, nudgeStore)
            vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
            advanceUntilIdle()

            vm.declineFirstContactGate()
            advanceUntilIdle()

            assertNull(vm.syncState.value.firstContactGate)
            val profile = nudgeStore.calibrationProfiles["bluetooth:AirPods Pro"]
            assertEquals(150, profile?.latencyMs)
            assertEquals(CalibrationProfile.Method.ESTIMATED, profile?.method)
            assertEquals(0, profile?.sampleCount)
            assertEquals(SyncCore.Route.BLUETOOTH to 150, engine.routeCalls.last())
        }

    @Test
    fun firstContactGateReoffersOnTheNextRouteChangeAfterDecline() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore()
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.onRouteChanged("wired", "Wired headphones", SyncCore.Route.WIRED)
        advanceUntilIdle()
        vm.declineFirstContactGate()
        advanceUntilIdle()
        assertNull(vm.syncState.value.firstContactGate)

        // A later reconnect on the SAME routeId re-offers — the declined
        // profile's sampleCount is still 0, "handled" never included it.
        vm.onRouteChanged("wired", "Wired headphones", SyncCore.Route.WIRED)
        advanceUntilIdle()

        assertEquals("wired", vm.syncState.value.firstContactGate?.routeId)
    }

    @Test
    fun acceptingTheGateAlwaysStartsTheGuidedAcousticFlow() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()

        vm.acceptFirstContactGate()

        assertNull(vm.syncState.value.firstContactGate)
        assertEquals(CalibrationState.Running, vm.syncState.value.calibration)
        assertEquals(1, engine.calibrationBegun)
    }

    // CFX-06: WIRED gets the identical acoustic-first treatment as every
    // other route — never startByEarCalibration() directly. By ear is
    // reached only via the existing chirp-timeout → Failed → "Try by ear
    // instead" path, tested separately
    // (chirpTimeoutReachesFailedOnAnyRouteType_noDeviceClassBranch above).
    @Test
    fun acceptingTheGateOnAWiredRouteStillStartsTheAcousticFlowNotToneMatchDirectly() =
        runTest(testDispatcher) {
            val engine = FakeSyncEngine()
            val tonePlayer = FakeTonePlayer()
            val vm = viewModel(engine, tonePlayer = tonePlayer)
            vm.onRouteChanged("wired", "Wired headphones", SyncCore.Route.WIRED)
            advanceUntilIdle()

            vm.acceptFirstContactGate()

            assertNull(vm.syncState.value.firstContactGate)
            assertEquals(CalibrationState.Running, vm.syncState.value.calibration)
            assertEquals(1, engine.calibrationBegun)
            assertEquals(0, tonePlayer.startCount)
    }

    @Test
    fun listeningAndMatchingProgressWhileTheGateIsStillShowing() = runTest(testDispatcher) {
        val vm = SessionViewModel(FakeSyncEngine(), FakeNudgeStore(), testDispatcher, FakeRecognitionProvider(null))
        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()
        assertTrue(vm.syncState.value.firstContactGate != null)

        vm.startListening()

        assertEquals(SessionPhase.MATCHING, vm.syncState.value.phase)
    }

    @Test
    fun listeningAndMatchingProgressAfterTheGateIsAccepted() = runTest(testDispatcher) {
        val vm = SessionViewModel(FakeSyncEngine(), FakeNudgeStore(), testDispatcher, FakeRecognitionProvider(null))
        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()
        vm.acceptFirstContactGate()

        vm.startListening()

        assertEquals(SessionPhase.MATCHING, vm.syncState.value.phase)
    }

    @Test
    fun listeningAndMatchingProgressAfterTheGateIsDeclined() = runTest(testDispatcher) {
        val vm = SessionViewModel(FakeSyncEngine(), FakeNudgeStore(), testDispatcher, FakeRecognitionProvider(null))
        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()
        vm.declineFirstContactGate()
        advanceUntilIdle()

        vm.startListening()

        assertEquals(SessionPhase.MATCHING, vm.syncState.value.phase)
    }

    // ---- CAL-10: trim promotion detection (pure function) ------------------

    @Test
    fun fewerThanThreeCommitsNeverPromote() {
        assertNull(trimPromotionMedian(listOf(-180, -185)))
    }

    @Test
    fun threeAgreeingCommitsBeyondTheFloorPromote() {
        assertEquals(-180, trimPromotionMedian(listOf(-180, -185, -178)))
    }

    @Test
    fun commitsOutsideToleranceDoNotPromote() {
        // Sorted median -180; both neighbours sit 30ms away — over the 25ms tolerance.
        assertNull(trimPromotionMedian(listOf(-150, -180, -210)))
    }

    @Test
    fun medianAtOrBelowTheFloorDoesNotPromote() {
        assertNull(trimPromotionMedian(listOf(18, 20, 22))) // median 20ms
        assertNull(trimPromotionMedian(listOf(28, 30, 32))) // median exactly 30ms — not ABOVE the floor
    }

    @Test
    fun onlyTheMostRecentThreeCommitsAreConsidered() {
        // A stale, wildly-off first entry must not poison a fresh agreeing trio.
        assertEquals(-180, trimPromotionMedian(listOf(9999, -180, -185, -178)))
    }

    // ---- CAL-10: trim promotion end-to-end ---------------------------------

    @Test
    fun deviceDetailSurfacesTheBannerOnlyOnceThreeCommitsAgree() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()
        vm.onNudgeCommitted(-180)
        vm.onNudgeCommitted(-183)
        advanceUntilIdle()

        vm.openDeviceShelf()
        advanceUntilIdle()
        vm.selectDevice("speaker")
        advanceUntilIdle()
        assertNull((vm.syncState.value.deviceReview as DeviceReviewPane.Detail).trimPromotionMedianMs)

        vm.onNudgeCommitted(-178)
        advanceUntilIdle()
        vm.openDeviceShelf()
        advanceUntilIdle()
        vm.selectDevice("speaker")
        advanceUntilIdle()

        assertEquals(-180, (vm.syncState.value.deviceReview as DeviceReviewPane.Detail).trimPromotionMedianMs)
    }

    @Test
    fun acceptingTrimPromotionFoldsTheMedianSetsByEarAndZeroesTheWheel() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        val vm = viewModel(engine, nudgeStore)
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()
        vm.onNudgeCommitted(-180)
        vm.onNudgeCommitted(-183)
        vm.onNudgeCommitted(-178)
        advanceUntilIdle()

        vm.acceptTrimPromotion("speaker", -180)
        advanceUntilIdle()

        val profile = nudgeStore.calibrationProfiles.getValue("speaker")
        assertEquals(-180, profile.latencyMs)
        assertEquals(CalibrationProfile.Method.BY_EAR, profile.method)
        // FIELD FIX (field test 8): a promoted trim is a latency value, not
        // a residual — it must NOT enter the referee's error ring.
        assertTrue(profile.refereeSamples.isEmpty())
        assertFalse(profile.drifted) // freshly folded — a chosen value is never drift
        assertEquals(0, vm.syncState.value.nudgeMs)
        assertEquals(0, nudgeStore.trims.getValue("speaker"))
        assertEquals(0, engine.nudgeCalls.last())
        assertEquals(SyncCore.Route.SPEAKER to -180, engine.routeCalls.last())
        // The consumed streak doesn't linger to immediately re-trigger.
        assertTrue(nudgeStore.trimCommitHistories["speaker"].isNullOrEmpty())
    }

    @Test
    fun decliningTrimPromotionClosesTheOpenBannerInPlace() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()
        vm.onNudgeCommitted(-180)
        vm.onNudgeCommitted(-183)
        vm.onNudgeCommitted(-178)
        advanceUntilIdle()
        vm.openDeviceShelf()
        advanceUntilIdle()
        vm.selectDevice("speaker")
        advanceUntilIdle()
        assertEquals(-180, (vm.syncState.value.deviceReview as DeviceReviewPane.Detail).trimPromotionMedianMs)

        vm.declineTrimPromotion("speaker")

        assertNull((vm.syncState.value.deviceReview as DeviceReviewPane.Detail).trimPromotionMedianMs)
    }

    // ---- CFX-08: drift banner "Later" dismisses in place -------------------

    @Test
    fun dismissDriftBannerClosesTheBannerInPlaceWithoutLeavingTheDetailPane() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204).copy(drifted = true)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.openDeviceShelf()
        advanceUntilIdle()
        vm.selectDevice("speaker")
        advanceUntilIdle()
        val before = vm.syncState.value.deviceReview as DeviceReviewPane.Detail
        assertTrue(before.profile.drifted)
        assertFalse(before.driftDismissed)

        vm.dismissDriftBanner("speaker")

        // Still the SAME Detail pane — never DeviceReviewPane.Shelf — with
        // the banner cleared and the profile itself untouched (the referee's
        // finding is still true; only the banner's visibility changed).
        val after = vm.syncState.value.deviceReview
        assertTrue(after is DeviceReviewPane.Detail)
        after as DeviceReviewPane.Detail
        assertEquals("speaker", after.profile.routeId)
        assertTrue(after.profile.drifted)
        assertTrue(after.driftDismissed)
    }

    @Test
    fun dismissDriftBannerIgnoresARouteIdThatIsNotTheOpenDetailPane() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 204).copy(drifted = true)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.openDeviceShelf()
        advanceUntilIdle()
        vm.selectDevice("speaker")
        advanceUntilIdle()

        vm.dismissDriftBanner("bluetooth:some-other-device")

        val detail = vm.syncState.value.deviceReview as DeviceReviewPane.Detail
        assertFalse(detail.driftDismissed)
    }

    @Test
    fun decliningTrimPromotionSuppressesForTheCooldownWindowThenExpires() = runTest(testDispatcher) {
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        val vm = viewModel(nudgeStore = nudgeStore)
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()
        vm.onNudgeCommitted(-180)
        vm.onNudgeCommitted(-183)
        vm.onNudgeCommitted(-178)
        advanceUntilIdle()

        val declinedAtMs = 1_000_000_000L
        vm.declineTrimPromotion("speaker", declinedAtMs)
        advanceUntilIdle()

        vm.openDeviceShelf()
        advanceUntilIdle()
        // 6 days later — still inside the 7-day cooling-off period.
        vm.selectDevice("speaker", nowMs = declinedAtMs + 6L * 86_400_000L)
        advanceUntilIdle()
        assertNull((vm.syncState.value.deviceReview as DeviceReviewPane.Detail).trimPromotionMedianMs)

        vm.openDeviceShelf()
        advanceUntilIdle()
        // 8 days later — cooldown expired, the trigger condition still holds.
        vm.selectDevice("speaker", nowMs = declinedAtMs + 8L * 86_400_000L)
        advanceUntilIdle()
        assertEquals(-180, (vm.syncState.value.deviceReview as DeviceReviewPane.Detail).trimPromotionMedianMs)
    }

    // ---- CFX-01: route attribution at calibration completion ---------------

    @Test
    fun chirpResultAfterRouteChangeWritesNoProfileAndSurfacesCancelled() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore().apply {
            // Route B is already known so onRouteChanged("B") doesn't raise
            // a first-contact gate that would otherwise complicate the
            // assertions below — this test is purely about the calibration
            // result, not the gate.
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 150)
        }
        val vm = viewModel(engine, nudgeStore)
        vm.onRouteChanged("bluetooth:Route A", "Route A", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()

        vm.startCalibration() // captures route A
        assertEquals(1, engine.calibrationBegun)
        assertEquals(CalibrationState.Running, vm.syncState.value.calibration)

        // The route changes to B WHILE the chirp is armed-and-playing —
        // this must invalidate the in-flight measurement immediately.
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()
        assertEquals(1, engine.calibrationCancelled)
        assertEquals(CalibrationState.Cancelled, vm.syncState.value.calibration)

        // The (now-stale) result finally lands.
        engine.emit(SyncCore.Event.CalibrationResult(latencyMs = 182, valid = true))
        advanceUntilIdle()

        // No profile written for EITHER route — B's pre-existing profile is
        // untouched, and A never had one to begin with.
        assertNull(nudgeStore.calibrationProfiles["bluetooth:Route A"])
        assertEquals(150, nudgeStore.calibrationProfiles["speaker"]?.latencyMs)
        assertEquals(CalibrationState.Cancelled, vm.syncState.value.calibration)
        // Never applied to the live engine either.
        assertTrue(engine.routeCalls.none { it.second == 182 })
    }

    @Test
    fun byEarCommitAfterRouteChangeWritesNoProfile() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore()
        val tonePlayer = FakeTonePlayer()
        val vm = viewModel(engine, nudgeStore, tonePlayer)
        vm.onRouteChanged("bluetooth:Route A", "Route A", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()
        vm.tryByEarInstead()
        vm.startByEarCalibration() // captures route A
        assertEquals(CalibrationState.ByEarRunning, vm.syncState.value.calibration)

        // The route changes to B mid-ByEarRunning — the proactive
        // invalidation stops the tone immediately.
        vm.onRouteChanged("wired", null, SyncCore.Route.WIRED)
        advanceUntilIdle()
        assertEquals(1, tonePlayer.stopCount)
        assertEquals(CalibrationState.Cancelled, vm.syncState.value.calibration)

        // "That's it" arrives after — the value it carries must never land.
        // commitByEar's own unconditional tonePlayer.stop() is harmless
        // (the tone is already stopped).
        vm.commitByEar(214)
        advanceUntilIdle()

        assertEquals(2, tonePlayer.stopCount)
        assertNull(nudgeStore.calibrationProfiles["bluetooth:Route A"])
        assertNull(nudgeStore.calibrationProfiles["wired"])
        assertTrue(engine.routeCalls.none { it.second == 214 })
    }

    @Test
    fun chirpResultWithNoInterveningRouteChangeStillWritesTheMeasuredProfile() = runTest(testDispatcher) {
        // Regression (CAL-04): the unchanged-route path must still write
        // exactly as before CFX-01.
        val engine = FakeSyncEngine()
        val nudgeStore = FakeNudgeStore()
        val vm = viewModel(engine, nudgeStore)
        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()

        vm.startCalibration()
        engine.emit(SyncCore.Event.CalibrationResult(latencyMs = 182, valid = true))
        advanceUntilIdle()

        assertEquals(CalibrationState.Success(182), vm.syncState.value.calibration)
        assertEquals(182, nudgeStore.calibrationProfiles["bluetooth:AirPods Pro"]?.latencyMs)
        assertEquals(CalibrationProfile.Method.MEASURED, nudgeStore.calibrationProfiles["bluetooth:AirPods Pro"]?.method)
    }

    @Test
    fun acceptingAKnownGoodGateStillStartsCalibrationRegressionAfterCFX01() = runTest(testDispatcher) {
        // Regression: the staleness guard must not affect the ordinary
        // (unchanged-route) accept path.
        val engine = FakeSyncEngine()
        val vm = viewModel(engine)
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()

        vm.acceptFirstContactGate()

        assertNull(vm.syncState.value.firstContactGate)
        assertEquals(CalibrationState.Running, vm.syncState.value.calibration)
        assertEquals(1, engine.calibrationBegun)
    }

    @Test
    fun acceptFirstContactGateDoesNothingOnceTheGateHasBeenSupersededByARouteChange() = runTest(testDispatcher) {
        // CFX-01 (tech-req §2.6, symmetric with declineFirstContactGate's
        // existing guard): by the time the user's tap reaches the
        // ViewModel, the route the gate named may no longer be current —
        // acceptFirstContactGate() must never start a measurement against
        // whatever NOW happens to be connected. onRouteChanged already
        // clears/replaces a superseded gate as part of its own atomic
        // state update (routeId and firstContactGate are always written
        // together), so accepting after that must be a safe no-op — this
        // pins that outcome down.
        val engine = FakeSyncEngine()
        val tonePlayer = FakeTonePlayer()
        val nudgeStore = FakeNudgeStore().apply {
            calibrationProfiles["speaker"] = calibrationProfile("speaker", latencyMs = 200)
        }
        val vm = viewModel(engine, nudgeStore, tonePlayer)
        vm.onRouteChanged("bluetooth:AirPods Pro", "AirPods Pro", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()
        assertNotNull(vm.syncState.value.firstContactGate)

        // The route moves on to an already-known device before "Calibrate
        // now" is tapped.
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()
        assertNull(vm.syncState.value.firstContactGate)

        vm.acceptFirstContactGate()

        assertEquals(0, engine.calibrationBegun)
        assertEquals(0, tonePlayer.startCount)
        assertNull(nudgeStore.calibrationProfiles["bluetooth:AirPods Pro"])
    }

    // ---- CFX-02: recalibrate / empty-state targeting -----------------------

    @Test
    fun shouldOpenGuidedCalibrationPaneReflectsWhetherRequestRecalibrateStartedSomething() {
        assertTrue(shouldOpenGuidedCalibrationPaneAfterRecalibrateRequest { true })
        assertFalse(shouldOpenGuidedCalibrationPaneAfterRecalibrateRequest { false })
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


    @Test
    fun firstContactGateHoldsPlaybackUntilResolved() = runTest(testDispatcher) {
        // CAL-09: the gate must hold the AIM, not recognition. The scenario is
        // a speaker paired mid-session, where the route lands after the track
        // is already resolved -- so a one-shot check at resolve time would
        // miss it entirely.
        val engine = FakeSyncEngine()
        val spotify = FakeSpotifyController()
        val vm = SessionViewModel(
            engine, FakeNudgeStore(), testDispatcher,
            spotify = spotify,
        )
        vm.startListening()
        vm.onMatchInFlight()
        // An unknown route raises the gate.
        vm.onRouteChanged("bluetooth:New Speaker", "New Speaker", SyncCore.Route.BLUETOOTH)
        advanceUntilIdle()
        assertNotNull(vm.syncState.value.firstContactGate)

        vm.onTrackResolved(track())
        advanceUntilIdle()
        assertEquals(
            "playback must not aim through an unmeasured device",
            emptyList<String>(), spotify.played,
        )

        vm.declineFirstContactGate()
        advanceUntilIdle()
        assertEquals(listOf("spotify:track:abc"), spotify.played)
    }

    // ---- CTL-01b: Event.ActiveProbe (technical-requirements.md §2.9) ------

    @Test
    fun activeProbeExecutesPauseDelayResumeThenEchoesWhenPlaybackLive() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val spotify = FakeSpotifyController()
        val callLog = mutableListOf<String>()
        spotify.probeCallLog = callLog
        engine.probeCallLog = callLog
        spotify.lastKnownPlayerState = livePlayerState()
        val vm = SessionViewModel(engine, FakeNudgeStore(), testDispatcher, spotify = spotify)

        engine.emit(SyncCore.Event.ActiveProbe(pauseMs = 200))

        // No virtual-time hang (ticket AC): a bounded 200 ms virtual delay
        // completes instantly here rather than the free-running-timer hang
        // maybeSampleReferee's doc comment describes — the whole suite stays
        // ~10 s because nothing here loops.
        advanceUntilIdle()

        assertEquals(listOf("pause", "resume", "notifyProbeExecuted"), callLog)
        assertEquals(1, engine.notifyProbeExecutedCount)
        // The 200 ms gap was virtual (TestCoroutineScheduler time), not a
        // real-wall-clock sleep — this test returns instantly either way.
        assertEquals(200L, testDispatcher.scheduler.currentTime)
    }

    @Test
    fun activeProbeDoesNothingWhenAlreadyPaused() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val spotify = FakeSpotifyController()
        val callLog = mutableListOf<String>()
        spotify.probeCallLog = callLog
        engine.probeCallLog = callLog
        spotify.lastKnownPlayerState = livePlayerState(isPaused = true)
        val vm = SessionViewModel(engine, FakeNudgeStore(), testDispatcher, spotify = spotify)

        engine.emit(SyncCore.Event.ActiveProbe(pauseMs = 200))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), callLog)
        assertEquals(0, engine.notifyProbeExecutedCount)
    }

    @Test
    fun activeProbeDoesNothingDuringCalibration() = runTest(testDispatcher) {
        val engine = FakeSyncEngine()
        val spotify = FakeSpotifyController()
        val callLog = mutableListOf<String>()
        spotify.probeCallLog = callLog
        engine.probeCallLog = callLog
        spotify.lastKnownPlayerState = livePlayerState()
        val vm = SessionViewModel(engine, FakeNudgeStore(), testDispatcher, spotify = spotify)

        vm.startCalibration()
        assertEquals(CalibrationState.Running, vm.syncState.value.calibration)

        engine.emit(SyncCore.Event.ActiveProbe(pauseMs = 200))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), callLog)
        assertEquals(0, engine.notifyProbeExecutedCount)
    }

    private fun livePlayerState(isPaused: Boolean = false) = SpotifyController.RemotePlayerState(
        trackUri = "spotify:track:abc",
        positionMs = 1_000L,
        isPaused = isPaused,
        receivedMonoNs = 0L,
    )

    private fun track() = TrackInfo(
        spotifyUri = "spotify:track:abc",
        isrc = "USABC1234567",
        title = "Song",
        artist = "Artist",
        durationMs = 200_000L,
    )

    /** CAL-04 test helper: a minimal, well-formed profile to seed [FakeNudgeStore] with. */
    private fun calibrationProfile(
        routeId: String,
        latencyMs: Int,
        method: CalibrationProfile.Method = CalibrationProfile.Method.MEASURED,
        acousticallyReachable: Boolean = true,
        sampleCount: Int = 1,
        // CFX-09: distinct updatedAtMs values are what a shelf-ordering test
        // needs to seed — defaults to 0L so every pre-existing call site
        // (which never cared about order) is unaffected.
        updatedAtMs: Long = 0L,
    ) = CalibrationProfile(
        routeId = routeId,
        routeClass = when {
            routeId.startsWith("bluetooth") -> "BLUETOOTH"
            routeId == "wired" -> "WIRED"
            else -> "SPEAKER"
        },
        deviceName = routeId,
        method = method,
        latencyMs = latencyMs,
        confidence = 1.0f,
        sampleCount = sampleCount,
        acousticallyReachable = acousticallyReachable,
        createdAtMs = 0L,
        updatedAtMs = updatedAtMs,
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

    // CTL-01b: shared call log, set by tests exercising Event.ActiveProbe so
    // pause/delay/resume/notifyProbeExecuted order can be asserted across
    // this fake and FakeSpotifyController together.
    var probeCallLog: MutableList<String>? = null
    var notifyProbeExecutedCount = 0
        private set

    override fun notifyProbeExecuted(): Boolean {
        notifyProbeExecutedCount += 1
        probeCallLog?.add("notifyProbeExecuted")
        return true
    }

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

    // CFX-07: settable so a test can simulate the engine refusing to arm
    // calibration (a bad session state) without touching native code.
    var beginCalibrationResult = true

    override fun beginCalibration(): Boolean {
        calibrationBegun += 1
        return beginCalibrationResult
    }

    override fun cancelCalibration(): Boolean {
        calibrationCancelled += 1
        return true
    }

    var latencyResidualSampled = 0
        private set

    override fun sampleLatencyResidual(): Boolean {
        latencyResidualSampled += 1
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

/** CAL-07: records start/stop calls without touching android.media.AudioTrack. */
private class FakeTonePlayer : TonePlayer {
    var startCount = 0
        private set
    var stopCount = 0
        private set

    override fun start() {
        startCount += 1
    }

    override fun stop() {
        stopCount += 1
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

    val calibrationProfiles = mutableMapOf<String, CalibrationProfile>()

    override suspend fun calibrationProfileFor(routeId: String): CalibrationProfile? =
        calibrationProfiles[routeId]

    override suspend fun saveCalibrationProfile(profile: CalibrationProfile) {
        calibrationProfiles[profile.routeId] = profile
    }

    // CFX-09: mirrors [DataStoreNudgeStore.allCalibrationProfiles]'s real
    // ordering contract (most-recently-updated first) instead of raw map
    // iteration — otherwise this fake would make openDeviceShelf's
    // connected-first ordering untestable (nothing to prove "the rest" is
    // in a stable order behind it).
    override suspend fun allCalibrationProfiles(): List<CalibrationProfile> =
        calibrationProfiles.values.toList().sortedByUpdatedAtDescending()

    val setpoints = mutableMapOf<String, Int>()

    override suspend fun engineSetpointFor(routeId: String): Int? = setpoints[routeId]

    override suspend fun saveEngineSetpoint(routeId: String, ms: Int) {
        setpoints[routeId] = ms
    }

    /** CAL-10: recent wheel-commit history, oldest first — mirrors [DataStoreNudgeStore]'s persisted ring. */
    val trimCommitHistories = mutableMapOf<String, MutableList<Int>>()

    override suspend fun trimCommitHistoryFor(routeId: String): List<Int> =
        trimCommitHistories[routeId]?.toList() ?: emptyList()

    override suspend fun appendTrimCommit(routeId: String, trimMs: Int) {
        trimCommitHistories.getOrPut(routeId) { mutableListOf() }.add(trimMs)
    }

    override suspend fun clearTrimCommitHistory(routeId: String) {
        trimCommitHistories.remove(routeId)
    }

    val trimPromotionDeclinedAt = mutableMapOf<String, Long>()

    override suspend fun trimPromotionDeclinedAtMs(routeId: String): Long? = trimPromotionDeclinedAt[routeId]

    override suspend fun saveTrimPromotionDeclinedAtMs(routeId: String, atMs: Long) {
        trimPromotionDeclinedAt[routeId] = atMs
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

/**
 * CAL-09: minimal [SpotifyController] double. Only what the first-contact
 * gate's playback-hold test needs — records whether `play` was reached.
 */
private class FakeSpotifyController : SpotifyController {
    val played = mutableListOf<String>()
    override val playerStates: kotlinx.coroutines.flow.Flow<SpotifyController.RemotePlayerState> =
        kotlinx.coroutines.flow.MutableSharedFlow()
    // CTL-01b: was a fixed-null val; ActiveProbe tests need to simulate a
    // live (unpaused) or already-paused player state.
    override var lastKnownPlayerState: SpotifyController.RemotePlayerState? = null
    override val isConnected: Boolean = true
    override suspend fun connect(): SpotifyController.ConnectionResult =
        SpotifyController.ConnectionResult.Connected
    override fun disconnect() = Unit
    override fun play(spotifyUri: String): Boolean { played += spotifyUri; return true }

    // CTL-01b: shared call log, set by tests exercising Event.ActiveProbe —
    // see FakeSyncEngine.probeCallLog.
    var probeCallLog: MutableList<String>? = null

    override fun pause(): Boolean {
        probeCallLog?.add("pause")
        return true
    }

    override fun resume(): Boolean {
        probeCallLog?.add("resume")
        return true
    }

    override fun seekTo(positionMs: Long): Boolean = true
}
