// test_policy.cpp — CORE-03 acceptance tests.
//
// Unit-level checks of the correction policy plus a closed-loop simulation
// (estimator + policy + simulated Spotify/world) proving the sawtooth stays
// inside the deadband under 0.05 % source skew.

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <initializer_list>

#include "estimator/estimator.h"
#include "policy/policy.h"

namespace {

int g_failures = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                               \
        }                                                               \
    } while (0)

constexpr uint64_t kSec = 1'000'000'000ull;

synccore::Estimate make_est(double error_ms, double drift_ppm = 0.0,
                            bool converged = false) {
    synccore::Estimate e;
    e.valid = true;
    e.error_ms = error_ms;
    e.drift_ppm = drift_ppm;
    e.confidence = 0.9f;
    e.converged = converged;
    return e;
}

void test_no_correction_inside_deadband() {
    synccore::CorrectionPolicy pol;
    for (double err : {0.0, 10.0, -20.0, 24.9}) {
        const auto a = pol.on_estimate(make_est(err), 100000.0, 10 * kSec);
        CHECK(a.kind == synccore::ActionKind::kNone);
    }
}

void test_correction_outside_deadband() {
    synccore::CorrectionPolicy pol;
    const auto a = pol.on_estimate(make_est(60.0), 100000.0, 10 * kSec);
    CHECK(a.kind == synccore::ActionKind::kSeek);
    // local + command latency (250) − error, no drift term.
    CHECK(a.seek_to_ms == 100190);
}

// Field Test 4: while the self-match guard starved the filter, its estimate
// coasted for ~25 s down to confidence 0.19 and the policy kept issuing
// −2.6 s seeks off it — audible as "3 beats behind" — even though every raw
// observation in that window read +177 ms. Unsupported estimates must not
// move audio.
void test_no_correction_on_stale_estimate() {
    synccore::CorrectionPolicy pol;
    auto stale = make_est(600.0);
    stale.confidence = 0.19f;  // the value observed in the field trace
    CHECK(pol.on_estimate(stale, 100000.0, 10 * kSec).kind ==
          synccore::ActionKind::kNone);

    // The same error, freshly measured, still corrects — the floor withholds
    // guesses, not genuine corrections.
    auto fresh = make_est(600.0);
    fresh.confidence = 0.8f;
    CHECK(pol.on_estimate(fresh, 100000.0, 20 * kSec).kind ==
          synccore::ActionKind::kSeek);

    // Track-lost is exempt: an error that large is worth acting on however
    // we came to believe it.
    synccore::CorrectionPolicy pol2;
    auto lost = make_est(5000.0);
    lost.confidence = 0.05f;
    CHECK(pol2.on_estimate(lost, 100000.0, 10 * kSec).kind ==
          synccore::ActionKind::kTrackLost);
}

void test_track_lost_threshold() {
    synccore::CorrectionPolicy pol;
    CHECK(pol.on_estimate(make_est(1999.0), 0.0, kSec).kind ==
          synccore::ActionKind::kSeek);
    CHECK(pol.on_estimate(make_est(2000.0), 0.0, 20 * kSec).kind ==
          synccore::ActionKind::kTrackLost);
    CHECK(pol.on_estimate(make_est(-2500.0), 0.0, 40 * kSec).kind ==
          synccore::ActionKind::kTrackLost);
}

void test_settle_suppression_and_ack() {
    synccore::CorrectionPolicy pol;
    CHECK(!pol.is_settling(10 * kSec));
    const auto a = pol.on_estimate(make_est(100.0), 100000.0, 10 * kSec);
    CHECK(a.kind == synccore::ActionKind::kSeek);
    // Awaiting the shell's ack: suppressed.
    CHECK(pol.is_settling(10 * kSec + kSec));
    // No further corrections while suppressed.
    CHECK(pol.on_estimate(make_est(100.0), 100000.0, 11 * kSec).kind ==
          synccore::ActionKind::kNone);
    // Ack starts the 3 s settle window.
    pol.on_seek_issued(12 * kSec);
    CHECK(pol.is_settling(12 * kSec + 2999 * (kSec / 1000)));
    CHECK(!pol.is_settling(15 * kSec + kSec / 100));
}

