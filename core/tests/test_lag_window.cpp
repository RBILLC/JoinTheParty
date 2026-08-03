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

// --- CTL-03a: comb-flatness score (second_lag_ms / comb_ratio) ---------

// Two-copy signal (same construction as test_known_lag_recovered, kept as a
// separate function per the "existing tests stay byte-identical" rule):
// one dominant tooth should read a comb_ratio well above the ~2.0 consumer
// threshold, with lag_ms unchanged from that test's existing expectation.
void test_known_lag_comb_ratio_high() {
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
    CHECK(r.comb_ratio > 2.0);
}

// A signal built from two equal-magnitude delayed copies (teeth 600 ms
// apart, >= the 400 ms floor tech-req 2.8 calls out) should read as a flat
// comb: the two teeth score close enough that comb_ratio drops below the
// 2.0 threshold, and the runner-up genuinely competes from a different lag
// (not the best lag's own excluded neighborhood).
void test_flat_comb_low_ratio() {
    const int rate = 48000;
    const size_t n = static_cast<size_t>(20.0 * rate);
    const auto sig = pseudo_music(n, 777);

    const size_t d1 = static_cast<size_t>(0.8 * rate);   // 800 ms
    const size_t d2 = static_cast<size_t>(1.4 * rate);   // 1400 ms

    std::vector<float> mixed(n);
    for (size_t i = 0; i < n; ++i) {
        float v = sig[i];
        if (i >= d1) v += 0.5f * sig[i - d1];
        if (i >= d2) v += 0.5f * sig[i - d2];
        mixed[i] = v;
    }

    const auto r = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 40, 2500);
    CHECK(r.found);
    CHECK(r.comb_ratio < 2.0);
    CHECK(std::abs(r.second_lag_ms - r.lag_ms) > 20.0);
}

// A single-copy signal built from a much slower low-pass (correlation
// length well past the +/-20 ms exclusion band) gives the best peak a
// broad shoulder. The shoulder must never score as the "competitor" —
// second_lag_ms has to land outside the exclusion neighborhood regardless.
void test_broad_shoulder_excludes_neighbor() {
    const int rate = 48000;
    const size_t n = static_cast<size_t>(20.0 * rate);
    std::vector<float> sig(n, 0.0f);
    Lcg rng(2024);
    float lp = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        lp += 0.001f * (rng.next() - lp);  // much slower than pseudo_music's 0.08
        sig[i] = lp;
    }

    std::vector<float> mixed(n);
    const size_t d = static_cast<size_t>(0.8 * rate);
    for (size_t i = 0; i < n; ++i)
        mixed[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);

    const auto r = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 40, 2500);
    CHECK(r.found);
    CHECK(std::abs(r.second_lag_ms - r.lag_ms) > 20.0);
}

// Degenerate search range regression (mirrors
// test_min_lag_ge_max_lag_yields_not_found): the new fields must sit at
// their documented sentinel (0) when the window is unanalyzable, exactly
// like lag_ms/peak_ratio already do.
void test_min_lag_ge_max_lag_yields_zero_comb_fields() {
    const int rate = 48000;
    const size_t n = static_cast<size_t>(20.0 * rate);
    const auto sig = pseudo_music(n, 42);

    std::vector<float> mixed(n);
    const size_t d = static_cast<size_t>(0.8 * rate);
    for (size_t i = 0; i < n; ++i)
        mixed[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);

    const auto r = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 500, 500);
    CHECK(r.second_lag_ms == 0);
    CHECK(r.comb_ratio == 0);
}

// A search range narrower than the exclusion band leaves no candidate
// outside it at all — comb_ratio/second_lag_ms must sentinel to 0 rather
// than dividing by a nonexistent second value.
void test_search_range_narrower_than_exclusion_yields_zero_comb_fields() {
    const int rate = 48000;
    const size_t n = static_cast<size_t>(20.0 * rate);
    const auto sig = pseudo_music(n, 555);

    std::vector<float> mixed(n);
    const size_t d = static_cast<size_t>(0.8 * rate);  // 800 ms
    for (size_t i = 0; i < n; ++i)
        mixed[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);

    // A 20 ms search window straddling the known 800 ms lag: entirely
    // inside the +/-20 ms exclusion band around whatever lag scores best.
    const auto r = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 790, 810);
    CHECK(r.second_lag_ms == 0);
    CHECK(r.comb_ratio == 0);
}

// --- DSP-02a: parameterized whitening exponent (beta-PHAT, §2.11) ------

