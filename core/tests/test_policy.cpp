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
    // tech-req §2.8's "Deliberate test change" (CTL-03b): a 1999 ms error is
    // now below lost_threshold_ms but at/above large_correction_threshold_ms
    // (1000), so it is held pending corroboration rather than fired off one
    // estimate — this replaces the pre-CTL-03 immediate-kSeek expectation.
    CHECK(pol.on_estimate(make_est(1999.0), 0.0, kSec).kind ==
          synccore::ActionKind::kNone);
    // A second, agreeing estimate fires it.
    CHECK(pol.on_estimate(make_est(1999.0), 0.0, 2 * kSec).kind ==
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

// ---- CTL-03b: large-correction corroboration hold ----------------------
// tech-req §2.8 Part B. Field test 8's song 2 took a single conf-0.74 fix
// 1259 ms off and it stood uncorrected. These tests drive the pending-
// record hold directly through on_estimate with make_est, in the same
// style as the CTL-02a ring tests above.

void test_large_correction_single_hold() {
    synccore::CorrectionPolicy pol;
    const auto a = pol.on_estimate(make_est(1200.0), 100000.0, 10 * kSec);
    CHECK(a.kind == synccore::ActionKind::kNone);
    CHECK(pol.current_fix_interval_ns() == 8 * kSec);  // fix_interval_min_ns
}

void test_large_correction_corroborated_pair() {
    synccore::CorrectionPolicy pol;
    CHECK(pol.on_estimate(make_est(1200.0), 100000.0, 10 * kSec).kind ==
          synccore::ActionKind::kNone);
    const auto a =
        pol.on_estimate(make_est(1210.0), 100100.0, 15 * kSec);
    CHECK(a.kind == synccore::ActionKind::kSeek);
    // local + command latency (250) − the FRESH 1210 ms error, no drift.
    CHECK(a.seek_to_ms == 99140);
}

void test_large_correction_disagreeing_pair() {
    synccore::CorrectionPolicy pol;
    // First large error opens a pending record.
    CHECK(pol.on_estimate(make_est(1200.0), 100000.0, 10 * kSec).kind ==
          synccore::ActionKind::kNone);
    // A disagreeing large error replaces the record, not accumulates it.
    CHECK(pol.on_estimate(make_est(1500.0), 100000.0, 15 * kSec).kind ==
          synccore::ActionKind::kNone);
    // Agrees with the replaced (1500) record: fires.
    CHECK(pol.on_estimate(make_est(1520.0), 100000.0, 20 * kSec).kind ==
          synccore::ActionKind::kSeek);
}

void test_large_correction_sub_threshold_clears() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;  // so a 300 ms error stays sub-deadband
    synccore::CorrectionPolicy pol(cfg);

    CHECK(pol.on_estimate(make_est(1200.0), 100000.0, 10 * kSec).kind ==
          synccore::ActionKind::kNone);
    // A sub-large-threshold (and here sub-deadband) estimate clears the
    // stale 1200 ms record outright.
    CHECK(pol.on_estimate(make_est(300.0), 100000.0, 15 * kSec).kind ==
          synccore::ActionKind::kNone);
    // The long-gone 1200 ms record must not corroborate this fresh 1210:
    // it opens a brand-new hold instead of firing.
    CHECK(pol.on_estimate(make_est(1210.0), 100000.0, 20 * kSec).kind ==
          synccore::ActionKind::kNone);
}

void test_large_correction_expiry() {
    synccore::CorrectionPolicy pol;
    CHECK(pol.on_estimate(make_est(1200.0), 100000.0, 10 * kSec).kind ==
          synccore::ActionKind::kNone);
    // An otherwise-corroborating 1210 ms estimate arrives after
    // large_pending_max_age_ns (30 s) has elapsed: the stale record has
    // expired, so this becomes a fresh pending record rather than firing.
    CHECK(pol.on_estimate(make_est(1210.0), 100000.0, 10 * kSec + 31 * kSec)
              .kind == synccore::ActionKind::kNone);
}

void test_large_correction_track_lost_precedence() {
    // lost_threshold_ms keeps absolute precedence, checked first exactly as
    // today: an error this large is a re-listen, not a seek to hold.
    synccore::CorrectionPolicy pol;
    CHECK(pol.on_estimate(make_est(2500.0), 100000.0, 10 * kSec).kind ==
          synccore::ActionKind::kTrackLost);
}

// ---- CTL-03b: closed-loop proof ------------------------------------------
// Estimator + policy + simulated world, in the closed-loop sims' style
// above. Proves the hold against real (not synthetic make_est) estimates,
// so the estimator's own outlier gate — inactive at mid-uncertainty,
// structurally why FT8's overshoot got through in the first place — is
// genuinely exercised rather than assumed.

// FT8's headline defect: a single conf-0.74 fix landed 1259 ms off mid-
// stream and stood uncorrected. Reproduce it with one clean bootstrap fix,
// then a long gap (letting the filter's posterior variance regrow past the
// estimator's outlier_gate_max_p00 — "mid-uncertainty," matching FT8's own
// gate-inactive acceptance) before the corrupted fix lands at fix #2. The
// policy's hold must never let it fire as a seek, and clean fixes
// afterward must pull the true error back under 25 ms.
void test_closed_loop_phantom_large_fix_held() {
    synccore::SyncEstimator est;
    synccore::CorrectionPolicy pol;

    const double true_error = 0.0;
    double local_pos = 60'000.0;
    int fixes = 0;
    int large_seeks = 0;
    int seeks_applied = 0;

    const uint64_t step = kSec / 10;
    const uint64_t horizon = 1400 * kSec;
    // A single long gap between fix #1 and fix #2 lets the estimator's
    // posterior variance regrow past outlier_gate_max_p00 before the
    // phantom lands — the mid-uncertainty window FT8's own trace caught.
    const uint64_t phantom_at = 1000 * kSec;
    bool phantom_injected = false;

    for (uint64_t t = step; t <= horizon; t += step) {
        local_pos += static_cast<double>(step) / kSec * 1000.0;
        if (t % kSec == 0)
            est.on_player_state(static_cast<int64_t>(local_pos), false, t);

        bool want_fix = (fixes == 0 && t >= kSec) ||
                         (fixes == 1 && t >= phantom_at) ||
                         (fixes >= 2 && pol.fix_request_due(t));
        if (!want_fix) continue;

        const bool is_phantom = (fixes == 1) && !phantom_injected;
        const double external = local_pos - true_error;
        double reported = external;
        float conf = 0.9f;
        if (is_phantom) {
            reported = external + 1259.0;  // FT8's own overshoot magnitude
            conf = 0.74f;                  // FT8's own accepted confidence
        }
        if (est.on_fix(static_cast<int64_t>(std::llround(reported)), t, 0.0,
                       conf)) {
            ++fixes;
            if (is_phantom) phantom_injected = true;
            pol.on_fix_accepted(t);
            const auto e = est.estimate_at(t);
            const auto a = pol.on_estimate(e, est.projected_local_ms(t), t);
#ifdef SIM_TRACE
            std::printf(
                "t=%6.1f true=%8.2f est=%8.2f conf=%.2f act=%d "
                "int=%llus\n",
                t / 1e9, true_error, e.error_ms, e.confidence, (int)a.kind,
                (unsigned long long)(pol.current_fix_interval_ns() / kSec));
#endif
            if (a.kind == synccore::ActionKind::kSeek) {
                ++seeks_applied;
                if (std::llround(std::abs(e.error_ms)) >= 1000) ++large_seeks;
                pol.on_seek_issued(t);
            }
        }
    }

    const auto final_est = est.estimate_at(horizon);
    std::printf(
        "  phantom-fix sim: %d fixes, %d seeks (%d of magnitude >=1000ms), "
        "final true |error| = %.1f ms\n",
        fixes, seeks_applied, large_seeks, std::abs(final_est.error_ms));
    CHECK(fixes >= 5);
    CHECK(large_seeks == 0);
    CHECK(std::abs(final_est.error_ms) <= 25.0);
}

