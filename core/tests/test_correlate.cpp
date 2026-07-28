// test_correlate.cpp — CORE-04 acceptance tests.
//
// GCC-PHAT accuracy at 20 dB and 6 dB SNR, PSR-based rejection, chirp
// loopback at a known 180 ms delay, streaming ChirpDetector behavior
// (detection + timeout), and an end-to-end session calibration round trip
// through the C ABI.

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>

#include "../src/synccore_testing.h"
#include "correlate/correlate.h"
#include "synccore/synccore.h"

namespace {

int g_failures = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                               \
        }                                                               \
    } while (0)

constexpr int kRate = 48000;
constexpr uint64_t kSec = 1'000'000'000ull;

// Deterministic pseudo-noise, unit-ish variance.
struct Lcg {
    uint32_t s;
    explicit Lcg(uint32_t seed) : s(seed) {}
    float next() {
        s = s * 1664525u + 1013904223u;
        return (static_cast<float>(s >> 8) / 8388608.0f) - 1.0f;
    }
};

// haystack = silence(offset) + reference + silence(tail), plus white noise
// at the requested SNR (dB, signal power vs noise power).
std::vector<float> embed(const std::vector<float>& ref, int offset_samples,
                         int total_samples, double snr_db, uint32_t seed) {
    std::vector<float> out(static_cast<size_t>(total_samples), 0.0f);
    double sig_pow = 0.0;
    for (float v : ref) sig_pow += static_cast<double>(v) * v;
    sig_pow /= static_cast<double>(ref.size());
    // LCG output is uniform in (−1,1): power = 1/3.
    const double noise_pow = sig_pow / std::pow(10.0, snr_db / 10.0);
    const float scale = static_cast<float>(std::sqrt(noise_pow / (1.0 / 3.0)));
    Lcg rng(seed);
    for (auto& v : out) v = scale * rng.next();
    for (size_t i = 0; i < ref.size(); ++i)
        out[static_cast<size_t>(offset_samples) + i] += ref[i];
    return out;
}

std::vector<float> pseudo_noise_ref(int samples, uint32_t seed) {
    Lcg rng(seed);
    std::vector<float> ref(static_cast<size_t>(samples));
    for (auto& v : ref) v = 0.5f * rng.next();
    return ref;
}

// Pseudo-music (low-passed white noise, same idiom as test_lag_window.cpp):
// CAL-03's referee runs the autocorrelation-based analyze_window, which
// (per dsp/lag_window.h's "mild whitening" design) is tuned for
// non-flat-spectrum program material, not raw white noise.
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

void test_gcc_phat_accuracy_20db() {
    const auto ref = pseudo_noise_ref(kRate / 2, 42);  // 0.5 s reference
    const int true_lag = static_cast<int>(0.2 * kRate);
    const auto hay = embed(ref, true_lag, kRate, 20.0, 7);
    const auto r = synccore::gcc_phat(hay.data(), hay.size(), ref.data(),
                                      ref.size(), kRate);
    CHECK(r.found);
    CHECK(std::abs(r.lag_samples - true_lag) <= (2 * kRate) / 1000);  // ±2 ms
}

void test_gcc_phat_accuracy_6db() {
    const auto ref = pseudo_noise_ref(kRate / 2, 43);
    const int true_lag = static_cast<int>(0.35 * kRate);
    const auto hay = embed(ref, true_lag, kRate * 2, 6.0, 11);
    const auto r = synccore::gcc_phat(hay.data(), hay.size(), ref.data(),
                                      ref.size(), kRate * 2);
    CHECK(r.found);
    CHECK(std::abs(r.lag_samples - true_lag) <= (5 * kRate) / 1000);  // ±5 ms
}

void test_gcc_phat_rejects_absent_needle() {
    const auto ref = pseudo_noise_ref(kRate / 2, 44);
    std::vector<float> hay(kRate, 0.0f);
    Lcg rng(99);
    for (auto& v : hay) v = 0.3f * rng.next();
    const auto r = synccore::gcc_phat(hay.data(), hay.size(), ref.data(),
                                      ref.size(), kRate);
    CHECK(!r.found);
}

