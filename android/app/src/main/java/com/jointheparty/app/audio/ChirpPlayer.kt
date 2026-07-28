package com.jointheparty.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * INT-03b: renders the calibration chirp through the active output route so
 * the mic (and the engine's armed ChirpDetector) can hear it.
 *
 * The waveform mirrors core/src/correlate/correlate.cpp::generate_chirp
 * EXACTLY — 200 ms linear sweep 800→4000 Hz rendered at 44.1 kHz (matching
 * the transport's sample rate so the sweep still occupies 200 ms of wall
 * time), 10 ms Hann-faded edges, amplitude 0.8, duplicated to both channels
 * — because the detector correlates the capture against that same
 * rendering; a mismatched sweep would simply never match.
 *
 * CAL-01 (tech-req §2.6): the transport traverses the same deep-buffer path
 * Spotify's own playback uses — MODE_STREAM, PERFORMANCE_MODE_POWER_SAVING,
 * CONTENT_TYPE_MUSIC, stereo, 44.1 kHz — instead of the fast-mixer path
 * (MODE_STATIC/mono/48 kHz/CONTENT_TYPE_SONIFICATION) the chirp used to
 * take. A chirp measured on the fast mixer reports a path the music never
 * travels (field-test-7: 207 ms real acoustic latency vs. 3 ms engine-
 * reported); this is a transport-only fix, the waveform is unchanged.
 *
 * Interface + impl split so SessionViewModel stays JVM-unit-testable.
 */
interface ChirpPlayer {
    fun play()
}

class AudioTrackChirpPlayer : ChirpPlayer {

    override fun play() {
        // Fire-and-forget worker: AudioTrack setup + the blocking MODE_STREAM
        // write/drain must not block the caller (startCalibration runs on
        // the main thread).
        thread(name = "chirp-player", isDaemon = true) {
            val pcm = renderChirp()

            // Large buffer, multiplied up from the platform minimum: a
            // buffer sized to exactly the chirp (as MODE_STATIC used to do)
            // is itself a fast-mixer signature. Sizing well above the
            // minimum is what biases the platform toward the deep-buffer
            // output path — the same effect PERFORMANCE_MODE_POWER_SAVING
            // requests explicitly below. 8x the minimum comfortably clears
            // typical deep-buffer sizes (tens of ms) while staying a small,
            // fixed amount of memory for a 200 ms mono-content, stereo-
            // duplicated chirp.
            val minBufferBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = maxOf(minBufferBytes * BUFFER_SIZE_FACTOR, pcm.size * 2)

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

            // PERFORMANCE_MODE_POWER_SAVING is the actual deep-buffer
            // request, and it's a Builder-time call (API 26+) — there is no
            // way to change performance mode on an already-built AudioTrack.
            // minSdk is 24, so on 24/25 this call isn't available at all —
            // no explicit deep-buffer request is possible on those API
            // levels. The large MODE_STREAM buffer above still biases the
            // platform toward picking a deep-buffer output on those
            // releases, but it's a bias, not a guarantee, the same way it
            // is on every API level once the mode request itself is
            // satisfied.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            }

            val track = try {
                trackBuilder.build()
            } catch (t: Throwable) {
                // A fire-and-forget thread swallows this silently otherwise:
                // the chirp simply never happens and calibration can only
                // time out with no clue why.
                com.jointheparty.app.debug.DebugLog.log("chirp: build failed — $t")
                return@thread
            }

            try {
                // MODE_STREAM requires play() before the first write() —
                // unlike MODE_STATIC, there's no buffer to preload before
                // starting. write() blocks until the data is queued.
                // startThresholdInFrames is readable on API 31+ — logged so
                // the device itself confirms the diagnosis below rather than
                // us asserting it.
                val thresholdFrames =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        track.startThresholdInFrames
                    } else {
                        -1
                    }
                com.jointheparty.app.debug.DebugLog.log(
                    "chirp: state=${track.state} rate=${track.sampleRate} " +
                        "capacity=${track.bufferSizeInFrames} threshold=$thresholdFrames " +
                        "frames=${pcm.size / CHANNEL_COUNT}",
                )
                track.play()
                val wrote = track.write(pcm, 0, pcm.size)

                // FIELD FIX (device test, 2026-07-28, second finding): a
                // MODE_STREAM track does not begin rendering until the
                // frames written reach its start threshold, which DEFAULTS
                // TO THE BUFFER CAPACITY. Instrumented run showed exactly
                // that: wrote=17640, playState=3 (playing), yet
                // head=0/8820 after a full 2 s — every frame accepted,
                // nothing rendered, total silence at the mic. Our own
                // CAL-01 change caused it: the 8x buffer that biases the
                // platform toward the deep-buffer path also raised the
                // default threshold far beyond a 200 ms chirp, so the
                // track could never start by construction.
                //
                // Pad the write with silence up to the track's REPORTED
                // capacity (bufferSizeInFrames — measured, not the
                // requested size). Reaching capacity satisfies the default
                // threshold on every API level, no API-31 branch needed.
                // The padding is silence, so nothing audible changes.
                val chirpFrames = pcm.size / CHANNEL_COUNT
                val capacityFrames = track.bufferSizeInFrames
                val padShorts = (capacityFrames - chirpFrames).coerceAtLeast(0) * CHANNEL_COUNT
                if (padShorts > 0) {
                    track.write(ShortArray(padShorts), 0, padShorts)
                }
                com.jointheparty.app.debug.DebugLog.log(
                    "chirp: wrote=$wrote padShorts=$padShorts playState=${track.playState}",
                )

                // FIELD FIX (device test, 2026-07-28): wait for the frames
                // to actually LEAVE, don't estimate how long that takes.
                //
                // The first version slept for the content duration plus the
                // buffer's own playout time. That is not the same as the
                // pipeline's end-to-end latency: on the deep-buffer path we
                // deliberately request (CAL-01) there is another ~150-200 ms
                // downstream of the track, and `release()` discards whatever
                // has not reached the speaker yet. The result was total
                // silence — logcat showed "stop(): called with 8820 frames
                // delivered", i.e. the whole chirp written and then thrown
                // away, so neither the room nor the phone's own mic ever
                // heard it and calibration could only ever time out.
                //
                // getPlaybackHeadPosition() counts frames the track has
                // actually rendered, so polling it until it reaches the
                // frame count is a measurement rather than a guess. The
                // timeout is a backstop for a stalled or routed-away track;
                // it must exceed any plausible pipeline latency, which is
                // exactly the quantity the old sleep was trying to predict.
                val deadline = System.nanoTime() + DRAIN_TIMEOUT_MS * 1_000_000L
                while (track.playbackHeadPosition < chirpFrames &&
                    System.nanoTime() < deadline
                ) {
                    Thread.sleep(DRAIN_POLL_MS)
                }
                com.jointheparty.app.debug.DebugLog.log(
                    "chirp: head=${track.playbackHeadPosition}/$chirpFrames " +
                        "playState=${track.playState}",
                )
            } finally {
                track.release()
            }
        }
    }

    private fun renderChirp(): ShortArray {
        val n = (SAMPLE_RATE * DURATION_S).toInt()
        val fade = (0.010 * SAMPLE_RATE).toInt()
        val k = (F1_HZ - F0_HZ) / DURATION_S
        // Interleaved stereo: the sweep is duplicated to both channels.
        val out = ShortArray(n * CHANNEL_COUNT)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val phase = 2.0 * PI * (F0_HZ * t + 0.5 * k * t * t)
            var a = AMPLITUDE
            if (i < fade) a *= 0.5 * (1.0 - cos(PI * i / fade))
            else if (i >= n - fade) a *= 0.5 * (1.0 - cos(PI * (n - 1 - i) / fade))
            val sample = (a * sin(phase) * Short.MAX_VALUE).toInt().toShort()
            out[i * CHANNEL_COUNT] = sample
            out[i * CHANNEL_COUNT + 1] = sample
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val DURATION_S = 0.2
        const val F0_HZ = 800.0
        const val F1_HZ = 4000.0
        const val AMPLITUDE = 0.8
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_SIZE_FACTOR = 8
        /** Backstop if the track stalls or is routed away mid-play. */
        const val DRAIN_TIMEOUT_MS = 2_000L
        const val DRAIN_POLL_MS = 10L
    }
}
