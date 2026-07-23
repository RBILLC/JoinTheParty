// aec.h — CORE-05: SyncCore's AEC layer over the (stubbed) WebRTC APM.
//
// Owned and called only by the session worker thread (non-RT: the worker
// consumes the capture ring, so AEC never runs on the audio callback).
// Processes capture audio in 10 ms blocks as APM requires, buffering
// remainders between calls. Reference audio (the synthesized rendering of
// what we're playing, architecture-spec §7.2) feeds ProcessReverseStream.
//
// With the stub APM this is a bit-exact passthrough; the pipeline, mode
// switching, and buffering behavior are real and tested, so vendoring the
// real APM changes no call sites.
#ifndef SYNCCORE_AEC_H
#define SYNCCORE_AEC_H

#include <cstdint>
#include <memory>
#include <vector>

#include "synccore/synccore.h"
#include "webrtc_apm.h"

namespace synccore {

class SyncCoreAec {
public:
    explicit SyncCoreAec(int sample_rate_hz);

    void set_mode(sc_aec_mode_t mode);
    sc_aec_mode_t mode() const { return mode_; }

    // Capture path: processes `block` in place when mode == SC_AEC_FULL,
    // otherwise leaves it untouched. Handles arbitrary block sizes by
    // internally chunking into APM's 10 ms frames (residual samples are
    // carried across calls, preserving alignment).
    void process_capture(std::vector<float>* block);

    // Reference (far-end) audio: what the device is playing right now.
    void push_reference(const float* mono, size_t frames);

private:
    void run_apm_capture(float* frames_10ms);

    int sample_rate_hz_;
    size_t frames_per_block_;  // 10 ms
    sc_aec_mode_t mode_ = SC_AEC_PLATFORM_ONLY;
    std::unique_ptr<webrtc::AudioProcessing> apm_;
    std::vector<float> capture_carry_;
    std::vector<float> reference_carry_;
    std::vector<float> scratch_;
};

}  // namespace synccore

#endif  // SYNCCORE_AEC_H
