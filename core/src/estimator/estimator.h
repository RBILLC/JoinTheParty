// estimator.h — CORE-02: the §6.1 timing model + 2-state Kalman filter.
//
// Pure logic, no threads, no clocks: every input carries a monotonic
// timestamp, so tests drive simulated time directly. Owned and called only
// by the session worker thread.
//
// State: x = [ error_ms, drift_ms_per_s ]
//   error_ms       sync error, + = local ahead of external (spec §6.1)
//   drift_ms_per_s error growth rate (1 ms/s == 1000 ppm)
//
// Measurements:
//   - a recognition fix + the latest player state → scalar error observation
//   - a non-zero frequency skew on the fix → direct drift observation
//     (external running fast ⇒ error shrinks ⇒ d ≈ −skew · 1000 ms/s)
#ifndef SYNCCORE_ESTIMATOR_H
#define SYNCCORE_ESTIMATOR_H

#include <cstdint>

namespace synccore {

struct EstimatorConfig {
    double meas_noise_ms2 = 25.0;      // R at confidence 1.0 → 5 ms std
    double skew_noise_ms2_per_s2 = 1e-4;  // R for skew-derived drift → 10 ppm std
    double q_error_ms2_per_s = 1.0;    // process noise, error state
    double q_drift_per_s = 4e-5;       // process noise, drift state (ms/s)²/s
    double init_error_var_ms2 = 1e6;
    double init_drift_var = 1e-2;      // (0.1 ms/s)² = (100 ppm)²
    double seek_exec_var_ms2 = 2500.0;  // execution uncertainty added per seek
    double deadband_ms = 25.0;
    int convergence_fixes = 3;         // consecutive in-deadband fixes → converged
    double conf_var_scale_ms = 25.0;   // posterior-std scale in confidence
    double conf_age_tau_s = 45.0;      // fix-age decay in confidence
};

struct Estimate {
    bool valid = false;         // false until the first accepted fix
    double error_ms = 0.0;
    double drift_ppm = 0.0;
    float confidence = 0.0f;
    bool converged = false;
    uint64_t last_fix_mono_ns = 0;
};

class SyncEstimator {
public:
    explicit SyncEstimator(const EstimatorConfig& cfg = {});

    void set_output_latency_ms(double ms) { output_latency_ms_ = ms; }
    void set_nudge_ms(double ms) { nudge_ms_ = ms; }

    void on_player_state(int64_t position_ms, bool is_paused, uint64_t mono_ns);

    // Fuses one recognition fix. Returns false (no update) if no player state
    // has been seen yet or the fix is older than the last fused one.
    bool on_fix(int64_t match_offset_ms, uint64_t capture_mono_ns,
                double frequency_skew, float provider_confidence);

    // A local seek was issued toward target_ms: shift the error state by the
    // commanded jump (evaluated at the expected landing time, issue +
    // command latency) and widen its variance by the execution uncertainty.
    // Without this, the filter carries pre-seek error into post-seek fixes
    // and the control loop chases a phantom offset.
    void on_local_seek(int64_t target_ms, uint64_t issued_mono_ns,
                       double command_latency_ms);

    // Projects the state to `now` without mutating it.
    Estimate estimate_at(uint64_t now_ns) const;

    // Local playback position (reported-position timeline) projected to
    // `now`. Valid only after on_player_state. Used by the policy to turn an
    // error into an absolute seek target.
    double projected_local_ms(uint64_t now_ns) const;
    bool has_player_state() const { return has_player_; }

    void reset();

private:
    void predict_to(uint64_t t_ns);

    EstimatorConfig cfg_;
    double output_latency_ms_ = 0.0;
    double nudge_ms_ = 0.0;

    bool has_player_ = false;
    int64_t player_position_ms_ = 0;
    bool player_paused_ = false;
    uint64_t player_mono_ns_ = 0;

    bool valid_ = false;
    double e_ = 0.0;   // error_ms
    double d_ = 0.0;   // drift ms/s
    double p00_ = 0.0, p01_ = 0.0, p11_ = 0.0;  // covariance (symmetric)
    uint64_t state_mono_ns_ = 0;
    uint64_t last_fix_mono_ns_ = 0;
    int in_deadband_streak_ = 0;
    bool converged_ = false;
};

}  // namespace synccore

#endif  // SYNCCORE_ESTIMATOR_H
