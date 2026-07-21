// test_estimator.cpp — CORE-02 acceptance tests, fully simulated time.
//
// World model used throughout: external speaker plays a track; local Spotify
// plays the same track offset by a true error (optionally growing under
// clock drift). Fixes report the external position; player states report the
// local position. All timestamps are synthetic nanoseconds.

#include <cmath>
#include <cstdio>

#include "estimator/estimator.h"

namespace {

int g_failures = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                               \
        }                                                               \
    } while (0)

#define CHECK_NEAR(val, target, tol)                                          \
    do {                                                                      \
        const double v = (val);                                               \
        if (std::abs(v - (target)) > (tol)) {                                 \
            std::printf("FAIL %s:%d: %s = %.4f, want %.4f +/- %.4f\n",        \
                        __FILE__, __LINE__, #val, v, (double)(target),        \
                        (double)(tol));                                       \
            ++g_failures;                                                     \
        }                                                                     \
    } while (0)

constexpr uint64_t kSec = 1'000'000'000ull;

// Drives a SyncEstimator with a synthetic world.
struct World {
    double true_error_ms;     // error at t=0
    double drift_ms_per_s;    // true error growth
    double fix_skew;          // frequency skew reported on fixes (0 = none)
    synccore::SyncEstimator est;

    explicit World(double err0, double drift = 0.0, double skew = 0.0,
                   synccore::EstimatorConfig cfg = {})
        : true_error_ms(err0), drift_ms_per_s(drift), fix_skew(skew), est(cfg) {}