// The companion case: the true error genuinely does step to ~1200 ms and
// stays there (the room seeks). The hold must still fire — once agreeing
// fixes corroborate it — and the loop must re-converge; exactly one large
// seek over the whole run.
//
// For a single fix to read close enough to the true ~1200 ms step to cross
// large_correction_threshold_ms at all, the filter must still be at (or
// have regrown into) mid-uncertainty when that fix lands — a tightly
// converged filter only partially believes any one observation (small
// Kalman gain), which is exactly why FT8's own follow-up errors read small
// even with a large true residual underneath. So: one clean bootstrap fix,
// then the room seeks during a long gap that regrows the posterior
// variance past the estimator's own outlier-gate threshold (the same
// mid-uncertainty window the phantom sim exploits) before the next fix
// lands — except here the jump is genuine and repeats, so the hold's
// second-fix corroboration should fire, not hold forever.
void test_closed_loop_genuine_large_jump_corrects() {
    synccore::SyncEstimator est;
    synccore::CorrectionPolicy pol;

    double true_error = 0.0;
    double local_pos = 60'000.0;
    bool seek_scheduled = false;
    double seek_target = 0.0;
    uint64_t seek_apply_at = 0;
    int fixes = 0;
    int seeks_applied = 0;
    int large_seeks = 0;

    const uint64_t step = kSec / 10;
    const uint64_t horizon = 1400 * kSec;
    const uint64_t jump_at = 500 * kSec;         // the room seeks here
    const uint64_t second_fix_at = 1000 * kSec;  // long gap after fix #1
    const uint64_t apply_delay =
        static_cast<uint64_t>(pol.command_latency_ms() / 1000.0 * kSec);

    for (uint64_t t = step; t <= horizon; t += step) {
        local_pos += static_cast<double>(step) / kSec * 1000.0;
        if (t == jump_at) true_error = 1200.0;

        if (seek_scheduled && t >= seek_apply_at) {
            seek_scheduled = false;
            const double external = local_pos - true_error;
            local_pos = seek_target;
            true_error = local_pos - external;
            est.on_player_state(static_cast<int64_t>(local_pos), false, t);
        }

        if (t % kSec == 0)
            est.on_player_state(static_cast<int64_t>(local_pos), false, t);

        const bool bootstrap = (fixes == 0 && t >= kSec);
        const bool second = (fixes == 1 && t >= second_fix_at);
        const bool due = (fixes >= 2 && pol.fix_request_due(t));
        if (bootstrap || second || due) {
            const double noise = (fixes % 2 == 0) ? 3.0 : -3.0;
            const double external = local_pos - true_error;
            if (est.on_fix(static_cast<int64_t>(std::llround(external + noise)),
                           t, 0.0, 0.9f)) {
                ++fixes;
                pol.on_fix_accepted(t);
                const auto e = est.estimate_at(t);
                const auto a = pol.on_estimate(e, est.projected_local_ms(t), t);
#ifdef SIM_TRACE
                std::printf(
                    "t=%6.1f true=%8.2f est=%8.2f conf=%.2f act=%d "
                    "int=%llus\n",
                    t / 1e9, true_error, e.error_ms, e.confidence, (int)a.kind,
                    (unsigned long long)(pol.current_fix_interval_ns() / kSec));
#endif
                if (a.kind == synccore::ActionKind::kSeek) {
                    ++seeks_applied;
                    if (std::llround(std::abs(e.error_ms)) >= 1000)
                        ++large_seeks;
                    est.on_local_seek(a.seek_to_ms, t, pol.command_latency_ms());
                    pol.on_seek_issued(t);
                    seek_scheduled = true;
                    seek_target = static_cast<double>(a.seek_to_ms);
                    seek_apply_at = t + apply_delay;
                }
                CHECK(a.kind != synccore::ActionKind::kTrackLost);
            }
        }
    }

    std::printf(
        "  genuine-jump sim: %d fixes, %d seeks (%d of magnitude >=1000ms), "
        "final true error = %.1f ms\n",
        fixes, seeks_applied, large_seeks, true_error);
    CHECK(fixes >= 5);
    CHECK(large_seeks == 1);
    CHECK(std::abs(true_error) <= 25.0);
}

// ---- CTL-01a: referee sentinel, probe scheduling & verdict ---------------
// tech-req §2.9. After any room discontinuity our own audio can become a
// perfectly continuous timeline the FT4 self-match guard cannot see. Two
// independent triggers request a deliberate pause/resume probe; the
// post-echo verdict either clears the suspicion or declares the track lost.

// Mutually-disagreeing referee lags (spread > 50 ms, the ring's agreement
// tolerance) spanning >= referee_starve_min_windows (4) windows and
// >= referee_starve_ns (45 s) while converged must request exactly one
// probe (the cooldown blocks a second even if starvation continues).
void test_referee_sentinel_starvation_requests_probe() {
    synccore::CorrectionPolicy pol;
    uint64_t t = 1 * kSec;
    // Converge the policy's cached convergence flag.
    pol.on_estimate(make_est(5.0, 0.0, true), 100000.0, t);

    // 5 windows, 12 s apart (60 s span >= 45 s), lags spread wide apart so
    // no 3 of them ever land within 50 ms of each other.
    const double lags[] = {100.0, 400.0, 900.0, 1500.0, 2200.0};
    bool probe_seen = false;
    for (double lag : lags) {
        t += 12 * kSec;
        pol.on_referee_window(lag, true, t);
        if (pol.probe_request_due(t)) probe_seen = true;
    }
    CHECK(probe_seen);

    // Resolve the probe (echo + genuine verdict) so probe_outstanding_
    // clears — isolating the cooldown gate specifically, rather than
    // proving only "never requested again while outstanding." Values kept
    // well clear of lost_threshold_ms (2000) so this resolution can't
    // itself trigger a track-lost reset.
    t += kSec;
    pol.on_probe_executed(550.0, t);
    t += kSec;
    pol.on_estimate(make_est(350.0, 0.0, true), 100000.0, t);
    t += kSec;
    pol.on_estimate(make_est(350.0, 0.0, true), 100000.0, t);  // genuine:
                                                                // shift -200


    // Cooldown (120 s) still blocks a second request even though
    // starvation continues and no probe is outstanding anymore.
    bool second_probe = false;
    for (int i = 0; i < 3; ++i) {
        t += 12 * kSec;
        pol.on_referee_window(100.0 + i * 700.0, true, t);
        if (pol.probe_request_due(t)) second_probe = true;
    }
    CHECK(!second_probe);
}

