// test_synccore.cpp — CORE-01 acceptance tests.
//
// Covers: config validation, create/destroy cycles, concurrent capture push
// vs. control-plane races, event delivery (thread + ordering + payloads),
// callback-clear guarantee, and an allocation guard proving sc_push_capture
// never allocates. Framework-free on purpose: zero third-party deps in the
// core test target (technical-requirements.md §4 pin policy).

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <thread>
#include <vector>

#include "synccore/synccore.h"
#include "../src/synccore_testing.h"

namespace {

int g_failures = 0;

#define CHECK(cond)                                                         \
    do {                                                                    \
        if (!(cond)) {                                                      \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);     \
            ++g_failures;                                                   \
        }                                                                   \
    } while (0)

// ---- Allocation guard -------------------------------------------------
// The audio thread sets this flag around sc_push_capture; any global
// new/delete while it is set is a real-time-safety violation.
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

sc_config_t valid_config() {
    sc_config_t cfg{};
    cfg.sample_rate_hz = 48000;
    cfg.channels = 1;
    cfg.initial_route = SC_ROUTE_SPEAKER;
    cfg.output_latency_prior_ms = -1;
    cfg.command_latency_prior_ms = -1;
    return cfg;
}

uint64_t mono_ns() {
    return static_cast<uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
}

void test_config_validation() {
    sc_session_t* s = nullptr;
    CHECK(sc_create(nullptr, &s) == SC_ERR_INVALID_ARG);

    sc_config_t cfg = valid_config();
    CHECK(sc_create(&cfg, nullptr) == SC_ERR_INVALID_ARG);

    cfg.sample_rate_hz = 44100;
    CHECK(sc_create(&cfg, &s) == SC_ERR_UNSUPPORTED_RATE);

    cfg = valid_config();
    cfg.channels = 2;
    CHECK(sc_create(&cfg, &s) == SC_ERR_INVALID_ARG);

    cfg = valid_config();
    cfg.output_latency_prior_ms = -2;
    CHECK(sc_create(&cfg, &s) == SC_ERR_INVALID_ARG);

    CHECK(s == nullptr);  // failed creates never touch *out

    // All control-plane entry points reject a null session.
    sc_recognition_fix_t fix{};
    sc_player_state_t ps{};
    CHECK(sc_submit_recognition_fix(nullptr, &fix) == SC_ERR_INVALID_ARG);
    CHECK(sc_submit_player_state(nullptr, &ps) == SC_ERR_INVALID_ARG);
    CHECK(sc_set_user_nudge_ms(nullptr, 0) == SC_ERR_INVALID_ARG);
    CHECK(sc_set_event_callback(nullptr, nullptr, nullptr) == SC_ERR_INVALID_ARG);
    sc_destroy(nullptr);  // documented no-op
}

void test_create_destroy_cycles() {
    for (int i = 0; i < 100; ++i) {
        sc_config_t cfg = valid_config();
        sc_session_t* s = nullptr;
        CHECK(sc_create(&cfg, &s) == SC_OK);
        CHECK(s != nullptr);
        sc_destroy(s);
    }
}

struct EventLog {
    std::atomic<int> estimates{0};
    std::atomic<int> rejects{0};
    std::atomic<double> last_error_ms{0.0};
    std::atomic<uint64_t> callback_thread_hash{0};
};

void event_cb(sc_event_type_t type, const void* payload, void* user) {
    auto* log = static_cast<EventLog*>(user);
    log->callback_thread_hash.store(
        std::hash<std::thread::id>{}(std::this_thread::get_id()));
    if (type == SC_EVT_SYNC_ESTIMATE) {
        auto* est = static_cast<const sc_evt_sync_estimate_t*>(payload);
        log->last_error_ms.store(est->error_ms);
        log->estimates.fetch_add(1);
    } else if (type == SC_EVT_FIX_REJECTED) {
        log->rejects.fetch_add(1);
    }
}

