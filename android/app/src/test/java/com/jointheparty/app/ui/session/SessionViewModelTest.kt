package com.jointheparty.app.ui.session

import com.jointheparty.app.audio.TonePlayer
import com.jointheparty.app.backend.BackendClient
import com.jointheparty.app.backend.ShazamTokenResult
import com.jointheparty.app.backend.TrackResolution
import com.jointheparty.app.core.SyncCore
import com.jointheparty.app.core.SyncEngine
import com.jointheparty.app.data.CalibrationProfile
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
import org.junit.Assert.assertFalse
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

        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 195, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 200, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 205, peakRatio = 5f, valid = true))
        advanceUntilIdle()

        val profile = nudgeStore.calibrationProfiles.getValue("speaker")
        assertEquals(1, profile.refereeSamples.size)
        assertEquals(200, profile.refereeSamples.single().residualMs) // median of 195/200/205
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

        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 210, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 215, peakRatio = 5f, valid = true))
        engine.emit(SyncCore.Event.LatencyResidual(residualMs = 220, peakRatio = 5f, valid = true))
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

        vm.requestRecalibrate()

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

        vm.requestRecalibrate()

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
        assertEquals(FirstContactVariant.ACOUSTIC, gate?.variant)

        // "speaker" already has a real (sampleCount=1) profile — handled.
        vm.onRouteChanged("speaker", null, SyncCore.Route.SPEAKER)
        advanceUntilIdle()
        assertNull(vm.syncState.value.firstContactGate)
    }

    @Test
    fun firstContactGateUsesTheHeadphoneClassVariantForWiredRoutes() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onRouteChanged("wired", "Wired headphones", SyncCore.Route.WIRED)
        advanceUntilIdle()

        assertEquals(FirstContactVariant.HEADPHONE, vm.syncState.value.firstContactGate?.variant)
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
    fun acceptingTheAcousticVariantStartsTheGuidedAcousticFlow() = runTest(testDispatcher) {
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
    fun acceptingTheHeadphoneVariantStartsTheToneMatchFlow() = runTest(testDispatcher) {
        val tonePlayer = FakeTonePlayer()
        val vm = viewModel(tonePlayer = tonePlayer)
        vm.onRouteChanged("wired", "Wired headphones", SyncCore.Route.WIRED)
        advanceUntilIdle()

        vm.acceptFirstContactGate()

        assertNull(vm.syncState.value.firstContactGate)
        assertEquals(CalibrationState.ByEarRunning, vm.syncState.value.calibration)
        assertEquals(1, tonePlayer.startCount)
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
        assertEquals(1, profile.refereeSamples.size)
        assertEquals(-180, profile.refereeSamples.single().residualMs)
        assertFalse(profile.drifted) // freshly folded — never "drifted" from itself
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

    /** CAL-04 test helper: a minimal, well-formed profile to seed [FakeNudgeStore] with. */
    private fun calibrationProfile(
        routeId: String,
        latencyMs: Int,
        method: CalibrationProfile.Method = CalibrationProfile.Method.MEASURED,
        acousticallyReachable: Boolean = true,
        sampleCount: Int = 1,
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
        updatedAtMs = 0L,
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

    override suspend fun allCalibrationProfiles(): List<CalibrationProfile> =
        calibrationProfiles.values.toList()

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
