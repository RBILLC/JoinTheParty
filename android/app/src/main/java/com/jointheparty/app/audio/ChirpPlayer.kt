package com.jointheparty.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * INT-03b: renders the calibration chirp through the active output route so
 * the mic (and the engine's armed ChirpDetector) can hear it.
 *
 * The waveform mirrors core/src/correlate/correlate.cpp::generate_chirp
 * EXACTLY — 200 ms linear sweep 800→4000 Hz at 48 kHz, 10 ms Hann-faded
 * edges, amplitude 0.8 — because the detector correlates the capture
 * against that same rendering; a mismatched sweep would simply never match.
 *
 * Interface + impl split so SessionViewModel stays JVM-unit-testable.
 */
interface ChirpPlayer {
    fun play()
}

class AudioTrackChirpPlayer : ChirpPlayer {

    override fun play() {
        // Fire-and-forget worker: AudioTrack setup takes ~ms and must not
        // block the caller (startCalibration runs on the main thread).
        thread(name = "chirp-player", isDaemon = true) {
            val pcm = renderChirp()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            try {
                track.write(pcm, 0, pcm.size)
                track.play()
                // Static-mode playback of 200 ms; wait it out before release.
                Thread.sleep(400)
            } finally {
                track.release()
            }
        }
    }

    private fun renderChirp(): ShortArray {
        val n = (SAMPLE_RATE * DURATION_S).toInt()
        val fade = (0.010 * SAMPLE_RATE).toInt()
        val k = (F1_HZ - F0_HZ) / DURATION_S
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val phase = 2.0 * PI * (F0_HZ * t + 0.5 * k * t * t)
            var a = AMPLITUDE
            if (i < fade) a *= 0.5 * (1.0 - cos(PI * i / fade))
            else if (i >= n - fade) a *= 0.5 * (1.0 - cos(PI * (n - 1 - i) / fade))
            out[i] = (a * sin(phase) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val DURATION_S = 0.2
        const val F0_HZ = 800.0
        const val F1_HZ = 4000.0
        const val AMPLITUDE = 0.8
    }
}
