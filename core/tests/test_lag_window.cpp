// test_lag_window.cpp — CAL-02 acceptance tests for the ported
// analyze_window/WindowLag module (dsp/lag_window.h).
//
// Mirrors test_correlate.cpp's synthetic-signal idiom: inline LCG PRNG,
// no fixture files, no WAV assets.

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "dsp/lag_window.h"

namespace {

int g_failures = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                               \
        }                                                               \
    } while (0)

// Deterministic pseudo-noise, unit-ish variance (same LCG as test_correlate.cpp).
struct Lcg {
    uint32_t s;
    explicit Lcg(uint32_t seed) : s(seed) {}
    float next() {
        s = s * 1664525u + 1013904223u;
        return (static_cast<float>(s >> 8) / 8388608.0f) - 1.0f;
    }
};

// Pseudo-music: low-passed white noise, so the spectrum isn't flat (a flat
// spectrum would make PHAT-style whitening trivially perfect and wouldn't
// exercise the "mild" whitening the way real program material does).
std::vector<float> pseudo_music(size_t n, uint32_t seed) {
    std::vector<float> sig(n, 0.0f);
    Lcg rng(seed);
    float lp = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        lp += 0.08f * (rng.next() - lp);
        sig[i] = lp;
    }
    return sig;
}

void test_known_lag_recovered() {
    const int rate = 48000;
    const double lag_s = 0.8;
    const size_t n = static_cast<size_t>(20.0 * rate);
    const auto sig = pseudo_music(n, 12345);

    std::vector<float> mixed(n);
    const size_t d = static_cast<size_t>(lag_s * rate);
    for (size_t i = 0; i < n; ++i)
        mixed[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);

    const auto r = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 40, 2500);
    CHECK(r.found);
    CHECK(std::abs(r.lag_ms - 800.0) <= 5.0);
    CHECK(r.peak_ratio > 4.0);
}

// A single source does NOT reliably fail the peak_ratio gate, and pinning
// that here is the point of this test.
//
// The search range [40, 2500] ms spans ~118k lags at 48 kHz. The maximum of
// a noise-like autocorrelation over N samples grows like sqrt(2 ln N) ≈ 4.8
// sigma, while mean|ac| ≈ 0.8 sigma, so peak_ratio lands near 6 on pure
// noise with no second copy present at all — comfortably above the 4.0
// threshold. peak_ratio is an extreme-value statistic over the search
// window, not evidence that two copies exist.
//
// What actually protects the referee is that a spurious peak lands at an
// ARBITRARY lag, different for every window, so the >=3-window agreement
// rule (tech-req 2.6) rejects it. This test pins both halves: a single
// source can look "found", and its reported lag does not reproduce.
void test_single_source_lag_does_not_reproduce() {
    const int rate = 48000;
    const size_t win = static_cast<size_t>(8.0 * rate);
    const auto sig = pseudo_music(static_cast<size_t>(40.0 * rate), 999);

    // Three non-overlapping windows of the same single-source signal.
    double lags[3];
    for (int i = 0; i < 3; ++i) {
        const auto r = synccore::analyze_window(
            sig.data() + static_cast<size_t>(i) * win, win, rate, 40, 2500);
        lags[i] = r.lag_ms;
    }

    // No two windows agree within the tolerance the referee uses, so the
    // aggregation rule discards this signal even though peak_ratio passed.
    const double kAgreeMs = 25.0;
    CHECK(std::abs(lags[0] - lags[1]) > kAgreeMs ||
          std::abs(lags[1] - lags[2]) > kAgreeMs);
}

void test_min_lag_ge_max_lag_yields_not_found() {
    const int rate = 48000;
    const size_t n = static_cast<size_t>(20.0 * rate);
    const auto sig = pseudo_music(n, 42);

    std::vector<float> mixed(n);
    const size_t d = static_cast<size_t>(0.8 * rate);
    for (size_t i = 0; i < n; ++i)
        mixed[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);

    // min_lag == max_lag: degenerate search range.
    auto r = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 500, 500);
    CHECK(!r.found);
    CHECK(r.lag_ms == 0);
    CHECK(r.peak_ratio == 0);

    // min_lag > max_lag: still degenerate, must not crash or search backwards.
    r = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 2500, 40);
    CHECK(!r.found);
    CHECK(r.lag_ms == 0);
    CHECK(r.peak_ratio == 0);
}

}  // namespace

int main() {
    test_known_lag_recovered();
    test_single_source_lag_does_not_reproduce();
    test_min_lag_ge_max_lag_yields_not_found();

    if (g_failures == 0) {
        std::printf("dsp_tests: all tests passed\n");
        return 0;
    }
    std::printf("dsp_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
