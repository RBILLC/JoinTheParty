// test_synccore.cpp — CORE-01 acceptance tests.
//
// Covers: config validation, create/destroy cycles, concurrent capture push
// vs. control-plane races, event delivery (thread + ordering + payloads),
// callback-clear guarantee, and an allocation guard proving sc_push_capture
// never allocates. Framework-free on purpose: zero third-party deps in the
// core test target (technical-requirements.md §4 pin policy).

#include <atomic>
#include <chrono>
#include <cmath>
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
    std::atomic<int> last_reject_reason{-1};
    std::atomic<double> last_error_ms{0.0};
    std::atomic<uint64_t> callback_thread_hash{0};
    std::atomic<int> corrections{0};
    std::atomic<int64_t> last_seek_to_ms{0};
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
        auto* rej = static_cast<const sc_evt_fix_rejected_t*>(payload);
        log->last_reject_reason.store(rej->reason);
        log->rejects.fetch_add(1);
    } else if (type == SC_EVT_CORRECTION) {
        auto* corr = static_cast<const sc_evt_correction_t*>(payload);
        log->last_seek_to_ms.store(corr->seek_to_ms);
        log->corrections.fetch_add(1);
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
    // Kalman posterior after one high-confidence fix against a huge prior:
    // essentially the measurement (50 ms), not exactly it.
    CHECK(std::abs(log.last_error_ms.load() - 50.0) < 0.5);
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

    // Command-latency readback: default prior resolves to 250 ms.
    int32_t latency = 0;
    CHECK(sc_get_command_latency_ms(s, &latency) == SC_OK);
    CHECK(latency == 250);
    CHECK(sc_get_command_latency_ms(s, nullptr) == SC_ERR_INVALID_ARG);

    float ref[64] = {0};
    CHECK(sc_push_reference(s, ref, 64, 0) == SC_OK);
    CHECK(sc_push_reference(s, nullptr, 64, 0) == SC_ERR_INVALID_ARG);

    sc_destroy(s);
}