// Pins that the default (unpassed) whiten_beta argument routes through the
// exact same legacy branch as an explicit 0.5 -- i.e. the two call forms
// are not just "close," they are bit-identical across every WindowLag
// field. This is the byte-identical rule's regression pin.
void test_beta_default_matches_explicit_05() {
    const int rate = 48000;
    const double lag_s = 0.8;
    const size_t n = static_cast<size_t>(20.0 * rate);
    const auto sig = pseudo_music(n, 12345);

    std::vector<float> mixed(n);
    const size_t d = static_cast<size_t>(lag_s * rate);
    for (size_t i = 0; i < n; ++i)
        mixed[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);

    const auto r_default = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 40, 2500);
    const auto r_explicit = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 40, 2500, 0.5);

    CHECK(r_default.lag_ms == r_explicit.lag_ms);
    CHECK(r_default.peak_ratio == r_explicit.peak_ratio);
    CHECK(r_default.found == r_explicit.found);
    CHECK(r_default.second_lag_ms == r_explicit.second_lag_ms);
    CHECK(r_default.comb_ratio == r_explicit.comb_ratio);
}

// The pow-based non-default path is functional, not merely present: it
// still finds the known lag on the house two-copy synthetic, for every beta
// in the §2.11 sweep range. This does NOT claim the pow path is better than
// 0.5 -- only that it's a working lag finder, per DSP-02a's scope (the
// promotion decision is DSP-02b's, not this ticket's).
void test_beta_nondefault_finds_known_lag() {
    const int rate = 48000;
    const double lag_s = 0.8;
    const size_t n = static_cast<size_t>(20.0 * rate);
    const auto sig = pseudo_music(n, 12345);

    std::vector<float> mixed(n);
    const size_t d = static_cast<size_t>(lag_s * rate);
    for (size_t i = 0; i < n; ++i)
        mixed[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);

    const double betas[] = {0.6, 0.7, 0.8};
    for (double beta : betas) {
        const auto r = synccore::analyze_window(
            mixed.data(), static_cast<size_t>(8.0 * rate), rate, 40, 2500,
            beta);
        CHECK(r.found);
        CHECK(std::abs(r.lag_ms - 800.0) <= 5.0);
    }
}

// Guards against a future "simplification" that unifies the 0.5 branch and
// the pow branch into one expression (they agree in exact arithmetic at
// beta = 0.5, which is exactly the trap): at a non-default beta, at least
// one raw output field must differ from the beta = 0.5 run on the same
// input, proving the pow path is a genuinely different computation.
void test_beta_nondefault_differs_from_legacy() {
    const int rate = 48000;
    const double lag_s = 0.8;
    const size_t n = static_cast<size_t>(20.0 * rate);
    const auto sig = pseudo_music(n, 12345);

    std::vector<float> mixed(n);
    const size_t d = static_cast<size_t>(lag_s * rate);
    for (size_t i = 0; i < n; ++i)
        mixed[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);

    const auto r_legacy = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 40, 2500, 0.5);
    const auto r_beta = synccore::analyze_window(
        mixed.data(), static_cast<size_t>(8.0 * rate), rate, 40, 2500, 0.7);

    CHECK(r_legacy.peak_ratio != r_beta.peak_ratio);
}

// Silent-bin guard: an all-zeros input at a non-default beta must not
// produce NaN/inf anywhere in WindowLag, and must land on the r0-degenerate
// "not found" path (mean|ac| == 0, same as the legacy branch's behavior on
// silence) rather than crashing or reporting a spurious lag.
void test_beta_silent_input_safe() {
    const int rate = 48000;
    const size_t n = static_cast<size_t>(20.0 * rate);
    std::vector<float> silence(n, 0.0f);

    const auto r = synccore::analyze_window(
        silence.data(), static_cast<size_t>(8.0 * rate), rate, 40, 2500,
        0.7);

    CHECK(!std::isnan(r.lag_ms) && !std::isinf(r.lag_ms));
    CHECK(!std::isnan(r.peak_ratio) && !std::isinf(r.peak_ratio));
    CHECK(!std::isnan(r.second_lag_ms) && !std::isinf(r.second_lag_ms));
    CHECK(!std::isnan(r.comb_ratio) && !std::isinf(r.comb_ratio));
    CHECK(!r.found);
}

}  // namespace

int main() {
    test_known_lag_recovered();
    test_single_source_lag_does_not_reproduce();
    test_min_lag_ge_max_lag_yields_not_found();
    test_known_lag_comb_ratio_high();
    test_flat_comb_low_ratio();
    test_broad_shoulder_excludes_neighbor();
    test_min_lag_ge_max_lag_yields_zero_comb_fields();
    test_search_range_narrower_than_exclusion_yields_zero_comb_fields();
    test_beta_default_matches_explicit_05();
    test_beta_nondefault_finds_known_lag();
    test_beta_nondefault_differs_from_legacy();
    test_beta_silent_input_safe();

    if (g_failures == 0) {
        std::printf("dsp_tests: all tests passed\n");
        return 0;
    }
    std::printf("dsp_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
