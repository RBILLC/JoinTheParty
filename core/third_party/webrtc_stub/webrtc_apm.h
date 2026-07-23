// STUB — compile-time stand-in for the WebRTC Audio Processing Module
// (CORE-05). PM decision 2026-07-22: do not vendor/build the real libwebrtc
// tree yet; this header mimics the modern APM surface (float deinterleaved
// ProcessStream / ProcessReverseStream + StreamConfig) closely enough that
// swapping in the real `webrtc-audio-processing` extraction later touches
// only core/CMakeLists.txt and this directory — SyncCoreAec call sites must
// not change. Stub processing is a bit-exact passthrough (no echo is
// actually cancelled); the pipeline plumbing, mode switching, and the
// recognition-side self-hearing guard (CORE-06) are what this build makes
// real.
#ifndef SYNCCORE_WEBRTC_STUB_APM_H
#define SYNCCORE_WEBRTC_STUB_APM_H

#include <cstddef>
#include <memory>

namespace webrtc {

class StreamConfig {
public:
    StreamConfig(int sample_rate_hz = 0, size_t num_channels = 0)
        : sample_rate_hz_(sample_rate_hz), num_channels_(num_channels) {}
    int sample_rate_hz() const { return sample_rate_hz_; }
    size_t num_channels() const { return num_channels_; }
    size_t num_frames() const {
        return static_cast<size_t>(sample_rate_hz_ / 100);  // 10 ms blocks
    }

private:
    int sample_rate_hz_;
    size_t num_channels_;
};

class AudioProcessing {
public:
    struct Config {
        struct EchoCanceller {
            bool enabled = false;
            bool mobile_mode = false;
        } echo_canceller;
    };

    static const int kNoError = 0;

    static std::unique_ptr<AudioProcessing> Create();

    void ApplyConfig(const Config& config) { config_ = config; }

    // Capture-path processing, in place. Real AEC3 subtracts the estimated
    // echo of previously supplied reverse-stream (reference) audio here.
    int ProcessStream(const float* const* src, const StreamConfig& input_config,
                      const StreamConfig& output_config, float* const* dest);

    // Far-end / reference audio (what the device is playing).
    int ProcessReverseStream(const float* const* src,
                             const StreamConfig& input_config,
                             const StreamConfig& output_config,
                             float* const* dest);

    // Capture-vs-render alignment hint, ms.
    int set_stream_delay_ms(int delay_ms) {
        stream_delay_ms_ = delay_ms;
        return kNoError;
    }

private:
    Config config_{};
    int stream_delay_ms_ = 0;
};

}  // namespace webrtc

#endif  // SYNCCORE_WEBRTC_STUB_APM_H
