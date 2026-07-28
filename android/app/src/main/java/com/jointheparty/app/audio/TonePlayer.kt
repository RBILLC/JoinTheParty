package com.jointheparty.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.jointheparty.app.ui.theme.DT
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * CAL-07: renders the by-ear tone-match reference tone through the active
 * output route, repeating every [DT.Calibration.toneMatchPeriodMs] until
 * [stop] — the audio half of the tone-match ritual; the visual strike and
 * `abClick` haptic are driven separately, UI-side (see `CalibrationSheet`).
 *
 * **Same transport as the fixed chirp (CAL-01), and for the same reason.**
 * A wrong-path tone yields a wrong-path offset: the user is judging
 * perceptual alignment between what they hear and the caliper cursor, so if
 * the tone plays down the fast-mixer path instead of the deep-buffer path
 * Spotify's own audio actually travels, they'll dial in a number that has
 * nothing to do with the route's real latency — the exact failure mode
 * field-test-7 found in the original chirp (207 ms real vs. 3 ms reported).
 * So: MODE_STREAM, `PERFORMANCE_MODE_POWER_SAVING` (API 26+ guarded, same
 * as [AudioTrackChirpPlayer] — no equivalent request exists below O),
 * `CONTENT_TYPE_MUSIC`, stereo, 44.1 kHz, and a large buffer sized well
 * above the platform minimum to bias the platform toward the same
 * deep-buffer output path.
 *
 * **Tone choice: a short (~15 ms) percussive click, not a sustained tone.**
 * A long sine has a slow perceptual onset — the ear has no single instant to
 * pin the "beat" to, which is exactly the ambiguity adjust-until-aligned
 * calibration can't afford. A click with a fast attack and a fast decay
 * (modeled here as a 1 ms linear fade-in into a ~1.8 kHz tone with an
 * exponential decay) reads as a single, sharply-localized instant in time —
 * closer to the transient a physical tick or woodblock produces — so the
 * user is aligning the caliper cursor to an actual instant, not to the
 * middle of a fade.
 *
 * Interface + impl split so `SessionViewModel` stays JVM-unit-testable, same
 * split as [ChirpPlayer].
 */
interface TonePlayer {
    fun start()
    fun stop()
}

class AudioTrackTonePlayer : TonePlayer {

    private val running = AtomicBoolean(false)

    override fun start() {
        // Idempotent: a second start() while already running is a no-op
        // rather than a second competing playback thread.
        if (!running.compareAndSet(false, true)) return

        thread(name = "tone-player", isDaemon = true) {
            val periodPcm = renderPeriod()

            // Same large-buffer-above-minimum sizing rationale as
            // AudioTrackChirpPlayer: biases the platform toward the
            // deep-buffer path alongside the explicit performance-mode
            // request below.
            val minBufferBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = maxOf(minBufferBytes * BUFFER_SIZE_FACTOR, periodPcm.size * 2)

            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            }

            val track = trackBuilder.build()

            try {
                track.play()
                // One period (click + trailing silence) per write(); a
                // MODE_STREAM blocking write against a deep buffer paces
                // the loop against real playback time far more accurately
                // than a Thread.sleep(periodMs) would, so repetitions stay
                // locked to toneMatchPeriodMs rather than drifting with
                // scheduling jitter. running is only checked between
                // writes, so stop() has a worst-case latency of one
                // in-flight period — acceptable for a user-cancelled UI
                // gesture, not a real-time deadline.
                while (running.get()) {
                    track.write(periodPcm, 0, periodPcm.size)
                }
            } finally {
                track.stop()
                track.release()
            }
        }
    }

    override fun stop() {
        running.set(false)
    }

    /**
     * One full tone-match period as interleaved 16-bit stereo PCM: the
     * click transient at the start, silence for the remainder. Rendered
     * once per [start] and looped via repeated `write()` calls.
     */
    private fun renderPeriod(): ShortArray {
        val periodSamples =
            (SAMPLE_RATE * (DT.Calibration.toneMatchPeriodMs / 1000.0)).toInt()
        val clickSamples = min((SAMPLE_RATE * (CLICK_MS / 1000.0)).toInt(), periodSamples)
        val attackSamples = (SAMPLE_RATE * (ATTACK_MS / 1000.0)).toInt().coerceAtLeast(1)
        // Five time constants is a >99% decay — comfortably inaudible
        // before the click's short window ends.
        val decayTau = (CLICK_MS - ATTACK_MS).coerceAtLeast(1.0) / 1000.0 / 5.0

        // Interleaved stereo, duplicated to both channels; the tail beyond
        // clickSamples stays at ShortArray's zero default (silence).
        val out = ShortArray(periodSamples * CHANNEL_COUNT)
        for (i in 0 until clickSamples) {
            val envelope = if (i < attackSamples) {
                i.toDouble() / attackSamples
            } else {
                exp(-(i - attackSamples).toDouble() / SAMPLE_RATE / decayTau)
            }
            val t = i.toDouble() / SAMPLE_RATE
            val sample =
                (AMPLITUDE * envelope * sin(2.0 * PI * CLICK_FREQ_HZ * t) * Short.MAX_VALUE)
                    .toInt().toShort()
            out[i * CHANNEL_COUNT] = sample
            out[i * CHANNEL_COUNT + 1] = sample
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CLICK_MS = 15.0
        const val ATTACK_MS = 1.0
        const val CLICK_FREQ_HZ = 1_800.0
        const val AMPLITUDE = 0.85
        const val CHANNEL_COUNT = 2
        const val BUFFER_SIZE_FACTOR = 8
    }
}