// Any 3 ringed windows agreeing within referee_agree_ms keep
// last_referee_agree_ns fresh, so starvation never accumulates and no
// probe is ever requested.
void test_referee_sentinel_agreement_never_requests_probe() {
    synccore::CorrectionPolicy pol;
    pol.on_estimate(make_est(5.0, 0.0, true), 100000.0, 1 * kSec);

    uint64_t t = 1 * kSec;
    bool probe_seen = false;
    for (int i = 0; i < 10; ++i) {
        t += 12 * kSec;
        // All windows agree at ~200 ms (well within the 50 ms tolerance).
        pol.on_referee_window(200.0 + (i % 2 == 0 ? 5.0 : -5.0), true, t);
        if (pol.probe_request_due(t)) probe_seen = true;
    }
    CHECK(!probe_seen);
}

// The sentinel requires convergence: identical starving windows while the
// policy has never seen a converged estimate must never request a probe.
void test_referee_sentinel_requires_convergence() {
    synccore::CorrectionPolicy pol;
    // No on_estimate/on_tick call with converged=true — converged_seen_
    // stays false from construction.
    uint64_t t = 1 * kSec;
    bool probe_seen = false;
    const double lags[] = {100.0, 400.0, 900.0, 1500.0, 2200.0};
    for (double lag : lags) {
        t += 12 * kSec;
        pol.on_referee_window(lag, true, t);
        if (pol.probe_request_due(t)) probe_seen = true;
    }
    CHECK(!probe_seen);
}

// Wittenmark turn-off trigger: valid low-confidence estimates sustained
// past probe_turnoff_dwell_ns (20 s) with no accepted fix in the span must
// request a probe; an accepted fix mid-dwell resets it.
void test_turnoff_trigger_requests_probe() {
    synccore::CorrectionPolicy pol;
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = false;

    uint64_t t = 0;
    bool probe_seen = false;
    for (int i = 0; i <= 21; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, /*playback_live=*/true, t);
        if (pol.probe_request_due(t)) probe_seen = true;
    }
    CHECK(probe_seen);
}

void test_turnoff_trigger_resets_on_accepted_fix() {
    synccore::CorrectionPolicy pol;
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = false;

    uint64_t t = 0;
    // Dwell for 15 s (short of the 20 s threshold), then a fix is accepted.
    for (int i = 0; i <= 15; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, true, t);
    }
    pol.on_fix_accepted(t);  // resets the dwell (t == 15 s here)

    // The NEXT on_tick call restarts the dwell clock at its own timestamp
    // (16 s) — not at the reset call's own 15 s. Confirm no probe through
    // 19 s of the fresh dwell (i.e. up to 35 s absolute), then the 20th
    // second (36 s absolute) completes it.
    bool probe_seen = false;
    for (int i = 1; i <= 20; ++i) {
        t = 15 * kSec + static_cast<uint64_t>(i) * kSec;  // 16 s..35 s
        pol.on_tick(low, true, t);
        if (pol.probe_request_due(t)) probe_seen = true;
    }
    CHECK(!probe_seen);

    t = 15 * kSec + 21 * kSec;  // 36 s: fresh dwell = 36 - 16 = 20 s
    pol.on_tick(low, true, t);
    CHECK(pol.probe_request_due(t));
}

// Rate-limit test: no probe while settling, even once the turn-off dwell
// has technically elapsed — and the same dwell DOES fire once settling
// ends, proving the settling check (not something else) was the blocker.
void test_no_probe_while_settling() {
    synccore::PolicyConfig cfg;
    cfg.settle_ns = 30 * kSec;  // wide enough to outlast the 20 s dwell
    synccore::CorrectionPolicy pol(cfg);

    // Fire an ordinary seek at t=0, then ack it so the long settle_ns
    // window (not the 5 s ack timeout) is what's under test.
    const auto seek = pol.on_estimate(make_est(100.0), 100000.0, 0);
    CHECK(seek.kind == synccore::ActionKind::kSeek);
    pol.on_seek_issued(0);
    CHECK(pol.is_settling(1));

    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = false;

    uint64_t t = 0;
    bool probe_seen = false;
    for (uint64_t sec = 1; sec <= 25; ++sec) {  // 25 s < 30 s settle window
        t = sec * kSec;
        pol.on_tick(low, /*playback_live=*/true, t);
        if (pol.probe_request_due(t)) probe_seen = true;
    }
    CHECK(!probe_seen);  // dwell (20 s) elapsed, but settling blocked it

    // Past the settle window: the same still-active dwell now fires.
    t = 31 * kSec;
    pol.on_tick(low, true, t);
    CHECK(pol.probe_request_due(t));
}

void test_no_probe_while_paused() {
    synccore::CorrectionPolicy pol;
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = false;

    uint64_t t = 0;
    bool probe_seen = false;
    for (int i = 0; i <= 21; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, /*playback_live=*/false, t);
        if (pol.probe_request_due(t)) probe_seen = true;
    }
    CHECK(!probe_seen);
}

