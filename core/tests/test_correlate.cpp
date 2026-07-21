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

}  // namespace

int main() {
    test_gcc_phat_accuracy_20db();
    test_gcc_phat_accuracy_6db();
    test_gcc_phat_rejects_absent_needle();
    test_chirp_loopback_180ms();
    test_chirp_detector_streaming();
    test_chirp_detector_timeout();
    test_session_calibration_roundtrip();

    if (g_failures == 0) {
        std::printf("correlate_tests: all tests passed\n");
        return 0;
    }
    std::printf("correlate_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
