#include "dsp/lag_window.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include "dsp/fft.h"

namespace synccore {

WindowLag analyze_window(const float* x, size_t n, int rate,
                         double min_lag_ms, double max_lag_ms) {
    WindowLag result;
    const size_t nfft = next_pow2(n * 2);
    std::vector<float> padded(nfft, 0.0f);
    std::memcpy(padded.data(), x, n * sizeof(float));

    RealFft fft(nfft);
    if (!fft.valid()) return result;

    std::vector<kiss_fft_cpx> spec;
    fft.forward(padded, spec);
    for (auto& b : spec) {
        // Mild whitening (power^0.5 kept) sharpens the copy-lag peak while
        // tolerating the music's own periodicity. See lag_window.h for why
        // this differs from gcc_phat's full PHAT.
        const float mag = std::sqrt(b.r * b.r + b.i * b.i) + 1e-9f;
        const float p = (b.r * b.r + b.i * b.i) / mag;
        b.r = p;
        b.i = 0.0f;
    }
    std::vector<float> ac;
    fft.inverse(spec, ac);

    const size_t min_lag = static_cast<size_t>(min_lag_ms * rate / 1000.0);
    const size_t max_lag = std::min(
        static_cast<size_t>(max_lag_ms * rate / 1000.0), nfft / 2 - 1);
    if (min_lag >= max_lag) return result;

    size_t best = min_lag;
    double best_v = -1e30, sum = 0;
    size_t count = 0;
    for (size_t k = min_lag; k <= max_lag; ++k) {
        const double v = ac[k];
        if (v > best_v) { best_v = v; best = k; }
        sum += std::abs(v);
        ++count;
    }
    const double mean = count ? sum / static_cast<double>(count) : 1.0;
    result.lag_ms = 1000.0 * static_cast<double>(best) / rate;
    result.peak_ratio = mean > 0 ? best_v / mean : 0;
    result.found = result.peak_ratio > 4.0;

    // CTL-03a: comb-flatness second pass. Additive only — everything above
    // this point is byte-identical to the pre-CTL-03a computation. Mirrors
    // the best-peak scan's raw-value comparison (max of v, not |v|) over the
    // same `ac` array, excluding a +/-20 ms neighborhood around `best` so
    // the best peak's own shoulder never scores as its own competitor.
    const size_t excl_samples = static_cast<size_t>(20.0 * rate / 1000.0);
    const size_t excl_lo = best > excl_samples ? best - excl_samples : 0;
    const size_t excl_hi = best + excl_samples;
    double second_v = -1e30;
    size_t second = 0;
    bool have_second = false;
    for (size_t k = min_lag; k <= max_lag; ++k) {
        if (k >= excl_lo && k <= excl_hi) continue;
        const double v = ac[k];
        if (v > second_v) { second_v = v; second = k; have_second = true; }
    }
    if (have_second) {
        result.second_lag_ms = 1000.0 * static_cast<double>(second) / rate;
        // Division guard only; the ratio itself is never clamped (see
        // WindowLag's doc comment — it can be negative or enormous).
        result.comb_ratio = second_v != 0.0 ? best_v / second_v : 0.0;
    }
    return result;
}

}  // namespace synccore
