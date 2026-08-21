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
    // CTL-05: drift observability for the FT10 cascade repro (the drift
    // clamp is what turned one mis-anchored fix into a long monotonic
    // climb — see docs/ctl05-investigation.md §2).
    std::atomic<double> last_drift_ppm{0.0};

    // tech-req §2.17 (CTL-06/W1): the two new diagnostic events.
    std::atomic<int> policy_state_events{0};
    std::atomic<int> last_policy_settled{-1};  // -1 = never seen, else 0/1
    std::atomic<int> last_policy_in_deadband_streak{0};
    std::atomic<int> fix_diag_events{0};
    std::atomic<int64_t> last_fix_diag_offset_ms{0};
    std::atomic<int> last_fix_diag_verdict{-1};
    std::atomic<int> last_fix_diag_tracks_room{0};
    std::atomic<int> last_fix_diag_tracks_cand{0};
    std::atomic<int64_t> last_fix_diag_anchor_offset_ms{0};
    std::atomic<int64_t> last_fix_diag_anchor_age_ms{0};
    std::atomic<double> last_fix_diag_off{0.0};
    std::atomic<double> last_fix_diag_predicted_room{0.0};
    std::atomic<double> last_fix_diag_local_audible_ms{0.0};
};

void event_cb(sc_event_type_t type, const void* payload, void* user) {
    auto* log = static_cast<EventLog*>(user);
    log->callback_thread_hash.store(
        std::hash<std::thread::id>{}(std::this_thread::get_id()));
    if (type == SC_EVT_SYNC_ESTIMATE) {
        auto* est = static_cast<const sc_evt_sync_estimate_t*>(payload);
        log->last_error_ms.store(est->error_ms);
        log->last_drift_ppm.store(est->drift_ppm);
        log->estimates.fetch_add(1);
    } else if (type == SC_EVT_FIX_REJECTED) {
        auto* rej = static_cast<const sc_evt_fix_rejected_t*>(payload);
        log->last_reject_reason.store(rej->reason);
        log->rejects.fetch_add(1);
    } else if (type == SC_EVT_CORRECTION) {
        auto* corr = static_cast<const sc_evt_correction_t*>(payload);
        log->last_seek_to_ms.store(corr->seek_to_ms);
        log->corrections.fetch_add(1);
    } else if (type == SC_EVT_POLICY_STATE) {
        auto* ps = static_cast<const sc_evt_policy_state_t*>(payload);
        log->last_policy_settled.store(ps->settled ? 1 : 0);
        log->last_policy_in_deadband_streak.store(ps->in_deadband_streak);
        log->policy_state_events.fetch_add(1);
    } else if (type == SC_EVT_FIX_DIAG) {
        auto* fd = static_cast<const sc_evt_fix_diag_t*>(payload);
        log->last_fix_diag_offset_ms.store(fd->match_offset_ms);
        log->last_fix_diag_verdict.store(static_cast<int>(fd->verdict));
        log->last_fix_diag_tracks_room.store(fd->tracks_room ? 1 : 0);
        log->last_fix_diag_tracks_cand.store(fd->tracks_cand ? 1 : 0);
        log->last_fix_diag_anchor_offset_ms.store(fd->room_anchor_offset_ms);
        log->last_fix_diag_anchor_age_ms.store(fd->room_anchor_age_ms);
        log->last_fix_diag_off.store(fd->off);
        log->last_fix_diag_predicted_room.store(fd->predicted_room);
        log->last_fix_diag_local_audible_ms.store(fd->local_audible_ms);
        log->fix_diag_events.fetch_add(1);
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
    // 13 500 → we are 1 500 ms ahead and must seek back. tech-req §2.8
    // (CTL-03b): 1 500 ms is at/above large_correction_threshold_ms, so this
    // FIRST fix is held pending corroboration — no correction may fire off
    // it alone (FT8's 1259 ms single-fix overshoot class).
    sc_recognition_fix_t fix{};
    fix.source = SC_FIX_SHAZAMKIT;
    fix.match_offset_ms = 13500;
    fix.capture_mono_ns = t0 + 5 * kSec;
    fix.confidence = 0.9f;
    const int est_before = log.estimates.load();
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    for (int i = 0; i < 400 && log.estimates.load() == est_before; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.corrections.load() == 0);  // held, not fired

    // Advance session time to t0+8 s and corroborate: a second fix captured
    // at t0+7 s (local was 17 000) reporting the room at 15 500 — the same
    // 1 500 ms error, within large_corroborate_agree_ms of the pending one.
    for (uint64_t k = 13; k <= 16; ++k)
        sc_push_capture(s, block.data(), 24000, t0 + k * 500'000'000ull);
    for (int i = 0; i < 200; ++i) {
        uint64_t end_ns = 0;
        std::vector<float> sink(1024);
        if (sc_copy_recent_capture(s, sink.data(), 1024, &end_ns) > 0 &&
            end_ns >= t0 + 8 * kSec)
            break;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    sc_recognition_fix_t fix2{};
    fix2.source = SC_FIX_SHAZAMKIT;
    fix2.match_offset_ms = 15500;
    fix2.capture_mono_ns = t0 + 7 * kSec;
    fix2.confidence = 0.9f;
    CHECK(sc_submit_recognition_fix(s, &fix2) == SC_OK);
    for (int i = 0; i < 400 && log.corrections.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));

    CHECK(log.corrections.load() == 1);
    // Session time is the END of the last capture block (k=16 starts at
    // t0+8 s, ends t0+8.5 s), so now = t0+8.5 s and local = 18 500. Correct
    // target: 18 500 + command latency (250) − 1 500 = 17 250. The
    // capture-time bug would compute from the fix's capture-time local
    // (17 000 + 250 − 1 500 = 15 750) — a 1.5 s deficit that lands the
    // phone behind the room and that the next correction would recreate.
    const int64_t seek = log.last_seek_to_ms.load();
    CHECK(seek > 17100 && seek < 17400);

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

// CTL-05 (docs/ctl05-investigation.md §6.2, GitHub issue #36): a single
// post-seek fix that happens to track the STALE pre-seek room anchor must
// not regain the guard's full arbitration authority on its own — that is
// exactly what mis-anchored FT10. This proves the guard stays FULLY armed
// (not weakened) through that one fix: a genuinely self-hearing fix
// arriving right after still gets rejected.
void test_post_seek_single_fix_cannot_reanchor() {
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

    // Establish and confirm a room reference 1 200 ms ahead of us, exactly
    // like the pre-existing self-match tests above.
    submit(16200, t0 + 5 * kSec);
    processed();
    submit(21200, t0 + 10 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);

    // A local corrective seek — arms post-seek reconfirmation.
    // Target chosen to land close to projected_local at the landing time
    // (10 000 + 11 250 = 21 250) plus a small +50 ms correction — this
    // keeps the estimator's error state near its pre-seek value instead of
    // injecting an unrelated large jump that would trip the OUTLIER gate
    // (estimator.cpp's innovation gate) on the fixes below; this test is
    // about guard/anchor state, not seek-execution accounting.
    CHECK(sc_notify_seek_issued(s, 21300, t0 + 11 * kSec) == SC_OK);

    // First post-seek fix: tracks the STALE pre-seek anchor (21 200 + 6 000
    // = 27 200) within the 500 ms gate by a ~90 ms coincidence — the exact
    // shape of FT10's fix B. It must be accepted (tracks_room bypasses
    // self-hearing), but must NOT alone regain full arbitration authority.
    submit(27290, t0 + 16 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);

    // A genuinely self-hearing fix arrives next: it lands exactly on our
    // own audible position (10 000 + 21 000 = 31 000) while breaking room
    // continuity. If the single prior fix had silently re-armed the guard
    // pointed at ITSELF, or — worse — had disarmed the guard entirely, this
    // would sail through. It must still be rejected.
    submit(31000, t0 + 21 * kSec);
    for (int i = 0; i < 400 && log.rejects.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.rejects.load() == 1);
    CHECK(log.last_reject_reason.load() == SC_REJECT_SELF_HEARING);

    sc_destroy(s);
}

// CTL-05: the flip side of the test above — two post-seek fixes that agree
// with EACH OTHER (not with the stale pre-seek anchor) must be able to
// promote themselves to the live, arbitration-capable anchor, and that
// promotion must carry REAL rejection authority afterward (not just passive
// acceptance) — proving the guard was correctly re-pointed, not weakened.
void test_post_seek_two_agreeing_fixes_reanchor() {
    constexpr uint64_t kSec = 1'000'000'000ull;
    sc_config_t cfg = valid_config();
    cfg.deadband_ms = 20000;
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

    submit(16200, t0 + 5 * kSec);
    processed();
    submit(21200, t0 + 10 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);

    // Target chosen to land close to projected_local at the landing time
    // (10 000 + 11 250 = 21 250) plus a small +50 ms correction — this
    // keeps the estimator's error state near its pre-seek value instead of
    // injecting an unrelated large jump that would trip the OUTLIER gate
    // (estimator.cpp's innovation gate) on the fixes below; this test is
    // about guard/anchor state, not seek-execution accounting.
    CHECK(sc_notify_seek_issued(s, 21300, t0 + 11 * kSec) == SC_OK);

    // P1: an offset that neither tracks the stale pre-seek anchor
    // (predicted 27 200, off by 650 ms) nor matches our own audible
    // position (26 000, off by 550 ms) — accepted outright (falls through
    // both the tracks_room and self-hearing checks) but does not yet earn
    // anchor authority (only one post-seek fix so far). Chosen close enough
    // to the estimator's current error to stay clear of the unrelated
    // OUTLIER innovation gate (estimator.cpp), same reasoning as the seek
    // target above.
    submit(26550, t0 + 16 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);

    // P2, 5 s later, agrees with P1 exactly (26 550 + 5 000): two post-seek
    // fixes now corroborate each other on a timeline that is NOT the stale
    // anchor — this promotes it.
    submit(31550, t0 + 21 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);

    // P3 is judged against the FRESH anchor and tracks it exactly — proof
    // the new timeline is now the live, trusted reference.
    submit(36550, t0 + 26 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);

    // P4 is a genuinely self-hearing fix relative to the NEW anchor (our
    // own audible position at t0+31s is 10 000 + 31 000 = 41 000, while the
    // new anchor predicts 41 550). If promotion had only granted passive
    // acceptance rather than real arbitration authority, this would sail
    // through uncaught.
    submit(41000, t0 + 31 * kSec);
    for (int i = 0; i < 400 && log.rejects.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.rejects.load() == 1);
    CHECK(log.last_reject_reason.load() == SC_REJECT_SELF_HEARING);

    sc_destroy(s);
}

// CTL-05: full reproduction of FT10's mis-anchoring cascade shape (GitHub
// issue #36) — a corrective seek, a first post-seek fix that coincidentally
// tracks the stale pre-seek anchor, then three fixes from a real, mutually
// consistent SECOND timeline. Pre-fix (verified by temporarily reverting
// the CTL-05 change and rerunning — see docs/ctl05-implementation-review.md
// for the readback), this exact sequence reproduces the bug: the second
// timeline's fixes are rejected SELF_HEARING three times running, the
// anchor only drops on the third, and a FOURTH fix is needed before the
// engine recovers — 20 s during which a large frequency_skew on the very
// first (mis-anchored) fix leaves the drift state pegged at the hard clamp
// the whole time. Post-fix, the second timeline corroborates itself (two
// fixes agreeing) and the THIRD fix of that timeline — not the fourth —
// is accepted directly, carrying the corrective skew that un-pegs drift
// five seconds sooner.
void test_ft10_cascade_repro_recovers_without_fourth_fix() {
    constexpr uint64_t kSec = 1'000'000'000ull;
    sc_config_t cfg = valid_config();
    cfg.deadband_ms = 20000;  // this test is about guard/drift state, not
                              // correction firing
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

    auto submit = [&](int64_t offset_ms, uint64_t t, double skew = 0.0) {
        sc_recognition_fix_t fix{};
        fix.source = SC_FIX_SHAZAMKIT;
        fix.match_offset_ms = offset_ms;
        fix.capture_mono_ns = t;
        fix.confidence = 0.9f;
        fix.frequency_skew = skew;
        CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);
    };
    auto processed = [&] {
        const int before = log.estimates.load();
        for (int i = 0; i < 400 && log.estimates.load() == before; ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
    };

    // Establish and confirm the pre-seek room anchor (21 200 @ t0+10s).
    submit(16200, t0 + 5 * kSec);
    processed();
    submit(21200, t0 + 10 * kSec);
    processed();
    CHECK(log.rejects.load() == 0);

    // Target chosen to land close to projected_local at the landing time
    // (10 000 + 11 250 = 21 250) plus a small +50 ms correction — this
    // keeps the estimator's error state near its pre-seek value instead of
    // injecting an unrelated large jump that would trip the OUTLIER gate
    // (estimator.cpp's innovation gate) on the fixes below; this test is
    // about guard/anchor state, not seek-execution accounting.
    CHECK(sc_notify_seek_issued(s, 21300, t0 + 11 * kSec) == SC_OK);

    // Fix B: tracks the STALE pre-seek anchor (predicted 27 200) within
    // ~90 ms — accepted, exactly like FT10's fix B. Carries a strong
    // negative skew (frequency_skew < 0 ⇒ drift observation > 0), which
    // (EstimatorConfig::drift_clamp_ms_per_s = 0.8 ms/s, estimator.h:35)
    // is what pegs the drift clamp at 800 ppm — the mechanism behind FT10's
    // "drift pegged at 800ppm on 178 log lines" finding.
    submit(27290, t0 + 16 * kSec, -0.001);
    processed();
    CHECK(log.rejects.load() == 0);
    CHECK(std::abs(log.last_drift_ppm.load() - 800.0) < 5.0);  // clamped

    // Fixes C and D: a real, mutually-consistent SECOND timeline (40 ms
    // apart from each other, per the investigation's own C→D reading),
    // ~1 000-1 090 ms off the stale anchor/candidate, and each individually
    // within kSelfMatchWindowMs of our own dead-reckoned audible position —
    // the exact false-positive shape the investigation traces (§2). Both
    // carry a correcting positive skew that, per §7.3, must NOT reach the
    // estimator while they are rejected.
    submit(31200, t0 + 21 * kSec, 0.0009);   // C
    submit(36200, t0 + 26 * kSec, 0.0009);   // D
    for (int i = 0; i < 400 && log.rejects.load() < 2; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    // Both C and D are still individually rejected — a rejected fix must
    // never gain adoption power it didn't have (§7.3) — but D's agreement
    // with C (40 ms, well inside kRoomContinuityGateMs) has now promoted
    // the anchor for fixes AFTER it.
    CHECK(log.rejects.load() == 2);
    CHECK(log.last_reject_reason.load() == SC_REJECT_SELF_HEARING);
    // Drift is UNCHANGED since B — neither C's nor D's skew reached the
    // estimator, exactly as required.
    CHECK(std::abs(log.last_drift_ppm.load() - 800.0) < 5.0);

    // Fix E: judged against the FRESHLY PROMOTED anchor (D, 36 200 @ t=26s)
    // and tracks it exactly (predicted 41 200) — accepted directly, the
    // THIRD fix of the real timeline, not a fourth. This is "the coherent
    // second timeline wins promptly": no fourth fix, no
    // kMaxConsecutiveSelfRejects drop-and-reseed was needed.
    submit(41200, t0 + 31 * kSec, 0.0009);
    processed();
    CHECK(log.rejects.load() == 2);  // no third reject was needed for E

    // Drift must have moved OFF the clamp — E's corrective skew reached the
    // estimator this fix, not stuck waiting for a fourth. This is the
    // direct test of "drift must not peg at the clamp": under the pre-fix
    // guard, this same sequence needs a FOURTH fix (5 s later) before any
    // corrective skew reaches the estimator at all, so drift would still
    // read exactly 800 ppm at this point (verified by temporary revert).
    // E lands 15 s after the last ACCEPTED fix (B) — C and D's rejection
    // means no on_fix call, hence no predict_to, ran for either of them —
    // so E's own position update also sees the covariance growth
    // predict_to accumulates over that full 15 s gap (including a nonzero
    // p01 cross-term), not just its skew update in isolation; the resulting
    // drift lands well short of a full return to zero. The load-bearing
    // property under test is simply that it is materially off the clamp,
    // not pegged at it.
    const double drift_after_e = log.last_drift_ppm.load();
    CHECK(std::abs(drift_after_e) < 750.0);          // below the clamp, not pegged
    CHECK(std::abs(drift_after_e - 800.0) > 300.0);  // materially moved

    // The estimator must not be left sitting in a stable wrong band: E's
    // own large position correction (41 200, tracking the real room) must
    // have moved the filtered error, not left it at whatever B alone set.
    const double error_after_e = log.last_error_ms.load();
    CHECK(std::abs(error_after_e) < 20000.0);  // inside the widened deadband

    sc_destroy(s);
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

// CTL-01a (tech-req §2.9) ABI coverage for SC_EVT_ACTIVE_PROBE /
// sc_notify_probe_executed. The full sentinel/turn-off/verdict decision
// logic already has a dedicated closed-form test suite at the policy level
// (core/tests/test_policy.cpp's referee-sentinel/turn-off/verdict tests) —
// driving that same logic end-to-end through the C ABI would mean either
// faking a genuine acoustic echo well enough to satisfy the referee's
// agreement ring, or reproducing the estimator's exact confidence-decay
// curve from outside to cross the Wittenmark dwell threshold — both
// heavier than this layer should carry, and the ticket's own acceptance
// criteria explicitly sanction the lighter route taken here: (a) the ABI
// check (tests/abi_c_check.c) covers the new enum value, payload struct,
// and function compiling/linking as plain C99; (b) this test covers
// sc_notify_probe_executed's documented contract — a null session is
// rejected, and calling with no probe outstanding on a live session is
// safely ignored (still SC_OK, no event fires as a side effect, and the
// session keeps working normally afterward).
void test_probe_executed_no_pending_is_safely_ignored() {
    CHECK(sc_notify_probe_executed(nullptr) == SC_ERR_INVALID_ARG);

    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    EventLog log;
    CHECK(sc_set_event_callback(s, event_cb, &log) == SC_OK);

    // No probe has ever been requested on this fresh session.
    CHECK(sc_notify_probe_executed(s) == SC_OK);

    // Give the worker a beat to process the command; confirm it produced
    // no event of any kind — safely ignored, not silently mis-firing one.
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    CHECK(log.estimates.load() == 0);
    CHECK(log.rejects.load() == 0);
    CHECK(log.corrections.load() == 0);

    // The session keeps working normally afterward — the stray echo left
    // no corrupted state behind.
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

    sc_destroy(s);
}

// ---- DSP-01b: tempogram consumer wiring (§2.10/§2.8) -------------------
//
// Self-contained click-track generator mirroring test_oss_ring.cpp's own
// Lcg/write_click/click_track helpers (ticket instruction: keep this file's
// test data inline rather than sharing a source file across test targets).

struct DspClickLcg {
    uint32_t s;
    explicit DspClickLcg(uint32_t seed) : s(seed) {}
    float next() {
        s = s * 1664525u + 1013904223u;
        return (static_cast<float>(s >> 8) / 8388608.0f) - 1.0f;
    }
};

void dsp_write_click(std::vector<float>& sig, size_t start, DspClickLcg& rng) {
    const size_t burst_len = static_cast<size_t>(0.004 * 48000);  // ~4 ms
    for (size_t i = 0; i < burst_len && start + i < sig.size(); ++i) {
        const float decay = std::exp(-3.0f * static_cast<float>(i) /
                                     static_cast<float>(burst_len));
        sig[start + i] += decay * rng.next();
    }
}

// A uniform click track at `period_ms` spacing over `duration_s` seconds at
// 48 kHz — the same band-limited-decaying-burst shape test_oss_ring.cpp's
// click_track uses, kept independent so this file has no cross-target
// source dependency.
std::vector<float> dsp_click_track(double period_ms, double duration_s,
                                   uint32_t seed) {
    const size_t n = static_cast<size_t>(duration_s * 48000);
    std::vector<float> sig(n, 0.0f);
    DspClickLcg rng(seed);
    const size_t period_samples =
        static_cast<size_t>(period_ms * 48000.0 / 1000.0);
    for (size_t start = 0; start < n; start += period_samples)
        dsp_write_click(sig, start, rng);
    return sig;
}

// Pushes sig[off, off+count) into the session's capture path via
// sc_push_capture, 10 ms (480-sample) blocks, chunked into ~4 s bursts with
// a short real-time drain margin between bursts so the ~12 s capture ring
// never overflows on pushes longer than its own span — the same
// burst-then-drain idiom test_correlate.cpp's push_two_copy_capture uses,
// extended here for streams longer than 12 s. *ts is the running capture
// timestamp, advanced by exactly count/48000 seconds on return.
void dsp_push_click_range(sc_session_t* s, const std::vector<float>& sig,
                          size_t off, size_t count, uint64_t* ts) {
    CHECK(off + count <= sig.size());  // catch an out-of-range slice, not read past it
    constexpr int block = 480;              // 10 ms @ 48 kHz
    constexpr uint64_t block_ns = 10'000'000ull;
    constexpr size_t kBurstBlocks = 400;    // ~4 s per burst
    size_t blocks_since_drain = 0;
    size_t pushed = 0;
    while (pushed + static_cast<size_t>(block) <= count) {
        sc_push_capture(s, sig.data() + off + pushed, block, *ts);
        pushed += static_cast<size_t>(block);
        *ts += block_ns;
        if (++blocks_since_drain >= kBurstBlocks) {
            std::this_thread::sleep_for(std::chrono::milliseconds(60));
            blocks_since_drain = 0;
        }
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(120));  // drain margin
}

// Worker wiring + cadence (DSP-01b AC): pushing a click track through the
// normal capture path feeds OnsetStrengthRing::push at the drain loop's
// post-AEC tap, but estimate_beat_period is invoked ONLY on the
// kSampleLatencyResidual cadence — never spontaneously, however much audio
// has flowed. Three residual samples spaced >=20 s of capture time apart
// (the module's own stability window, tech-req §2.10) should land the
// beat_period_ms mirror near the click period.
void test_oss_ring_wiring_and_cadence() {
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    constexpr double kPeriodMs = 500.0;  // 120 BPM
    // 4 chunks of 20 s each get pushed below (the initial pre-command push
    // plus one per residual-command loop iteration) = 80 s consumed; a few
    // seconds of margin avoids reading past the generated track's end.
    constexpr double kTotalS = 85.0;
    const auto click = dsp_click_track(kPeriodMs, kTotalS, 5150);
    const size_t chunk_samples = static_cast<size_t>(20.0 * 48000);  // 20 s

    uint64_t ts = 1'000'000'000ull;
    size_t off = 0;

    // First 20 s of audio, no residual command issued yet.
    dsp_push_click_range(s, click, off, chunk_samples, &ts);
    off += chunk_samples;

    int32_t beat_comb = -1;
    double beat_period_ms = -1.0;
    sc_test_get_beat_state(s, &beat_comb, &beat_period_ms);
    CHECK(beat_comb == 0);
    CHECK(beat_period_ms == 0.0);  // never polled -> still the {0,0,false} default

    // Three residual samples, each preceded by another 20 s of capture --
    // spaced far enough apart in capture time for the module's own
    // last-3-agree-over->=20s stability rule to have a chance to latch.
    for (int i = 0; i < 3; ++i) {
        dsp_push_click_range(s, click, off, chunk_samples, &ts);
        off += chunk_samples;
        CHECK(sc_sample_latency_residual(s) == SC_OK);
        std::this_thread::sleep_for(std::chrono::milliseconds(150));
    }

    sc_test_get_beat_state(s, &beat_comb, &beat_period_ms);
    if (std::abs(beat_period_ms - kPeriodMs) > 10.0) {
        std::printf("  [cadence] beat_period_ms=%.2f (expect ~%.1f)\n",
                   beat_period_ms, kPeriodMs);
    }
    CHECK(std::abs(beat_period_ms - kPeriodMs) <= 10.0);

    sc_destroy(s);
}

// §2.8 cross-check wiring (DSP-01b AC). Construction matters here: a bare
// click track of independent noise bursts has NO coherent autocorrelation
// teeth -- burst i and burst i+k are different noise realizations, so
// their lag-k products sum to realization noise and second_lag_ms lands
// essentially anywhere (an earlier draft of this test asserted off such a
// track and was a coin flip that happened to pass a few runs in a row).
// The deterministic acoustic form of the Billie Jean ambiguity is
// BEAT-ALIGNED COHERENT COPIES: the same underlying track superposed at
// delays of one and two beat periods, x(t) = c(t) + c(t-625ms) +
// c(t-1250ms). Coherent pairs then put true teeth at 625 ms (two pair
// contributions: c0-c1, c1-c2) and 1250 ms (one: c0-c2), so
// analyze_window's best lag is ~625 and its second_lag_ms ~1250 -- landing
// on k=2 of the beat period the tempogram reads off the (unchanged) 625 ms
// click grid. Every quantity the flag depends on is coherent, not
// realization noise.
void test_beat_comb_cross_check_wiring() {
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    constexpr double kPeriodMs = 625.0;
    constexpr double kTotalS = 90.0;
    constexpr size_t kPeriodSamples = 30000;  // 625 ms @ 48 kHz, exact
    const auto base = dsp_click_track(kPeriodMs, kTotalS, 9001);
    std::vector<float> click(base.size());
    for (size_t i = 0; i < base.size(); ++i) {
        float v = base[i];
        if (i >= kPeriodSamples) v += base[i - kPeriodSamples];
        if (i >= 2 * kPeriodSamples) v += base[i - 2 * kPeriodSamples];
        click[i] = v / 3.0f;  // headroom: three superposed copies
    }
    const size_t chunk_samples = static_cast<size_t>(20.0 * 48000);

    uint64_t ts = 1'000'000'000ull;
    size_t off = 0;

    int32_t beat_comb = -1;
    double beat_period_ms = -1.0;
    // Poll every 20 s until the estimate stabilizes AND the comb flag
    // latches (bounded attempts so a genuine wiring regression fails fast
    // instead of hanging). Requiring both in the loop condition -- rather
    // than breaking on stability and asserting the flag from that single
    // window -- keeps one window-boundary artifact from failing the run
    // while still requiring the flag to genuinely latch off the acoustics.
    bool corroborated = false;
    for (int i = 0; i < 6 && off + chunk_samples <= click.size(); ++i) {
        dsp_push_click_range(s, click, off, chunk_samples, &ts);
        off += chunk_samples;
        CHECK(sc_sample_latency_residual(s) == SC_OK);
        // The analysis worker publishes beat state to the mirror atomics
        // asynchronously; one fixed 150 ms sleep assumes it finishes within
        // a beat, which TSan's 5-15x slowdown breaks (core-ci linux-tsan
        // observed the mirror a full pass stale: pass 0 printed 0.00 where
        // an unsanitized run prints 626.19, and the comb latch never caught
        // up within the 4 available chunks). Poll bounded instead — same
        // acoustic assertion, no single-sleep timing assumption. The
        // deadline (20 x 150 ms) only burns fully on passes where the flag
        // is legitimately still down.
        for (int w = 0; w < 20; ++w) {
            std::this_thread::sleep_for(std::chrono::milliseconds(150));
            sc_test_get_beat_state(s, &beat_comb, &beat_period_ms);
            if (std::abs(beat_period_ms - kPeriodMs) <= 10.0 &&
                beat_comb == 1) {
                break;
            }
        }
        std::printf("  [comb] pass %d beat_period_ms=%.2f beat_comb=%d\n",
                   i, beat_period_ms, beat_comb);
        if (std::abs(beat_period_ms - kPeriodMs) <= 10.0 && beat_comb == 1 &&
            i >= 2) {
            corroborated = true;
            break;
        }
    }
    CHECK(corroborated);

    sc_destroy(s);
}

// Dedicated, minimal event log/callback for the track-lost test below --
// kept separate from the shared EventLog/event_cb above (which has no
// SC_EVT_TRACK_LOST handling and is used by many other tests) rather than
// widening a shared helper for one additive test.
struct DspTrackLostLog {
    std::atomic<int> track_lost{0};
};

void dsp_track_lost_cb(sc_event_type_t type, const void*, void* user) {
    if (type == SC_EVT_TRACK_LOST)
        static_cast<DspTrackLostLog*>(user)->track_lost.fetch_add(1);
}

// kTrackLost epoch (DSP-01b AC): after a stable beat estimate, forcing the
// track-lost path must clear the beat-state mirrors -- a fresh epoch must
// never let a beat estimate (or comb corroboration) survive into it, the
// same rule §2.7's persistence ring and CorrectionPolicy::reset() already
// follow. Track-lost is forced the same way test_policy.cpp's own
// track-lost tests do it: a single huge-error fix (|error_ms| far past
// lost_threshold_ms=2000) fires kTrackLost immediately, even on the very
// first-ever fix, since the outlier gate only engages once the filter is
// already confident (p00_ small) -- not true before any fix has landed.
void test_track_lost_clears_beat_state() {
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    constexpr double kPeriodMs = 500.0;
    constexpr double kTotalS = 65.0;
    const auto click = dsp_click_track(kPeriodMs, kTotalS, 3113);
    const size_t chunk_samples = static_cast<size_t>(20.0 * 48000);

    uint64_t ts = 1'000'000'000ull;
    size_t off = 0;
    for (int i = 0; i < 3; ++i) {
        dsp_push_click_range(s, click, off, chunk_samples, &ts);
        off += chunk_samples;
        CHECK(sc_sample_latency_residual(s) == SC_OK);
        std::this_thread::sleep_for(std::chrono::milliseconds(150));
    }

    int32_t beat_comb = -1;
    double beat_period_ms = -1.0;
    sc_test_get_beat_state(s, &beat_comb, &beat_period_ms);
    if (std::abs(beat_period_ms - kPeriodMs) > 10.0) {
        std::printf("  [track-lost] pre-lost beat_period_ms=%.2f "
                   "(expect ~%.1f)\n",
                   beat_period_ms, kPeriodMs);
    }
    CHECK(std::abs(beat_period_ms - kPeriodMs) <= 10.0);  // sanity: really did lock

    // Force kTrackLost: first-ever fix, huge offset well past
    // lost_threshold_ms (2000 ms) in magnitude.
    const uint64_t fix_ns = ts + 1'000'000'000ull;
    sc_player_state_t ps{};
    ps.position_ms = 60'000;
    ps.is_paused = false;
    ps.received_mono_ns = fix_ns;
    CHECK(sc_submit_player_state(s, &ps) == SC_OK);

    DspTrackLostLog log;
    CHECK(sc_set_event_callback(s, dsp_track_lost_cb, &log) == SC_OK);

    sc_recognition_fix_t fix{};
    fix.source = SC_FIX_SHAZAMKIT;
    fix.match_offset_ms = 54'000;  // ~6 s off -> |error_ms| well past 2000
    fix.capture_mono_ns = fix_ns;
    fix.confidence = 0.9f;
    CHECK(sc_submit_recognition_fix(s, &fix) == SC_OK);

    for (int i = 0; i < 400 && log.track_lost.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.track_lost.load() >= 1);  // sanity: the forcing actually worked

    sc_test_get_beat_state(s, &beat_comb, &beat_period_ms);
    CHECK(beat_comb == 0);
    CHECK(beat_period_ms == 0.0);

    sc_destroy(s);
}

// ---- DSP-03a: volume-duck echo contract + deferred detector math -------
//
// tech-req §2.12. The tier switch / trigger composition / verdict-band
// decision logic already has a dedicated closed-form suite at the policy
// level (core/tests/test_policy.cpp's DSP-03a block), matching the CTL-01a
// precedent test_probe_executed_no_pending_is_safely_ignored's own comment
// cites just above. This layer covers what that one can't: (a) the ABI
// contract (abi_c_check.c covers compile/link; this file covers the echo's
// runtime behavior end-to-end through the real C ABI), and (b) the
// deferred matched-filter detector itself, which is worker-side DSP with
// no policy-level equivalent to test against.

// DSP-03a echo contract: a null session is rejected, and a stray echo (no
// duck outstanding) on a live session is harmless — still SC_OK, no event
// fires as a side effect, and the session keeps working normally
// afterward. Mirrors test_probe_executed_no_pending_is_safely_ignored
// above.
void test_duck_executed_echo_contract() {
    CHECK(sc_notify_duck_executed(nullptr, 60) == SC_ERR_INVALID_ARG);

    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    EventLog log;
    CHECK(sc_set_event_callback(s, event_cb, &log) == SC_OK);

    // No duck has ever been requested on this fresh session.
    CHECK(sc_notify_duck_executed(s, 60) == SC_OK);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    CHECK(log.estimates.load() == 0);
    CHECK(log.rejects.load() == 0);
    CHECK(log.corrections.load() == 0);

    // The session keeps working normally afterward -- the stray echo left
    // no corrupted state behind.
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

    sc_destroy(s);
}

// Uniform-noise generator for the deferred-detector tests below --
// independent of DspClickLcg above (a different stream/use: continuous
// noise, not sparse click bursts). `dip_len_s <= 0` omits the dip entirely
// (the no-dip control test's signal); otherwise samples in
// [dip_start_s, dip_start_s + dip_len_s) are scaled by dip_scale on top of
// `amplitude` -- 0.5 there is a -6.02 dB POWER dip (10*log10(0.5^2)), since
// the detector's envelope reads mean(x^2), not the raw amplitude.
struct DuckNoiseLcg {
    uint32_t s;
    explicit DuckNoiseLcg(uint32_t seed) : s(seed) {}
    float next() {
        s = s * 1664525u + 1013904223u;
        return (static_cast<float>(s >> 8) / 8388608.0f) - 1.0f;  // [-1, 1)
    }
};

// Sample counts (not seconds->float->size_t conversions) throughout, so
// every segment boundary is an EXACT multiple of the 480-frame push block
// dsp_push_click_range uses -- a double-seconds computation here previously
// truncated 5.35 s * 48000 Hz down by a whole block (floating-point
// representation error in the accumulated 5.0 + 0.10 + 0.15 + 0.10), which
// silently dropped the last ~10 ms of the first push and desynchronized the
// dip's placement relative to the echo it was supposed to precede.
std::vector<float> duck_noise_track(size_t total_samples, double amplitude,
                                    size_t dip_start_sample,
                                    size_t dip_len_samples, double dip_scale,
                                    uint32_t seed) {
    std::vector<float> sig(total_samples, 0.0f);
    DuckNoiseLcg rng(seed);
    const size_t dip_end = dip_start_sample + dip_len_samples;
    for (size_t i = 0; i < total_samples; ++i) {
        const double scale = (dip_len_samples > 0 && i >= dip_start_sample &&
                              i < dip_end)
                                  ? amplitude * dip_scale
                                  : amplitude;
        sig[i] = static_cast<float>(scale * rng.next());
    }
    return sig;
}

// Shared layout for the two deferred-detector tests below (relative to
// session start): 5.0 s clean baseline, a 100 ms clean buffer (the tail of
// the 3 s-preceding-the-search-window baseline the detector actually
// reads), then [in the dip test only] a 150 ms dip landing right at the
// start of the search window [echo_ns-250ms, ...), then 100 ms more clean
// audio up to the echo point at 5.35 s. duck_ms defaults to 150 (default
// config), so the search window reaches to echo+150ms+750ms = 6.25 s;
// pushing on to 7.0 s total clears tick()'s own +250 ms analysis margin
// (ready at 6.5 s) with real headroom, comfortably under the 12 s history
// ring (no wraparound to reason about).
constexpr size_t kDuckTestBaselineSamples = 5 * 48000;   // 5.0 s
constexpr size_t kDuckTestBufferSamples = 4800;          // 0.10 s
constexpr size_t kDuckTestDipSamples = 7200;             // 0.15 s
constexpr size_t kDuckTestEchoSamples =                  // 5.35 s
    kDuckTestBaselineSamples + kDuckTestBufferSamples + kDuckTestDipSamples +
    kDuckTestBufferSamples;
constexpr size_t kDuckTestTotalSamples = 7 * 48000;  // 7.0 s
constexpr double kDuckTestAmplitude = 0.3;

// Deferred detector math (DSP-03a AC): a real -6 dB power dip placed a
// couple hundred ms before a known echo point, run through the actual
// capture path and sc_notify_duck_executed, must land close to the true
// depth with a significant z once tick()'s deferred analysis runs.
void test_duck_deferred_detector_finds_dip() {
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    const auto sig = duck_noise_track(
        kDuckTestTotalSamples, kDuckTestAmplitude,
        /*dip_start_sample=*/kDuckTestBaselineSamples + kDuckTestBufferSamples,
        /*dip_len_samples=*/kDuckTestDipSamples, /*dip_scale=*/0.5,
        /*seed=*/7331);

    uint64_t ts = 1'000'000'000ull;
    dsp_push_click_range(s, sig, 0, kDuckTestEchoSamples, &ts);

    CHECK(sc_notify_duck_executed(s, 60) == SC_OK);
    // The worker drains whatever's sitting in the RT ring BEFORE processing
    // its pending command queue each loop iteration (worker_loop's own
    // ordering) -- without this pause, the second push below can flood the
    // ring well before the worker ever dequeues kDuckExecuted, so that same
    // iteration's drain (which runs first) would sweep in ALL of the
    // post-echo audio too and let it advance wk.now_ns past the intended
    // echo point BEFORE the echo is stamped. A short wait here (well over
    // the 2 ms worker poll interval, with nothing new to drain in the
    // meantime) lets the echo land against exactly what was pushed above,
    // matching how the real shell's echo always happens strictly after the
    // duck it's reporting on, with no audio racing ahead of it.
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    const size_t remaining = sig.size() - kDuckTestEchoSamples;
    dsp_push_click_range(s, sig, kDuckTestEchoSamples, remaining, &ts);
    // dsp_push_click_range's own trailing 120 ms drain margin already
    // covers "the audio actually drained"; this extra margin covers "tick()
    // actually ran the deferred analysis once ready" (a worker-poll-cycle
    // concern, not a capture-drain one).
    std::this_thread::sleep_for(std::chrono::milliseconds(300));

    double dip_db = -999.0, z = -999.0;
    sc_test_get_duck_metrics(s, &dip_db, &z);
    std::printf("  [duck] dip_db=%.2f z=%.2f (expect ~6.0 dB, z well above 3)\n",
               dip_db, z);
    CHECK(std::abs(dip_db - 6.0) <= 1.5);
    CHECK(z >= 3.0);

    sc_destroy(s);
}

// No-dip control: the identical layout/timing with no quiet segment at all
// must read a near-zero dip -- proves the detector isn't reading a
// spurious dip out of pure noise (a max-over-positions matched filter has
// some natural upward bias even on flat noise; this bounds it).
void test_duck_deferred_detector_no_dip_reads_near_zero() {
    sc_config_t cfg = valid_config();
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);

    // dip_len_samples = 0 disables the dip entirely -- uniform amplitude
    // throughout.
    const auto sig = duck_noise_track(kDuckTestTotalSamples, kDuckTestAmplitude,
                                      0, 0, 1.0, /*seed=*/4242);

    uint64_t ts = 1'000'000'000ull;
    dsp_push_click_range(s, sig, 0, kDuckTestEchoSamples, &ts);

    CHECK(sc_notify_duck_executed(s, 60) == SC_OK);
    // The worker drains whatever's sitting in the RT ring BEFORE processing
    // its pending command queue each loop iteration (worker_loop's own
    // ordering) -- without this pause, the second push below can flood the
    // ring well before the worker ever dequeues kDuckExecuted, so that same
    // iteration's drain (which runs first) would sweep in ALL of the
    // post-echo audio too and let it advance wk.now_ns past the intended
    // echo point BEFORE the echo is stamped. A short wait here (well over
    // the 2 ms worker poll interval, with nothing new to drain in the
    // meantime) lets the echo land against exactly what was pushed above,
    // matching how the real shell's echo always happens strictly after the
    // duck it's reporting on, with no audio racing ahead of it.
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    const size_t remaining = sig.size() - kDuckTestEchoSamples;
    dsp_push_click_range(s, sig, kDuckTestEchoSamples, remaining, &ts);
    std::this_thread::sleep_for(std::chrono::milliseconds(300));

    double dip_db = -999.0, z = -999.0;
    sc_test_get_duck_metrics(s, &dip_db, &z);
    std::printf("  [duck-control] dip_db=%.2f z=%.2f (expect near 0)\n", dip_db,
               z);
    CHECK(std::abs(dip_db) <= 1.5);

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

// tech-req §2.17 (CTL-06/W1): SC_EVT_POLICY_STATE is dispatched at the exact
// same two call sites as SC_EVT_SYNC_ESTIMATE (synccore.cpp's emit_estimate/
// emit_policy_state pairing), so the two counts must always match — no
// timer of its own, no missed or extra emission. settled_ (tech-req §2.15)
// starts false, is explicitly kept false by the correction this test fires,
// and becomes observably true only once a LATER fix's pre-decision snapshot
// reads back the value the verify fix's own on_estimate call just set — the
// same "emitted before this call's own decision" timing SC_EVT_SYNC_ESTIMATE
// itself already has, so a THIRD fix is needed to observe the transition.
void test_policy_state_cadence_and_settled_transition() {
    constexpr uint64_t kSec = 1'000'000'000ull;
    sc_config_t cfg = valid_config();  // default deadband (25 ms)
    sc_session_t* s = nullptr;
    CHECK(sc_create(&cfg, &s) == SC_OK);
    EventLog log;
    sc_set_event_callback(s, event_cb, &log);

    const uint64_t t0 = mono_ns();
    sc_player_state_t ps{};
    ps.position_ms = 10000;
    ps.received_mono_ns = t0;
    CHECK(sc_submit_player_state(s, &ps) == SC_OK);

    // fix1: local ahead by 200 ms — above the 25 ms default deadband, well
    // below large_correction_threshold_ms (1000) — an ordinary, immediate
    // correction.
    sc_recognition_fix_t fix1{};
    fix1.source = SC_FIX_SHAZAMKIT;
    fix1.match_offset_ms = 9800;
    fix1.capture_mono_ns = t0;
    fix1.confidence = 0.9f;
    CHECK(sc_submit_recognition_fix(s, &fix1) == SC_OK);
    for (int i = 0; i < 400 && log.corrections.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.corrections.load() == 1);
    CHECK(log.estimates.load() == 1);
    CHECK(log.policy_state_events.load() == log.estimates.load());
    CHECK(log.last_policy_settled.load() == 0);

    // Ack the seek — starts the 3 s settle window.
    const int64_t seek_to_ms = log.last_seek_to_ms.load();
    CHECK(sc_notify_seek_issued(s, seek_to_ms, t0) == SC_OK);

    // fix2: the post-settle verify fix (tech-req §2.15's "the one place a
    // landed correction gets confirmed"), past the 3 s settle window, with
    // an offset matching the SAME never-updated player-state projection the
    // seek's own aim was computed from — error settles back near zero.
    sc_recognition_fix_t fix2{};
    fix2.source = SC_FIX_SHAZAMKIT;
    fix2.match_offset_ms = 14000;  // projected_local_ms(t0+4s) = 10000+4000
    fix2.capture_mono_ns = t0 + 4 * kSec;
    fix2.confidence = 0.9f;
    int before = log.estimates.load();
    CHECK(sc_submit_recognition_fix(s, &fix2) == SC_OK);
    for (int i = 0; i < 400 && log.estimates.load() == before; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.estimates.load() == before + 1);
    CHECK(log.policy_state_events.load() == log.estimates.load());
    CHECK(std::abs(log.last_error_ms.load()) < 150.0);
    // settled_ was just set true INSIDE this fix's own on_estimate call,
    // which runs AFTER this fix's policy-state snapshot was already taken
    // (mirroring SC_EVT_SYNC_ESTIMATE's own pre-decision timing) — so THIS
    // event still reads false; the transition becomes visible on the NEXT
    // one.
    CHECK(log.last_policy_settled.load() == 0);

    // fix3: any further small-error fix. Its pre-decision snapshot now
    // reads back fix2's settled_=true — the transition tech-req §2.17 exists
    // to make visible (FT10/FT11's own failed `grep -i settl` probe).
    sc_recognition_fix_t fix3{};
    fix3.source = SC_FIX_SHAZAMKIT;
    fix3.match_offset_ms = 14100;  // projected_local_ms(t0+4.1s) = 10000+4100
    fix3.capture_mono_ns = t0 + 4 * kSec + 100'000'000ull;
    fix3.confidence = 0.9f;
    before = log.estimates.load();
    CHECK(sc_submit_recognition_fix(s, &fix3) == SC_OK);
    for (int i = 0; i < 400 && log.estimates.load() == before; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.estimates.load() == before + 1);
    CHECK(log.policy_state_events.load() == log.estimates.load());
    CHECK(log.last_policy_settled.load() == 1);

    sc_destroy(s);
}

// tech-req §2.17 (CTL-06/W1): SC_EVT_FIX_DIAG must carry the CORE-06 guard's
// own arbitration inputs/outputs. Reuses test_self_hearing_guard's exact
// scenario and offsets (unmodified elsewhere in this file) so every expected
// diagnostic value below is checked against that already-verified test's own
// geometry (its inline comments/assertions), rather than an unverified one.
void test_fix_diag_accepted_and_self_hearing() {
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

    // Bootstrap fix: the session's very first, accepted unconditionally
    // (no anchor existed yet to arbitrate against).
    submit(16200, t0 + 5 * kSec);
    processed();
    CHECK(log.fix_diag_events.load() == 1);
    CHECK(log.last_fix_diag_verdict.load() == SC_FIX_DIAG_ACCEPTED);
    CHECK(log.last_fix_diag_offset_ms.load() == 16200);
    CHECK(log.last_fix_diag_tracks_room.load() == 0);
    CHECK(log.last_fix_diag_anchor_offset_ms.load() == -1);
    CHECK(log.last_fix_diag_anchor_age_ms.load() == -1);

    // Second room fix: corroborates and confirms the anchor — tracks_room
    // is now true (it tracks the just-seeded anchor), and the anchor
    // reported is the LIVE one as it stood BEFORE this fix's own promotion
    // (the bootstrap's 16 200 @ t0+5s, aged 5 s at this fix's capture time).
    submit(21200, t0 + 10 * kSec);
    processed();
    CHECK(log.fix_diag_events.load() == 2);
    CHECK(log.last_fix_diag_verdict.load() == SC_FIX_DIAG_ACCEPTED);
    CHECK(log.last_fix_diag_tracks_room.load() == 1);
    CHECK(log.last_fix_diag_anchor_offset_ms.load() == 16200);
    CHECK(log.last_fix_diag_anchor_age_ms.load() == 5000);

    // Self-match against the CONFIRMED reference (test_self_hearing_guard's
    // own scenario, verbatim): lands on our OWN audible position (25 000)
    // while the room prediction says 26 200.
    submit(25000, t0 + 15 * kSec);
    for (int i = 0; i < 400 && log.rejects.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.rejects.load() == 1);
    CHECK(log.fix_diag_events.load() == 3);
    CHECK(log.last_fix_diag_verdict.load() == SC_FIX_DIAG_SELF_HEARING);
    CHECK(log.last_fix_diag_offset_ms.load() == 25000);
    CHECK(log.last_fix_diag_tracks_room.load() == 0);
    CHECK(log.last_fix_diag_tracks_cand.load() == 0);
    // Live anchor as it stood at arbitration time: the confirmed 21 200 @
    // t0+10s, aged 5 s at this fix's capture time (t0+15s).
    CHECK(log.last_fix_diag_anchor_offset_ms.load() == 21200);
    CHECK(log.last_fix_diag_anchor_age_ms.load() == 5000);
    CHECK(std::abs(log.last_fix_diag_off.load() - 25000.0) < 0.5);
    CHECK(std::abs(log.last_fix_diag_predicted_room.load() - 26200.0) < 0.5);
    CHECK(std::abs(log.last_fix_diag_local_audible_ms.load() - 25000.0) < 0.5);

    sc_destroy(s);
}

// tech-req §2.17 (CTL-06/W1): SC_EVT_FIX_DIAG across the exact CTL-05
// post-seek corroboration sequence test_post_seek_two_agreeing_fixes_reanchor
// already established (same setup, same offsets, unmodified elsewhere in
// this file) — every expected diagnostic value below is checked against
// that already-verified test's own geometry.
void test_fix_diag_post_seek_corroboration() {
    constexpr uint64_t kSec = 1'000'000'000ull;
    sc_config_t cfg = valid_config();
    cfg.deadband_ms = 20000;
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

    submit(16200, t0 + 5 * kSec);
    processed();
    submit(21200, t0 + 10 * kSec);
    processed();
    CHECK(log.fix_diag_events.load() == 2);

    CHECK(sc_notify_seek_issued(s, 21300, t0 + 11 * kSec) == SC_OK);

    // P1: accepted, but only one post-seek fix so far — not yet promoted.
    submit(26550, t0 + 16 * kSec);
    processed();
    CHECK(log.fix_diag_events.load() == 3);
    CHECK(log.last_fix_diag_verdict.load() == SC_FIX_DIAG_ACCEPTED);
    CHECK(log.last_fix_diag_tracks_room.load() == 0);
    CHECK(log.last_fix_diag_anchor_offset_ms.load() == 21200);
    CHECK(log.last_fix_diag_anchor_age_ms.load() == 6000);

    // P2: agrees with P1, promoting the anchor — but the diagnostic reports
    // the anchor as it stood BEFORE this fix's own promotion (still
    // 21 200), the same pre-mutation snapshot rule self_hearing_candidate
    // itself already follows.
    submit(31550, t0 + 21 * kSec);
    processed();
    CHECK(log.fix_diag_events.load() == 4);
    CHECK(log.last_fix_diag_verdict.load() == SC_FIX_DIAG_ACCEPTED);
    CHECK(log.last_fix_diag_anchor_offset_ms.load() == 21200);
    CHECK(log.last_fix_diag_anchor_age_ms.load() == 11000);

    // P3: judged against the FRESH (just-promoted) anchor and tracks it
    // exactly.
    submit(36550, t0 + 26 * kSec);
    processed();
    CHECK(log.fix_diag_events.load() == 5);
    CHECK(log.last_fix_diag_verdict.load() == SC_FIX_DIAG_ACCEPTED);
    CHECK(log.last_fix_diag_tracks_room.load() == 1);
    CHECK(log.last_fix_diag_anchor_offset_ms.load() == 31550);
    CHECK(log.last_fix_diag_anchor_age_ms.load() == 5000);

    // P4: self-hearing against the anchor P3 just re-established.
    submit(41000, t0 + 31 * kSec);
    for (int i = 0; i < 400 && log.rejects.load() < 1; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    CHECK(log.rejects.load() == 1);
    CHECK(log.fix_diag_events.load() == 6);
    CHECK(log.last_fix_diag_verdict.load() == SC_FIX_DIAG_SELF_HEARING);
    CHECK(log.last_fix_diag_anchor_offset_ms.load() == 36550);
    CHECK(log.last_fix_diag_anchor_age_ms.load() == 5000);
    CHECK(std::abs(log.last_fix_diag_off.load() - 41000.0) < 0.5);
    CHECK(std::abs(log.last_fix_diag_predicted_room.load() - 41550.0) < 0.5);
    CHECK(std::abs(log.last_fix_diag_local_audible_ms.load() - 41000.0) < 0.5);

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
    test_post_seek_single_fix_cannot_reanchor();
    test_post_seek_two_agreeing_fixes_reanchor();
    test_ft10_cascade_repro_recovers_without_fourth_fix();
    test_correction_leads_by_recognition_age();
    test_copy_recent_capture();
    test_concurrent_capture_and_control();
    test_probe_executed_no_pending_is_safely_ignored();
    test_oss_ring_wiring_and_cadence();
    test_beat_comb_cross_check_wiring();
    test_track_lost_clears_beat_state();
    test_duck_executed_echo_contract();
    test_duck_deferred_detector_finds_dip();
    test_duck_deferred_detector_no_dip_reads_near_zero();
    test_policy_state_cadence_and_settled_transition();
    test_fix_diag_accepted_and_self_hearing();
    test_fix_diag_post_seek_corroboration();

    if (g_failures == 0) {
        std::printf("synccore_tests: all tests passed\n");
        return 0;
    }
    std::printf("synccore_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
