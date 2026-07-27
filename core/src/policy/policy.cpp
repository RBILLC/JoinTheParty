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

    // Adapt the recognition cadence to the state we're in.
    if (est.converged) {
        fix_interval_ns_ = cfg_.fix_interval_max_ns;
    } else if (std::abs(e) > cfg_.deadband_ms) {
        fix_interval_ns_ = cfg_.fix_interval_min_ns;
    } else {
        fix_interval_ns_ = cfg_.fix_interval_base_ns;
    }

    // A stale estimate is not evidence. Track-lost above still fires (a
    // wildly wrong error is worth acting on however we learned it), but a
    // micro-seek computed from a coasted state is a guess, and executing it
    // both moves audio the listener can hear and feeds the latency learner
    // a landing it cannot interpret.
    if (est.confidence < cfg_.min_confidence_to_correct) return action;

    // Correct when out of the deadband now, or predicted to leave it before
    // the next measurement (§6.3 skew pre-emption).
    const double horizon_s =
        static_cast<double>(fix_interval_ns_) / 1e9;
    const double predicted = e + drift_ms_per_s * horizon_s;
    if (std::abs(e) >= cfg_.deadband_ms ||
        std::abs(predicted) >= cfg_.deadband_ms) {
        // Aim so the post-seek error drifts through zero across the next
        // interval instead of starting at zero and drifting out.
        const double drift_centering = drift_ms_per_s * horizon_s / 2.0;
        action.kind = ActionKind::kSeek;
        action.seek_to_ms = static_cast<int64_t>(
            std::llround(projected_local_ms + cfg_.command_latency_ms - e -
                         drift_centering));
        seek_pending_ack_ = true;
        seek_emitted_ns_ = now_ns;
        awaiting_verify_ = true;
        last_centering_ms_ = drift_centering;
    }
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
