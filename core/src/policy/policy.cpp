#include "policy/policy.h"

#include <algorithm>
#include <cmath>

namespace synccore {

CorrectionPolicy::CorrectionPolicy(const PolicyConfig& cfg) : cfg_(cfg) {
    reset();
}

void CorrectionPolicy::reset() {
    seek_pending_ack_ = false;
    seek_emitted_ns_ = 0;
    settle_until_ns_ = 0;
    fix_interval_ns_ = cfg_.fix_interval_base_ns;
    next_request_ns_ = 0;
    awaiting_verify_ = false;
    last_centering_ms_ = 0.0;
    ring_clear();
    large_pending_ = false;
    large_pending_error_ms_ = 0.0;
    large_pending_ns_ = 0;
}

void CorrectionPolicy::ring_append(double error_ms, uint64_t mono_ns) {
    const std::size_t idx = (ring_head_ + ring_count_) % kRingCapacity;
    ring_[idx] = {error_ms, mono_ns};
    if (ring_count_ < kRingCapacity) {
        ++ring_count_;
    } else {
        ring_head_ = (ring_head_ + 1) % kRingCapacity;
    }
}

void CorrectionPolicy::ring_clear() {
    ring_count_ = 0;
    ring_head_ = 0;
}

double CorrectionPolicy::ring_mean() const {
    if (ring_count_ == 0) return 0.0;
    double sum = 0.0;
    for (std::size_t i = 0; i < ring_count_; ++i)
        sum += ring_[(ring_head_ + i) % kRingCapacity].error_ms;
    return sum / static_cast<double>(ring_count_);
}

uint64_t CorrectionPolicy::ring_oldest_ns() const {
    return ring_count_ ? ring_[ring_head_].mono_ns : 0;
}

uint64_t CorrectionPolicy::ring_newest_ns() const {
    if (ring_count_ == 0) return 0;
    return ring_[(ring_head_ + ring_count_ - 1) % kRingCapacity].mono_ns;
}

bool CorrectionPolicy::ring_all_agree(double mean, double tol) const {
    for (std::size_t i = 0; i < ring_count_; ++i) {
        if (std::abs(ring_[(ring_head_ + i) % kRingCapacity].error_ms - mean) >
            tol)
            return false;
    }
    return true;
}

bool CorrectionPolicy::is_settling(uint64_t now_ns) const {
    if (seek_pending_ack_ &&
        now_ns < seek_emitted_ns_ + cfg_.seek_ack_timeout_ns)
        return true;
    return now_ns < settle_until_ns_;
}

void CorrectionPolicy::on_seek_issued(uint64_t now_ns) {
    seek_pending_ack_ = false;
    settle_until_ns_ = now_ns + cfg_.settle_ns;
    // Verify the landing shortly after the settle window ends.
    next_request_ns_ = settle_until_ns_ + cfg_.post_settle_verify_ns;
}

Action CorrectionPolicy::on_estimate(const Estimate& est,
                                     double projected_local_ms,
                                     uint64_t now_ns) {
    Action action;
    if (!est.valid || is_settling(now_ns)) return action;

    const double e = est.error_ms;
    const double drift_ms_per_s = est.drift_ppm * 1e-3;

    // Command-latency learning: the first estimate after a seek settles
    // should read ≈ the centering aim (plus drift since landing). Any excess
    // is the landing bias — actual minus assumed command latency.
    if (awaiting_verify_) {
        awaiting_verify_ = false;
        const double since_issue_s =
            static_cast<double>(now_ns - seek_emitted_ns_) / 1e9;
        const double since_landing_s =
            std::max(0.0, since_issue_s - cfg_.command_latency_ms / 1000.0);
        const double expected =
            -last_centering_ms_ + drift_ms_per_s * since_landing_s;
        const double latency_innovation = expected - e;
        if (std::abs(latency_innovation) <= cfg_.latency_adapt_clamp_ms) {
            cfg_.command_latency_ms =
                std::clamp(cfg_.command_latency_ms +
                               cfg_.latency_adapt_gain * latency_innovation,
                           cfg_.command_latency_min_ms,
                           cfg_.command_latency_max_ms);
        }
    }

    if (std::abs(e) >= cfg_.lost_threshold_ms) {
        action.kind = ActionKind::kTrackLost;
        reset();
        return action;
    }

    // CTL-02 (tech-req §2.7) persistence ring: only genuinely converged,
    // fresh fix evidence accumulates into the cluster. Losing convergence —
    // even briefly — invalidates the cluster's premise, so a non-converged
    // estimate clears it outright rather than pausing it.
    if (est.converged) {
        ring_append(e, now_ns);
    } else {
        ring_clear();
    }

    // Adapt the recognition cadence to the state we're in. A live,
    // above-floor persistence cluster is corroboration-hungry: it drops to
    // the base interval (same constant already used for the non-converged
    // case) instead of stretching to max, so confirm_min_fixes corroborating
    // samples arrive faster.
    if (est.converged) {
        fix_interval_ns_ = (ring_count_ >= 1 &&
                             std::abs(ring_mean()) > cfg_.confirm_floor_ms)
                                ? cfg_.fix_interval_base_ns
                                : cfg_.fix_interval_max_ns;
    } else if (std::abs(e) > cfg_.deadband_ms) {
        fix_interval_ns_ = cfg_.fix_interval_min_ns;
    } else {
        fix_interval_ns_ = cfg_.fix_interval_base_ns;
    }

    // CTL-03b (tech-req §2.8): a live pending large-correction record is
    // corroboration-hungry in exactly the same sense as CTL-02's cluster —
    // override to the minimum interval so the confirming (or replacing) fix
    // arrives fast. Re-applied at the end of this function too, since the
    // large-correction branch below may (re)arm or clear the record for
    // this very call.
    if (large_pending_) fix_interval_ns_ = cfg_.fix_interval_min_ns;

    // A stale estimate is not evidence. Track-lost above still fires (a
    // wildly wrong error is worth acting on however we learned it), but a
    // micro-seek computed from a coasted state is a guess, and executing it
    // both moves audio the listener can hear and feeds the latency learner
    // a landing it cannot interpret.
    if (est.confidence < cfg_.min_confidence_to_correct) return action;

    // Correct when out of the deadband now, or predicted to leave it before
    // the next measurement (§6.3 skew pre-emption). Aim so the post-seek
    // error drifts through zero across the next interval instead of
    // starting at zero and drifting out.
    const double horizon_s =
        static_cast<double>(fix_interval_ns_) / 1e9;
    const double predicted = e + drift_ms_per_s * horizon_s;
    const double drift_centering = drift_ms_per_s * horizon_s / 2.0;

    // CTL-03b (tech-req §2.8): a fresh estimate back under the large-
    // correction threshold invalidates any stale pending record outright —
    // independent of whether this estimate itself crosses the (possibly
    // widened) instantaneous deadband below. The skew-preemption arm
    // (|predicted| ≥ deadband with a small |e|) can therefore never reach
    // the hold: |e| < threshold clears the record here and falls through
    // to the ordinary instantaneous path unchanged.
    if (std::abs(e) < cfg_.large_correction_threshold_ms) {
        large_pending_ = false;
    }

    if (std::abs(e) >= cfg_.deadband_ms ||
        std::abs(predicted) >= cfg_.deadband_ms) {
        if (std::abs(e) >= cfg_.large_correction_threshold_ms) {
            // Held instead of fired: FT8's own 1259 ms overshoot landed off
            // one conf-0.74 fix because the estimator's outlier gate is
            // inactive at mid-uncertainty. Expire a stale record first —
            // corroboration must be genuinely fresh evidence.
            if (large_pending_ &&
                now_ns - large_pending_ns_ >= cfg_.large_pending_max_age_ns) {
                large_pending_ = false;
            }
            if (large_pending_ &&
                std::abs(e - large_pending_error_ms_) <=
                    cfg_.large_corroborate_agree_ms) {
                // Corroborated: fire from the FRESH error, not the stale
                // pending one, through the existing target formula.
                action.kind = ActionKind::kSeek;
                action.seek_to_ms = static_cast<int64_t>(std::llround(
                    projected_local_ms + cfg_.command_latency_ms - e -
                    drift_centering));
                seek_pending_ack_ = true;
                seek_emitted_ns_ = now_ns;
                awaiting_verify_ = true;
                last_centering_ms_ = drift_centering;
                ring_clear();
                large_pending_ = false;
            } else {
                // No live, agreeing record: store/replace and hold — a
                // disagreeing large error restarts the record rather than
                // accumulating it.
                large_pending_ = true;
                large_pending_error_ms_ = e;
                large_pending_ns_ = now_ns;
            }
        } else {
            action.kind = ActionKind::kSeek;
            action.seek_to_ms = static_cast<int64_t>(
                std::llround(projected_local_ms + cfg_.command_latency_ms - e -
                             drift_centering));
            seek_pending_ack_ = true;
            seek_emitted_ns_ = now_ns;
            awaiting_verify_ = true;
            last_centering_ms_ = drift_centering;
            ring_clear();  // a correction changes the operating point
        }
    } else if (est.converged &&
               ring_count_ >= static_cast<std::size_t>(cfg_.confirm_min_fixes) &&
               (ring_newest_ns() - ring_oldest_ns()) >= cfg_.confirm_window_ns &&
               ring_all_agree(ring_mean(), cfg_.confirm_agree_ms) &&
               std::abs(ring_mean()) > cfg_.confirm_floor_ms &&
               std::abs(ring_mean()) < cfg_.lost_threshold_ms) {
        // CTL-02 persistence trigger: the instantaneous path above keeps
        // precedence (checked first, unfired here) — this is the second,
        // slower gate for a stable, corroborated residual a widened
        // deadband_ms would otherwise hold forever. Corrects from the
        // cluster mean, not the instantaneous error.
        const double mean = ring_mean();
        action.kind = ActionKind::kSeek;
        action.seek_to_ms = static_cast<int64_t>(std::llround(
            projected_local_ms + cfg_.command_latency_ms - mean -
            drift_centering));
        seek_pending_ack_ = true;
        seek_emitted_ns_ = now_ns;
        awaiting_verify_ = true;
        last_centering_ms_ = drift_centering;
        ring_clear();
    }

    // Re-apply: this call's large-correction branch above may have just
    // (re)armed, corroborated-and-cleared, or expired the pending record.
    if (large_pending_) fix_interval_ns_ = cfg_.fix_interval_min_ns;
    return action;
}

void CorrectionPolicy::on_fix_accepted(uint64_t now_ns) {
    next_request_ns_ = now_ns + fix_interval_ns_;
}

bool CorrectionPolicy::fix_request_due(uint64_t now_ns) {
    if (next_request_ns_ == 0 || now_ns < next_request_ns_) return false;
    if (is_settling(now_ns)) return false;
    next_request_ns_ = now_ns + cfg_.request_retry_ns;
    return true;
}

}  // namespace synccore
