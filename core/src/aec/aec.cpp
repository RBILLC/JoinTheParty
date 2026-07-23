#include "aec/aec.h"

#include <algorithm>
#include <cstring>

namespace synccore {

SyncCoreAec::SyncCoreAec(int sample_rate_hz)
    : sample_rate_hz_(sample_rate_hz),
      frames_per_block_(static_cast<size_t>(sample_rate_hz / 100)),
      apm_(webrtc::AudioProcessing::Create()) {
    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = true;  // phone speaker scenario
    apm_->ApplyConfig(config);
}

void SyncCoreAec::set_mode(sc_aec_mode_t mode) {
    if (mode == mode_) return;
    mode_ = mode;
    // Mode flips invalidate the carried alignment.
    capture_carry_.clear();
    reference_carry_.clear();
}

void SyncCoreAec::run_apm_capture(float* frames_10ms) {
    const webrtc::StreamConfig cfg(sample_rate_hz_, 1);
    const float* const src[1] = {frames_10ms};
    float* const dest[1] = {frames_10ms};
    apm_->ProcessStream(src, cfg, cfg, dest);
}

void SyncCoreAec::process_capture(std::vector<float>* block) {
    if (mode_ != SC_AEC_FULL || block->empty()) return;

    // Prepend any carried remainder from the previous call.
    scratch_.clear();
    scratch_.reserve(capture_carry_.size() + block->size());
    scratch_.insert(scratch_.end(), capture_carry_.begin(), capture_carry_.end());
    scratch_.insert(scratch_.end(), block->begin(), block->end());

    size_t processed = 0;
    while (scratch_.size() - processed >= frames_per_block_) {
        run_apm_capture(scratch_.data() + processed);
        processed += frames_per_block_;
    }
    capture_carry_.assign(scratch_.begin() + processed, scratch_.end());

    // Return the processed prefix; the carried tail is delivered with the
    // next block. (Adds up to <10 ms of AEC pipeline delay — irrelevant to
    // recognition, which consumes seconds of audio per pass.)
    block->assign(scratch_.begin(), scratch_.begin() + processed);
}

void SyncCoreAec::push_reference(const float* mono, size_t frames) {
    if (mode_ != SC_AEC_FULL || !mono || frames == 0) return;

    reference_carry_.insert(reference_carry_.end(), mono, mono + frames);
    const webrtc::StreamConfig cfg(sample_rate_hz_, 1);
    size_t processed = 0;
    while (reference_carry_.size() - processed >= frames_per_block_) {
        float* chunk = reference_carry_.data() + processed;
        const float* const src[1] = {chunk};
        float* const dest[1] = {chunk};
        apm_->ProcessReverseStream(src, cfg, cfg, dest);
        processed += frames_per_block_;
    }
    reference_carry_.erase(reference_carry_.begin(),
                           reference_carry_.begin() + processed);
}

}  // namespace synccore
