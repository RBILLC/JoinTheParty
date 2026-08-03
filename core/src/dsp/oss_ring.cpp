#include "dsp/oss_ring.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace synccore {

namespace {
// Local pi constant rather than <cmath>'s non-portable M_PI (not guaranteed
// available without _USE_MATH_DEFINES on MSVC) — same pattern
// correlate.cpp already uses.
constexpr double kPi = 3.14159265358979323846;
}  // namespace

OnsetStrengthRing::OnsetStrengthRing(int rate_hz)
    : fft_(kFrameSize),
      hann_(kFrameSize, 0.0f),
      stage_(kFrameSize, 0.0f),
      windowed_(kFrameSize, 0.0f),
      spec_(kNumBins),
      rate_hz_(rate_hz) {
    // Precomputed once; never touched again after construction.
    for (size_t i = 0; i < kFrameSize; ++i) {
        hann_[i] = static_cast<float>(
            0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(i) /
                                 static_cast<double>(kFrameSize - 1)));
    }
}

void OnsetStrengthRing::push(const float* samples, size_t n,
                             uint64_t end_mono_ns) {
    last_push_mono_ns_ = end_mono_ns;
    size_t offset = 0;
    while (offset < n) {
        const size_t space = kFrameSize - stage_fill_;
        const size_t take = std::min(space, n - offset);
        std::memcpy(stage_.data() + stage_fill_, samples + offset,
                   take * sizeof(float));
        stage_fill_ += take;
        offset += take;

        if (stage_fill_ == kFrameSize) {
            process_frame();
            // Slide the overlap buffer down by one hop; the retained tail
            // becomes the head of the next frame (50% overlap, H=512).
            std::memmove(stage_.data(), stage_.data() + kHopSize,
                        (kFrameSize - kHopSize) * sizeof(float));
            stage_fill_ = kFrameSize - kHopSize;
        }
    }
}

void OnsetStrengthRing::process_frame() {
    for (size_t i = 0; i < kFrameSize; ++i) {
        windowed_[i] = stage_[i] * hann_[i];
    }
    fft_.forward(windowed_, spec_);

    // Half-wave-rectified spectral flux: Delta(m) = sum_k max(0, Y(m,k) -
    // Y(m-1,k)), Y(m,k) = ln(1 + gamma*|X(m,k)|). prev_y_ holds Y(m-1,.).
    double delta = 0.0;
    for (size_t k = 0; k < kNumBins; ++k) {
        const double re = spec_[k].r;
        const double im = spec_[k].i;
        const double mag = std::sqrt(re * re + im * im);
        const double y = std::log(1.0 + kLogCompressionGamma * mag);
        if (have_prev_y_) {
            const double d = y - prev_y_[k];
            if (d > 0.0) delta += d;
        }
        prev_y_[k] = y;
    }
    have_prev_y_ = true;
    ++frames_emitted_;

    push_flux(delta);
}

void OnsetStrengthRing::push_flux(double delta) {
    // Causal running-sum delay line (kFluxWindowLen = 94 samples wide).
    if (flux_count_ == kFluxWindowLen) {
        flux_running_sum_ -= flux_ring_[flux_write_];
    } else {
        ++flux_count_;
    }
    flux_ring_[flux_write_] = delta;
    flux_running_sum_ += delta;
    flux_write_ = (flux_write_ + 1) % kFluxWindowLen;

    if (flux_count_ != kFluxWindowLen) return;  // window not full yet

    const double mean =
        flux_running_sum_ / static_cast<double>(kFluxWindowLen);
    // The newest value currently in the window sits at
    // (flux_write_ - 1 + kFluxWindowLen) % kFluxWindowLen; the delayed
    // sample o(.) reports is kFluxDelayHalfWindow behind that (~W back
    // from "now"), the causal stand-in for the non-causal
    // Delta(m-W..m+W) mean-removal window tech-req §2.10 describes.
    const size_t delayed_idx =
        (flux_write_ + kFluxWindowLen - 1 - kFluxDelayHalfWindow) %
        kFluxWindowLen;
    double o = flux_ring_[delayed_idx] - mean;
    if (o < 0.0) o = 0.0;
    oss_ring_push(o);
}

void OnsetStrengthRing::oss_ring_push(double o) {
    oss_ring_[oss_write_] = o;
    oss_write_ = (oss_write_ + 1) % kOssRingCapacity;
    if (oss_count_ < kOssRingCapacity) ++oss_count_;
}

double OnsetStrengthRing::oss_at(size_t m) const {
    // m=0 is the oldest valid sample, m=oss_count_-1 the newest. Callers
    // must keep m < oss_count_.
    const size_t idx =
        (oss_write_ + kOssRingCapacity - oss_count_ + m) % kOssRingCapacity;
    return oss_ring_[idx];
}

void OnsetStrengthRing::history_append(double period_ms, uint64_t mono_ns) {
    history_[history_write_] = HistorySample{period_ms, mono_ns};
    history_write_ = (history_write_ + 1) % kHistoryCapacity;
    if (history_count_ < kHistoryCapacity) ++history_count_;
}

const OnsetStrengthRing::HistorySample& OnsetStrengthRing::history_at(
    size_t m) const {
    const size_t idx = (history_write_ + kHistoryCapacity - history_count_ +
                       m) % kHistoryCapacity;
    return history_[idx];
}