    double true_error_at(uint64_t t) const {
        return true_error_ms + drift_ms_per_s * (static_cast<double>(t) / kSec);
    }
    // Local playback timeline: position == 60 000 + t (ms), advancing 1:1.
    void push_player(uint64_t t) {
        est.on_player_state(60'000 + static_cast<int64_t>(t / 1'000'000), false, t);
    }
    // External position = local − true_error.
    bool push_fix(uint64_t t, float conf = 0.9f, double noise_ms = 0.0) {
        const double local = 60'000.0 + static_cast<double>(t) / 1e6;
        const double external = local - true_error_at(t) + noise_ms;
        return est.on_fix(static_cast<int64_t>(std::llround(external)), t,
                          fix_skew, conf);
    }
};

void test_requires_player_state() {
    synccore::SyncEstimator e;
    CHECK(!e.on_fix(1000, kSec, 0.0, 0.9f));
    CHECK(!e.estimate_at(kSec).valid);
}

void test_converges_within_three_fixes() {
    World w(40.0);
    for (int i = 1; i <= 3; ++i) {
        const uint64_t t = static_cast<uint64_t>(i) * 10 * kSec;
        w.push_player(t - kSec / 2);
        CHECK(w.push_fix(t));
    }
    const auto est = w.est.estimate_at(30 * kSec);
    CHECK(est.valid);
    CHECK_NEAR(est.error_ms, 40.0, 2.0);
}

void test_out_of_order_fix_rejected() {
    World w(40.0);
    w.push_player(9 * kSec);
    CHECK(w.push_fix(10 * kSec));
    CHECK(!w.push_fix(10 * kSec));      // same timestamp
    CHECK(!w.push_fix(9 * kSec));       // older
}

void test_drift_tracking_with_skew() {
    // 50 ppm true drift, reported via frequency skew (external slow by
    // 50 ppm ⇒ skew = −50e−6 ⇒ error grows at +0.05 ms/s).
    World w(0.0, 0.05, -50e-6);
    for (uint64_t t = 5 * kSec; t <= 60 * kSec; t += 5 * kSec) {
        w.push_player(t - kSec / 2);
        CHECK(w.push_fix(t));
    }
    const auto est = w.est.estimate_at(60 * kSec);
    CHECK_NEAR(est.drift_ppm, 50.0, 5.0);   // backlog CORE-02 AC
    // And the projected error keeps tracking after the last fix.
    const double predicted = w.est.estimate_at(80 * kSec).error_ms;
    CHECK_NEAR(predicted, w.true_error_at(80 * kSec), 3.0);
}

void test_drift_tracking_observation_only() {
    // No skew reports: drift must be inferred from error growth alone. That
    // only identifies well when fix noise is small relative to per-interval
    // drift (0.25 ms per 5 s here), so this scenario declares low-noise
    // measurements. The production path for fast drift ID is the skew
    // observation (previous test); this guards the fallback.
    synccore::EstimatorConfig cfg;
    cfg.meas_noise_ms2 = 1.0;  // 1 ms std — clean-fixture regime
    World w(0.0, 0.05, 0.0, cfg);
    for (uint64_t t = 5 * kSec; t <= 300 * kSec; t += 5 * kSec) {
        w.push_player(t - kSec / 2);
        CHECK(w.push_fix(t, 0.95f));
    }
    const auto est = w.est.estimate_at(300 * kSec);
    CHECK_NEAR(est.drift_ppm, 50.0, 15.0);
}

void test_confidence_decays_with_fix_age() {
    World w(10.0);
    w.push_player(kSec);
    CHECK(w.push_fix(2 * kSec));
    const float fresh = w.est.estimate_at(3 * kSec).confidence;
    const float stale = w.est.estimate_at(60 * kSec).confidence;
    CHECK(fresh > 0.3f);
    CHECK(stale < fresh * 0.5f);  // backlog CORE-02 AC: drops when stale
}

void test_convergence_flag_semantics() {
    World w(5.0);  // inside the 25 ms deadband
    for (int i = 1; i <= 2; ++i) {
        const uint64_t t = static_cast<uint64_t>(i) * 10 * kSec;
        w.push_player(t);
        w.push_fix(t);
        CHECK(!w.est.estimate_at(t).converged);  // needs 3 consecutive
    }
    w.push_player(30 * kSec);
    w.push_fix(30 * kSec);
    CHECK(w.est.estimate_at(30 * kSec).converged);

    // A large-error fix breaks convergence and resets the streak.
    w.true_error_ms = 200.0;
    w.push_player(40 * kSec);
    w.push_fix(40 * kSec);
    CHECK(!w.est.estimate_at(40 * kSec).converged);
}

void test_nudge_shifts_setpoint() {
    // With a −30 ms nudge, a true offset of −30 ms reads as zero error:
    // the wheel re-aims the target rather than fighting the estimator.
    World w(-30.0);
    w.est.set_nudge_ms(-30.0);
    for (int i = 1; i <= 3; ++i) {
        const uint64_t t = static_cast<uint64_t>(i) * 10 * kSec;
        w.push_player(t);
        w.push_fix(t);
    }
    CHECK_NEAR(w.est.estimate_at(30 * kSec).error_ms, 0.0, 2.0);
}

void test_output_latency_enters_model() {
    // 100 ms output latency: reported local position leads what is audible,
    // so with reported error 0 the audible error is −100 ms.
    World w(0.0);
    w.est.set_output_latency_ms(100.0);
    for (int i = 1; i <= 3; ++i) {
        const uint64_t t = static_cast<uint64_t>(i) * 10 * kSec;
        w.push_player(t);
        w.push_fix(t);
    }
    CHECK_NEAR(w.est.estimate_at(30 * kSec).error_ms, -100.0, 2.0);
}

}  // namespace

int main() {
    test_requires_player_state();
    test_converges_within_three_fixes();
    test_out_of_order_fix_rejected();
    test_drift_tracking_with_skew();
    test_drift_tracking_observation_only();
    test_confidence_decays_with_fix_age();
    test_convergence_flag_semantics();
    test_nudge_shifts_setpoint();
    test_output_latency_enters_model();

    if (g_failures == 0) {
        std::printf("estimator_tests: all tests passed\n");
        return 0;
    }
    std::printf("estimator_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
