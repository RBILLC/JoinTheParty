// test_oss_ring.cpp — DSP-01a acceptance tests for the onset-strength
// ring/tempogram module (dsp/oss_ring.h).
//
// Framework-free, mirroring test_lag_window.cpp/test_synccore.cpp's house
// conventions: local CHECK macro, inline LCG-driven synthetic signal
// generation, no fixture files or WAV assets, an operator-new hook for the
// allocation guard (test_synccore.cpp:34-51's pattern).

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <vector>

#include "dsp/oss_ring.h"

namespace {

int g_failures = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                               \
        }                                                               \
    } while (0)

// ---- Allocation guard (mirrors test_synccore.cpp:34-51) ---------------
thread_local bool tl_forbid_alloc = false;
std::atomic<uint64_t> g_forbidden_allocs{0};

}  // namespace

void* operator new(std::size_t n) {
    if (tl_forbid_alloc) g_forbidden_allocs.fetch_add(1);
    if (void* p = std::malloc(n ? n : 1)) return p;
    throw std::bad_alloc{};
}
void operator delete(void* p) noexcept {
    if (tl_forbid_alloc) g_forbidden_allocs.fetch_add(1);
    std::free(p);
}
void operator delete(void* p, std::size_t) noexcept { ::operator delete(p); }