// Verdict, genuine: two post-echo estimates shifted by ~-pause_ms clear the
// probe without a track-lost, and the shifted residual subsequently reaches
// §2.7's persistence gate (composition pinned in the same test).
void test_probe_verdict_genuine_composes_with_persistence_gate() {
    synccore::PolicyConfig cfg;
    cfg.deadband_ms = 350.0;  // so the post-probe residual doesn't
                              // instantaneously correct
    synccore::CorrectionPolicy pol(cfg);

    // Converge the sentinel's cached flag and arm a probe via the turn-off
    // trigger (simplest deterministic path to an outstanding request).
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = true;
    uint64_t t = 0;
    for (int i = 0; i <= 20; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, true, t);
    }
    CHECK(pol.probe_request_due(t));

    // Shell echoes: pre-probe error snapshot is whatever estimate_at reads
    // at the echo (the worker wiring passes this in; here we drive
    // on_probe_executed directly per the policy-level test convention).
    // 350 ms, Vienna-class, so the post-shift residual (150) clears
    // confirm_floor_ms (125) and can go on to reach the persistence gate.
    t += kSec;
    pol.on_probe_executed(/*current_error_ms=*/350.0, t);

    // Two post-echo estimates shifted by -200 (== -pause_ms) qualify as
    // genuine (mean_shift <= -pause_ms/2 == -100): 150 - 350 = -200.
    t += kSec;
    const auto a1 = pol.on_estimate(make_est(150.0, 0.0, true), 100000.0, t);
    CHECK(a1.kind != synccore::ActionKind::kTrackLost);
    t += 10 * kSec;
    const auto a2 = pol.on_estimate(make_est(150.0, 0.0, true), 100000.0, t);
    CHECK(a2.kind != synccore::ActionKind::kTrackLost);

    // Composition: the (now-unsuppressed) ring accumulates these two
    // above-floor converged samples; a third, spanning >= confirm_window_ns
    // and agreeing, fires the persistence gate.
    t += 10 * kSec;
    const auto a3 = pol.on_estimate(make_est(150.0, 0.0, true), 100000.0, t);
    CHECK(a3.kind == synccore::ActionKind::kSeek);
}

// Verdict, self-match: two post-echo estimates essentially unshifted (mean
// shift near zero, nowhere close to -pause_ms/2) must return kTrackLost.
void test_probe_verdict_self_match_returns_track_lost() {
    synccore::CorrectionPolicy pol;
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = true;
    uint64_t t = 0;
    for (int i = 0; i <= 20; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, true, t);
    }
    CHECK(pol.probe_request_due(t));

    t += kSec;
    pol.on_probe_executed(50.0, t);

    t += kSec;
    pol.on_estimate(make_est(52.0), 100000.0, t);  // ~unshifted
    t += kSec;
    const auto a = pol.on_estimate(make_est(48.0), 100000.0, t);  // ~unshifted
    CHECK(a.kind == synccore::ActionKind::kTrackLost);
}

// Verdict, inconclusive via a never-echoed request: the request simply
// expires after probe_verdict_window_ns; the cooldown still applies (no
// second request within probe_cooldown_ns of the first).
void test_probe_never_echoed_expires_and_cooldown_holds() {
    synccore::CorrectionPolicy pol;
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = true;
    uint64_t t = 0;
    for (int i = 0; i <= 20; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, true, t);
    }
    CHECK(pol.probe_request_due(t));
    const uint64_t requested_at = t;

    // No echo. Advance past probe_verdict_window_ns (20 s) with plain
    // on_tick calls (playback still live, still low confidence, but the
    // dwell has already fired once — a fresh dwell can't restart the probe
    // before the cooldown anyway).
    for (int i = 1; i <= 21; ++i) {
        t = requested_at + static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, true, t);
    }
    // Well within probe_cooldown_ns (120 s) of the first request: no second
    // probe, confirming the expiry didn't waive the cooldown.
    CHECK(!pol.probe_request_due(t));
}

// Verdict, inconclusive via too few post-echo fixes: fewer than
// probe_verdict_min_fixes arrive before probe_verdict_window_ns elapses —
// no verdict (no track-lost), and the cooldown still applies.
void test_probe_verdict_window_expires_with_too_few_fixes() {
    synccore::CorrectionPolicy pol;
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = true;
    uint64_t t = 0;
    for (int i = 0; i <= 20; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, true, t);
    }
    CHECK(pol.probe_request_due(t));

    t += kSec;
    pol.on_probe_executed(50.0, t);
    const uint64_t echo_at = t;

    // Exactly one post-echo estimate (probe_verdict_min_fixes is 2) — not
    // enough to reach a verdict.
    t += 5 * kSec;
    const auto a = pol.on_estimate(make_est(50.0, 0.0, true), 100000.0, t);
    CHECK(a.kind != synccore::ActionKind::kTrackLost);

    // Advance past the verdict window (20 s from the echo) via on_tick:
    // the pending verdict must expire, not linger.
    for (uint64_t tt = echo_at + 6 * kSec; tt <= echo_at + 25 * kSec;
         tt += kSec) {
        t = tt;
        pol.on_tick(low, true, t);
    }
    // No probe re-requested within the cooldown of the original request.
    CHECK(!pol.probe_request_due(t));
}

// Seek suppression: an out-of-deadband estimate arriving while a probe is
// outstanding must return kNone, never kSeek — but the lost-threshold
// kTrackLost check keeps precedence over the suppression.
void test_seek_suppressed_while_probe_outstanding() {
    synccore::CorrectionPolicy pol;
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = true;
    uint64_t t = 0;
    for (int i = 0; i <= 20; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, true, t);
    }
    CHECK(pol.probe_request_due(t));  // probe now outstanding, no echo yet

    // An ordinary out-of-deadband, high-confidence estimate: would fire a
    // kSeek without a probe outstanding, but must return kNone here.
    t += kSec;
    const auto a = pol.on_estimate(make_est(200.0), 100000.0, t);
    CHECK(a.kind == synccore::ActionKind::kNone);

    // Track-lost precedence still holds even while a probe is outstanding.
    t += kSec;
    const auto lost = pol.on_estimate(make_est(2500.0), 100000.0, t);
    CHECK(lost.kind == synccore::ActionKind::kTrackLost);
}

// Epoch: reset() clears all sentinel/probe state — starvation accumulated
// before the reset must never fire a probe after it. Deliberately built so
// a broken (no-op) reset would be CAUGHT: 4 disagreeing windows fed close
// together satisfy the ring's count threshold (>=4) without yet satisfying
// the elapsed-time threshold (45 s) — proving the ring reached capacity
// pre-reset. After reset, converged is re-established and a single window
// is fed at a far-future timestamp. If reset had left the ring/last-agree
// state intact, that single call would complete a stale count>=4 PLUS read
// a huge elapsed time off the stale last-agree stamp — arming a probe from
// pre-reset accumulation alone. A correct reset starts the ring over at 1,
// which can never satisfy the count threshold by itself.
void test_probe_state_cleared_on_reset() {
    synccore::CorrectionPolicy pol;
    pol.on_estimate(make_est(5.0, 0.0, true), 100000.0, 1 * kSec);

    uint64_t t = 1 * kSec;
    const double lags[] = {100.0, 400.0, 900.0, 1500.0};  // meets the count
                                                           // threshold, all
                                                           // close together
    for (double lag : lags) {
        t += kSec;
        pol.on_referee_window(lag, true, t);
    }
    CHECK(!pol.probe_request_due(t));  // count satisfied; elapsed time isn't

    pol.reset();
    pol.on_estimate(make_est(5.0, 0.0, true), 100000.0, 1 * kSec);

    const uint64_t far_future = 10'000 * kSec;
    pol.on_referee_window(2200.0, true, far_future);
    CHECK(!pol.probe_request_due(far_future));
}

