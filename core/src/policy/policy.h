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
};

}  // namespace synccore

#endif  // SYNCCORE_POLICY_H
