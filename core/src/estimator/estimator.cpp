#include "estimator/estimator.h"

#include <algorithm>
#include <cmath>

namespace synccore {

namespace {
constexpr double kNsPerSec = 1e9;
constexpr double kMsPerSecPerPpm = 1e-3;  // 1 ppm == 0.001 ms/s
}  // namespace

SyncEstimator::SyncEstimator(const EstimatorConfig& cfg) : cfg_(cfg) { reset(); }

void SyncEstimator::reset() {
    valid_ = false;
    e_ = 0.0;
    d_ = 0.0;
    p00_ = cfg_.init_error_var_ms2;
    p01_ = 0.0;
    p11_ = cfg_.init_drift_var;
    state_mono_ns_ = 0;
    last_fix_mono_ns_ = 0;
    in_deadband_streak_ = 0;
    converged_ = false;
    has_player_ = false;
    outlier_pending_ = false;
}

void SyncEstimator::on_player_state(int64_t position_ms, bool is_paused,
                                    uint64_t mono_ns) {
    has_player_ = true;
    player_position_ms_ = position_ms;
    player_paused_ = is_paused;
    player_mono_ns_ = mono_ns;
}

double SyncEstimator::projected_local_ms(uint64_t now_ns) const {
    if (!has_player_) return 0.0;
    if (player_paused_ || now_ns <= player_mono_ns_)
        return static_cast<double>(player_position_ms_);
    return static_cast<double>(player_position_ms_) +
           static_cast<double>(now_ns - player_mono_ns_) / 1e6;
}

void SyncEstimator::predict_to(uint64_t t_ns) {
    if (t_ns <= state_mono_ns_) return;
    const double dt = static_cast<double>(t_ns - state_mono_ns_) / kNsPerSec;
    // x ← F·x with F = [1 dt; 0 1];  P ← F·P·Fᵀ + Q·dt
    e_ += d_ * dt;
    const double p00 = p00_ + dt * (2.0 * p01_ + dt * p11_);
    const double p01 = p01_ + dt * p11_;
    p00_ = p00 + cfg_.q_error_ms2_per_s * dt;
    p01_ = p01;
    p11_ += cfg_.q_drift_per_s * dt;
    state_mono_ns_ = t_ns;
}

bool SyncEstimator::on_fix(int64_t match_offset_ms, uint64_t capture_mono_ns,
                           double frequency_skew, float provider_confidence) {
    if (!has_player_) return false;
    if (valid_ && capture_mono_ns <= last_fix_mono_ns_) return false;

    // §6.1: sync_error = local_audible − external, with the nudge shifting
    // the setpoint (the wheel re-aims the target, ui-ux §6.2).
    const double local_audible =
        projected_local_ms(capture_mono_ns) - output_latency_ms_;
    const double z =
        local_audible - static_cast<double>(match_offset_ms) - nudge_ms_;

    if (!valid_) {
        state_mono_ns_ = capture_mono_ns;
        valid_ = true;
    }
    predict_to(capture_mono_ns);

    // Innovation gate (audit §4.6): a confident filter refuses one wild
    // fix; two in a row are believed (real jumps repeat, noise doesn't).
    if (p00_ < cfg_.outlier_gate_max_p00 &&
        std::abs(z - e_) > cfg_.outlier_gate_ms) {
        if (!outlier_pending_) {
            outlier_pending_ = true;
            return false;
        }
        // Second consecutive outlier: fall through and accept.
    }
    outlier_pending_ = false;

    // Scalar update, H = [1 0].
    const float conf = std::clamp(provider_confidence, 0.05f, 1.0f);
    const double r = cfg_.meas_noise_ms2 / static_cast<double>(conf * conf);
    {
        const double s = p00_ + r;
        const double k0 = p00_ / s;
        const double k1 = p01_ / s;
        const double innov = z - e_;
        e_ += k0 * innov;
        d_ += k1 * innov;
        const double p00 = (1.0 - k0) * p00_;
        const double p01 = (1.0 - k0) * p01_;
        const double p11 = p11_ - k1 * p01_;
        p00_ = p00;
        p01_ = p01;
        p11_ = p11;
    }

    // Skew-derived drift observation, H = [0 1].
    if (frequency_skew != 0.0) {
        const double zd = -frequency_skew * 1000.0;  // fraction → ms/s
        const double s = p11_ + cfg_.skew_noise_ms2_per_s2;
        const double k0 = p01_ / s;
        const double k1 = p11_ / s;
        const double innov = zd - d_;
        e_ += k0 * innov;
        d_ += k1 * innov;
        const double p00 = p00_ - k0 * p01_;
        const double p01 = (1.0 - k1) * p01_;
        const double p11 = (1.0 - k1) * p11_;
        p00_ = p00;
        p01_ = p01;
        p11_ = p11;
    }

    d_ = std::clamp(d_, -cfg_.drift_clamp_ms_per_s, cfg_.drift_clamp_ms_per_s);

    last_fix_mono_ns_ = capture_mono_ns;

    if (std::abs(e_) <= cfg_.deadband_ms) {
        in_deadband_streak_ = std::min(in_deadband_streak_ + 1, 1000);
    } else {
        in_deadband_streak_ = 0;
    }
    converged_ = in_deadband_streak_ >= cfg_.convergence_fixes;
    return true;
}

void SyncEstimator::on_local_seek(int64_t target_ms, uint64_t issued_mono_ns,
                                  double command_latency_ms) {
    if (!valid_ || !has_player_) return;
    predict_to(issued_mono_ns);
    const uint64_t landing_ns =
        issued_mono_ns +
        static_cast<uint64_t>(command_latency_ms > 0.0 ? command_latency_ms * 1e6
                                                       : 0.0);
    const double delta =
        static_cast<double>(target_ms) - projected_local_ms(landing_ns);
    e_ += delta;
    p00_ += cfg_.seek_exec_var_ms2;
    if (std::abs(e_) > cfg_.deadband_ms) {
        in_deadband_streak_ = 0;
        converged_ = false;
    }
}

Estimate SyncEstimator::estimate_at(uint64_t now_ns) const {
    Estimate est;
    if (!valid_) return est;

    double e = e_;
    double p00 = p00_;
    double age_s = 0.0;
    if (now_ns > state_mono_ns_) {
        const double dt = static_cast<double>(now_ns - state_mono_ns_) / kNsPerSec;
        e += d_ * dt;
        p00 += dt * (2.0 * p01_ + dt * p11_) + cfg_.q_error_ms2_per_s * dt;
        age_s = static_cast<double>(now_ns - last_fix_mono_ns_) / kNsPerSec;
    }

    est.valid = true;
    est.error_ms = e;
    est.drift_ppm = d_ / kMsPerSecPerPpm;
    const double conf_var = 1.0 / (1.0 + std::sqrt(std::max(p00, 0.0)) /
                                             cfg_.conf_var_scale_ms);
    const double conf_age = std::exp(-age_s / cfg_.conf_age_tau_s);
    est.confidence = static_cast<float>(std::clamp(conf_var * conf_age, 0.0, 1.0));
    est.converged = converged_ && std::abs(e) <= cfg_.deadband_ms;
    est.last_fix_mono_ns = last_fix_mono_ns_;
    return est;
}

}  // namespace synccore
