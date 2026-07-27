package com.jointheparty.app.recognition

import com.jointheparty.app.core.SyncEngine

/**
 * NAT-06b: [ACRCloudProvider.PcmWindowSource] backed by the engine's
 * capture-history tee (`sc_copy_recent_capture`) — the same post-AEC audio
 * the sync core hears, so speaker-mode echo suppression benefits
 * recognition too.
 *
 * ACRCloud recommends short low-rate samples over long hi-fi ones, so the
 * 48 kHz window is decimated to 8 kHz by mean-of-6 (a crude but adequate
 * anti-alias for fingerprinting, which lives well below 4 kHz;
 * TODO(NAT-06c): proper FIR decimator if match rates disappoint). 10 s of
 * 8 kHz PCM16 ≈ 160 KB per identify upload.
 */
class EnginePcmWindowSource(
    private val engine: SyncEngine,
) : ACRCloudProvider.PcmWindowSource {

    private val floatBuffer = FloatArray(WINDOW_SECONDS * ENGINE_RATE_HZ)

    override fun latestWindow(): ACRCloudProvider.PcmWindowSource.PcmWindow? {
        val window = engine.copyRecentCapture(floatBuffer)
        if (window == null) {
            com.jointheparty.app.debug.DebugLog.log("capture: no audio in ring yet")
            return null
        }
        // A too-short sample can't fingerprint; wait for more audio.
        if (window.frames < MIN_SECONDS * ENGINE_RATE_HZ) {
            com.jointheparty.app.debug.DebugLog.log(
                "capture: filling ${window.frames * 1000 / ENGINE_RATE_HZ}ms / need ${MIN_SECONDS * 1000}ms",
            )
            return null
        }

        val decimated = window.frames / DECIMATION
        val pcm = ByteArray(decimated * 2)
        for (i in 0 until decimated) {
            var acc = 0f
            val base = i * DECIMATION
            for (j in 0 until DECIMATION) acc += floatBuffer[base + j]
            val sample = ((acc / DECIMATION).coerceIn(-1f, 1f) * Short.MAX_VALUE)
                .toInt()
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        // Peak level tells us the mic is actually hearing something (vs a
        // dead/muted stream that would fingerprint to nothing).
        var peak = 0f
        for (i in 0 until window.frames) {
            val a = kotlin.math.abs(floatBuffer[i]); if (a > peak) peak = a
        }
        com.jointheparty.app.debug.DebugLog.log(
            "capture: ${window.frames * 1000 / ENGINE_RATE_HZ}ms window, peak=${"%.2f".format(peak)} → uploading",
        )
        return ACRCloudProvider.PcmWindowSource.PcmWindow(
            pcm = pcm,
            sampleRateHz = ENGINE_RATE_HZ / DECIMATION,
            endMonoNs = window.endMonoNs,
        )
    }

    private companion object {
        const val ENGINE_RATE_HZ = 48_000

        /**
         * Field Test 5: a song change took ~15 s to pick up, and most of that
         * was this window. It always holds the MOST RECENT audio, so straight
         * after the room changes track it is still mostly the OLD song — no
         * match is possible until the new song dominates it. At 10 s that is
         * ~6-8 s of dead time before the first identify can even succeed.
         *
         * 6 s stays well above ACRCloud's 3-5 s fingerprinting floor (we have
         * matched reliably at peak levels as low as 0.12) while halving the
         * contamination window, and it shrinks each upload to ~96 KB.
         */
        const val WINDOW_SECONDS = 6
        const val MIN_SECONDS = 3
        const val DECIMATION = 6  // 48 kHz → 8 kHz
    }
}