void test_chirp_loopback_180ms() {
    const auto chirp = synccore::generate_chirp(kRate);
    const int true_lag = static_cast<int>(0.18 * kRate);
    const auto hay = embed(chirp, true_lag, kRate, 10.0, 21);
    const auto r = synccore::gcc_phat(hay.data(), hay.size(), chirp.data(),
                                      chirp.size(), kRate);
    CHECK(r.found);
    const double measured_ms = 1000.0 * r.lag_samples / kRate;
    CHECK(std::abs(measured_ms - 180.0) <= 5.0);  // backlog CORE-04 AC
}

void test_chirp_detector_streaming() {
    synccore::ChirpDetector det(kRate);
    const auto chirp = synccore::generate_chirp(kRate);

    // t0 = 1 s; chirp audible starting at 1.3 s → latency 300 ms.
    det.arm(1 * kSec);
    const int block = kRate / 100;  // 10 ms blocks
    const auto audio = embed(chirp, static_cast<int>(0.3 * kRate),
                             2 * kRate, 15.0, 5);  // 2 s stream from t=1 s
    synccore::ChirpDetector::Detection final_det;
    for (size_t off = 0; off + block <= audio.size(); off += block) {
        const uint64_t ts = 1 * kSec + off * (kSec / kRate);
        det.push(audio.data() + off, block, ts);
        const auto d = det.poll(ts + block * (kSec / kRate));
        if (d.done) {
            final_det = d;
            break;
        }
    }
    CHECK(final_det.done);
    CHECK(final_det.valid);
    CHECK(std::abs(final_det.latency_ms - 300) <= 5);
}

void test_chirp_detector_timeout() {
    synccore::ChirpDetector det(kRate);
    det.arm(1 * kSec);
    std::vector<float> silence(kRate / 100, 0.0f);
    det.push(silence.data(), silence.size(), 1 * kSec);
    auto d = det.poll(1 * kSec + kSec / 2);
    CHECK(!d.done);
    d = det.poll(10 * kSec);  // past the 8 s window
    CHECK(d.done);
    CHECK(!d.valid);
    CHECK(!det.armed());
}

// ---- End-to-end through the C ABI --------------------------------------

struct CalLog {
    std::atomic<bool> got{false};
    std::atomic<bool> valid{false};
    std::atomic<int32_t> latency_ms{0};
};

void cal_cb(sc_event_type_t type, const void* payload, void* user) {
    if (type != SC_EVT_CALIBRATION_RESULT) return;
    auto* log = static_cast<CalLog*>(user);
    auto* res = static_cast<const sc_evt_calibration_result_t*>(payload);
    log->valid.store(res->valid);
    log->latency_ms.store(res->measured_latency_ms);
    log->got.store(true);
}

// FIELD FIX regression (device test, 2026-07-28): arming the detector with
// wk.now_ns trusted "capture already flowing", which the idle-screen flow
// violates — capture and calibration start together, so now_ns is frozen at
// the PREVIOUS session's last timestamp. A t0 minutes stale made the 8 s
// window expire on the first poll (single tap: instant Failed while the
// chirp was audibly playing), and a rapid re-tap "measured" the staleness of
// the clock itself (a 2232 ms "latency" on device). The arm now defers to
// the next drained capture block. This test recreates the stale-clock gap:
// session time is established, capture stops, a long gap passes, then
// calibration begins simultaneously with fresh capture.
void test_calibration_arm_defers_to_fresh_capture() {
    sc_config_t cfg{};
    cfg.sample_rate_hz = kRate;
    cfg.channels = 1;
    cfg.initial_route = SC_ROUTE_SPEAKER;
    cfg.output_latency_prior_ms = -1;
    cfg.command_latency_prior_ms = -1;
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    CalLog log;
    sc_set_event_callback(s, cal_cb, &log);

    const int block = kRate / 100;  // 10 ms
    const uint64_t block_ns = kSec / 100;
    std::vector<float> silence(static_cast<size_t>(block), 0.0f);

    // Establish session time, then STOP pushing — now_ns freezes here.
    uint64_t ts = 1 * kSec;
    for (int i = 0; i < 50; ++i, ts += block_ns)
        sc_push_capture(s, silence.data(), block, ts);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));  // drain

    // 60 s of dead time with no capture at all — the stale-clock gap.
    ts += 60 * kSec;

    // Begin calibration BEFORE any fresh capture arrives (the idle-screen
    // ordering), then stream capture whose chirp lands 300 ms in.
    CHECK(sc_begin_calibration(s) == SC_OK);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    const auto chirp = synccore::generate_chirp(kRate);
    const auto audio = embed(chirp, static_cast<int>(0.3 * kRate),
                             2 * kRate, 15.0, 47);
    for (size_t off = 0; off + block <= audio.size() && !log.got.load();
         off += block, ts += block_ns)
        sc_push_capture(s, audio.data() + off, block, ts);

    for (int i = 0; i < 200 && !log.got.load(); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));

    // Pre-fix this was got=true valid=FALSE (instant timeout: the 60 s gap
    // exceeded the 8 s window before the chirp existed). Post-fix t0 is the
    // first fresh block, so the chirp lands 300 ms after t0 and measures as
    // exactly that — the gap contributes nothing.
    CHECK(log.got.load());
    CHECK(log.valid.load());
    CHECK(std::abs(log.latency_ms.load() - 300) <= 5);

    sc_destroy(s);
}