// ---- DSP-03a: volume-duck tier, deferred verdict, escalation -------------
// tech-req §2.12. Composes with the CTL-01a machinery just above: the
// duck_tier_first switch (ORCHESTRATOR RULING R1) routes both existing
// triggers to a duck request instead of a pause request; the pause probe
// becomes reachable only through on_duck_result's inconclusive-escalation
// path (R3). All tests below set cfg.duck_tier_first = true unless
// otherwise noted — the default-false regression test is the one
// exception, proving shipped CTL-01a behavior is untouched at defaults.
//
// The duck verdict itself is driven by calling on_duck_result directly
// (dip_db/z as if the worker's matched-filter analysis had already run) —
// the policy-level test convention test_probe_verdict_* above already
// established for on_probe_executed, and explicitly sanctioned for this
// ticket's verdict tests. No on_duck_executed-equivalent exists in the
// policy: unlike the pause probe, the duck's verdict needs no per-echo
// snapshot (see on_duck_result's own doc comment for why), so there is
// nothing for such a method to do.

// Drives the Wittenmark turn-off trigger (simplest deterministic path to an
// outstanding request, same technique the CTL-01a verdict tests above use)
// far enough to arm exactly one request. Returns the timestamp the request
// armed at. NOTE: the dwell condition it relies on stays satisfied
// afterward (nothing here resets it) as long as on_tick keeps seeing a
// low-confidence estimate — several tests below rely on that to probe
// cooldown/re-arm behavior without rebuilding the dwell from scratch.
uint64_t drive_turnoff_trigger(synccore::CorrectionPolicy& pol) {
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = true;
    uint64_t t = 0;
    for (int i = 0; i <= 20; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, true, t);
    }
    return t;
}

void test_duck_first_starvation_trigger_arms_duck() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    uint64_t t = 1 * kSec;
    pol.on_estimate(make_est(5.0, 0.0, true), 100000.0, t);

    // Same starvation drive as test_referee_sentinel_starvation_requests_
    // probe above (mutually-disagreeing lags spanning >= 45 s while
    // converged), but with duck_tier_first set: it must arm the DUCK
    // request, never the pause probe.
    const double lags[] = {100.0, 400.0, 900.0, 1500.0, 2200.0};
    bool duck_seen = false;
    bool probe_seen = false;
    for (double lag : lags) {
        t += 12 * kSec;
        pol.on_referee_window(lag, true, t);
        if (pol.duck_request_due(t)) duck_seen = true;
        if (pol.probe_request_due(t)) probe_seen = true;
    }
    CHECK(duck_seen);
    CHECK(!probe_seen);
}

void test_duck_first_turnoff_trigger_arms_duck() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = false;

    uint64_t t = 0;
    bool duck_seen = false;
    bool probe_seen = false;
    for (int i = 0; i <= 21; ++i) {
        t = static_cast<uint64_t>(i) * kSec;
        pol.on_tick(low, /*playback_live=*/true, t);
        if (pol.duck_request_due(t)) duck_seen = true;
        if (pol.probe_request_due(t)) probe_seen = true;
    }
    CHECK(duck_seen);
    CHECK(!probe_seen);
}

// Default-config regression: with duck_tier_first left at its default
// (false), the EXACT starvation drive from
// test_referee_sentinel_starvation_requests_probe above must still arm the
// pause probe and duck_request_due must never fire — pins that shipped
// CTL-01a behavior is byte-identically untouched by this ticket at
// defaults.
void test_duck_tier_first_default_false_regression() {
    synccore::CorrectionPolicy pol;  // default PolicyConfig
    uint64_t t = 1 * kSec;
    pol.on_estimate(make_est(5.0, 0.0, true), 100000.0, t);

    const double lags[] = {100.0, 400.0, 900.0, 1500.0, 2200.0};
    bool probe_seen = false;
    bool duck_seen = false;
    for (double lag : lags) {
        t += 12 * kSec;
        pol.on_referee_window(lag, true, t);
        if (pol.probe_request_due(t)) probe_seen = true;
        if (pol.duck_request_due(t)) duck_seen = true;
    }
    CHECK(probe_seen);
    CHECK(!duck_seen);
}

// Verdict, self-dominant: deep, significant dip at the nominal 6 dB duck
// (achieved_deci_db=60) reaches the same kTrackLost path a self-match pause
// verdict does.
void test_duck_verdict_self_dominant_track_lost() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    uint64_t t = drive_turnoff_trigger(pol);
    CHECK(pol.duck_request_due(t));

    t += kSec;
    const auto a = pol.on_duck_result(/*dip_db=*/5.0, /*z=*/4.0,
                                      /*achieved_deci_db=*/60, t);
    CHECK(a.kind == synccore::ActionKind::kTrackLost);
}

// Verdict, room-dominant: shallow dip clears the episode without a
// track-lost, and the reset sentinel state lets a later, independent
// starvation re-arm a duck once its own cooldown has elapsed.
void test_duck_verdict_room_dominant_clears_and_can_rearm() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    uint64_t t = drive_turnoff_trigger(pol);
    CHECK(pol.duck_request_due(t));
    const uint64_t requested_at = t;

    t += kSec;
    const auto a = pol.on_duck_result(1.0, 5.0, 60, t);
    CHECK(a.kind == synccore::ActionKind::kNone);
    CHECK(!pol.probe_request_due(t));  // cleared, no escalation

    // Sentinel state resets: once the 60 s duck cooldown elapses, the
    // still-active turn-off dwell (nothing above reset it) can re-arm a
    // fresh duck request.
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = false;
    t = requested_at + 61 * kSec;
    pol.on_tick(low, true, t);
    CHECK(pol.duck_request_due(t));
}

// Verdict, inconclusive: mid-band dip escalates exactly once to the pause
// probe; a stray second on_duck_result call in the resolved episode fires
// no second escalation.
void test_duck_verdict_inconclusive_escalates_once() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    uint64_t t = drive_turnoff_trigger(pol);
    CHECK(pol.duck_request_due(t));

    t += kSec;
    const auto a = pol.on_duck_result(2.5, 4.0, 60, t);
    CHECK(a.kind == synccore::ActionKind::kNone);
    CHECK(pol.probe_request_due(t));   // escalated exactly once
    CHECK(!pol.probe_request_due(t));  // one-shot: already consumed

    // duck_outstanding_ was already resolved by the call above, so this is
    // a stray result — no second escalation.
    t += kSec;
    const auto a2 = pol.on_duck_result(2.5, 4.0, 60, t);
    CHECK(a2.kind == synccore::ActionKind::kNone);
    CHECK(!pol.probe_request_due(t));
}