void test_events_and_payloads() {
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    EventLog log;
    CHECK(sc_set_event_callback(s, event_cb, &log) == SC_OK);

    // Player at 10 000 ms, fix says external source is at 9 950 ms
    // → synthetic estimate error = +50 ms (local ahead).
    sc_player_state_t ps{};
    ps.position_ms = 10000;
    ps.is_paused = false;
    ps.received_mono_ns = mono_ns();
    CHECK(sc_submit_player_state(s, &ps) == SC_OK);

    sc_recognition_fix_t fix{};
    fix.source = SC_FIX_SHAZAMKIT;
    fix.match_offset_ms = 9950;
    fix.capture_mono_ns = mono_ns();
    fix.confidence = 0.9f;
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);

    for (int i = 0; i < 200 && log.estimates.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.estimates.load() == 1);
    CHECK(log.last_error_ms.load() == 50.0);
    // Events arrive on the worker thread, never the caller's.
    CHECK(log.callback_thread_hash.load() !=
          std::hash<std::thread::id>{}(std::this_thread::get_id()));

    // A fix inside the post-seek settle window is rejected, not estimated.
    CHECK(sc_notify_seek_issued(s, 12000, mono_ns()) == SC_OK);
    fix.capture_mono_ns = mono_ns();
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    for (int i = 0; i < 200 && log.rejects.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.rejects.load() == 1);
    CHECK(log.estimates.load() == 1);

    // Confidence outside [0,1] rejected at the ABI edge.
    fix.confidence = 1.5f;
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_ERR_INVALID_ARG);

    // After clearing the callback no further events are delivered.
    CHECK(sc_set_event_callback(s, nullptr, nullptr) == SC_OK);
    fix.confidence = 0.9f;
    fix.capture_mono_ns = mono_ns() + 4'000'000'000ull;  // past settle window
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    CHECK(log.estimates.load() == 1);

    sc_destroy(s);
}

void test_setters_clamp_and_validate() {
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    CHECK(sc_set_user_nudge_ms(s, 5000) == SC_OK);   // clamps to +750
    CHECK(sc_set_user_nudge_ms(s, -5000) == SC_OK);  // clamps to -750
    CHECK(sc_set_output_route(s, SC_ROUTE_BLUETOOTH, 180) == SC_OK);
    CHECK(sc_set_output_route(s, static_cast<sc_route_t>(99), 0) ==
          SC_ERR_INVALID_ARG);
    CHECK(sc_set_aec_mode(s, SC_AEC_FULL) == SC_OK);
    CHECK(sc_notify_seek_issued(s, -1, 0) == SC_ERR_INVALID_ARG);
    CHECK(sc_notify_local_playback(s, 1234) == SC_OK);
    CHECK(sc_begin_calibration(s) == SC_OK);
    CHECK(sc_cancel_calibration(s) == SC_OK);

    float ref[64] = {0};
    CHECK(sc_push_reference(s, ref, 64, 0) == SC_OK);
    CHECK(sc_push_reference(s, nullptr, 64, 0) == SC_ERR_INVALID_ARG);

    sc_destroy(s);
}

void test_concurrent_capture_and_control() {
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    EventLog log;
    sc_set_event_callback(s, event_cb, &log);

    std::atomic<bool> run{true};

    // "Audio thread": 480-frame blocks (10 ms cadence), allocation-forbidden.
    std::thread audio([&] {
        std::vector<float> block(480, 0.25f);
        tl_forbid_alloc = true;
        while (run.load(std::memory_order_relaxed)) {
            sc_push_capture(s, block.data(), 480, mono_ns());
            tl_forbid_alloc = false;  // sleep may allocate on some runtimes
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            tl_forbid_alloc = true;
        }
        tl_forbid_alloc = false;
    });

    // Control thread racing setters + submissions against the audio thread.
    std::thread control([&] {
        for (int i = 0; i < 500; ++i) {
            sc_set_user_nudge_ms(s, (i % 300) - 150);
            sc_player_state_t ps{};
            ps.position_ms = i * 20;
            ps.received_mono_ns = mono_ns();
            sc_submit_player_state(s, &ps);
            if (i % 50 == 0) {
                sc_recognition_fix_t fix{};
                fix.match_offset_ms = i * 20;
                fix.capture_mono_ns = mono_ns();
                fix.confidence = 0.5f;
                sc_submit_recognition_fix(s, &fix);
            }
            std::this_thread::sleep_for(std::chrono::microseconds(500));
        }
    });

    control.join();
    run.store(false);
    audio.join();

    uint64_t consumed = 0, overruns = 0;
    // Give the worker a beat to drain the tail of the ring.
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    sc_test_stats(s, &consumed, &overruns);
    CHECK(consumed > 0);
    CHECK(overruns == 0);  // 10 ms blocks against a ~12 s ring must never drop
    CHECK(log.estimates.load() >= 1);
    CHECK(g_forbidden_allocs.load() == 0);  // push path allocated nothing

    sc_destroy(s);
}

}  // namespace

int main() {
    test_config_validation();
    test_create_destroy_cycles();
    test_events_and_payloads();
    test_setters_clamp_and_validate();
    test_concurrent_capture_and_control();

    if (g_failures == 0) {
        std::printf("synccore_tests: all tests passed\n");
        return 0;
    }
    std::printf("synccore_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
