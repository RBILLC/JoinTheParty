#include "estimator/hypothesis_bank.h"

#include <algorithm>
#include <cmath>

namespace synccore {

namespace {
constexpr double kNsPerSec = 1e9;
}  // namespace

HypothesisBank::HypothesisBank(const MhtConfig& cfg, const EstimatorConfig& est_cfg)
    : cfg_(cfg), est_cfg_(est_cfg), hyp_est_cfg_(est_cfg) {
    max_hyp_ = std::clamp(cfg_.mht_max_hypotheses, 1, kMhtMaxHypothesesCap);
    deadband_ms_ = est_cfg_.deadband_ms;

    // §2.16 Design/house rule: this bank owns admission via its own
    // per-hypothesis chi-square gate (Grinberg Eq. 3.2), which GENERALIZES
    // estimator.h's fixed outlier_gate_ms/outlier_gate_max_p00 pair. Give
    // every hypothesis's own SyncEstimator a config with that internal
    // fixed gate disabled so the two gates never fight -- verified against
    // estimator.cpp's on_fix: the gate only trips when
    // `p00_ < cfg_.outlier_gate_max_p00`, and p00_ is a variance (always
    // >= 0), so outlier_gate_max_p00 = 0 makes that comparison permanently
    // false and on_fix always falls through to the normal Kalman update.
    hyp_est_cfg_.outlier_gate_max_p00 = 0.0;

    for (int i = 0; i < kMhtMaxHypothesesCap; ++i) {
        hyps_[i].estimator = SyncEstimator(hyp_est_cfg_);
    }
}

void HypothesisBank::set_output_latency_ms(double ms) {
    output_latency_ms_ = ms;
    for (int i = 0; i < max_hyp_; ++i) {
        if (hyps_[i].active) hyps_[i].estimator.set_output_latency_ms(ms);
    }
}

void HypothesisBank::set_nudge_ms(double ms) {
    nudge_ms_ = ms;
    for (int i = 0; i < max_hyp_; ++i) {
        if (hyps_[i].active) hyps_[i].estimator.set_nudge_ms(ms);
    }
}

void HypothesisBank::set_deadband_ms(double ms) {
    deadband_ms_ = ms;
    for (int i = 0; i < max_hyp_; ++i) {
        if (hyps_[i].active) hyps_[i].estimator.set_deadband_ms(ms);
    }
}

void HypothesisBank::on_player_state(int64_t position_ms, bool is_paused,
                                     uint64_t mono_ns) {
    has_player_ = true;
    player_position_ms_ = position_ms;
    player_paused_ = is_paused;
    player_mono_ns_ = mono_ns;
    for (int i = 0; i < max_hyp_; ++i) {
        if (hyps_[i].active)
            hyps_[i].estimator.on_player_state(position_ms, is_paused, mono_ns);
    }
}

void HypothesisBank::on_local_seek(int64_t target_ms, uint64_t issued_mono_ns,
                                   double command_latency_ms) {
    for (int i = 0; i < max_hyp_; ++i) {
        Hypothesis& h = hyps_[i];
        if (!h.active) continue;
        h.estimator.on_local_seek(target_ms, issued_mono_ns, command_latency_ms);
        // Mirror the estimator's own predict-then-widen order (see
        // estimator.cpp's on_local_seek): project the gate-only sidecar to
        // the issue time, then inflate it by the same execution-
        // uncertainty term the real estimator's p00_ gets.
        predict_sidecar(h, issued_mono_ns);
        h.sidecar_p += est_cfg_.seek_exec_var_ms2;
    }
}

double HypothesisBank::projected_local_ms(uint64_t now_ns) const {
    if (!has_player_) return 0.0;
    if (player_paused_ || now_ns <= player_mono_ns_)
        return static_cast<double>(player_position_ms_);
    return static_cast<double>(player_position_ms_) +
           static_cast<double>(now_ns - player_mono_ns_) / 1e6;
}

void HypothesisBank::predict_sidecar(Hypothesis& h, uint64_t t_ns) const {
    if (t_ns <= h.sidecar_mono_ns) return;
    const double dt = static_cast<double>(t_ns - h.sidecar_mono_ns) / kNsPerSec;
    h.sidecar_p += est_cfg_.q_error_ms2_per_s * dt;
    h.sidecar_mono_ns = t_ns;
}

bool HypothesisBank::warranted(const BeatEstimate& beat, double comb_ratio) const {
    if (!cfg_.mht_enabled) return false;
    // lag_window.h's WindowLag::comb_ratio doc comment: 0 or negative
    // means "no meaningful competitor," not a claim of a real, tiny ratio
    // -- must never warrant.
    if (comb_ratio <= 0.0) return false;
    if (comb_ratio > cfg_.mht_warrant_comb_ratio_max) return false;
    if (beat.period_ms <= 0.0) return false;
    if (cfg_.mht_warrant_requires_stable_beat && !beat.stable) return false;
    return true;
}

double HypothesisBank::effective_existence(const Hypothesis& h, uint64_t now_ns) const {
    double age_s = 0.0;
    if (now_ns > h.last_admit_mono_ns) {
        age_s = static_cast<double>(now_ns - h.last_admit_mono_ns) / kNsPerSec;
    }
    const double decay = std::exp(-age_s / cfg_.mht_existence_age_tau_s);
    return h.existence * decay;
}

int HypothesisBank::find_seed_slot(uint64_t now_ns) {
    for (int i = 0; i < max_hyp_; ++i) {
        if (!hyps_[i].active) return i;
    }
    // Bank full: evict the lowest-existence hypothesis first (§2.16
    // verbatim), using the age-decayed effective existence so an
    // old-but-never-corroborated-since hypothesis is correctly identified
    // as the weakest even if its raw stored existence hasn't been touched
    // in a while.
    //
    // DESIGN CHOICE (deliberate refinement of §2.16's wording, not silent):
    // a victim is only evicted if its effective existence is STRICTLY
    // BELOW the incoming seed's birth existence (mht_existence_birth).
    // Taken fully literally, "evict the lowest-existence hypothesis" would
    // let a multi-candidate seeding pass evict its OWN just-seeded
    // siblings: every same-pass seed shares existence == birth and the
    // same last_admit time, so a plain strict-min scan ties and keeps
    // picking slot 0 -- on a first warranted fix into an empty bank with
    // mht_max_hypotheses=4, the k=0/+1/-1/+2/-2/+3/-3 seeding order fills
    // the bank at +2, then -2 evicts k=0 (the fix's own, single most
    // plausible offset), +3 evicts -2, -3 evicts +3: the bank ends up
    // missing k=0 and having churned through two far teeth in one pass.
    // The birth-floor comparison below fixes this two ways:
    //   (a) same-pass siblings (all sitting exactly at birth) can never
    //       evict each other -- v < birth is false when v == birth.
    //   (b) a bank already full of CORROBORATED hypotheses (existence
    //       risen above birth from admitted fixes) is not displaced by
    //       fresh, unproven teeth from a new warranted fix either -- if
    //       the room truly moved, the old hypotheses stop corroborating,
    //       their existence decays below birth, and only then do they
    //       become evictable. When no victim qualifies, seeding for this
    //       candidate stops (find_seed_slot returns -1); because the
    //       candidate order is already k=0 outward, the bank keeps the
    //       most plausible hypotheses it already has.
    int worst = -1;
    double worst_val = cfg_.mht_existence_birth;
    for (int i = 0; i < max_hyp_; ++i) {
        const double v = effective_existence(hyps_[i], now_ns);
        if (v < worst_val) {
            worst_val = v;
            worst = i;
        }
    }
    if (worst < 0) return -1;  // no qualifying victim -- do not seed.
    hyps_[worst].active = false;
    --active_count_;
    return worst;
}

void HypothesisBank::seed_one(int64_t offset_ms, uint64_t capture_mono_ns,
                              double frequency_skew, float provider_confidence) {
    const int slot = find_seed_slot(capture_mono_ns);
    if (slot < 0) return;  // bank full, no qualifying victim -- see the
                            // DESIGN CHOICE note on find_seed_slot.
    Hypothesis& h = hyps_[slot];

    // Each hypothesis is one SyncEstimator instance reusing estimator.h
    // verbatim (§2.16 Design) -- fresh state per seed, not a mutated
    // survivor from whatever occupied this slot before.
    h.estimator = SyncEstimator(hyp_est_cfg_);
    h.estimator.set_output_latency_ms(output_latency_ms_);
    h.estimator.set_nudge_ms(nudge_ms_);
    h.estimator.set_deadband_ms(deadband_ms_);
    if (has_player_) {
        h.estimator.on_player_state(player_position_ms_, player_paused_,
                                    player_mono_ns_);
    }
    h.estimator.on_fix(offset_ms, capture_mono_ns, frequency_skew,
                       provider_confidence);

    const float conf = std::clamp(provider_confidence, 0.05f, 1.0f);
    // Post-single-fix posterior variance collapses toward R when the
    // prior (init_error_var_ms2) is huge -- Kalman gain approx 1 -- so
    // seed the gate-only sidecar directly at R rather than re-deriving
    // the full 2-state collapse this sidecar deliberately doesn't track.
    h.sidecar_p = est_cfg_.meas_noise_ms2 / static_cast<double>(conf * conf);
    h.sidecar_mono_ns = capture_mono_ns;

    h.existence = cfg_.mht_existence_birth;
    h.last_admit_mono_ns = capture_mono_ns;
    h.active = true;
    ++active_count_;
}

void HypothesisBank::on_fix(int64_t match_offset_ms, uint64_t capture_mono_ns,
                            double frequency_skew, float provider_confidence,
                            const BeatEstimate& beat, double comb_ratio) {
    // Ships default-OFF (§2.16's corpus gate). A disabled bank must be a
    // true no-op, not merely "never seeds."
    if (!cfg_.mht_enabled) return;

    const double local_audible = projected_local_ms(capture_mono_ns) - output_latency_ms_;
    const float conf = std::clamp(provider_confidence, 0.05f, 1.0f);
    const double r = est_cfg_.meas_noise_ms2 / static_cast<double>(conf * conf);

    // --- Admission pass: offer this fix to every LIVE hypothesis through
    // its own chi-square gate, regardless of whether this fix warrants
    // seeding a new one (§2.16: "warrant governs SEEDING only").
    const double z = local_audible - static_cast<double>(match_offset_ms) - nudge_ms_;
    for (int i = 0; i < max_hyp_; ++i) {
        Hypothesis& h = hyps_[i];
        if (!h.active) continue;

        predict_sidecar(h, capture_mono_ns);
        const double s = h.sidecar_p + r;
        const double innov = z - h.estimator.estimate_at(capture_mono_ns).error_ms;
        const double chi2 = (s > 0.0) ? (innov * innov) / s : 0.0;

        if (chi2 <= cfg_.mht_chi2_gate_1dof) {
            if (h.estimator.on_fix(match_offset_ms, capture_mono_ns, frequency_skew,
                                   provider_confidence)) {
                // Admitted: Kalman-shaped shrink of the gate-only sidecar,
                // and a saturating existence rise (IPDA corroboration).
                h.sidecar_p = h.sidecar_p * r / s;
                h.existence = std::clamp(
                    h.existence + cfg_.mht_existence_gain_admit * (1.0 - h.existence),
                    0.0, 1.0);
                h.last_admit_mono_ns = capture_mono_ns;
            }
            // Else: the estimator's own guard rejected it (e.g. stale
            // timestamp) -- neither corroboration nor non-corroboration,
            // no existence change.
        } else {
            // Gated out: non-corroboration event, immediate penalty. The
            // continuous age-decay half of §2.16's "decays ... with age"
            // is applied lazily at query time (effective_existence), not
            // here.
            h.existence *= cfg_.mht_existence_gate_miss_decay;
        }
    }

    // --- Prune pass: drop hypotheses whose age-decayed existence has
    // fallen below the floor (IPDA prune rule, §2.16).
    for (int i = 0; i < max_hyp_; ++i) {
        Hypothesis& h = hyps_[i];
        if (!h.active) continue;
        if (effective_existence(h, capture_mono_ns) < cfg_.mht_existence_prune_floor) {
            h.active = false;
            --active_count_;
        }
    }

    // --- Seeding pass: only on a warranted fix (§2.16 Design paragraph).
    if (!warranted(beat, comb_ratio)) return;

    // Never seed a candidate that duplicates a live hypothesis: reuses the
    // same z/innovation shape as the admission gate above, just against a
    // candidate offset instead of the fix's own, and compared to a fixed
    // ms tolerance instead of the chi-square gate (an "already basically
    // the same hypothesis" check, not a statistical admission test).
    auto is_duplicate = [&](int64_t candidate_ms) {
        const double zc = local_audible - static_cast<double>(candidate_ms) - nudge_ms_;
        for (int i = 0; i < max_hyp_; ++i) {
            if (!hyps_[i].active) continue;
            const double innov =
                zc - hyps_[i].estimator.estimate_at(capture_mono_ns).error_ms;
            if (std::abs(innov) < cfg_.mht_dedup_agree_ms) return true;
        }
        return false;
    };

    const int k_max = std::max(0, cfg_.mht_seed_k_max);

    // k = 0: the fix's own offset.
    if (!is_duplicate(match_offset_ms)) {
        seed_one(match_offset_ms, capture_mono_ns, frequency_skew, provider_confidence);
    }
    // k = 1..mht_seed_k_max: fix_offset +/- k * beat.period_ms.
    for (int k = 1; k <= k_max; ++k) {
        const int64_t shift =
            static_cast<int64_t>(std::llround(static_cast<double>(k) * beat.period_ms));
        const int64_t up = match_offset_ms + shift;
        const int64_t down = match_offset_ms - shift;
        if (!is_duplicate(up)) {
            seed_one(up, capture_mono_ns, frequency_skew, provider_confidence);
        }
        if (!is_duplicate(down)) {
            seed_one(down, capture_mono_ns, frequency_skew, provider_confidence);
        }
    }
}

bool HypothesisBank::active() const { return active_count_ > 0; }

int HypothesisBank::active_count() const { return active_count_; }

DominantHypothesis HypothesisBank::dominant_at(uint64_t now_ns) const {
    DominantHypothesis out;
    int best = -1;
    double best_val = -1.0;
    for (int i = 0; i < max_hyp_; ++i) {
        if (!hyps_[i].active) continue;
        const double v = effective_existence(hyps_[i], now_ns);
        if (v > best_val) {
            best_val = v;
            best = i;
        }
    }
    if (best < 0) return out;

    out.existence = best_val;
    out.estimate = hyps_[best].estimator.estimate_at(now_ns);
    // out.estimate.valid can be false even for the winning hypothesis: if
    // it was ever seeded before the bank had player state, seed_one's
    // on_fix call returns false without updating state (estimator.cpp's
    // own has_player_ guard, on_fix's first line), so that estimator stays
    // permanently invalid while its existence can still rise on later
    // admits. Never report valid=true over an invalid estimate -- that
    // would hand the policy an actuation off garbage (error_ms defaults
    // to 0, not a real reading).
    out.valid = best_val >= cfg_.mht_existence_actuate_threshold && out.estimate.valid;
    return out;
}

void HypothesisBank::reset() {
    for (int i = 0; i < kMhtMaxHypothesesCap; ++i) {
        Hypothesis& h = hyps_[i];
        h.active = false;
        h.estimator = SyncEstimator(hyp_est_cfg_);
        h.existence = 0.0;
        h.sidecar_p = 0.0;
        h.sidecar_mono_ns = 0;
        h.last_admit_mono_ns = 0;
    }
    active_count_ = 0;

    // Epoch rule mirrors SyncEstimator::reset(): player state is dropped,
    // but forwarded configuration (output_latency_ms_/nudge_ms_/
    // deadband_ms_) is not -- estimator.cpp's own reset() leaves
    // output_latency_ms_/nudge_ms_/cfg_.deadband_ms untouched too.
    has_player_ = false;
    player_position_ms_ = 0;
    player_paused_ = false;
    player_mono_ns_ = 0;
}

}  // namespace synccore