void test_session_calibration_roundtrip() {
    sc_config_t cfg{};
    cfg.sample_rate_hz = kRate;
    cfg.channels = 1;
    cfg.initial_route = SC_ROUTE_SPEAKER;
    cfg.output_latency_prior_ms = -1;
    cfg.command_latency_prior_ms = -1;
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    CalLog log;
    sc_set_event_callback(s, cal_cb, &log);

    const int block = kRate / 100;  // 10 ms
    const uint64_t block_ns = kSec / 100;
    std::vector<float> silence(static_cast<size_t>(block), 0.0f);

    // 0.5 s of pre-roll capture establishes session time = 1.5 s.
    uint64_t ts = 1 * kSec;
    for (int i = 0; i < 50; ++i, ts += block_ns)
        sc_push_capture(s, silence.data(), block, ts);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));  // drain

    // Begin calibration at session time 1.5 s; the chirp becomes audible
    // 300 ms later.
    CHECK(sc_begin_calibration(s) == SC_OK);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    const auto chirp = synccore::generate_chirp(kRate);
    const auto audio = embed(chirp, static_cast<int>(0.3 * kRate),
                             2 * kRate, 15.0, 31);  // stream from t=1.5 s
    for (size_t off = 0; off + block <= audio.size() && !log.got.load();
         off += block, ts += block_ns)
        sc_push_capture(s, audio.data() + off, block, ts);

    for (int i = 0; i < 200 && !log.got.load(); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));

    CHECK(log.got.load());
    CHECK(log.valid.load());
    CHECK(std::abs(log.latency_ms.load() - 300) <= 5);

    sc_destroy(s);
}

// ---- CAL-03 acoustic referee (sc_sample_latency_residual) --------------

struct ResidualLog {
    std::atomic<int> estimates{0};
    std::atomic<bool> last_converged{false};
    std::atomic<bool> got_residual{false};
    std::atomic<bool> residual_valid{false};
    std::atomic<int32_t> residual_ms{0};
    std::atomic<float> peak_ratio{0.0f};
};

void residual_cb(sc_event_type_t type, const void* payload, void* user) {
    auto* log = static_cast<ResidualLog*>(user);
    if (type == SC_EVT_SYNC_ESTIMATE) {
        auto* e = static_cast<const sc_evt_sync_estimate_t*>(payload);
        log->last_converged.store(e->converged);
        log->estimates.fetch_add(1);
    } else if (type == SC_EVT_LATENCY_RESIDUAL) {
        auto* r = static_cast<const sc_evt_latency_residual_t*>(payload);
        log->residual_valid.store(r->valid);
        log->residual_ms.store(r->residual_ms);
        log->peak_ratio.store(r->peak_ratio);
        log->got_residual.store(true);
    }
}