// CORE-06 self-match guard, rewritten after Field Test 3 (2026-07-26).
//
// Geometry under test is the one the field run actually produced: the phone
// plays out its own speaker, so its mic hears BOTH the room and itself, and
// the room runs 1 200 ms ahead of us. The recognizer then reports one of two
// populations — the room's offset, or our own — and the guard must keep only
// the first without ever rejecting a bootstrap fix or a room perturbation.
//
// Deadband is opened wide here on purpose: this test is about which fixes
// reach the filter, not about corrections, and a correction would pull the
// settle window in and mask the result.
void test_self_hearing_guard() {
    constexpr uint64_t kSec = 1'000'000'000ull;
    sc_config_t cfg = valid_config();
    cfg.deadband_ms = 5000;
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    EventLog log;
    sc_set_event_callback(s, event_cb, &log);

    // Our own playback: 10 000 ms at t0, advancing 1:1 from there.
    const uint64_t t0 = mono_ns();
    sc_player_state_t ps{};
    ps.position_ms = 10000;
    ps.received_mono_ns = t0;
    CHECK(sc_submit_player_state(s, &ps) == SC_OK);
    CHECK(sc_set_aec_mode(s, SC_AEC_FULL) == SC_OK);

    auto submit = [&](int64_t offset_ms, uint64_t t) {
        sc_recognition_fix_t fix{};
        fix.source = SC_FIX_SHAZAMKIT;
        fix.match_offset_ms = offset_ms;
        fix.capture_mono_ns = t;
        fix.confidence = 0.9f;
        CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    };
    // Acceptance is asserted through the FILTER STATE, not an event count:
    // the worker also emits interpolated estimates whenever session time
    // advances, so counting SC_EVT_SYNC_ESTIMATE would count those too.
    // `processed` waits for one such emission to prove the command drained.
    auto processed = [&] {
        const int before = log.estimates.load();
        for (int i = 0; i < 400 && log.estimates.load() == before; ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
    };
    auto error_is = [&](double want) {
        return std::abs(log.last_error_ms.load() - want) < 120.0;
    };

    // Bootstrap (local 15 000, room 16 200): nothing to arbitrate against
    // yet, so it MUST be accepted — rejecting the first fix is precisely how
    // a session gets stuck in MATCHING forever. It seeds the room reference
    // but does NOT yet earn the right to judge other fixes.
    submit(16200, t0 + 5 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);
    CHECK(error_is(-1200.0));  // we are genuinely 1.2 s behind the room

    // A second room fix on the same continuous timeline (16 200 + 5 000)
    // corroborates the reference — only now may it reject anything.
    submit(21200, t0 + 10 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);
    CHECK(error_is(-1200.0));

    // Self-match against the CONFIRMED reference: it lands on our OWN
    // audible position (25 000) while the room prediction says 26 200. This
    // is the fix that used to sail through and tell the filter it was
    // perfectly synced while the room ran away from us.
    submit(25000, t0 + 15 * kSec);
    for (int i = 0; i < 400 && log.rejects.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.rejects.load() == 1);
    CHECK(log.last_reject_reason.load() == SC_REJECT_SELF_HEARING);
    CHECK(error_is(-1200.0));  // filter state untouched by the rejected fix

    // The genuine room fix still tracks the prediction (21 200 + 10 000)
    // even though we remain 1 200 ms adrift → accepted, no new reject.
    submit(31200, t0 + 20 * kSec);
    processed();
    CHECK(log.rejects.load() == 1);
    CHECK(error_is(-1200.0));

    // Room perturbation: someone skips the source 700 ms ahead. That breaks
    // the room prediction, but it does NOT sit on our position, so the guard
    // must let it through — otherwise the app could never follow the room.
    // The filter steps partway toward the new observation (−1 900).
    submit(36900, t0 + 25 * kSec);
    processed();
    CHECK(log.rejects.load() == 1);
    const double moved = log.last_error_ms.load();
    CHECK(moved < -1250.0 && moved > -1950.0);

    sc_destroy(s);
}

// Field Test 4, the systematic ~1 s lag: a recognition fix is 0.8–1.9 s old
// by the time the recognizer answers. A seek target computed for the fix's
// CAPTURE time lands exactly that far behind the room, and since every
// correction re-established it, no amount of correcting ever removed it.
// The decision must be made at current session time.
void test_correction_leads_by_recognition_age() {
    constexpr uint64_t kSec = 1'000'000'000ull;
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    EventLog log;
    sc_set_event_callback(s, event_cb, &log);

    // Local playback: 10 000 ms at t0, advancing 1:1.
    const uint64_t t0 = mono_ns();
    sc_player_state_t ps{};
    ps.position_ms = 10000;
    ps.received_mono_ns = t0;
    CHECK(sc_submit_player_state(s, &ps) == SC_OK);

    // Advance session time to t0 + 6 s the way the shell does it — with
    // capture. The core reads no clocks; capture timestamps ARE its clock.
    std::vector<float> block(24000, 0.0f);  // 0.5 s at 48 kHz
    for (uint64_t k = 1; k <= 12; ++k)
        sc_push_capture(s, block.data(), 24000, t0 + k * 500'000'000ull);
    for (int i = 0; i < 200; ++i) {
        uint64_t end_ns = 0;
        std::vector<float> sink(1024);
        if (sc_copy_recent_capture(s, sink.data(), 1024, &end_ns) > 0 &&
            end_ns >= t0 + 6 * kSec)
            break;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }

    // A fix CAPTURED at t0+5 s (local was 15 000 then) reporting the room at
    // 13 500 → we are 1 500 ms ahead and must seek back. But by now it is
    // t0+6 s and local is 16 000, so the target must be computed from 16 000,
    // not 15 000 — a 1 000 ms difference that is exactly the lag we heard.
    sc_recognition_fix_t fix{};
    fix.source = SC_FIX_SHAZAMKIT;
    fix.match_offset_ms = 13500;
    fix.capture_mono_ns = t0 + 5 * kSec;
    fix.confidence = 0.9f;
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    for (int i = 0; i < 400 && log.corrections.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));

    CHECK(log.corrections.load() == 1);
    // Session time is the END of the last capture block, so now = t0+6.5 s
    // and local = 16 500. Correct target: 16 500 + command latency (250) −
    // 1 500 = 15 250. The capture-time bug gives 15 000 + 250 − 1 500 =
    // 13 750 — a 1.5 s deficit that lands the phone behind the room and that
    // the next correction would recreate.
    const int64_t seek = log.last_seek_to_ms.load();
    CHECK(seek > 15100 && seek < 15400);

    sc_destroy(s);
}

