// policy.h — CORE-03: the §6.2 correction policy.
//
// Pure decision logic, no threads, no clocks. Consumes filtered estimates
// and produces actions (seek / track-lost) plus the adaptive recognition
// cadence. Owned and called only by the session worker thread.
//
// Rules implemented (architecture-spec §6.2/§6.3):
//   - 25 ms deadband: no corrections inside it
//   - micro-seek when the filtered error — or the error predicted one fix
//     interval ahead under the drift estimate (skew pre-emption) — leaves
//     the deadband; drift-compensated target centers the sawtooth
//   - 3 s settle suppression after a seek is issued (plus an ack window
//     between emitting a correction and the shell's sc_notify_seek_issued)
//   - |error| ≥ 2 s → track lost
//   - fix cadence: 8 s when erroring, 10 s base, stretched to 30 s once
//     converged; a verification fix shortly after each settle window
//   - persistence gate (tech-req §2.7, CTL-02): a stable, corroborated
//     residual cluster — confirm_min_fixes converged fixes spanning
//     confirm_window_ns, all within confirm_agree_ms of their mean and
//     above confirm_floor_ms — earns one correction from the cluster mean
//     even while sitting inside a widened deadband_ms
//   - large-correction corroboration hold (tech-req §2.8 Part B, CTL-03b):
//     a proposed seek with |error| ≥ large_correction_threshold_ms is held
//     as a pending record instead of firing off one estimate; the next
//     fresh estimate fires it — computed from that fresh error — only if
//     it agrees within large_corroborate_agree_ms and is still ≥ threshold,
//     else the record is replaced or, below threshold, cleared outright
//   - referee sentinel + active probe (tech-req §2.9, CTL-01a): after any
//     room discontinuity, our own audio can become a perfectly continuous
//     timeline that the FT4 self-match guard structurally cannot see. Two
//     independent triggers — referee agreement starvation
//     (on_referee_window) and the Wittenmark turn-off case (on_tick, for a
//     starving filter that never reaches on_estimate through an accepted
//     fix) — both request a deliberate pause/resume probe, rate-limited and
//     gated like any other correction. The verdict from the post-echo
//     estimates either clears the suspicion or declares the track lost;
//     seeks are suppressed (not the underlying ring/pending-hold
//     accumulation) for as long as a probe request is outstanding
#ifndef SYNCCORE_POLICY_H
#define SYNCCORE_POLICY_H

#include <cstddef>
#include <cstdint>

#include "estimator/estimator.h"

namespace synccore {

struct PolicyConfig {
    // Spotify keeps playing while a seek command is in flight: an absolute
    // target computed at decision time lands late by the command latency, so
    // the target must lead by it. Seeded from
    // sc_config_t.command_latency_prior_ms, then learned online: the first
    // post-settle estimate after each seek reveals the landing bias
    // (actual − assumed latency), which no amount of re-seeking can remove
    // while the lead stays wrong.
    double command_latency_ms = 250.0;
    double latency_adapt_gain = 0.7;
    double latency_adapt_clamp_ms = 500.0;  // ignore implausible innovations
    double command_latency_min_ms = 0.0;
    double command_latency_max_ms = 2000.0;
    double deadband_ms = 25.0;
    double lost_threshold_ms = 2000.0;
    // Field Test 4: never act on an estimate the measurements no longer
    // support. While the self-match guard was rejecting fixes, the filter
    // coasted for ~25 s and its confidence decayed to 0.19 — and the policy
    // then issued −2.6 s corrections that audibly threw the phone out of
    // sync ("3 beats behind"), even though every raw observation in that
    // window read +177 ms. An accepted fix restores confidence to ~0.8
    // immediately, so genuine corrections are unaffected; only unsupported
    // ones are withheld. Seeking on a guess is worse than not seeking.
    float min_confidence_to_correct = 0.35f;
    uint64_t settle_ns = 3'000'000'000ull;
    uint64_t seek_ack_timeout_ns = 5'000'000'000ull;
    uint64_t fix_interval_min_ns = 8'000'000'000ull;
    uint64_t fix_interval_base_ns = 10'000'000'000ull;
    uint64_t fix_interval_max_ns = 30'000'000'000ull;
    uint64_t post_settle_verify_ns = 500'000'000ull;  // fix 0.5 s after settle
    uint64_t request_retry_ns = 5'000'000'000ull;     // re-request if unanswered