// z-gate: a deep dip that fails the significance test (z < duck_min_z) is
// inconclusive, not self-dominant, and still escalates.
void test_duck_verdict_z_gate_inconclusive_escalates() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    uint64_t t = drive_turnoff_trigger(pol);
    CHECK(pol.duck_request_due(t));

    t += kSec;
    const auto a = pol.on_duck_result(5.0, 1.0, 60, t);
    CHECK(a.kind == synccore::ActionKind::kNone);
    CHECK(pol.probe_request_due(t));
}

// R4 scaling: at half the nominal achieved depth (30 deci-dB = 3 dB), the
// self-dominant D threshold halves (4.0 -> 2.0); at achieved_deci_db=0 the
// verdict is inconclusive regardless of D (a duck that commanded no depth
// proves nothing) but still escalates.
void test_duck_verdict_scaling() {
    {
        synccore::PolicyConfig cfg;
        cfg.duck_tier_first = true;
        synccore::CorrectionPolicy pol(cfg);
        uint64_t t = drive_turnoff_trigger(pol);
        CHECK(pol.duck_request_due(t));
        t += kSec;
        const auto a = pol.on_duck_result(2.2, 4.0, 30, t);
        CHECK(a.kind == synccore::ActionKind::kTrackLost);
    }
    {
        synccore::PolicyConfig cfg;
        cfg.duck_tier_first = true;
        synccore::CorrectionPolicy pol(cfg);
        uint64_t t = drive_turnoff_trigger(pol);
        CHECK(pol.duck_request_due(t));
        t += kSec;
        const auto a = pol.on_duck_result(9.0, 9.0, 0, t);
        CHECK(a.kind != synccore::ActionKind::kTrackLost);
        CHECK(pol.probe_request_due(t));  // still consumes/escalates
    }
}

// Cooldown: a second duck request is blocked inside duck_cooldown_ns
// (60 s), allowed once it has elapsed. probe_cooldown_ns (120 s) is left
// completely alone by this ticket — already regression-checked by every
// existing CTL-01a cooldown test above, which this file does not modify.
void test_duck_cooldown_enforced() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    uint64_t t = drive_turnoff_trigger(pol);
    CHECK(pol.duck_request_due(t));
    const uint64_t requested_at = t;

    // Resolve the episode so duck_outstanding_ itself isn't what's blocking
    // the next request, isolating the cooldown gate specifically.
    t += kSec;
    pol.on_duck_result(1.0, 5.0, 60, t);

    // The turn-off dwell is still satisfied from the original drive (never
    // reset above), so every subsequent on_tick call re-attempts a duck
    // request — exactly what's needed to isolate the cooldown gate itself.
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = false;

    t = requested_at + 30 * kSec;  // well within the 60 s cooldown
    pol.on_tick(low, true, t);
    CHECK(!pol.duck_request_due(t));

    t = requested_at + 61 * kSec;  // past the 60 s cooldown
    pol.on_tick(low, true, t);
    CHECK(pol.duck_request_due(t));
}

// Seek suppression: an out-of-deadband estimate arriving while a duck is
// outstanding must return kNone, never kSeek — mirrors
// test_seek_suppressed_while_probe_outstanding above, for the duck tier.
void test_seek_suppressed_while_duck_outstanding() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    uint64_t t = drive_turnoff_trigger(pol);
    CHECK(pol.duck_request_due(t));  // duck now outstanding, no verdict yet

    t += kSec;
    const auto a = pol.on_estimate(make_est(200.0), 100000.0, t);
    CHECK(a.kind == synccore::ActionKind::kNone);

    // Track-lost precedence still holds even while a duck is outstanding.
    t += kSec;
    const auto lost = pol.on_estimate(make_est(2500.0), 100000.0, t);
    CHECK(lost.kind == synccore::ActionKind::kTrackLost);
}

// Expiry (orchestrator-added): a duck the shell never echoes — or whose
// verdict never arrives — must expire probe_verdict_window_ns after the
// request, exactly like §2.9's never-echoed pause probe. Without on_tick's
// duck-expiry block, duck_outstanding_ stays true forever: seeks are
// suppressed indefinitely and no future duck can ever arm. Deleting that
// expiry block fails this test.
void test_duck_expires_when_never_echoed() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    uint64_t t = drive_turnoff_trigger(pol);
    CHECK(pol.duck_request_due(t));  // duck outstanding, never echoed

    // While outstanding: the existing suppression rule holds.
    t += kSec;
    const auto suppressed = pol.on_estimate(make_est(200.0), 100000.0, t);
    CHECK(suppressed.kind == synccore::ActionKind::kNone);

    // No echo ever arrives. Past probe_verdict_window_ns (20 s from the
    // request), an on_tick pass expires the episode...
    t += 21 * kSec;
    synccore::Estimate low;
    low.valid = true;
    low.confidence = 0.19f;
    low.converged = false;
    pol.on_tick(low, true, t);  // within the 60 s cooldown: cannot re-arm
    CHECK(!pol.duck_request_due(t));

    // ...and the SAME out-of-deadband estimate that was suppressed above
    // may now seek again — the outstanding flag no longer pins suppression.
    t += kSec;
    const auto freed = pol.on_estimate(make_est(200.0), 100000.0, t);
    CHECK(freed.kind == synccore::ActionKind::kSeek);
}

// Epoch: reset() clears the duck outstanding flag (and, per its own
// comment, the escalation flag alongside it). Deliberately built so a
// broken (no-op) reset would be caught: without it, the post-reset
// on_duck_result call below would proceed to decide a verdict (and
// possibly escalate) instead of being ignored as a stray result.
void test_duck_state_cleared_on_reset() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    uint64_t t = drive_turnoff_trigger(pol);
    CHECK(pol.duck_request_due(t));

    pol.reset();

    t += kSec;
    const auto a = pol.on_duck_result(2.5, 4.0, 60, t);
    CHECK(a.kind == synccore::ActionKind::kNone);
    CHECK(!pol.probe_request_due(t));  // no escalation from a stray result
}

// Stray result: on_duck_result with nothing outstanding is ignored — no
// action, no escalation.
void test_stray_duck_result_ignored() {
    synccore::PolicyConfig cfg;
    cfg.duck_tier_first = true;
    synccore::CorrectionPolicy pol(cfg);
    const auto a = pol.on_duck_result(5.0, 4.0, 60, 10 * kSec);
    CHECK(a.kind == synccore::ActionKind::kNone);
    CHECK(!pol.probe_request_due(10 * kSec));
}

// ---- CTL-04: convergence settling hysteresis ----------------------------
// tech-req §2.15. FT9's Test 2 (Billie Jean) recorded a 150 ms CTL-02
// gate-firing followed, seconds later, by three SMALLER-than-large
// instantaneous corrections (633/542/547 ms) landing while the audible
// impression was already close. Once a correction lands at floor and the
// existing settle/verify window confirms it, further proposals below the
// large/lost bypass — instantaneous or persistence-gate — must clear a
// stronger N-of-M bar before firing.

