#include "correlate/correlate.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "kiss_fft.h"
#include "kiss_fftr.h"

namespace synccore {

namespace {

constexpr double kPi = 3.14159265358979323846;

size_t next_pow2(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}

}  // namespace

CorrelationResult gcc_phat(const float* haystack, size_t haystack_len,
                           const float* needle, size_t needle_len,
                           int max_lag_samples, float psr_threshold) {
    CorrelationResult result;
    if (!haystack || !needle || needle_len == 0 ||
        haystack_len < needle_len || max_lag_samples < 0)
        return result;

    const size_t nfft = next_pow2(haystack_len + needle_len);
    const size_t nbins = nfft / 2 + 1;

    kiss_fftr_cfg fwd = kiss_fftr_alloc(static_cast<int>(nfft), 0, nullptr, nullptr);
    kiss_fftr_cfg inv = kiss_fftr_alloc(static_cast<int>(nfft), 1, nullptr, nullptr);
    if (!fwd || !inv) {
        free(fwd);
        free(inv);
        return result;
    }

    std::vector<float> padded(nfft, 0.0f);
    std::vector<kiss_fft_cpx> spec_h(nbins), spec_n(nbins);

    std::memcpy(padded.data(), haystack, haystack_len * sizeof(float));
    kiss_fftr(fwd, padded.data(), spec_h.data());

    std::fill(padded.begin(), padded.end(), 0.0f);
    std::memcpy(padded.data(), needle, needle_len * sizeof(float));
    kiss_fftr(fwd, padded.data(), spec_n.data());

    // Cross-spectrum with PHAT whitening: S = X·Y* / |X·Y*|.
    for (size_t i = 0; i < nbins; ++i) {
        const float re = spec_h[i].r * spec_n[i].r + spec_h[i].i * spec_n[i].i;
        const float im = spec_h[i].i * spec_n[i].r - spec_h[i].r * spec_n[i].i;
        const float mag = std::sqrt(re * re + im * im);
        if (mag > 1e-12f) {
            spec_h[i].r = re / mag;
            spec_h[i].i = im / mag;
        } else {
            spec_h[i].r = 0.0f;
            spec_h[i].i = 0.0f;
        }
    }

    std::vector<float> corr(nfft);
    kiss_fftri(inv, spec_h.data(), corr.data());
    free(fwd);
    free(inv);

    const int search =
        std::min<int>(max_lag_samples,
                      static_cast<int>(haystack_len - needle_len));
    int best_lag = 0;
    float best = -1.0f;
    for (int lag = 0; lag <= search; ++lag) {
        const float v = std::abs(corr[static_cast<size_t>(lag)]);
        if (v > best) {
            best = v;
            best_lag = lag;
        }
    }

    // Peak-to-sidelobe ratio over the searched range, excluding ±1 ms
    // around the peak.
    const int guard = std::max(1, static_cast<int>(needle_len) / 200);
    double sum = 0.0;
    int count = 0;
    for (int lag = 0; lag <= search; ++lag) {
        if (std::abs(lag - best_lag) <= guard) continue;
        sum += std::abs(corr[static_cast<size_t>(lag)]);
        ++count;
    }
    const float sidelobe =
        count > 0 ? static_cast<float>(sum / count) : 1e-12f;

    result.lag_samples = best_lag;
    result.peak = best;
    result.peak_to_sidelobe = sidelobe > 0.0f ? best / sidelobe : 0.0f;
    result.found = result.peak_to_sidelobe >= psr_threshold;
    return result;
}

std::vector<float> generate_chirp(int sample_rate_hz, double duration_s,
                                  double f0_hz, double f1_hz,
                                  float amplitude) {
    const size_t n = static_cast<size_t>(duration_s * sample_rate_hz);
    std::vector<float> out(n);
    const double k = (f1_hz - f0_hz) / duration_s;  // sweep rate
    const size_t fade = static_cast<size_t>(0.010 * sample_rate_hz);
    for (size_t i = 0; i < n; ++i) {
        const double t = static_cast<double>(i) / sample_rate_hz;
        const double phase = 2.0 * kPi * (f0_hz * t + 0.5 * k * t * t);
        double a = amplitude;
        if (i < fade)
            a *= 0.5 * (1.0 - std::cos(kPi * i / fade));
        else if (i >= n - fade)
            a *= 0.5 * (1.0 - std::cos(kPi * (n - 1 - i) / fade));
        out[i] = static_cast<float>(a * std::sin(phase));
    }
    return out;
}

ChirpDetector::ChirpDetector(int sample_rate_hz)
    : sample_rate_(sample_rate_hz),
      chirp_(generate_chirp(sample_rate_hz)) {}

void ChirpDetector::arm(uint64_t t0_ns) {
    t0_ns_ = t0_ns;
    armed_ = true;
    have_buffer_start_ = false;
    buffer_.clear();
    analyzed_len_ = 0;
}

void ChirpDetector::disarm() {
    armed_ = false;
    buffer_.clear();
    buffer_.shrink_to_fit();
}

void ChirpDetector::push(const float* buf, size_t frames,
                         uint64_t first_frame_mono_ns) {
    if (!armed_ || frames == 0) return;
    if (!have_buffer_start_) {
        buffer_start_ns_ = first_frame_mono_ns;
        have_buffer_start_ = true;
    }
    const size_t cap = static_cast<size_t>(kMaxBufferS * sample_rate_);
    if (buffer_.size() + frames > cap) return;  // enough context buffered
    buffer_.insert(buffer_.end(), buf, buf + frames);
}

ChirpDetector::Detection ChirpDetector::poll(uint64_t now_ns) {
    Detection det;
    if (!armed_) return det;

    if (now_ns > t0_ns_ + static_cast<uint64_t>(kTimeoutS * 1e9)) {
        det.done = true;
        det.valid = false;
        disarm();
        return det;
    }

    const size_t min_new = static_cast<size_t>(kMinNewAudioS * sample_rate_);
    if (buffer_.size() < chirp_.size() + min_new ||
        buffer_.size() < analyzed_len_ + min_new)
        return det;
    analyzed_len_ = buffer_.size();

    const int max_lag = static_cast<int>(buffer_.size() - chirp_.size());
    const auto r = gcc_phat(buffer_.data(), buffer_.size(), chirp_.data(),
                            chirp_.size(), max_lag);
    if (!r.found) return det;

    const double heard_ns =
        static_cast<double>(buffer_start_ns_) +
        static_cast<double>(r.lag_samples) * 1e9 / sample_rate_;
    det.done = true;
    det.valid = true;
    det.latency_ms = static_cast<int32_t>(
        std::llround((heard_ns - static_cast<double>(t0_ns_)) / 1e6));
    disarm();
    return det;
}

}  // namespace synccore