// The anti-poisoning rule: the very first fix of a session is accepted
// without arbitration, so it may itself be a self-match. A lone, unconfirmed
// seed must therefore never be allowed to reject anything — otherwise one
// bad bootstrap silently locks the session out of every real measurement.
void test_self_match_guard_ignores_unconfirmed_reference() {
    constexpr uint64_t kSec = 1'000'000'000ull;
    sc_config_t cfg = valid_config();
    cfg.deadband_ms = 5000;
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    EventLog log;
    sc_set_event_callback(s, event_cb, &log);

    const uint64_t t0 = mono_ns();
    sc_player_state_t ps{};
    ps.position_ms = 10000;
    ps.received_mono_ns = t0;
    CHECK(sc_submit_player_state(s, &ps) == SC_OK);
    CHECK(sc_set_aec_mode(s, SC_AEC_FULL) == SC_OK);

    sc_recognition_fix_t fix{};
    fix.source = SC_FIX_SHAZAMKIT;
    fix.confidence = 0.9f;
    fix.match_offset_ms = 16200;
    fix.capture_mono_ns = t0 + 5 * kSec;
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    for (int i = 0; i < 400 && log.estimates.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));

    // Textbook self-match shape (our position 20 000, prediction 21 200) —
    // but the reference has only one fix behind it, so it must NOT reject.
    const int before = log.estimates.load();
    fix.match_offset_ms = 20000;
    fix.capture_mono_ns = t0 + 10 * kSec;
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    for (int i = 0; i < 400 && log.estimates.load() == before; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.rejects.load() == 0);

    sc_destroy(s);
}