void test_ack_timeout_frees_policy() {
    synccore::CorrectionPolicy pol;
    pol.on_estimate(make_est(100.0), 100000.0, 10 * kSec);  // never acked
    CHECK(pol.is_settling(14 * kSec));
    CHECK(!pol.is_settling(15 * kSec + 1));  // 5 s ack timeout elapsed
}

void test_fix_cadence_adaptation() {
    synccore::CorrectionPolicy pol;

    // Not scheduled until a fix is accepted.
    CHECK(!pol.fix_request_due(100 * kSec));

    // Base cadence: 10 s.
    pol.on_estimate(make_est(5.0), 0.0, 100 * kSec);
    pol.on_fix_accepted(100 * kSec);
    CHECK(!pol.fix_request_due(109 * kSec));
    CHECK(pol.fix_request_due(110 * kSec));
    // Fires once, then re-arms the 5 s retry.
    CHECK(!pol.fix_request_due(110 * kSec + kSec));
    CHECK(pol.fix_request_due(115 * kSec + kSec));

    // Converged: stretched to 30 s.
    pol.on_estimate(make_est(5.0, 0.0, true), 0.0, 120 * kSec);
    CHECK(pol.current_fix_interval_ns() == 30 * kSec);

    // Out of deadband: tightened to 8 s.
    pol.on_seek_issued(120 * kSec);  // absorb the pending correction state
    pol.on_estimate(make_est(80.0), 0.0, 130 * kSec);
    CHECK(pol.current_fix_interval_ns() == 8 * kSec);
}

void test_preemptive_skew_correction() {
    // Inside the deadband now, but 500 ppm drift will cross it before the
    // next fix: correct pre-emptively, aiming past center (§6.3).
    synccore::CorrectionPolicy pol;
    const auto a = pol.on_estimate(make_est(20.0, 500.0), 100000.0, 10 * kSec);
    CHECK(a.kind == synccore::ActionKind::kSeek);
    // horizon = 10 s base interval;
    // target = local + 250 − 20 − 0.5·10/2 = local + 227.5.
    CHECK(a.seek_to_ms == 100000 + 227 || a.seek_to_ms == 100000 + 228);
}

void test_command_latency_adaptation() {
    // Post-seek estimates biased −50 ms (landing later than assumed) must
    // pull the learned command latency upward.
    synccore::CorrectionPolicy pol;
    CHECK(pol.command_latency_ms() == 250.0);
    auto a = pol.on_estimate(make_est(100.0), 100000.0, 10 * kSec);
    CHECK(a.kind == synccore::ActionKind::kSeek);
    pol.on_seek_issued(10 * kSec);
    // Verify estimate after settle reads −50: latency innovation = +50.
    pol.on_estimate(make_est(-50.0), 100000.0, 14 * kSec);
    CHECK(pol.command_latency_ms() > 250.0 + 30.0);
    CHECK(pol.command_latency_ms() < 250.0 + 50.0 + 1.0);
}