// Drives the estimator to LOCKED (converged) the same way
// test_estimator.cpp's World harness does: a small, constant, in-deadband
// error across 3 consecutive fixes (EstimatorConfig::convergence_fixes).
// Player position advances 1:1 from 60 000 ms; each fix reports the
// external source a fixed 5 ms behind local, well inside the default 25 ms
// deadband, and each fix continues the SAME room timeline so the CORE-06
// self-match guard never has a reason to reject one.
void drive_to_converged(sc_session_t* s, ResidualLog* log, uint64_t* last_t_out) {
    constexpr uint64_t kSec = 1'000'000'000ull;
    uint64_t t = 0;
    for (int i = 1; i <= 3; ++i) {
        t = static_cast<uint64_t>(i) * 10 * kSec;
        sc_player_state_t ps{};
        ps.position_ms = 60'000 + static_cast<int64_t>(t / 1'000'000ull);
        ps.is_paused = false;
        ps.received_mono_ns = t;
        CHECK(sc_submit_player_state(s, &ps) == SC_OK);

        sc_recognition_fix_t fix{};
        fix.source = SC_FIX_SHAZAMKIT;
        const double local = 60'000.0 + static_cast<double>(t) / 1e6;
        fix.match_offset_ms = static_cast<int64_t>(std::llround(local - 5.0));
        fix.capture_mono_ns = t;
        fix.confidence = 0.9f;
        CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);

        const int before = log->estimates.load();
        for (int j = 0; j < 400 && log->estimates.load() == before; ++j)
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    CHECK(log->last_converged.load());
    *last_t_out = t;
}

// Builds an n-sample capture buffer containing one pseudo-music signal plus
// a copy of itself delayed by lag_s seconds at half amplitude — the
// "room + our own output" geometry the referee is meant to detect — and
// pushes it into the session's capture history in 10 ms blocks starting at
// start_ns.
void push_two_copy_capture(sc_session_t* s, double lag_s, double seconds,
                           uint64_t start_ns, uint32_t seed) {
    const size_t n = static_cast<size_t>(seconds * kRate);
    const auto sig = pseudo_music(n, seed);
    const size_t d = static_cast<size_t>(lag_s * kRate);
    std::vector<float> mixed(n);
    for (size_t i = 0; i < n; ++i)
        mixed[i] = sig[i] + (i >= d ? 0.5f * sig[i - d] : 0.0f);

    const int block = kRate / 100;  // 10 ms
    const uint64_t block_ns = kSec / 100;
    uint64_t ts = start_ns;
    for (size_t off = 0; off + static_cast<size_t>(block) <= mixed.size();
         off += static_cast<size_t>(block), ts += block_ns)
        sc_push_capture(s, mixed.data() + off, block, ts);
}

// Acceptance criterion (backlog CAL-03): a two-copy capture at a known lag,
// sampled while LOCKED, reports that lag within ±5 ms and valid=true.
void test_latency_residual_roundtrip() {
    sc_config_t cfg{};
    cfg.sample_rate_hz = kRate;
    cfg.channels = 1;
    cfg.initial_route = SC_ROUTE_SPEAKER;
    cfg.output_latency_prior_ms = -1;
    cfg.command_latency_prior_ms = -1;
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    ResidualLog log;
    sc_set_event_callback(s, residual_cb, &log);

    uint64_t last_t = 0;
    drive_to_converged(s, &log, &last_t);

    // 6 s window, 300 ms injected lag — comfortably inside [40, 2500] ms.
    push_two_copy_capture(s, 0.3, 6.0, last_t + kSec, 777);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));  // drain margin

    CHECK(sc_sample_latency_residual(s) == SC_OK);
    for (int i = 0; i < 1000 && !log.got_residual.load(); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(10));

    CHECK(log.got_residual.load());
    CHECK(log.residual_valid.load());
    CHECK(std::abs(log.residual_ms.load() - 300) <= 5);
    CHECK(log.peak_ratio.load() > 4.0f);

    sc_destroy(s);
}