// Field Test 4: the guard locked itself out — once its reference disagreed
// with reality it rejected EVERY subsequent fix while the mic confirmed the
// session was actually in sync. A reference that keeps rejecting is more
// likely wrong than the room is, so it must be dropped and re-seeded.
void test_self_match_guard_recovers_from_bad_reference() {
    constexpr uint64_t kSec = 1'000'000'000ull;
    sc_config_t cfg = valid_config();
    cfg.deadband_ms = 5000;
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    EventLog log;
    sc_set_event_callback(s, event_cb, &log);

    const uint64_t t0 = mono_ns();
    sc_player_state_t ps{};
    ps.position_ms = 10000;
    ps.received_mono_ns = t0;
    CHECK(sc_submit_player_state(s, &ps) == SC_OK);
    CHECK(sc_set_aec_mode(s, SC_AEC_FULL) == SC_OK);

    auto submit = [&](int64_t offset_ms, uint64_t t) {
        sc_recognition_fix_t fix{};
        fix.source = SC_FIX_SHAZAMKIT;
        fix.match_offset_ms = offset_ms;
        fix.capture_mono_ns = t;
        fix.confidence = 0.9f;
        CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    };
    auto processed = [&] {
        const int before = log.estimates.load();
        for (int i = 0; i < 400 && log.estimates.load() == before; ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
    };

    // Establish and confirm a room reference 1 200 ms ahead of us.
    submit(16200, t0 + 5 * kSec);
    processed();
    submit(21200, t0 + 10 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);

    // Three consecutive self-matches. The first two are rejected; the third
    // rejection also DROPS the reference rather than defending it forever.
    submit(25000, t0 + 15 * kSec);
    submit(30000, t0 + 20 * kSec);
    submit(35000, t0 + 25 * kSec);
    for (int i = 0; i < 400 && log.rejects.load() < 3; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.rejects.load() == 3);
    CHECK(log.last_reject_reason.load() == SC_REJECT_SELF_HEARING);

    // With the reference dropped, measurements flow again instead of the
    // session going permanently deaf.
    const int before = log.rejects.load();
    submit(41200, t0 + 30 * kSec);
    processed();
    CHECK(log.rejects.load() == before);
}

// NAT-06b: the capture-history tee returns the newest frames in order with
// the end timestamp of the last frame.
void test_copy_recent_capture() {
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    // Push 3 blocks of 480 frames with recognizable ramps.
    std::vector<float> block(480);
    uint64_t ts = 1'000'000'000ull;
    for (int b = 0; b < 3; ++b) {
        for (int i = 0; i < 480; ++i)
            block[static_cast<size_t>(i)] = static_cast<float>(b) + i * 1e-4f;
        sc_push_capture(s, block.data(), 480, ts);
        ts += 10'000'000ull;  // 10 ms cadence
    }
    // Wait for the worker to drain into history.
    std::vector<float> out(2048, -99.0f);
    uint64_t end_ns = 0;
    int32_t n = 0;
    for (int i = 0; i < 200; ++i) {
        n = sc_copy_recent_capture(s, out.data(),
                                   static_cast<int32_t>(out.size()), &end_ns);
        if (n >= 3 * 480) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    CHECK(n == 3 * 480);
    // Chronological: first copied frame is block 0's first sample, last is
    // block 2's last sample.
    CHECK(std::abs(out[0] - 0.0f) < 1e-6f);
    CHECK(std::abs(out[static_cast<size_t>(n - 1)] - (2.0f + 479 * 1e-4f)) < 1e-5f);
    // End timestamp = last block start + 480 frames at 48 kHz = +10 ms.
    CHECK(end_ns == 1'000'000'000ull + 2 * 10'000'000ull + 10'000'000ull);

    CHECK(sc_copy_recent_capture(nullptr, out.data(), 100, nullptr) == 0);
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

// FIELD TEST 8 regression: the capture history must not survive a session
// epoch change — a fresh join matched the PREVIOUS session's song from the
// ring's stale tail. sc_reset_capture_history is the shell's "new stream"
// signal.
void test_reset_capture_history_clears_the_ring() {
    sc_config_t cfg{};
    cfg.sample_rate_hz = 48000;
    cfg.channels = 1;
    cfg.initial_route = SC_ROUTE_SPEAKER;
    cfg.output_latency_prior_ms = -1;
    cfg.command_latency_prior_ms = -1;
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    std::vector<float> loud(480, 0.5f);
    uint64_t ts = 1'000'000'000ull;
    for (int i = 0; i < 100; ++i, ts += 10'000'000ull)
        sc_push_capture(s, loud.data(), 480, ts);
    std::this_thread::sleep_for(std::chrono::milliseconds(150));

    std::vector<float> out(48000, -1.0f);
    uint64_t end_ns = 0;
    CHECK(sc_copy_recent_capture(s, out.data(), 48000, &end_ns) > 0);

    CHECK(sc_reset_capture_history(s) == SC_OK);
    CHECK(sc_copy_recent_capture(s, out.data(), 48000, &end_ns) == 0);
    float lvl = -1.0f;
    CHECK(sc_get_input_level(s, &lvl) == SC_OK);
    CHECK(lvl == 0.0f);

    // New epoch's audio flows normally afterwards.
    for (int i = 0; i < 50; ++i, ts += 10'000'000ull)
        sc_push_capture(s, loud.data(), 480, ts);
    std::this_thread::sleep_for(std::chrono::milliseconds(150));
    CHECK(sc_copy_recent_capture(s, out.data(), 48000, &end_ns) > 0);

    sc_destroy(s);
}

int main() {
    test_reset_capture_history_clears_the_ring();
    test_config_validation();
    test_create_destroy_cycles();
    test_events_and_payloads();
    test_setters_clamp_and_validate();
    test_self_hearing_guard();
    test_self_match_guard_recovers_from_bad_reference();
    test_self_match_guard_ignores_unconfirmed_reference();
    test_correction_leads_by_recognition_age();
    test_copy_recent_capture();
    test_concurrent_capture_and_control();

    if (g_failures == 0) {
        std::printf("synccore_tests: all tests passed\n");
        return 0;
    }
    std::printf("synccore_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
