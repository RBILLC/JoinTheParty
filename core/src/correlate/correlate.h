// correlate.h — CORE-04: GCC-PHAT cross-correlation + chirp calibration.
//
// Pure DSP over float buffers; no threads, no clocks. Used by the latency
// self-calibration path (architecture-spec §6.4) and later by AEC reference
// alignment (CORE-06).
#ifndef SYNCCORE_CORRELATE_H
#define SYNCCORE_CORRELATE_H

#include <cstdint>
#include <vector>

namespace synccore {

struct CorrelationResult {
    bool found = false;      // peak cleared the peak-to-sidelobe threshold
    int lag_samples = 0;     // where `needle` starts inside `haystack`
    float peak = 0.0f;
    float peak_to_sidelobe = 0.0f;
};

// Generalized cross-correlation with phase transform. Finds the offset of
// `needle` (reference signal) within `haystack` (capture), searching lags
// [0, max_lag_samples]. PHAT whitening makes the peak sharp and robust to
// the coloration/level of real loudspeaker+room+mic chains.
CorrelationResult gcc_phat(const float* haystack, size_t haystack_len,
                           const float* needle, size_t needle_len,
                           int max_lag_samples,
                           float psr_threshold = 8.0f);

// The calibration chirp (architecture-spec §6.4): linear sweep, Hann-faded
// edges. The shell plays a bundled rendering of exactly this waveform; the
// detector below matches the mic capture against it.
std::vector<float> generate_chirp(int sample_rate_hz,
                                  double duration_s = 0.2,
                                  double f0_hz = 800.0,
                                  double f1_hz = 4000.0,
                                  float amplitude = 0.8f);

// Streaming chirp detector for the session worker: accumulate timestamped
// capture, correlate periodically, report when the chirp is heard.
class ChirpDetector {
public:
    explicit ChirpDetector(int sample_rate_hz);

    // Begin a detection window. t0_ns is the calibration origin (the moment
    // the shell commanded chirp playback).
    void arm(uint64_t t0_ns);
    void disarm();
    bool armed() const { return armed_; }

    // Feed capture. first_frame_mono_ns timestamps buf[0].
    void push(const float* buf, size_t frames, uint64_t first_frame_mono_ns);

    struct Detection {
        bool done = false;       // detection finished (found or timed out)
        bool valid = false;
        int32_t latency_ms = 0;  // chirp-heard time − t0
    };
    // Runs correlation if enough new audio arrived; cheap to call often.
    Detection poll(uint64_t now_ns);

private:
    int sample_rate_;
    std::vector<float> chirp_;
    std::vector<float> buffer_;
    uint64_t buffer_start_ns_ = 0;
    uint64_t t0_ns_ = 0;
    bool armed_ = false;
    bool have_buffer_start_ = false;
    size_t analyzed_len_ = 0;

    static constexpr double kTimeoutS = 8.0;
    static constexpr double kMaxBufferS = 10.0;
    static constexpr double kMinNewAudioS = 0.25;
};

}  // namespace synccore

#endif  // SYNCCORE_CORRELATE_H