// Gating test (backlog CAL-03): calling sc_sample_latency_residual with NO
// prior converged==true estimate must yield valid=false even though the
// capture contains a clean, easily-detected two-copy signal — the
// convergence gate, not the peak_ratio gate, is what fails here. Exactly
// one fix is submitted (not enough for the 3-consecutive-fix convergence
// requirement), so the estimator is valid but never LOCKED.
void test_latency_residual_gating_unconverged() {
    constexpr uint64_t kSecLocal = 1'000'000'000ull;
    sc_config_t cfg{};
    cfg.sample_rate_hz = kRate;
    cfg.channels = 1;
    cfg.initial_route = SC_ROUTE_SPEAKER;
    cfg.output_latency_prior_ms = -1;
    cfg.command_latency_prior_ms = -1;
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    ResidualLog log;
    sc_set_event_callback(s, residual_cb, &log);

    sc_player_state_t ps{};
    ps.position_ms = 60'000;
    ps.is_paused = false;
    ps.received_mono_ns = 10 * kSecLocal;
    CHECK(sc_submit_player_state(s, &ps) == SC_OK);

    sc_recognition_fix_t fix{};
    fix.source = SC_FIX_SHAZAMKIT;
    fix.match_offset_ms = 59'995;  // 5 ms in-deadband, same as the converged path
    fix.capture_mono_ns = 10 * kSecLocal;
    fix.confidence = 0.9f;
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    for (int i = 0; i < 400 && log.estimates.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.estimates.load() >= 1);
    CHECK(!log.last_converged.load());  // one fix: not LOCKED yet

    push_two_copy_capture(s, 0.3, 6.0, 20 * kSecLocal, 778);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    CHECK(sc_sample_latency_residual(s) == SC_OK);
    for (int i = 0; i < 1000 && !log.got_residual.load(); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(10));

    CHECK(log.got_residual.load());
    CHECK(!log.residual_valid.load());
    // Confirms this is the convergence gate, not a peak_ratio miss: the
    // same signal that gates VALID in the roundtrip test still measures a
    // clean, high peak_ratio here.
    CHECK(log.peak_ratio.load() > 4.0f);

    sc_destroy(s);
}

// AEC-mode test (backlog CAL-03): sc_sample_latency_residual forces AEC off
// for the sampled window and restores the prior mode immediately after,
// never leaving the session in a different AEC state than it found it in.
void test_latency_residual_restores_aec_mode() {
    sc_config_t cfg{};
    cfg.sample_rate_hz = kRate;
    cfg.channels = 1;
    cfg.initial_route = SC_ROUTE_SPEAKER;
    cfg.output_latency_prior_ms = -1;
    cfg.command_latency_prior_ms = -1;
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    ResidualLog log;
    sc_set_event_callback(s, residual_cb, &log);

    CHECK(sc_set_aec_mode(s, SC_AEC_FULL) == SC_OK);

    uint64_t last_t = 0;
    drive_to_converged(s, &log, &last_t);
    push_two_copy_capture(s, 0.3, 6.0, last_t + kSec, 779);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    CHECK(sc_sample_latency_residual(s) == SC_OK);
    for (int i = 0; i < 1000 && !log.got_residual.load(); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    CHECK(log.got_residual.load());

    // The referee's own SC_EVT_LATENCY_RESIDUAL dispatch happens (on the
    // worker thread) strictly after it restores the prior mode, so once the
    // event has landed the restore is guaranteed complete — this is a
    // post-condition check, not a race against the worker.
    sc_aec_mode_t mode = SC_AEC_OFF;
    sc_test_get_aec_mode(s, &mode);
    CHECK(mode == SC_AEC_FULL);

    sc_destroy(s);
}

}  // namespace

int main() {
    test_gcc_phat_accuracy_20db();
    test_gcc_phat_accuracy_6db();
    test_gcc_phat_rejects_absent_needle();
    test_chirp_loopback_180ms();
    test_chirp_detector_streaming();
    test_chirp_detector_timeout();
    test_session_calibration_roundtrip();
    test_calibration_arm_defers_to_fresh_capture();
    test_latency_residual_roundtrip();
    test_latency_residual_gating_unconverged();
    test_latency_residual_restores_aec_mode();

    if (g_failures == 0) {
        std::printf("correlate_tests: all tests passed\n");
        return 0;
    }
    std::printf("correlate_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