// ---- Closed-loop simulation --------------------------------------------
// World: external speaker with 0.05 % fast clock (error grows +0.5 ms/s),
// initial mis-sync of 350 ms, Spotify lands seeks 300 ms after the
// correction event while the policy's prior assumes 250 ms — the loop must
// learn the extra 50 ms online. Fixes carry ±3 ms alternating noise and the
// true skew.
void test_closed_loop_sawtooth_within_deadband() {
    synccore::SyncEstimator est;
    synccore::CorrectionPolicy pol;

    const double drift = 0.5;  // ms/s == 500 ppm == 0.05 %
    double true_error = 350.0;
    double local_pos = 60'000.0;  // reported-position timeline, ms
    bool seek_scheduled = false;
    double seek_target = 0.0;
    uint64_t seek_apply_at = 0;
    int seeks_applied = 0;
    int fixes = 0;
    double worst_after_convergence = 0.0;

    const uint64_t step = kSec / 10;  // 100 ms
    const uint64_t horizon = 600 * kSec;

    for (uint64_t t = step; t <= horizon; t += step) {
        const double dt_s = static_cast<double>(step) / kSec;
        true_error += drift * dt_s;
        local_pos += dt_s * 1000.0;

        // Pending seek lands after the command latency.
        if (seek_scheduled && t >= seek_apply_at) {
            seek_scheduled = false;
            ++seeks_applied;
            const double external = local_pos - true_error;
            local_pos = seek_target;
            true_error = local_pos - external;
            est.on_player_state(static_cast<int64_t>(local_pos), false, t);
        }

        // Player state once per second.
        if (t % kSec == 0)
            est.on_player_state(static_cast<int64_t>(local_pos), false, t);

        // Recognition fix when the policy asks (plus one bootstrap fix).
        const bool bootstrap = (fixes == 0 && t >= kSec);
        if (bootstrap || pol.fix_request_due(t)) {
            const double noise = (fixes % 2 == 0) ? 3.0 : -3.0;
            const double external = local_pos - true_error;
            if (est.on_fix(static_cast<int64_t>(std::llround(external + noise)),
                           t, -drift / 1000.0, 0.9f)) {
                ++fixes;
                pol.on_fix_accepted(t);
                const auto e = est.estimate_at(t);
                const auto a = pol.on_estimate(e, est.projected_local_ms(t), t);
#ifdef SIM_TRACE
                std::printf(
                    "t=%6.1f true=%8.2f est=%8.2f drift=%6.1f conv=%d "
                    "int=%llus act=%d\n",
                    t / 1e9, true_error, e.error_ms, e.drift_ppm, e.converged,
                    (unsigned long long)(pol.current_fix_interval_ns() / kSec),
                    (int)a.kind);
#endif
                if (a.kind == synccore::ActionKind::kSeek) {
                    // The shell issues the seek immediately and echoes
                    // sc_notify_seek_issued; Spotify lands it 300 ms later
                    // (50 ms worse than the policy's initial prior).
                    est.on_local_seek(a.seek_to_ms, t,
                                      pol.command_latency_ms());
                    pol.on_seek_issued(t);
                    seek_scheduled = true;
                    seek_target = static_cast<double>(a.seek_to_ms);
                    seek_apply_at = t + 3 * (kSec / 10);
                }
                CHECK(a.kind != synccore::ActionKind::kTrackLost);
            }
        }

        if (t > 60 * kSec)
            worst_after_convergence =
                std::max(worst_after_convergence, std::abs(true_error));
    }

    std::printf(
        "  sawtooth sim: %d fixes, %d seeks, learned latency %.0f ms, worst "
        "|error| after 60 s = %.1f ms\n",
        fixes, seeks_applied, pol.command_latency_ms(),
        worst_after_convergence);
    CHECK(fixes >= 15);
    CHECK(seeks_applied >= 2);  // drift forces recurring pre-emptive seeks
    CHECK(std::abs(pol.command_latency_ms() - 300.0) < 25.0);  // learned
    CHECK(worst_after_convergence <= 25.0);  // backlog CORE-03 AC
}

// ---- CTL-02a: persistence gate + residual ring -------------------------
// tech-req §2.7. A shell that widens deadband_ms past confirm_floor_ms (as
// Android does at 350) can lock onto a stable, corroborated residual that
// the instantaneous deadband will never touch (FT8: Vienna ~285 ms, Dreams
// ~285 ms, held for the rest of the cycle). These tests drive the ring
// directly through on_estimate with make_est's converged flag, exactly as
// field-test-8-results.md's traces read.

void test_persistence_gate_vienna_class() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(cfg);

    // 3 converged fixes, 10 s apart, constant ~285 ms error: nothing fires
    // until both confirm_min_fixes (3) and confirm_window_ns (20 s) are met.
    CHECK(pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 10 * kSec)
              .kind == synccore::ActionKind::kNone);
    CHECK(pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 20 * kSec)
              .kind == synccore::ActionKind::kNone);
    const auto a =
        pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 30 * kSec);
    CHECK(a.kind == synccore::ActionKind::kSeek);
    // local + command latency (250) − cluster mean (285), no drift term.
    CHECK(a.seek_to_ms == 99965);

    // Ring cleared: the very next converged estimate (past settle) doesn't
    // immediately re-fire — it's a fresh cluster of one sample.
    pol.on_seek_issued(30 * kSec);
    CHECK(!pol.is_settling(34 * kSec));
    CHECK(pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 34 * kSec)
              .kind == synccore::ActionKind::kNone);
}