BeatEstimate OnsetStrengthRing::estimate_beat_period(uint64_t now_ns) {
    BeatEstimate result;
    if (oss_count_ < kMinValidOssSamples) return result;  // not enough data

    // r(l) = (1/(count-l)) * sum_{m=0}^{count-1-l} o(m)*o(m+l), unbiased,
    // over the FULL lag range [0, kMaxHarmonicLag] — the harmonic term
    // s(l)=r_hat(l)+w*r_hat(2l) needs r_hat(2l) up to 2*kSearchLagMax=224
    // regardless of the [kSearchLagMin, kSearchLagMax] search band.
    const size_t count = oss_count_;
    double raw[kMaxHarmonicLag + 1];
    for (size_t lag = 0; lag <= kMaxHarmonicLag; ++lag) {
        double sum = 0.0;
        const size_t terms = count - lag;
        for (size_t m = 0; m < terms; ++m) {
            sum += oss_at(m) * oss_at(m + lag);
        }
        raw[lag] = sum / static_cast<double>(terms);
    }

    const double r0 = raw[0];
    if (r0 <= 0.0) return result;  // silent/degenerate signal

    double r_hat[kMaxHarmonicLag + 1];
    for (size_t lag = 0; lag <= kMaxHarmonicLag; ++lag) {
        r_hat[lag] = raw[lag] / r0;
    }

    // Harmonic-sum octave disambiguation over the search band.
    size_t best_l = kSearchLagMin;
    double best_s = r_hat[kSearchLagMin] +
                   kHarmonicSumWeight * r_hat[2 * kSearchLagMin];
    double s_sum = best_s;
    for (size_t l = kSearchLagMin + 1; l <= kSearchLagMax; ++l) {
        const double s = r_hat[l] + kHarmonicSumWeight * r_hat[2 * l];
        s_sum += s;
        if (s > best_s) {
            best_s = s;
            best_l = l;
        }
    }
    const size_t search_count = kSearchLagMax - kSearchLagMin + 1;
    const double s_mean = s_sum / static_cast<double>(search_count);

    // Parabolic sub-bin interpolation around l* (tech-req §2.10). best_l
    // is always within [kSearchLagMin, kSearchLagMax] = [24,112], so
    // best_l-1/best_l+1 stay inside r_hat's valid [0, kMaxHarmonicLag]
    // range.
    double delta = 0.0;
    {
        const double rm1 = r_hat[best_l - 1];
        const double rp1 = r_hat[best_l + 1];
        const double rc = r_hat[best_l];
        const double denom = rm1 - 2.0 * rc + rp1;
        if (denom != 0.0) {
            delta = 0.5 * (rm1 - rp1) / denom;
            // Defensive clamp: the interpolation formula assumes a clean
            // local parabola; guard a near-flat/degenerate neighborhood
            // from sending the correction outside a single bin. Not
            // spec-mandated, but never fires on a genuine sharp peak.
            if (delta > 0.5) delta = 0.5;
            if (delta < -0.5) delta = -0.5;
        }
    }

    result.period_ms =
        1000.0 * (static_cast<double>(best_l) + delta) / kOssRateHz;
    // Diagnostics/CSV-only (see BeatEstimate's doc comment) — never fed
    // into the `stable` gate below or any consumer as evidence.
    result.salience = s_mean != 0.0 ? best_s / s_mean : 0.0;

    // Frozen-ring guard: only an estimate computed over at least one new
    // OSS frame earns a stability-history entry. Duplicate polls of an
    // unchanged ring (capture stalled while session time advances via
    // player-state timestamps) read the existing history but append
    // nothing — `stable` means the estimate reproduced across independent
    // windows of audio, not that the same window was polled three times
    // (§2.10; see frames_at_last_history_'s doc comment). frames_emitted_
    // is strictly positive whenever we get here (oss_count_ >=
    // kMinValidOssSamples requires emitted frames), so after reset() the
    // first qualifying poll always appends.
    if (frames_emitted_ != frames_at_last_history_) {
        history_append(result.period_ms, now_ns);
        frames_at_last_history_ = frames_emitted_;
    }
    result.stable = false;
    if (history_count_ >= 3) {
        const HistorySample& a = history_at(history_count_ - 3);
        const HistorySample& b = history_at(history_count_ - 2);
        const HistorySample& c = history_at(history_count_ - 1);
        const double mx = std::max({a.period_ms, b.period_ms, c.period_ms});
        const double mn = std::min({a.period_ms, b.period_ms, c.period_ms});
        const uint64_t span_ns = c.mono_ns - a.mono_ns;
        if ((mx - mn) <= kStableAgreeMs && span_ns >= kStableWindowNs) {
            result.stable = true;
        }
    }
    return result;
}

bool beat_comb_corroborated(double second_lag_ms, const BeatEstimate& beat) {
    // Sentinel/precondition guards (oss_ring.h's doc comment): 0 is
    // WindowLag::second_lag_ms's own "no competitor" sentinel, not a claim
    // of a competing lag at 0 ms; an unstable or degenerate beat estimate
    // has nothing to corroborate against.
    if (second_lag_ms <= 0.0 || !beat.stable || beat.period_ms <= 0.0)
        return false;
    for (int k = 1; k <= kMaxBeatHarmonicMultiplier; ++k) {
        const double harmonic_ms = static_cast<double>(k) * beat.period_ms;
        if (std::fabs(second_lag_ms - harmonic_ms) < kBeatCombAgreeMs)
            return true;
    }
    return false;
}

void OnsetStrengthRing::reset() {
    stage_fill_ = 0;

    have_prev_y_ = false;
    for (size_t k = 0; k < kNumBins; ++k) prev_y_[k] = 0.0;

    flux_write_ = 0;
    flux_count_ = 0;
    flux_running_sum_ = 0.0;
    for (size_t i = 0; i < kFluxWindowLen; ++i) flux_ring_[i] = 0.0;

    oss_write_ = 0;
    oss_count_ = 0;

    history_write_ = 0;
    history_count_ = 0;
    frames_at_last_history_ = 0;

    frames_emitted_ = 0;
    last_push_mono_ns_ = 0;
}

}  // namespace synccore