    // tech-req §2.7 (CTL-02): persistence gate. A shell that widens
    // deadband_ms past confirm_floor_ms (Android runs 350) can lock LOCKED
    // onto a stable, corroborated residual the instantaneous deadband will
    // never touch — field test 8's Vienna/Dreams held ~285-300 ms echoes
    // for entire cycles, zero corrections. This is a second, slower gate
    // layered above the instantaneous one, never a replacement for it.

    // Minimum ring occupancy before a cluster of converged fixes can be
    // judged coherent enough to act on.
    int confirm_min_fixes = 3;
    // Minimum span the qualifying ring samples must cover — a handful of
    // fixes seconds apart is not yet "persistent."
    uint64_t confirm_window_ns = 20'000'000'000ull;
    // Max deviation of any ring sample from the cluster mean, sized off the
    // two FT8 failure shapes: the deadband-150 churn class chases the
    // song's own ~500 ms beat-comb spacing, while Vienna/Dreams's residual
    // is constant to within tens of ms across the whole cycle — 60 ms
    // admits the latter and rejects the former by close to an order of
    // magnitude.
    double confirm_agree_ms = 60.0;
    // Absolute floor epsilon: a cluster at or below this is healthy sync,
    // never corrected. RFC 5905 states its own step threshold two ways —
    // Figure 27's parameter table says 125 ms, Appendix A.5.5.6's reference
    // pseudocode defines STEPT .128 — an internal discrepancy the RFC never
    // resolves. This spec picks the table's 125 on purpose (tech-req
    // §2.7): FT8's healthy locks read −30 to −63 ms and the broken class
    // reads 250–314 ms, so 125 sits well clear of both with margin either
    // way.
    double confirm_floor_ms = 125.0;

    // tech-req §2.8 Part B (CTL-03b): large-correction corroboration hold.
    // Field test 8's song 2 took a single conf-0.74 fix 1259 ms off and it
    // stood uncorrected — the estimator's own outlier gate is inactive by
    // design at mid-uncertainty ("post-reset first fixes always land"), so
    // a wild single fix landed unchallenged, and the follow-up errors that
    // would have exposed it hid near the deadband. This is the runtime
    // defense at the policy layer; the estimator stays untouched.

    // At or above this magnitude (but below lost_threshold_ms, which keeps
    // its existing checked-first precedence), a proposed seek is never
    // fired from one estimate alone — it is held as a pending record until
    // a fresh estimate corroborates it.
    double large_correction_threshold_ms = 1000.0;
    // Max deviation between the pending error and the next fresh error to
    // count as agreement. Deliberately 150, not the ~50 ms first
    // suggested: sc_config_t.deadband_ms's own comment cites Field Test
    // 2's measured ±100–150 ms single-fix recognition noise, and a 50 ms
    // gate would starve real large corrections indefinitely on that noise
    // floor alone — a worse failure than the overshoot it exists to
    // prevent (tech-req §2.8's rationale for 150-not-50).
    double large_corroborate_agree_ms = 150.0;
    // The pending record expires unfired if no corroborating fix arrives
    // within this span.
    uint64_t large_pending_max_age_ns = 30'000'000'000ull;

    // tech-req §2.9 (CTL-01a): referee sentinel + active probe. Two
    // independent triggers (agreement starvation and the Wittenmark
    // turn-off case) both funnel through one probe request, rate-limited
    // and gated exactly like any other correction: never while settling,
    // never while a probe is already outstanding, never while playback is
    // paused.

    // Max deviation between ringed referee lags to count as "the same lag"
    // — matches §2.6's own shell-side ±50 ms agreement convention for
    // committing a residual sample into a CalibrationProfile.
    double referee_agree_ms = 50.0;
    // Minimum referee-window ring occupancy before the sentinel may judge
    // agreement has genuinely starved, rather than just gone quiet for a
    // moment.
    int referee_starve_min_windows = 4;
    // How long agreement may go missing, while converged, before the
    // sentinel suspects a self-match. FT8's own self-match episodes ran
    // for minutes once the room dropped out, so 45 s sits comfortably
    // inside the failure window while staying clear of ordinary
    // between-song silence.
    uint64_t referee_starve_ns = 45'000'000'000ull;

