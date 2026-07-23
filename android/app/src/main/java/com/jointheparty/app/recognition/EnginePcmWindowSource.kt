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
        val window = engine.copyRecentCapture(floatBuffer) ?: return null
        // A too-short sample can't fingerprint; wait for more audio.
        if (window.frames < MIN_SECONDS * ENGINE_RATE_HZ) return null

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
        return ACRCloudProvider.PcmWindowSource.PcmWindow(
            pcm = pcm,
            sampleRateHz = ENGINE_RATE_HZ / DECIMATION,
            endMonoNs = window.endMonoNs,
        )
    }

    private companion object {
        const val ENGINE_RATE_HZ = 48_000
        const val WINDOW_SECONDS = 10
        const val MIN_SECONDS = 3
        const val DECIMATION = 6  // 48 kHz → 8 kHz
    }
}
