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
#ifndef SYNCCORE_POLICY_H
#define SYNCCORE_POLICY_H

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
    uint64_t settle_ns = 3'000'000'000ull;
    uint64_t seek_ack_timeout_ns = 5'000'000'000ull;
    uint64_t fix_interval_min_ns = 8'000'000'000ull;
    uint64_t fix_interval_base_ns = 10'000'000'000ull;
    uint64_t fix_interval_max_ns = 30'000'000'000ull;
    uint64_t post_settle_verify_ns = 500'000'000ull;  // fix 0.5 s after settle
    uint64_t request_retry_ns = 5'000'000'000ull;     // re-request if unanswered
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
    PolicyConfig cfg_;
    bool seek_pending_ack_ = false;
    uint64_t seek_emitted_ns_ = 0;
    uint64_t settle_until_ns_ = 0;
    uint64_t fix_interval_ns_ = 0;
    uint64_t next_request_ns_ = 0;  // 0 = not scheduled
    bool awaiting_verify_ = false;  // seek issued; next estimate calibrates
    double last_centering_ms_ = 0.0;
};

}  // namespace synccore

#endif  // SYNCCORE_POLICY_H