    // Wittenmark turn-off trigger (research-closed-loop-control.md §5 item
    // 4): a valid-but-unconfident estimate that persists this long, with
    // no accepted fix in the span, means passive learning has effectively
    // turned off — the FT4 episode this generalizes decayed to confidence
    // 0.19 over roughly this long while the self-match guard rejected
    // every fix.
    uint64_t probe_turnoff_dwell_ns = 20'000'000'000ull;

    // Shared rate limit for both triggers: at most one probe every this
    // long, counted from the last ARMED request (not the last verdict).
    uint64_t probe_cooldown_ns = 120'000'000'000ull;
    // How long the shell pauses (then resumes) playback for the probe.
    // Field Test 2 measured single-fix recognition noise of ±100–150 ms —
    // 200 ms clears that noise floor, but only marginally; if a field pass
    // sees the verdict read as inconclusive/ambiguous more than rarely,
    // this is the value to widen upward rather than tightening
    // probe_verdict_min_fixes or the shift threshold that reads off it.
    int32_t probe_pause_ms = 200;
    // How many post-echo estimates the verdict averages over before
    // deciding.
    int probe_verdict_min_fixes = 2;
    // Window within which the verdict must resolve — measured from the
    // request if the shell never echoes (it couldn't execute: already
    // paused, mid-calibration) or from the echo if it lands but too few
    // fixes arrive before the window closes. Either way the cooldown still
    // applies from the original request time, so a silently-declined probe
    // can't be retried in a tight loop.
    uint64_t probe_verdict_window_ns = 20'000'000'000ull;
};

enum class ActionKind { kNone, kSeek, kTrackLost };

struct Action {
    ActionKind kind = ActionKind::kNone;
    int64_t seek_to_ms = 0;
};

class CorrectionPolicy {
public:
    explicit CorrectionPolicy(const PolicyConfig& cfg = {});

    // Decide on a fresh estimate. projected_local_ms comes from the
    // estimator's player-state projection at `now`.
    Action on_estimate(const Estimate& est, double projected_local_ms,
                       uint64_t now_ns);

    // Shell echo after executing a correction (sc_notify_seek_issued).
    void on_seek_issued(uint64_t now_ns);

    // True while measurements must be suppressed: between emitting a
    // correction and its ack (bounded by seek_ack_timeout), and for
    // settle_ns after the ack.
    bool is_settling(uint64_t now_ns) const;

    // Recognition cadence. on_fix_accepted schedules the next request;
    // fix_request_due returns true at most once per due time and re-arms a
    // retry window so unanswered requests repeat.
    void on_fix_accepted(uint64_t now_ns);
    bool fix_request_due(uint64_t now_ns);

    uint64_t current_fix_interval_ns() const { return fix_interval_ns_; }

    void set_command_latency_ms(double ms) { cfg_.command_latency_ms = ms; }
    double command_latency_ms() const { return cfg_.command_latency_ms; }
    void set_deadband_ms(double ms) { cfg_.deadband_ms = ms; }

    // tech-req §2.9 (CTL-01a): the worker reads this to fill
    // sc_evt_active_probe_t.pause_ms when dispatching SC_EVT_ACTIVE_PROBE.
    int32_t probe_pause_ms() const { return cfg_.probe_pause_ms; }

    // tech-req §2.9 (CTL-01a): referee sentinel. Called right where the
    // kSampleLatencyResidual handler fills sc_evt_latency_residual_t, right
    // after it dispatches SC_EVT_LATENCY_RESIDUAL — a pure additional
    // consumer of the same per-window result, never a second measurement.
    void on_referee_window(double lag_ms, bool valid, uint64_t now_ns);

    // Wittenmark turn-off trigger. Called from the worker's tick(), which
    // runs off capture-time progress independent of accepted fixes — the
    // only way to catch a filter that has stopped reaching on_estimate at
    // all. playback_live comes from the shell's last sc_player_state_t.
    void on_tick(const Estimate& est, bool playback_live, uint64_t now_ns);

    // Worker polls this once per tick(), mirroring fix_request_due's
    // one-shot pattern: true at most once per armed request. The worker
    // turns a true return into SC_EVT_ACTIVE_PROBE.
    bool probe_request_due(uint64_t now_ns);

    // Shell echo (sc_notify_probe_executed) after actually pausing and
    // resuming playback for the probe — mirrors on_seek_issued's role for
    // sc_notify_seek_issued. current_error_ms is the estimator's error at
    // the echo, snapshotted as the verdict's pre-probe baseline. A stray
    // echo with no outstanding request is safely ignored.
    void on_probe_executed(double current_error_ms, uint64_t now_ns);

