package com.jointheparty.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * DSP-03b (technical-requirements.md §2.12): the volume-duck actuator's
 * seam onto `android.media.AudioManager`. JVM unit tests cannot touch a
 * real `AudioManager` — the `android.jar` unit-test stubs throw
 * `UnsupportedOperationException` on every call — so `SessionViewModel`
 * talks to this interface instead, the same seam [com.jointheparty.app
 * .core.SyncEngine] provides over the native SyncCore bridge.
 *
 * Every method is scoped to `AudioManager.STREAM_MUSIC` implicitly — there
 * is exactly one stream this app ever ducks.
 */
interface StreamVolumeController {
    /** Current `STREAM_MUSIC` volume index. */
    fun getStreamVolume(): Int

    /**
     * Sets `STREAM_MUSIC` volume to [index]. Implementations MUST pass
     * flags = 0 — NEVER `AudioManager.FLAG_SHOW_UI`. A system volume toast
     * firing mid-duck would defeat the entire point of a near-inaudible
     * probe (§2.12).
     */
    fun setStreamVolume(index: Int)

    /** dB for `STREAM_MUSIC` at [index], relative to the active/assumed output device. */
    fun getStreamVolumeDb(index: Int): Float
}

/**
 * Thin `AudioManager`-backed implementation — deliberately logic-free (see
 * [StreamVolumeController]'s doc comment: JVM tests exercise the duck
 * decision logic in `SessionViewModel` against a fake, never this class).
 *
 * `getStreamVolumeDb` requires a `deviceType` argument to look up the right
 * gain curve. Per §2.12's documented fallback, this class always passes
 * [AudioDeviceInfo.TYPE_BUILTIN_SPEAKER] rather than reaching into
 * `AudioRouteObserver`'s route-classification plumbing here: the duck's
 * target-index selection only ever reads a DELTA off this curve (original
 * dB minus target dB), so a device-type mismatch shifts the absolute curve
 * but not its relative shape enough to matter at a ~6 dB nominal duck —
 * and keeping this class independent of route observation keeps it
 * logic-free, matching the class's one job (talk to `AudioManager`,
 * nothing else).
 *
 * `getStreamVolumeDb(int, int, int)` is API 28+ (Android P); minSdk is 24.
 * Callers MUST only construct this class when
 * `Build.VERSION.SDK_INT >= Build.VERSION_CODES.P` — see
 * `SessionGraph`'s wiring, which passes `null` below that level so
 * `SessionViewModel`'s existing "no controller" gate (§2.12) covers older
 * devices for free.
 */
@RequiresApi(Build.VERSION_CODES.P)
class AudioManagerStreamVolumeController(context: Context) : StreamVolumeController {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun getStreamVolume(): Int =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun setStreamVolume(index: Int) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, /* flags = */ 0)
    }

    override fun getStreamVolumeDb(index: Int): Float =
        audioManager.getStreamVolumeDb(
            AudioManager.STREAM_MUSIC,
            index,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
}