// The window-span condition must matter independently of the fix count: a
// burst of agreeing fixes that hasn't yet PERSISTED for confirm_window_ns
// is not persistence (RFC 5905's WATCH discipline, tech-req §2.7). Without
// this pin, dropping the span term from the trigger would pass every other
// test in this file.
void test_persistence_gate_window_span_required() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(cfg);

    // Three agreeing, above-floor converged fixes only 4 s apart: count and
    // agreement are satisfied, the 20 s span is not — must not fire.
    CHECK(pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 10 * kSec)
              .kind == synccore::ActionKind::kNone);
    CHECK(pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 14 * kSec)
              .kind == synccore::ActionKind::kNone);
    CHECK(pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 18 * kSec)
              .kind == synccore::ActionKind::kNone);

    // A fourth agreeing fix stretches the cluster past the window: fires.
    const auto a =
        pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 31 * kSec);
    CHECK(a.kind == synccore::ActionKind::kSeek);
    CHECK(a.seek_to_ms == 99965);  // local + 250 − cluster mean (285)
}

// Field-test-8 addendum's deadband-150 lesson: a fixed threshold must not
// mistake the song's own beat-comb scatter for a stable cluster. Alternating
// residuals well inside the 350 ms deadband must never persistence-fire.
void test_persistence_gate_churn_class_never_fires() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(cfg);

    const double errs[] = {280.0, -250.0, 300.0, -260.0, 290.0,
                            -240.0, 310.0, -255.0, 295.0, -245.0};
    uint64_t t = 10 * kSec;
    for (double e : errs) {
        CHECK(pol.on_estimate(make_est(e, 0.0, true), 100000.0, t).kind ==
              synccore::ActionKind::kNone);
        t += 10 * kSec;
    }
}

// A cluster at or below confirm_floor_ms is healthy sync, never corrected.
void test_persistence_gate_floor_test() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(cfg);

    uint64_t t = 10 * kSec;
    for (int i = 0; i < 5; ++i) {
        CHECK(pol.on_estimate(make_est(60.0, 0.0, true), 100000.0, t).kind ==
              synccore::ActionKind::kNone);
        t += 10 * kSec;
    }
}

// Field Test 4's confidence floor guards the persistence path exactly as it
// guards the instantaneous one.
void test_persistence_gate_confidence_gate() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(cfg);

    uint64_t t = 10 * kSec;
    for (int i = 0; i < 5; ++i) {
        auto est = make_est(285.0, 0.0, true);
        est.confidence = 0.19f;
        CHECK(pol.on_estimate(est, 100000.0, t).kind ==
              synccore::ActionKind::kNone);
        t += 10 * kSec;
    }
}

// Loss of convergence invalidates the cluster's premise outright.
void test_persistence_ring_clears_on_non_converged() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(cfg);

    // One converged, above-floor sample: cadence tightens to base while the
    // cluster is open.
    pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 10 * kSec);
    CHECK(pol.current_fix_interval_ns() == cfg.fix_interval_base_ns);

    // A non-converged estimate clears the ring outright.
    pol.on_estimate(make_est(5.0, 0.0, false), 100000.0, 20 * kSec);

    // The next converged sample starts a fresh cluster of one: if the ring
    // had not been cleared, this low-error sample would join the earlier
    // 285 ms entry and the mean would still read above the floor.
    pol.on_estimate(make_est(5.0, 0.0, true), 100000.0, 30 * kSec);
    CHECK(pol.current_fix_interval_ns() == cfg.fix_interval_max_ns);
}

