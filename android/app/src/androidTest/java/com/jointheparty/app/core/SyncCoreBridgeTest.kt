package com.jointheparty.app.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * NAT-04 acceptance: engine instantiation from Kotlin, event round trip on
 * a real device/emulator, and error-payload correctness through the JNI
 * marshaling (mirrors core/tests/test_synccore.cpp expectations).
 */
@RunWith(AndroidJUnit4::class)
class SyncCoreBridgeTest {

    private fun monoNs(): Long = System.nanoTime()

    @Test
    fun rejectsUnsupportedConfig() {
        var threw = false
        try {
            SyncCore(sampleRateHz = 44_100).close()
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("44.1 kHz must be rejected by the ABI", threw)
    }

    @Test
    fun createReceiveEstimateDestroy() = runBlocking {
        SyncCore().use { core ->
            assertEquals(250, core.commandLatencyMs())  // default prior

            // events is a hot SharedFlow with no replay: subscribe BEFORE
            // submitting (UNDISPATCHED runs each collector to its first
            // suspension point synchronously) or the worker's events race
            // the collector and are dropped.
            val estimateJob = async(start = CoroutineStart.UNDISPATCHED) {
                core.events.filterIsInstance<SyncCore.Event.SyncEstimate>().first()
            }
            val correctionJob = async(start = CoroutineStart.UNDISPATCHED) {
                core.events.filterIsInstance<SyncCore.Event.Correction>().first()
            }

            // Player at 10 000 ms, external source at 9 950 ms → +50 ms.
            assertTrue(core.submitPlayerState(10_000, false, monoNs()))
            assertTrue(
                core.submitRecognitionFix(
                    source = SyncCore.FixSource.SHAZAMKIT,
                    matchOffsetMs = 9_950,
                    captureMonoNs = monoNs(),
                    frequencySkew = 0.0,
                    confidence = 0.9f,
                )
            )

            val estimate = withTimeout(5_000) { estimateJob.await() }
            assertTrue(
                "expected ~+50 ms, got ${estimate.errorMs}",
                abs(estimate.errorMs - 50.0) < 1.0,
            )

            // +50 ms is outside the deadband: a correction must follow, and
            // its target must lead by the command latency (250 ms default).
            val correction = withTimeout(5_000) { correctionJob.await() }
            assertTrue(
                "target ${correction.seekToMs} should be near player+200",
                abs(correction.seekToMs - 10_200) < 60,
            )
        }
    }

    @Test
    fun settleWindowRejectsFixes() = runBlocking {
        SyncCore().use { core ->
            val rejectedJob = async(start = CoroutineStart.UNDISPATCHED) {
                core.events.filterIsInstance<SyncCore.Event.FixRejected>().first()
            }
            assertTrue(core.submitPlayerState(10_000, false, monoNs()))
            assertTrue(core.notifySeekIssued(12_000, monoNs()))
            assertTrue(
                core.submitRecognitionFix(
                    source = SyncCore.FixSource.SHAZAMKIT,
                    matchOffsetMs = 9_950,
                    captureMonoNs = monoNs(),
                    frequencySkew = 0.0,
                    confidence = 0.9f,
                )
            )
            val rejected = withTimeout(5_000) { rejectedJob.await() }
            assertEquals(SyncCore.RejectReason.SETTLING, rejected.reason)
        }
    }

    @Test
    fun capturePushIsSafeFromWorkerThread() {
        SyncCore().use { core ->
            val block = FloatArray(480)
            val t = Thread {
                var ts = monoNs()
                repeat(200) {
                    core.pushCapture(block, 480, ts)
                    ts += 10_000_000L
                }
            }
            t.start()
            t.join()
        }
    }
}
