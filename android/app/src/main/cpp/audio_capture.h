// audio_capture.h — NAT-02: Oboe input capture, pushed straight into
// SyncCore with no JNI on the audio thread.
//
// This is the "production audio path" docs/android-implementation-review.md
// §2 calls out as the NAT-02 follow-up to the JNI bring-up path
// (`SyncCore.nativePushCapture` in synccore_jni.cpp, which remains for
// Kotlin-side tests only). `OboeCapture::onAudioReady` runs on Oboe's
// realtime audio callback thread and calls `sc_push_capture` directly, so
// it must obey the same RT contract synccore.h documents for that call:
// lock-free, allocation-free, no logging (technical-requirements.md §1.1).
//
// ---- Timestamping ----
// sc_push_capture wants the monotonic-clock timestamp (ns) of the FIRST
// frame in the block being pushed. Oboe hands us `numFrames` and the raw
// buffer per callback but not that timestamp directly, so we derive it:
//
//   1. Primary path: `stream->getTimestamp(CLOCK_MONOTONIC, &framePosition,
//      &timeNanoseconds)`. For an input stream this is a (frame index,
//      time) correspondence point supplied by the audio HAL — "frame
//      `framePosition` arrived at time `timeNanoseconds`". We combine it
//      with `stream->getFramesRead()` (the running total of frames
//      delivered to the app, updated before this callback fires, so it
//      already includes this callback's `numFrames`) to get this
//      callback's first-frame index: `getFramesRead() - numFrames`. The gap
//      between that index and the HAL's `framePosition` is extrapolated to
//      nanoseconds at the nominal 48 kHz rate and added to
//      `timeNanoseconds`. Because we re-derive this from a fresh hardware
//      reference every callback (rather than accumulating an running
//      estimate), per-callback integer-division rounding does not drift
//      over time — worst case is a fraction of one sample period of
//      jitter, reset every callback.
//   2. Fallback: `getTimestamp` commonly returns a non-OK result for the
//      first several callbacks after `start()` (the HAL hasn't produced a
//      timestamp yet), and in principle could fail later too. When that
//      happens we fall back to `clock_gettime(CLOCK_MONOTONIC)` taken *now*
//      (i.e. treating "now" as roughly the arrival time of the LAST frame
//      of this buffer) minus `numFrames / sampleRate` worth of nanoseconds
//      to back-compute the first frame's time. This is less precise (it
//      ignores scheduling jitter between the HAL producing the buffer and
//      this callback actually running) but guarantees every pushed block
//      still carries a timestamp.
//
// All the arithmetic above is done in integer nanoseconds to avoid
// pulling floating point into the RT path for anything but the sample
// data itself.
//
// ---- Restart-on-disconnect ----
// Route changes (Bluetooth disconnect, headset unplug, audio focus loss to
// a higher-priority app) surface to Oboe as a stream error, delivered via
// `AudioStreamErrorCallback::onErrorAfterClose` — Oboe has already closed
// the (now-invalid) stream by the time this fires. Oboe's own guidance is
// that reopening a stream must not happen synchronously inside that
// callback, so we hand off to a dedicated `std::thread` that reopens (or,
// if `stop()` has since been called, does nothing). `restart_thread_mutex_`
// guards the `std::thread` object itself (so `stop()` and a concurrent
// `onErrorAfterClose` never race on assigning/joining it), while
// `lifecycle_mutex_` guards the actual `oboe::AudioStream` open/close so a
// restart and an explicit `start()`/`stop()` can never run concurrently.
// `stopping_` lets an in-flight restart notice a `stop()` and skip
// reopening instead of racing it.
#ifndef SYNCCORE_ANDROID_AUDIO_CAPTURE_H
#define SYNCCORE_ANDROID_AUDIO_CAPTURE_H

#include <atomic>
#include <memory>
#include <mutex>
#include <thread>

#include <oboe/Oboe.h>

#include "synccore/synccore.h"

namespace synccore_android {

// Owns one Oboe input stream and feeds it straight into a SyncCore session.
// Not copyable; the caller owns lifetime (BridgeHandle holds it via
// std::unique_ptr in synccore_jni.cpp) and MUST call stop() before the
// sc_session_t* it was constructed with is destroyed — see the
// destruction-order comment beside nativeDestroy in synccore_jni.cpp.
class OboeCapture : public oboe::AudioStreamDataCallback,
                     public oboe::AudioStreamErrorCallback {
 public:
    // `session` must outlive this object; ownership stays with the caller.
    explicit OboeCapture(sc_session_t* session);

    // Calls stop() defensively in case the caller forgot.
    ~OboeCapture() override;

    OboeCapture(const OboeCapture&) = delete;
    OboeCapture& operator=(const OboeCapture&) = delete;

    // Opens and starts the input stream. Safe to call from any non-RT
    // thread. Returns false if the stream could not be opened/started, or
    // if the device negotiated a format sc_push_capture can't accept (see
    // formatSupported()) — in that case the stream is left closed.
    bool start();

    // Stops and closes the stream and joins any in-flight restart, so it is
    // safe to destroy *this (or the sc_session_t it pushes into)
    // immediately after stop() returns. Safe to call from any non-RT
    // thread; idempotent (including "never started").
    void stop();

    // True while the stream is open and believed to be running. Goes false
    // between an onErrorAfterClose disconnect and the restart completing.
    bool running() const { return running_.load(std::memory_order_acquire); }

    // False if the stream last negotiated something other than 48 kHz mono.
    // SyncCore v1 only accepts 48000 Hz / 1 channel (synccore.h,
    // sc_config_t::sample_rate_hz); rather than resample or mis-push
    // multi-channel data as mono, we refuse to start and surface the
    // failure here. Checked once per open, immediately after openStream()
    // and before requestStart(), so onAudioReady only ever runs against a
    // validated format.
    bool formatSupported() const {
        return format_supported_.load(std::memory_order_acquire);
    }

    // oboe::AudioStreamDataCallback — runs on Oboe's realtime audio thread.
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback — runs on an Oboe-internal thread,
    // never the audio callback thread, but reopening here directly is still
    // unsafe per Oboe's own guidance; see the restart-on-disconnect note
    // above.
    void onErrorAfterClose(oboe::AudioStream* stream,
                           oboe::Result error) override;

 private:
    // Caller must hold lifecycle_mutex_.
    bool openAndStartLocked();
    void closeLocked();

    // Runs on restart_thread_.
    void restartAfterDisconnect();

    // Derives the monotonic-ns timestamp of the first frame of the current
    // callback; see the Timestamping note above.
    uint64_t firstFrameTimestampNs(oboe::AudioStream* stream,
                                   int32_t numFrames) const;

    sc_session_t* const session_;

    std::mutex lifecycle_mutex_;  // guards stream_ open/close/restart work
    std::shared_ptr<oboe::AudioStream> stream_;

    std::mutex restart_thread_mutex_;  // guards restart_thread_ itself
    std::thread restart_thread_;
    std::atomic<bool> restart_in_flight_{false};

    std::atomic<bool> stopping_{false};
    std::atomic<bool> running_{false};
    std::atomic<bool> format_supported_{true};

    static constexpr int32_t kTargetSampleRateHz = 48000;
    static constexpr int32_t kTargetChannelCount = 1;
};

}  // namespace synccore_android

#endif  // SYNCCORE_ANDROID_AUDIO_CAPTURE_H