// A correction — instantaneous or persistence — changes the operating
// point: post-seek residuals are a new cluster, not a continuation.
void test_persistence_ring_clears_on_seek() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(cfg);

    // One converged, above-floor sample opens a cluster.
    pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 10 * kSec);
    CHECK(pol.current_fix_interval_ns() == cfg.fix_interval_base_ns);

    // A converged estimate that itself crosses the (widened) instantaneous
    // deadband fires the ordinary micro-seek path, which also clears the
    // ring.
    const auto seek =
        pol.on_estimate(make_est(400.0, 0.0, true), 100000.0, 20 * kSec);
    CHECK(seek.kind == synccore::ActionKind::kSeek);
    pol.on_seek_issued(20 * kSec);

    // Past the settle window: a fresh, low-error converged sample should
    // read as a cluster of one (mean below the floor), not join the stale
    // 285/400 ms entries.
    CHECK(!pol.is_settling(24 * kSec));
    pol.on_estimate(make_est(5.0, 0.0, true), 100000.0, 24 * kSec);
    CHECK(pol.current_fix_interval_ns() == cfg.fix_interval_max_ns);
}

// Corroboration-hungry cadence: a live above-floor cluster tightens
// cadence to the base interval; once the cluster clears (fires + resets),
// cadence reverts to the normal converged (max) interval.
void test_persistence_gate_cadence_adaptation() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(cfg);

    pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 10 * kSec);
    CHECK(pol.current_fix_interval_ns() == cfg.fix_interval_base_ns);
    pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 20 * kSec);
    CHECK(pol.current_fix_interval_ns() == cfg.fix_interval_base_ns);

    // 3rd sample both fires the persistence trigger and clears the ring.
    const auto a =
        pol.on_estimate(make_est(285.0, 0.0, true), 100000.0, 30 * kSec);
    CHECK(a.kind == synccore::ActionKind::kSeek);
    pol.on_seek_issued(30 * kSec);

    // Past settle, with the cluster cleared, cadence reverts to max.
    CHECK(!pol.is_settling(34 * kSec));
    pol.on_estimate(make_est(5.0, 0.0, true), 100000.0, 34 * kSec);
    CHECK(pol.current_fix_interval_ns() == cfg.fix_interval_max_ns);
}

// ---- CTL-02b: closed-loop proof -----------------------------------------
// Estimator + policy + simulated world, in the sawtooth sim's style. Proves
// the persistence gate against real (not synthetic make_est) converged
// estimates, and proves the deadband-150 lesson holds at 350 under scatter.

// FT8's Vienna: world locks at ~285 ms and holds it constant (no drift).
// The instantaneous 350 ms deadband never touches this; the persistence
// gate must, within ~90 s of convergence, and land the true error under
// confirm_floor_ms.
void test_closed_loop_vienna_persistence() {
    synccore::EstimatorConfig ecfg;
    ecfg.deadband_ms = 350.0;
    synccore::SyncEstimator est(ecfg);

    synccore::PolicyConfig pcfg;
    pcfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(pcfg);

    double true_error = 285.0;
    double local_pos = 60'000.0;
    bool seek_scheduled = false;
    double seek_target = 0.0;
    uint64_t seek_apply_at = 0;
    int seeks_applied = 0;
    int fixes = 0;
    uint64_t converged_at = 0;
    uint64_t persistence_fire_at = 0;

    const uint64_t step = kSec / 10;
    const uint64_t horizon = 300 * kSec;
    const uint64_t apply_delay =
        static_cast<uint64_t>(pol.command_latency_ms() / 1000.0 * kSec);

    for (uint64_t t = step; t <= horizon; t += step) {
        local_pos += static_cast<double>(step) / kSec * 1000.0;

        if (seek_scheduled && t >= seek_apply_at) {
            seek_scheduled = false;
            ++seeks_applied;
            const double external = local_pos - true_error;
            local_pos = seek_target;
            true_error = local_pos - external;
            est.on_player_state(static_cast<int64_t>(local_pos), false, t);
        }

        if (t % kSec == 0)
            est.on_player_state(static_cast<int64_t>(local_pos), false, t);

        const bool bootstrap = (fixes == 0 && t >= kSec);
        if (bootstrap || pol.fix_request_due(t)) {
            const double noise = (fixes % 2 == 0) ? 3.0 : -3.0;
            const double external = local_pos - true_error;
            if (est.on_fix(
                    static_cast<int64_t>(std::llround(external + noise)), t,
                    0.0, 0.9f)) {
                ++fixes;
                pol.on_fix_accepted(t);
                const auto e = est.estimate_at(t);
                if (e.converged && converged_at == 0) converged_at = t;
                const auto a = pol.on_estimate(e, est.projected_local_ms(t), t);
                if (a.kind == synccore::ActionKind::kSeek) {
                    est.on_local_seek(a.seek_to_ms, t,
                                      pol.command_latency_ms());
                    pol.on_seek_issued(t);
                    seek_scheduled = true;
                    seek_target = static_cast<double>(a.seek_to_ms);
                    seek_apply_at = t + apply_delay;
                    if (converged_at != 0 && persistence_fire_at == 0)
                        persistence_fire_at = t;
                }
                CHECK(a.kind != synccore::ActionKind::kTrackLost);
            }
        }
    }

    std::printf(
        "  vienna persistence sim: %d fixes, %d seeks, converged at %.1fs, "
        "fired at %.1fs, final true error = %.1f ms\n",
        fixes, seeks_applied, converged_at / 1e9, persistence_fire_at / 1e9,
        true_error);

    CHECK(converged_at != 0);
    CHECK(persistence_fire_at != 0);
    CHECK(persistence_fire_at - converged_at <= 90 * kSec);
    CHECK(std::abs(true_error) < pcfg.confirm_floor_ms);
    CHECK(seeks_applied <= 2);
}