namespace {

// Deterministic pseudo-noise (same LCG as test_correlate.cpp/test_lag_window.cpp).
struct Lcg {
    uint32_t s;
    explicit Lcg(uint32_t seed) : s(seed) {}
    float next() {
        s = s * 1664525u + 1013904223u;
        return (static_cast<float>(s >> 8) / 8388608.0f) - 1.0f;
    }
};

constexpr int kRate = 48000;

// A band-limited click: a short decaying noise burst (~4 ms), the kind of
// broadband transient that produces a sharp onset in spectral flux across
// many bins, without being a bare unit impulse.
void write_click(std::vector<float>& sig, size_t start, float amp, Lcg& rng) {
    const size_t burst_len = static_cast<size_t>(0.004 * kRate);  // ~4 ms
    for (size_t i = 0; i < burst_len && start + i < sig.size(); ++i) {
        const float decay = std::exp(-3.0f * static_cast<float>(i) /
                                    static_cast<float>(burst_len));
        sig[start + i] += amp * decay * rng.next();
    }
}

// Click amplitude for click #click_index in a uniformly-spaced train.
// weak_every_other=false: every click is full strength (the plain BPM
// tests). weak_every_other=true (the octave-ambiguity test): odd clicks
// are attenuated to weak_amp; even (accent) clicks stay full strength.
double click_amplitude(size_t click_index, bool weak_every_other,
                      double weak_amp) {
    if (!weak_every_other) return 1.0;
    return (click_index % 2 == 1) ? weak_amp : 1.0;
}

// A uniform click track at `period_ms` spacing over `duration_s` seconds.
// If `weak_every_other` is set, alternating clicks are attenuated to
// `weak_amp` — used by the octave-ambiguity test to bury a true fund period
// of 2*period_ms inside a denser, uniformly-spaced click stream.
std::vector<float> click_track(double period_ms, double duration_s,
                               uint32_t seed, bool weak_every_other = false,
                               double weak_amp = 0.15) {
    const size_t n = static_cast<size_t>(duration_s * kRate);
    std::vector<float> sig(n, 0.0f);
    Lcg rng(seed);
    const size_t period_samples =
        static_cast<size_t>(period_ms * kRate / 1000.0);
    size_t click_index = 0;
    for (size_t start = 0; start < n; start += period_samples, ++click_index) {
        const float amp = static_cast<float>(
            click_amplitude(click_index, weak_every_other, weak_amp));
        write_click(sig, start, amp, rng);
    }
    return sig;
}

// Feeds `sig` into `ring` in varying, realistic block sizes (480-4096
// samples), sampling estimate_beat_period() every `sample_every_s` of
// pushed audio. Returns every sampled BeatEstimate in push order, so
// callers can assert across the whole run rather than only the last call.
std::vector<synccore::BeatEstimate> run_and_sample(
    synccore::OnsetStrengthRing& ring, const std::vector<float>& sig,
    double sample_every_s) {
    std::vector<synccore::BeatEstimate> samples;
    static const size_t kBlockSizes[] = {480, 960, 1024, 2048, 4096};
    size_t bs_idx = 0;
    size_t offset = 0;
    const double ns_per_sample = 1.0e9 / kRate;
    uint64_t next_sample_ns =
        static_cast<uint64_t>(sample_every_s * 1.0e9);

    while (offset < sig.size()) {
        const size_t bs = kBlockSizes[bs_idx % 5];
        ++bs_idx;
        const size_t take = std::min(bs, sig.size() - offset);
        offset += take;
        const uint64_t end_ns =
            static_cast<uint64_t>(static_cast<double>(offset) * ns_per_sample);
        ring.push(sig.data() + offset - take, take, end_ns);

        if (end_ns >= next_sample_ns) {
            samples.push_back(ring.estimate_beat_period(end_ns));
            next_sample_ns += static_cast<uint64_t>(sample_every_s * 1.0e9);
        }
    }
    return samples;
}

// Independently computes the same unbiased normalized autocorrelation
// formula tech-req §2.10 specifies, directly over a ground-truth click
// amplitude sequence sampled at the OSS rate (93.75 Hz) — a stand-in for
// what OnsetStrengthRing's internal o(m) looks like, without reading any
// of its private state. Used only to prove, independently of the module
// under test, that a naive (non-harmonic) argmax over this signal favors
// the denser click spacing — i.e. that the octave-ambiguity test is a
// genuine negative test and not a tautology.
// period_ms/lag_ms must land on exact OSS-bin multiples of the 10.6667 ms
// bin spacing (kHopSize/kRate) so the proxy's own index placement is exact
// and this independent check isn't itself corrupted by sub-bin rounding
// drift.
double proxy_r_hat_at_ms(double period_ms, double lag_ms, double duration_s,
                        bool weak_every_other, double weak_amp) {
    const double kOssRateHz = 93.75;
    const size_t n = static_cast<size_t>(duration_s * kOssRateHz);
    std::vector<double> proxy(n, 0.0);
    const double period_bins = period_ms / 1000.0 * kOssRateHz;
    size_t click_index = 0;
    for (double pos = 0.0; pos < static_cast<double>(n);
        pos += period_bins, ++click_index) {
        const size_t idx = static_cast<size_t>(pos + 0.5);
        if (idx >= n) break;
        proxy[idx] = click_amplitude(click_index, weak_every_other, weak_amp);
    }
    const size_t lag = static_cast<size_t>(lag_ms / 1000.0 * kOssRateHz + 0.5);
    if (lag >= n) return 0.0;
    auto r_at = [&](size_t l) {
        double sum = 0.0;
        const size_t terms = n - l;
        for (size_t m = 0; m < terms; ++m) sum += proxy[m] * proxy[m + l];
        return sum / static_cast<double>(terms);
    };
    const double r0 = r_at(0);
    if (r0 <= 0.0) return 0.0;
    return r_at(lag) / r0;
}

// --- Test 1-2: click tracks at known BPM -------------------------------

void run_click_track_case(double period_ms, const char* label) {
    // >=25 s of signal; spaced samples so the last 3 history entries can
    // legitimately span >=20 s (kStableWindowNs), matching how
    // estimate_beat_period is meant to be polled (the referee's
    // kSampleLatencyResidual cadence), not per-frame.
    const double kDurationS = 50.0;
    const double kSampleEveryS = 12.0;

    synccore::OnsetStrengthRing ring(kRate);
    const auto sig = click_track(period_ms, kDurationS, 4242);
    const auto samples = run_and_sample(ring, sig, kSampleEveryS);

    CHECK(!samples.empty());
    const auto& last = samples.back();
    if (!last.stable) {
        std::printf("  [%s] final estimate not stable (period_ms=%.2f)\n",
                   label, last.period_ms);
    }
    CHECK(last.stable);
    CHECK(std::abs(last.period_ms - period_ms) <= 5.0);
}

void test_120bpm_click_track_locks() {
    run_click_track_case(500.0, "120bpm");
}

void test_90bpm_click_track_locks() {
    run_click_track_case(666.7, "90bpm");
}

void test_60bpm_click_track_locks() {
    run_click_track_case(1000.0, "60bpm");
}

// --- Test 3: octave-ambiguity (harmonic-sum picks the fundamental) -----

void test_octave_ambiguity_picks_fundamental() {
    // Bin choice, load-bearing: the sub/fund pair is 32/64 OSS bins, not
    // the more obvious 24/48 (closer to the spec's literal "250/500 ms"
    // example). A perfectly regular synthetic click machine is exactly
    // self-similar at EVERY multiple of its accent period, not just the
    // fundamental itself -- with a 24/48 pair, the fundamental's own
    // octave-up (2*48=96) is *also* inside the search band
    // [kSearchLagMin, kSearchLagMax]=[24,112] and, being itself an exact
    // multiple of the true period, ties the fundamental's s(l) score
    // (verified empirically: the estimate flip-flopped between 512 ms
    // and its own octave 1024 ms across runs -- a real property of an
    // idealized, perfectly-regular click train that no actual program
    // material has, since real dynamics vary measure to measure). Fund=64
    // keeps 2*64=128 outside the search band entirely, removing that
    // spurious competitor while still fully exercising the sub-vs-fund
    // (32-vs-64, the ticket's "double-time subdivision") disambiguation
    // the harmonic sum exists for.
    const size_t kSubBins = 32;
    const size_t kFundBins = 64;
    const double kBinMs = 1000.0 * 512.0 / kRate;  // kHopSize/kRate, ms/bin
    const double kSubPeriodMs = kSubBins * kBinMs;    // ~341.3 ms
    const double kFundPeriodMs = kFundBins * kBinMs;  // ~682.7 ms
    const double kDurationS = 50.0;
    const double kSampleEveryS = 12.0;
    // Not negligible: for an exactly-periodic click train, the true
    // fundamental's own raw r_hat is *always* 1.0 (Cauchy-Schwarz: shifting
    // an exactly-periodic signal by its own period reproduces it exactly).
    // A vanishing weak_amp (e.g. 0.15) makes the subdivision lag trivially
    // lose to that ceiling. Keeping the weak clicks strong (0.6x) instead
    // makes the subdivision lag genuinely *competitive* with the
    // fundamental's raw score -- the real ambiguity the harmonic sum has
    // to resolve, matching the AC's "r_hat within ~20% of the winner"
    // alternative to a strict argmax flip.
    const double kWeakAmp = 0.6;

    // Independent, non-tautological verification computed directly from
    // the ground-truth click pattern (not from any OnsetStrengthRing
    // internal state): confirm the subdivision lag's raw r_hat is
    // genuinely competitive with (within 20% of) the fundamental's, so a
    // naive/non-harmonic scheme could plausibly prefer it.
    const double r_hat_sub = proxy_r_hat_at_ms(kSubPeriodMs, kSubPeriodMs,
                                              kDurationS,
                                              /*weak_every_other=*/true,
                                              kWeakAmp);
    const double r_hat_fund = proxy_r_hat_at_ms(kSubPeriodMs, kFundPeriodMs,
                                               kDurationS,
                                               /*weak_every_other=*/true,
                                               kWeakAmp);
    std::printf(
        "  [octave] proxy r_hat(sub=%.1fms)=%.4f r_hat(fund=%.1fms)=%.4f\n",
        kSubPeriodMs, r_hat_sub, kFundPeriodMs, r_hat_fund);
    CHECK(r_hat_fund > 0.0);
    CHECK(r_hat_sub >= 0.8 * r_hat_fund);  // competitive: within 20%

    synccore::OnsetStrengthRing ring(kRate);
    const auto sig = click_track(kSubPeriodMs, kDurationS, 909,
                                 /*weak_every_other=*/true, kWeakAmp);
    const auto samples = run_and_sample(ring, sig, kSampleEveryS);

    CHECK(!samples.empty());
    for (size_t i = 0; i < samples.size(); ++i) {
        std::printf("  [octave] sample %zu period_ms=%.2f salience=%.3f "
                   "stable=%d\n",
                   i, samples[i].period_ms, samples[i].salience,
                   samples[i].stable);
    }
    const auto& last = samples.back();
    CHECK(last.stable);
    CHECK(std::abs(last.period_ms - kFundPeriodMs) <= 10.0);
}

// --- Test 4: no-beat white noise never latches onto stability ----------

void test_no_beat_white_noise_never_stable() {
    const double kDurationS = 50.0;
    const double kSampleEveryS = 12.0;

    synccore::OnsetStrengthRing ring(kRate);
    const size_t n = static_cast<size_t>(kDurationS * kRate);
    std::vector<float> noise(n);
    Lcg rng(31337);
    for (size_t i = 0; i < n; ++i) noise[i] = 0.5f * rng.next();

    const auto samples = run_and_sample(ring, noise, kSampleEveryS);
    CHECK(!samples.empty());
    for (size_t i = 0; i < samples.size(); ++i) {
        if (samples[i].stable) {
            std::printf("  [no-beat] sample %zu unexpectedly stable "
                       "(period_ms=%.2f)\n",
                       i, samples[i].period_ms);
        }
        CHECK(!samples[i].stable);
    }
}

// --- Test 5: allocation guard -------------------------------------------

void test_zero_allocation_after_construction() {
    synccore::OnsetStrengthRing ring(kRate);  // construction may allocate

    const size_t n = static_cast<size_t>(30.0 * kRate);
    std::vector<float> sig = click_track(500.0, 30.0, 555);
    CHECK(sig.size() == n);

    static const size_t kBlockSizes[] = {480, 960, 1024, 2048, 4096};
    size_t bs_idx = 0;
    size_t offset = 0;
    const double ns_per_sample = 1.0e9 / kRate;

    g_forbidden_allocs.store(0);
    tl_forbid_alloc = true;
    while (offset < sig.size()) {
        const size_t bs = kBlockSizes[bs_idx % 5];
        ++bs_idx;
        const size_t take = std::min(bs, sig.size() - offset);
        offset += take;
        const uint64_t end_ns = static_cast<uint64_t>(
            static_cast<double>(offset) * ns_per_sample);
        ring.push(sig.data() + offset - take, take, end_ns);
        (void)ring.estimate_beat_period(end_ns);
    }
    tl_forbid_alloc = false;

    CHECK(g_forbidden_allocs.load() == 0);
}

// --- Test 6: a frozen ring must not latch stability ---------------------
//
// Session time can advance while capture is stalled: player-state
// timestamps advance wk.now_ns with no audio flowing, and the shell keeps
// requesting referee samples on its own cadence. Without the frozen-ring
// guard, three polls of an UNCHANGED OSS ring spaced >= 20 s of session
// time apart return three identical periods that "agree within +/-10 ms
// spanning >= 20 s" — latching stable off a single window and defeating
// §2.10's reproducibility-across-independent-windows intent. Pins that
// duplicate polls append nothing to the stability history.

void test_frozen_ring_does_not_latch_stable() {
    synccore::OnsetStrengthRing ring(kRate);
    const auto sig = click_track(500.0, 25.0, 777);
    // Push 25 s of clean 120 BPM audio with no estimates along the way
    // (sample interval far beyond the signal length -> pushes only).
    (void)run_and_sample(ring, sig, 1.0e9);

    const uint64_t t0 = 25'000'000'000ull;
    const auto e1 = ring.estimate_beat_period(t0);
    CHECK(std::abs(e1.period_ms - 500.0) <= 5.0);  // sanity: real estimate

    // Capture stalls; session time keeps advancing. Two more polls over
    // the byte-identical ring, spanning 24 s > kStableWindowNs in total.
    const auto e2 = ring.estimate_beat_period(t0 + 12'000'000'000ull);
    const auto e3 = ring.estimate_beat_period(t0 + 24'000'000'000ull);
    CHECK(!e2.stable);
    CHECK(!e3.stable);  // would latch true without the frozen-ring guard
}

// --- Test 7: reset() is an epoch boundary -------------------------------

void test_reset_clears_stability_and_ring() {
    synccore::OnsetStrengthRing ring(kRate);
    const auto sig = click_track(500.0, 50.0, 8675309);
    const auto samples = run_and_sample(ring, sig, 12.0);
    CHECK(!samples.empty());
    CHECK(samples.back().stable);  // sanity: the run really did lock

    ring.reset();

    const auto after = ring.estimate_beat_period(1);
    CHECK(after.period_ms == 0);
    CHECK(after.salience == 0);
    CHECK(!after.stable);
}

// --- Test 8: DSP-01b's beat_comb_corroborated pure function -------------
//
// Pure-function tests, no OnsetStrengthRing instance needed — just
// BeatEstimate values constructed directly, mirroring how the worker reads
// them off oss_ring's own output. Cases straight from the DSP-01b ticket.

synccore::BeatEstimate make_beat(double period_ms, bool stable) {
    synccore::BeatEstimate b;
    b.period_ms = period_ms;
    b.stable = stable;
    return b;
}

void test_beat_comb_corroborated_k2_within_tolerance() {
    // stable beat 500 ms, second_lag_ms 1000.5 -> true (k=2, |0.5| < 30 ms).
    const auto beat = make_beat(500.0, true);
    CHECK(synccore::beat_comb_corroborated(1000.5, beat));
}

void test_beat_comb_corroborated_k4_within_range() {
    // stable beat 500 ms, second_lag_ms 2000.0 -> true (k=4 exactly) --
    // pins the PM-directed multiplier range (spec text alone only implies
    // "a small integer," the ticket's own negative test only exercises
    // k=1..3, so k=4 needs its own dedicated positive pin).
    const auto beat = make_beat(500.0, true);
    CHECK(synccore::beat_comb_corroborated(2000.0, beat));
}

void test_beat_comb_not_corroborated_no_nearby_harmonic() {
    // stable beat 500 ms, second_lag_ms 760 -> false (no k in [1,4] lands
    // within 30 ms: nearest candidates are 500ms(k=1, |260| off) and
    // 1000ms(k=2, |240| off)).
    const auto beat = make_beat(500.0, true);
    CHECK(!synccore::beat_comb_corroborated(760.0, beat));
}

void test_beat_comb_not_corroborated_when_unstable() {
    // non-stable beat, second_lag_ms exactly 2x period -> false: stability
    // is required regardless of how exact the harmonic alignment is.
    const auto beat = make_beat(500.0, /*stable=*/false);
    CHECK(!synccore::beat_comb_corroborated(1000.0, beat));
}

void test_beat_comb_sentinels() {
    // second_lag_ms == 0 is WindowLag's own "no competitor" sentinel.
    const auto beat = make_beat(500.0, true);
    CHECK(!synccore::beat_comb_corroborated(0.0, beat));

    // beat.period_ms == 0 (e.g. a fresh/never-estimated BeatEstimate{}) has
    // nothing to corroborate against, even if (degenerately) marked stable.
    auto degenerate = make_beat(0.0, true);
    CHECK(!synccore::beat_comb_corroborated(1000.0, degenerate));
}

}  // namespace

int main() {
    test_120bpm_click_track_locks();
    test_90bpm_click_track_locks();
    test_60bpm_click_track_locks();
    test_octave_ambiguity_picks_fundamental();
    test_no_beat_white_noise_never_stable();
    test_zero_allocation_after_construction();
    test_frozen_ring_does_not_latch_stable();
    test_reset_clears_stability_and_ring();
    test_beat_comb_corroborated_k2_within_tolerance();
    test_beat_comb_corroborated_k4_within_range();
    test_beat_comb_not_corroborated_no_nearby_harmonic();
    test_beat_comb_not_corroborated_when_unstable();
    test_beat_comb_sentinels();

    if (g_failures == 0) {
        std::printf("test_oss_ring: all tests passed\n");
        return 0;
    }
    std::printf("test_oss_ring: %d check(s) FAILED\n", g_failures);
    return 1;
}
