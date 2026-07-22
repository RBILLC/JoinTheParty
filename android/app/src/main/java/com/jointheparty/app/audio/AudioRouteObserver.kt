package com.jointheparty.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.jointheparty.app.core.SyncCore

/**
 * NAT-02: watches the active OUTPUT route so the shell can feed
 * `SyncCore.setOutputRoute` the right per-route latency prior
 * (architecture-spec.md §5 — latency priors are calibrated per route) and
 * so route changes (which also surface as an Oboe input-stream disconnect —
 * see android/app/src/main/cpp/audio_capture.h's restart-on-disconnect
 * note) have a human-diagnosable route to correlate with.
 *
 * Built on `AudioManager.registerAudioDeviceCallback`
 * (`AudioDeviceCallback`, both API 23+), so it works down to minSdk 24
 * without version gating.
 *
 * Classification priority when multiple output devices are simultaneously
 * reported (rare, but `AudioManager` allows it): Bluetooth (A2DP / SCO /
 * BLE) > wired (headset / headphones / USB headset) > speaker — a user with
 * both a Bluetooth device and a wired plug connected almost always means
 * the wired one is incidental (e.g. a charging dongle), so Bluetooth wins.
 *
 * `routeId` is a stable identity string suitable for keying persisted
 * latency priors: `"bluetooth:<productName>"`, `"wired"`, or `"speaker"`.
 * `routeName` is a human-readable label (the Bluetooth device's product
 * name) or null for wired/speaker, which have no per-device identity worth
 * showing.
 */
class AudioRouteObserver(
    context: Context,
    private val onRouteChanged: (route: SyncCore.Route, routeId: String, routeName: String?) -> Unit,
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var lastRouteId: String? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = classifyAndNotify()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = classifyAndNotify()
    }

    /** Registers the callback and immediately fires the current route. */
    fun start() {
        audioManager.registerAudioDeviceCallback(callback, /* handler = */ null)
        classifyAndNotify()
    }

    /** Unregisters the callback. Idempotent; safe if never started. */
    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun classifyAndNotify() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val classified = classify(outputs)
        // Debounce: only invoke the caller when the classified route
        // actually changed, so e.g. two AudioDeviceInfo entries for the
        // same Bluetooth device (common during connect) don't double-fire.
        if (classified.routeId == lastRouteId) return
        lastRouteId = classified.routeId
        onRouteChanged(classified.route, classified.routeId, classified.routeName)
    }

    private fun classify(devices: Array<AudioDeviceInfo>): ClassifiedRoute {
        val bluetooth = devices.firstOrNull { isBluetooth(it.type) }
        if (bluetooth != null) {
            val name = bluetooth.productName?.toString()?.takeIf { it.isNotBlank() } ?: "bluetooth"
            return ClassifiedRoute(SyncCore.Route.BLUETOOTH, "bluetooth:$name", name)
        }

        val wired = devices.firstOrNull { isWired(it.type) }
        if (wired != null) {
            return ClassifiedRoute(SyncCore.Route.WIRED, "wired", null)
        }

        return ClassifiedRoute(SyncCore.Route.SPEAKER, "speaker", null)
    }

    private fun isBluetooth(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> true
        else -> false
    }

    private fun isWired(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> true
        else -> false
    }

    private data class ClassifiedRoute(
        val route: SyncCore.Route,
        val routeId: String,
        val routeName: String?,
    )
}