    void reset();

private:
    // Fixed-size ring of recent converged-fix residuals backing the
    // persistence gate (tech-req §2.7). No heap allocation, no STL
    // container growth — the policy runs on the worker thread of a
    // realtime audio product.
    static constexpr std::size_t kRingCapacity = 8;
    struct RingSample {
        double error_ms = 0.0;
        uint64_t mono_ns = 0;
    };

    void ring_append(double error_ms, uint64_t mono_ns);
    void ring_clear();
    double ring_mean() const;
    uint64_t ring_oldest_ns() const;
    uint64_t ring_newest_ns() const;
    bool ring_all_agree(double mean, double tol) const;

    PolicyConfig cfg_;
    bool seek_pending_ack_ = false;
    uint64_t seek_emitted_ns_ = 0;
    uint64_t settle_until_ns_ = 0;
    uint64_t fix_interval_ns_ = 0;
    uint64_t next_request_ns_ = 0;  // 0 = not scheduled
    bool awaiting_verify_ = false;  // seek issued; next estimate calibrates
    double last_centering_ms_ = 0.0;

    RingSample ring_[kRingCapacity];
    std::size_t ring_count_ = 0;
    std::size_t ring_head_ = 0;  // index of the oldest sample

    // Pending large-correction record (tech-req §2.8 Part B, CTL-03b). A
    // sibling piece of state to the persistence ring above, but tracking a
    // single held estimate rather than a cluster — see policy.cpp for the
    // clearing rules (reset, any emitted seek, track-lost, expiry, and any
    // fresh estimate whose |error| drops back below the threshold).
    bool large_pending_ = false;
    double large_pending_error_ms_ = 0.0;
    uint64_t large_pending_ns_ = 0;

    // tech-req §2.9 (CTL-01a): referee sentinel ring. Small and fixed-size
    // for the same real-time-product reason as the persistence ring above.
    static constexpr std::size_t kRefereeRingCapacity = 8;
    struct RefereeSample {
        double lag_ms = 0.0;
        bool valid = false;
        uint64_t mono_ns = 0;
    };

    void referee_ring_append(double lag_ms, bool valid, uint64_t mono_ns);
    // True if some valid ringed lag has at least 3 valid entries (itself
    // included) within tol of it — "any 3 ringed lags mutually agree".
    bool referee_any_three_agree(double tol) const;
    // Shared rate-limit/gate check for both probe triggers. No-op if a
    // probe is already outstanding, settling, playback is paused, or the
    // cooldown since the last armed request hasn't elapsed.
    void try_request_probe(uint64_t now_ns);

    RefereeSample referee_ring_[kRefereeRingCapacity];
    std::size_t referee_ring_count_ = 0;
    std::size_t referee_ring_head_ = 0;
    uint64_t last_referee_agree_ns_ = 0;

    // Convergence as of the last Estimate seen via on_estimate/on_tick —
    // on_referee_window has no Estimate of its own to read it from.
    bool converged_seen_ = false;
    // Last playback-live state seen via on_tick (the shell's last
    // sc_player_state_t), consulted by try_request_probe regardless of
    // which trigger is arming the request.
    bool playback_live_ = true;

    // Wittenmark turn-off dwell tracking.
    bool turnoff_dwell_active_ = false;
    uint64_t turnoff_dwell_start_ns_ = 0;

    // Probe request/verdict state.
    bool probe_outstanding_ = false;      // armed request through verdict
                                           // resolution/expiry
    bool probe_pending_dispatch_ = false; // one-shot, consumed by
                                           // probe_request_due
    bool probe_ever_requested_ = false;   // first request skips the cooldown
    uint64_t last_probe_request_ns_ = 0;  // cooldown anchor
    uint64_t probe_request_ns_ = 0;       // never-echoed expiry anchor
    bool probe_verdict_pending_ = false;  // echo received; verdict running
    double probe_pre_error_ms_ = 0.0;
    uint64_t probe_verdict_start_ns_ = 0;
    int probe_verdict_fixes_seen_ = 0;
    double probe_verdict_shift_sum_ = 0.0;
};

}  // namespace synccore

#endif  // SYNCCORE_POLICY_H