// AC1: a correction landing at <= settle_enter_threshold_ms (150) and
// clearing the standard settle/verify window enters settled — proven via
// the very next behavioral contrast: an out-of-deadband proposal that would
// have fired instantly pre-settled (as the FIRST 200 ms correction below
// did) is instead held.
void test_settled_entry_on_floor_landing_and_verify() {
    synccore::CorrectionPolicy pol;
    uint64_t t = 10 * kSec;
    CHECK(pol.on_estimate(make_est(200.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kSeek);
    pol.on_seek_issued(t);

    // Post-settle verify fix lands at floor (80 <= 150): enters settled.
    t += 4 * kSec;
    CHECK(!pol.is_settling(t));
    CHECK(pol.on_estimate(make_est(80.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kNone);

    // Proof of entry: a fresh, single, out-of-deadband proposal — which
    // pre-settled fires off one estimate (see the 200 ms correction above)
    // — is instead held.
    t += 5 * kSec;
    const auto held =
        pol.on_estimate(make_est(200.0, 0.0, true), 100000.0, t);
    CHECK(held.kind == synccore::ActionKind::kNone);
}

// AC2: while settled, a fresh instantaneous-path proposal below
// large_correction_threshold_ms is held, not fired, until
// settled_confirm_min_fixes (5) agree within settled_confirm_agree_ms (40)
// — fires on the 5th agreeing sample, from the cluster mean, mirroring
// §2.7's own "cluster mean, not the instantaneous value."
void test_settled_raises_bar_for_instantaneous_path_until_corroborated() {
    synccore::CorrectionPolicy pol;
    uint64_t t = 10 * kSec;
    CHECK(pol.on_estimate(make_est(200.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kSeek);
    pol.on_seek_issued(t);

    t += 4 * kSec;
    CHECK(pol.on_estimate(make_est(120.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kNone);  // enters settled; itself held (1/5)
    t += 5 * kSec;
    CHECK(pol.on_estimate(make_est(130.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kNone);  // 2/5
    t += 5 * kSec;
    CHECK(pol.on_estimate(make_est(125.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kNone);  // 3/5
    t += 5 * kSec;
    CHECK(pol.on_estimate(make_est(135.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kNone);  // 4/5
    t += 5 * kSec;
    const auto fired =
        pol.on_estimate(make_est(128.0, 0.0, true), 100000.0, t);  // 5/5
    CHECK(fired.kind == synccore::ActionKind::kSeek);
    // Cluster mean of [120,130,125,135,128] = 127.6; local + the policy's
    // OWN (by now latency-adapted, per the 120 ms verify fix above —
    // test_command_latency_adaptation exercises that mechanism directly) −
    // mean, no drift term.
    CHECK(fired.seek_to_ms ==
          static_cast<int64_t>(
              std::llround(100000.0 + pol.command_latency_ms() - 127.6)));
}

// AC3: a fresh estimate >= large_correction_threshold_ms (or
// lost_threshold_ms) exits settled immediately and unconditionally,
// regardless of any pending settled-bar hold — routing instead to §2.8's
// OWN, unaffected 2-sample corroboration (not the stronger 5), and leaving
// the policy genuinely un-settled afterward (proven by a subsequent single
// small proposal firing off one estimate again).
void test_settled_large_or_lost_error_bypasses_immediately() {
    synccore::CorrectionPolicy pol;
    uint64_t t = 10 * kSec;
    CHECK(pol.on_estimate(make_est(200.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kSeek);
    pol.on_seek_issued(t);

    t += 4 * kSec;
    // Enters settled; itself held (1/5).
    CHECK(pol.on_estimate(make_est(50.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kNone);

    // A large error bypasses settled immediately and routes to §2.8's own
    // hold — first sample held, per that mechanism's own rule.
    t += 5 * kSec;
    CHECK(pol.on_estimate(make_est(1200.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kNone);
    // A SECOND agreeing large sample fires via §2.8's own pair-corroboration
    // (large_corroborate_agree_ms=150) — NOT settled_confirm_min_fixes (5).
    // If settled_ still gated this branch, two samples could never suffice.
    t += 5 * kSec;
    const auto largeFire =
        pol.on_estimate(make_est(1210.0, 0.0, true), 100000.0, t);
    CHECK(largeFire.kind == synccore::ActionKind::kSeek);
    pol.on_seek_issued(t);

    // Past settle: a single fresh, small, out-of-deadband (non-large)
    // proposal must fire IMMEDIATELY off one estimate — proving settled_
    // was genuinely cleared by the bypass (pre-bypass, the 50 ms sample
    // above needed 5 agreeing samples; this one must not).
    t += 4 * kSec;
    CHECK(!pol.is_settling(t));
    const auto after = pol.on_estimate(make_est(300.0, 0.0, true), 100000.0, t);
    CHECK(after.kind == synccore::ActionKind::kSeek);
}

// AC4: settled clears on any emitted seek (instantaneous, persistence-gate,
// or settled-gate) and on reset().
void test_settled_clears_on_emitted_seek_and_on_reset() {
    // Part A: the settled-gate's OWN fire clears settled_.
    {
        synccore::CorrectionPolicy pol;
        uint64_t t = 10 * kSec;
        CHECK(pol.on_estimate(make_est(200.0, 0.0, true), 100000.0, t).kind ==
              synccore::ActionKind::kSeek);
        pol.on_seek_issued(t);

        t += 4 * kSec;
        CHECK(pol.on_estimate(make_est(120.0, 0.0, true), 100000.0, t).kind ==
              synccore::ActionKind::kNone);
        t += 5 * kSec;
        CHECK(pol.on_estimate(make_est(130.0, 0.0, true), 100000.0, t).kind ==
              synccore::ActionKind::kNone);
        t += 5 * kSec;
        CHECK(pol.on_estimate(make_est(125.0, 0.0, true), 100000.0, t).kind ==
              synccore::ActionKind::kNone);
        t += 5 * kSec;
        CHECK(pol.on_estimate(make_est(135.0, 0.0, true), 100000.0, t).kind ==
              synccore::ActionKind::kNone);
        t += 5 * kSec;
        const auto fired =
            pol.on_estimate(make_est(128.0, 0.0, true), 100000.0, t);
        CHECK(fired.kind == synccore::ActionKind::kSeek);  // settled-gate fires
        pol.on_seek_issued(t);

        t += 4 * kSec;
        CHECK(!pol.is_settling(t));
        const auto after =
            pol.on_estimate(make_est(300.0, 0.0, true), 100000.0, t);
        CHECK(after.kind == synccore::ActionKind::kSeek);  // settled_ cleared
    }
    // Part B: reset() clears settled_.
    {
        synccore::CorrectionPolicy pol;
        uint64_t t = 10 * kSec;
        CHECK(pol.on_estimate(make_est(200.0, 0.0, true), 100000.0, t).kind ==
              synccore::ActionKind::kSeek);
        pol.on_seek_issued(t);

        t += 4 * kSec;
        CHECK(pol.on_estimate(make_est(120.0, 0.0, true), 100000.0, t).kind ==
              synccore::ActionKind::kNone);  // settled, held

        pol.reset();

        t += 5 * kSec;
        const auto after =
            pol.on_estimate(make_est(300.0, 0.0, true), 100000.0, t);
        CHECK(after.kind == synccore::ActionKind::kSeek);  // reset() cleared it
    }
}

// Orchestrator-added pin: the settled-hold branch (settled_ &&
// |e| >= deadband_ms) is not the only instantaneous seek reachable while
// settled — a purely PREEMPTIVE proposal (|e| itself still inside the
// deadband, only the drift-projected |predicted| crosses it — deviation 2's
// own carve-out) falls through to the plain-instantaneous fire branch
// instead, and that branch must ALSO clear settled_ on any emitted seek
// (tech-req §2.15's epoch rule says "any emitted seek," not "any emitted
// seek except a preemptive one"). A badly-landed preemptive seek left
// settled_ = true against the fresh residual pre-fix, keeping the raised
// 5-fix bar in place exactly when a genuine perturbation must not be slowed.
// Proven by the same behavioral-contrast convention as the other tests in
// this section: after the preemptive seek fires and its own verify lands
// ABOVE settle_enter_threshold_ms (so it does NOT itself re-enter settled),
// a fresh out-of-deadband, sub-large proposal must fire IMMEDIATELY off one
// estimate — held would mean settled_ survived the preemptive seek.
void test_settled_clears_on_preemptive_instantaneous_seek() {
    synccore::CorrectionPolicy pol;
    uint64_t t = 10 * kSec;

    // Land a correction and enter settled via the post-verify fix, exactly
    // like the other tests in this section.
    CHECK(pol.on_estimate(make_est(200.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kSeek);
    pol.on_seek_issued(t);

    t += 4 * kSec;
    CHECK(pol.on_estimate(make_est(80.0, 0.0, true), 100000.0, t).kind ==
          synccore::ActionKind::kNone);  // enters settled; itself held (1/5)

    // A purely preemptive proposal: |e|=10 stays INSIDE deadband_ms (25), so
    // the settled-hold branch's own |e| >= deadband_ms gate never engages —
    // only the drift-projected |predicted| (well past 25 at this drift/
    // horizon) crosses it, reaching the plain-instantaneous fire branch
    // while settled_ is still true.
    t += 5 * kSec;
    const auto preemptive =
        pol.on_estimate(make_est(10.0, 1000.0, true), 100000.0, t);
    CHECK(preemptive.kind == synccore::ActionKind::kSeek);
    pol.on_seek_issued(t);

    // The preemptive seek's own verify lands ABOVE settle_enter_threshold_ms
    // (300 > 150), so it does NOT itself re-enter settled — isolating
    // whether the PREVIOUS (preemptive) fire cleared settled_, rather than
    // this call happening to re-enter it. Fires immediately off this one
    // estimate only if settled_ was genuinely cleared; held would mean it
    // survived the preemptive seek.
    t += 4 * kSec;
    CHECK(!pol.is_settling(t));
    const auto after = pol.on_estimate(make_est(300.0, 0.0, true), 100000.0, t);
    CHECK(after.kind == synccore::ActionKind::kSeek);
}

// AC5: a reconstruction of Test 1's (Vienna) trim=0 alternating segment —
// "43-52 ms interleaved with 1257-1639 ms harmonic-multiple readings...
// never stabilized" — must never spuriously enter settled. Because entry
// only ever happens inside the single post-seek verify check, and this
// churn never lets two low-mode readings land back-to-back (each low
// reading's own fire is always immediately followed by a large,
// bypass-triggering harmonic reading), the mechanism structurally can't
// latch settled_ true here — proven by a final clean small proposal firing
// off one estimate, exactly the pre-settled instantaneous rule.
void test_settled_never_spuriously_enters_during_harmonic_ambiguous_churn() {
    synccore::CorrectionPolicy pol;
    const double readings[] = {
        43.0, 1257.0, 52.0, 1639.0, 48.0, 1257.0, 45.0, 1639.0, 50.0, 1257.0,
    };
    uint64_t t = 10 * kSec;
    for (double e : readings) {
        pol.on_estimate(make_est(e, 0.0, true), 100000.0, t);
        t += 6 * kSec;
    }

    CHECK(!pol.is_settling(t));
    const auto after = pol.on_estimate(make_est(300.0, 0.0, true), 100000.0, t);
    CHECK(after.kind == synccore::ActionKind::kSeek);
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
    test_large_correction_single_hold();
    test_large_correction_corroborated_pair();
    test_large_correction_disagreeing_pair();
    test_large_correction_sub_threshold_clears();
    test_large_correction_expiry();
    test_large_correction_track_lost_precedence();
    test_closed_loop_phantom_large_fix_held();
    test_closed_loop_genuine_large_jump_corrects();
    test_referee_sentinel_starvation_requests_probe();
    test_referee_sentinel_agreement_never_requests_probe();
    test_referee_sentinel_requires_convergence();
    test_turnoff_trigger_requests_probe();
    test_turnoff_trigger_resets_on_accepted_fix();
    test_no_probe_while_settling();
    test_no_probe_while_paused();
    test_probe_verdict_genuine_composes_with_persistence_gate();
    test_probe_verdict_self_match_returns_track_lost();
    test_probe_never_echoed_expires_and_cooldown_holds();
    test_probe_verdict_window_expires_with_too_few_fixes();
    test_seek_suppressed_while_probe_outstanding();
    test_probe_state_cleared_on_reset();

    test_duck_first_starvation_trigger_arms_duck();
    test_duck_first_turnoff_trigger_arms_duck();
    test_duck_tier_first_default_false_regression();
    test_duck_verdict_self_dominant_track_lost();
    test_duck_verdict_room_dominant_clears_and_can_rearm();
    test_duck_verdict_inconclusive_escalates_once();
    test_duck_verdict_z_gate_inconclusive_escalates();
    test_duck_verdict_scaling();
    test_duck_cooldown_enforced();
    test_seek_suppressed_while_duck_outstanding();
    test_duck_expires_when_never_echoed();
    test_duck_state_cleared_on_reset();
    test_stray_duck_result_ignored();

    test_settled_entry_on_floor_landing_and_verify();
    test_settled_raises_bar_for_instantaneous_path_until_corroborated();
    test_settled_large_or_lost_error_bypasses_immediately();
    test_settled_clears_on_emitted_seek_and_on_reset();
    test_settled_clears_on_preemptive_instantaneous_seek();
    test_settled_never_spuriously_enters_during_harmonic_ambiguous_churn();

    if (g_failures == 0) {
        std::printf("policy_tests: all tests passed\n");
        return 0;
    }
    std::printf("policy_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
