package com.shazam.shazamkit

/**
 * STUB — compile-time stand-in for the Apple ShazamKit Android AAR (NAT-06).
 * Delete when the real dependency is vendored; call sites must not change.
 *
 * Mirrors `com.shazam.shazamkit.AudioSampleRateInHz`: the sample rates
 * ShazamKit's streaming session accepts. This app always requests
 * [SAMPLE_RATE_48000] — SyncCore v1 only supports 48 kHz mono
 * (technical-requirements.md §1.2's `sc_config_t.sample_rate_hz`).
 */
enum class AudioSampleRateInHz {
    SAMPLE_RATE_48000,
    SAMPLE_RATE_44100,
    SAMPLE_RATE_32000,
    SAMPLE_RATE_16000,
}