// The deadband-150 lesson must hold at 350: fixes scattered ±300 ms
// alternating around a true error of zero must never persistence-fire,
// over minutes of simulated time.
void test_closed_loop_stability_no_churn_at_350() {
    synccore::EstimatorConfig ecfg;
    ecfg.deadband_ms = 350.0;
    synccore::SyncEstimator est(ecfg);

    synccore::PolicyConfig pcfg;
    pcfg.deadband_ms = 350.0;
    synccore::CorrectionPolicy pol(pcfg);

    const double true_error = 0.0;
    double local_pos = 60'000.0;
    int seeks_applied = 0;
    int fixes = 0;

    const uint64_t step = kSec / 10;
    const uint64_t horizon = 330 * kSec;  // > 5 min simulated

    for (uint64_t t = step; t <= horizon; t += step) {
        local_pos += static_cast<double>(step) / kSec * 1000.0;

        if (t % kSec == 0)
            est.on_player_state(static_cast<int64_t>(local_pos), false, t);

        const bool bootstrap = (fixes == 0 && t >= kSec);
        if (bootstrap || pol.fix_request_due(t)) {
            const double noise = (fixes % 2 == 0) ? 300.0 : -300.0;
            const double external = local_pos - true_error;
            if (est.on_fix(
                    static_cast<int64_t>(std::llround(external + noise)), t,
                    0.0, 0.9f)) {
                ++fixes;
                pol.on_fix_accepted(t);
                const auto e = est.estimate_at(t);
                const auto a = pol.on_estimate(e, est.projected_local_ms(t), t);
                if (a.kind == synccore::ActionKind::kSeek) {
                    ++seeks_applied;
                    pol.on_seek_issued(t);
                }
                CHECK(a.kind != synccore::ActionKind::kTrackLost);
            }
        }
    }

    std::printf("  stability sim: %d fixes, %d seeks (expect 0)\n", fixes,
                seeks_applied);
    CHECK(fixes >= 10);
    CHECK(seeks_applied == 0);
}

}  // namespace

int main() {
    test_no_correction_inside_deadband();
    test_correction_outside_deadband();
    test_track_lost_threshold();
    test_no_correction_on_stale_estimate();
    test_settle_suppression_and_ack();
    test_ack_timeout_frees_policy();
    test_fix_cadence_adaptation();
    test_preemptive_skew_correction();
    test_command_latency_adaptation();
    test_closed_loop_sawtooth_within_deadband();
    test_persistence_gate_vienna_class();
    test_persistence_gate_window_span_required();
    test_persistence_gate_churn_class_never_fires();
    test_persistence_gate_floor_test();
    test_persistence_gate_confidence_gate();
    test_persistence_ring_clears_on_non_converged();
    test_persistence_ring_clears_on_seek();
    test_persistence_gate_cadence_adaptation();
    test_closed_loop_vienna_persistence();
    test_closed_loop_stability_no_churn_at_350();

    if (g_failures == 0) {
        std::printf("policy_tests: all tests passed\n");
        return 0;
    }
    std::printf("policy_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
