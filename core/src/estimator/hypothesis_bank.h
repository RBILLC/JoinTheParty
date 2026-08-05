// hypothesis_bank.h -- MHT-01: the §2.16 Multi-Hypothesis Tracking bank.
//
// Pure logic, no threads, no clocks: every input carries a monotonic
// timestamp, mirroring estimator.h's own contract (tests drive simulated
// time directly). Owned and called only by the session worker thread, the
// same home as estimator.h and oss_ring.h.
//
// PURPOSE (tech-req §2.16). Repetitive material (Billie Jean-class) can
// produce several structurally-plausible offset candidates spaced at
// beat-period multiples -- both ACRCloud's fingerprint match and the
// mic's own autocorrelation lag estimate can land on the wrong tooth of
// that comb. FT9's evidence: Billie Jean matched >=6 distinct Spotify
// catalog editions across ~134 phase transitions, and the mic's own
// lag_ms alternated between a low mode (40-64 ms) and near-integer
// multiples of beat_period_ms (~512-516 ms), with comb_ratio mostly
// 1.0-1.7 -- ambiguous by lag_window.h's own comb_ratio construction. This
// bank tracks several parallel offset hypotheses instead of committing to
// one, and actuates only once a single hypothesis's IPDA-style existence
// probability clears a threshold.
//
// HARD LIMIT, restated verbatim from §2.16 (do not relax, do not grow a
// second path around it): this bank NEVER touches self-match. Every fix
// passed to on_fix() must already have cleared the caller's §7.3
// self-match guard -- this module holds NO self-match logic of any kind,
// and must never be given one. research-closed-loop-control.md §2(iii) is
// explicit that PDA/IPDA's clutter model assumes independent,
// identically-distributed clutter; self-match "clutter" is neither (it is
// self-correlated, caused by our own last seek, and anomalously *cleaner*
// than genuine fixes) -- routing it through this bank "would not merely be
// unhelpful, it would be actively wrong," because the anomalously clean
// self-match candidate would win the existence-probability competition
// precisely because it looks best. §7.3/§2.9's guard and probe own
// self-match exclusively.
//
// SCHEDULE. §2.16 is "Design only... explicitly not scheduled" pending its
// own corpus gate; this bank ships with mht_enabled = false by default
// (MhtConfig) and every entry point is a true no-op while disabled -- no
// on-device default change is authorized by this file alone.
//
// DESIGN CHOICES made by this implementation (not fully pinned by §2.16,
// documented here per its own "provisional, Epic 10" framing):
//   - Variance-for-gating: estimator.h/.cpp stay byte-untouched (its
//     posterior covariance is private, and §2.16 lists the estimator
//     "Unchanged"). Each hypothesis instead carries a small sidecar
//     1-state variance recursion (P += q*dt; S = P + R; on admit
//     P <- P*R/S; seek handling widens by seek_exec_var_ms2), reusing
//     EstimatorConfig's own q_error_ms2_per_s/meas_noise_ms2/
//     seek_exec_var_ms2 constants. This sidecar is GATE-ONLY -- it never
//     feeds state estimation, which stays entirely SyncEstimator's.
//   - Admission gate: a 1-dof Mahalanobis chi-square test on the
//     innovation (Grinberg §3.2 Eq. 3.2), sized to each hypothesis's own
//     sidecar variance -- this generalizes estimator.h's fixed
//     outlier_gate_ms/outlier_gate_max_p00 pair from an implicit
//     two-hypothesis gate into a principled, per-hypothesis, N-hypothesis
//     one. Each hypothesis's own SyncEstimator is constructed with its
//     internal fixed outlier gate DISABLED (outlier_gate_max_p00 = 0 --
//     see hypothesis_bank.cpp's constructor comment for why this reliably
//     disables it) so the two gates never fight; this bank's chi-square
//     gate owns admission exclusively.
//   - Existence dynamics (IPDA, Musicki & Evans via Grinberg §4): a
//     scalar per hypothesis that rises (saturating) on each admitted fix,
//     takes an immediate multiplicative penalty when a fix is offered but
//     gated out (non-corroboration), and separately decays continuously
//     with age since its last admitted fix -- reusing estimator.h's own
//     conf_age_tau_s decay idiom verbatim, applied per-hypothesis. Never
//     blended across hypotheses (PDA's soft-blend, Eq. 3.6, is explicitly
//     rejected by §2.16): the policy actuates on the single dominant
//     hypothesis only.
//   - Eviction floor (deliberate refinement of §2.16's "evicting the
//     lowest-existence hypothesis first" wording): a full bank only gives
//     up a slot to a fresh candidate if the weakest occupant's effective
//     existence is STRICTLY BELOW mht_existence_birth. Read fully
//     literally, "evict the lowest" has no floor -- on a single warranted
//     fix seeding several same-pass candidates (all born at identical
//     existence/timestamp), that reading lets later candidates evict
//     their own earlier siblings, up to and including evicting the fix's
//     own k=0 offset, the single most plausible candidate of the batch.
//     The birth floor makes same-pass siblings mutually un-evictable and
//     keeps an already-corroborated bank stable against fresh, unproven
//     teeth -- a hypothesis is only displaced once it has genuinely
//     stopped corroborating and decayed below birth. See
//     hypothesis_bank.cpp's find_seed_slot for the full trace of the bug
//     this prevents.
#ifndef SYNCCORE_ESTIMATOR_HYPOTHESIS_BANK_H
#define SYNCCORE_ESTIMATOR_HYPOTHESIS_BANK_H

#include <cstdint>

#include "dsp/oss_ring.h"       // BeatEstimate (period_ms, stable)
#include "estimator/estimator.h"

namespace synccore {

// Fixed-size hypothesis-slot cap: house zero-heap-allocation-after-
// construction convention (mirrors oss_ring.h's fixed-array rings).
// MhtConfig::mht_max_hypotheses is clamped into [1, kMhtMaxHypothesesCap]
// by the constructor.
constexpr int kMhtMaxHypothesesCap = 8;

struct MhtConfig {
    // Master schedule gate: §2.16's corpus gate (non-negotiable, not yet
    // run) forbids on-device default changes, so the bank ships
    // default-OFF. Every HypothesisBank entry point is a no-op while this
    // is false.
    bool mht_enabled = false;

    // Bounded bank size (§2.16 "Bounded memory and CPU"): a "small bank,"
    // no field data yet to size it more precisely. Clamped into
    // [1, kMhtMaxHypothesesCap] by the constructor.
    int mht_max_hypotheses = 4;

    // Seeding fan-out: candidates at fix_offset +/- k*beat.period_ms for
    // k = 1..mht_seed_k_max, plus k=0 (the fix itself). Matches the
    // seeding contract oss_ring.h's BeatEstimate doc comment already
    // documents (DSP-01b, "k = 1..3").
    int mht_seed_k_max = 3;

    // WARRANT gate (§2.16 Design): a fix only seeds NEW hypotheses when
    // comb_ratio sits at/below this -- Billie Jean's own measured
    // ambiguous band ("mostly 1.0-1.7," FT9) is the sizing basis. Above
    // this, a comb reading reads as one unambiguous copy-lag (Dreams'
    // corrected 4.3, per §2.16's own correction to the original brief)
    // and must NOT spawn a bank.
    double mht_warrant_comb_ratio_max = 1.7;

    // WARRANT gate: require oss_ring.h's BeatEstimate.stable before
    // seeding -- an unstable/unreliable beat_period_ms must not seed
    // hypotheses at all.
    bool mht_warrant_requires_stable_beat = true;

    // IPDA prune floor (Musicki & Evans via Grinberg §4): hypotheses whose
    // age-decayed existence probability falls below this are dropped.
    // Provisional; not corpus-validated (§2.16's own table).
    double mht_existence_prune_floor = 0.05;

    // A hypothesis's estimate may actuate (DominantHypothesis::valid) only
    // once its existence clears this. Provisional; not corpus-validated.
    double mht_existence_actuate_threshold = 0.75;

    // --- IPDA/chi-square internals not named by §2.16's table, needed to
    // make the design concrete. All provisional (Epic 10, no corpus run
    // has validated any of these, same caveat §2.16 states for the table
    // above). ---

    // Validation-gate threshold gamma for the 1-dof Mahalanobis
    // chi-square test (Grinberg §3.2 Eq. 3.2): a fix is admitted into a
    // given hypothesis iff innovation^2 / S <= this, S = that
    // hypothesis's own sidecar variance + measurement noise. 3.841 =
    // chi-square(1 dof, P_G = 0.95), the standard quantile-table value
    // for a 95% gate probability (Eq. 3.2's own construction: "gate
    // threshold derived from a target false-exclusion probability, not
    // picked arbitrarily"). This GENERALIZES estimator.h's fixed
    // outlier_gate_ms/outlier_gate_max_p00 pair into a per-hypothesis,
    // covariance-scaled gate.
    double mht_chi2_gate_1dof = 3.841;

    // IPDA existence dynamics (Musicki & Evans via Grinberg §4). A newly
    // seeded hypothesis starts "uncertain" rather than fully believed or
    // fully doubted.
    double mht_existence_birth = 0.5;

    // Saturating rise per corroborating (chi-square-admitted) fix:
    // existence += mht_existence_gain_admit * (1 - existence). 0.25
    // reaches ~90% existence after ~8 consecutive corroborating fixes
    // starting from birth.
    double mht_existence_gain_admit = 0.25;

    // Multiplicative penalty applied immediately when a fix is OFFERED to
    // a hypothesis but fails its chi-square gate -- the discrete,
    // event-driven half of §2.16's "decays on non-corroboration and with
    // age."
    double mht_existence_gate_miss_decay = 0.6;

    // The continuous, time-only half of the same decay: existence is
    // scaled by exp(-age_s / this) at query time, age_s measured since
    // the hypothesis's last ADMITTED fix. Reuses the conf_age_tau_s DECAY
    // IDIOM from estimator.h verbatim (same default, 45.0 s) rather than
    // inventing a new one, applied per-hypothesis instead of to one
    // shared estimator.
    double mht_existence_age_tau_s = 45.0;

    // Agreement gate (ms), used two ways: (a) a candidate seed offset
    // within this of an already-live hypothesis's current innovation is
    // treated as a duplicate and not reseeded (never seed a candidate
    // that duplicates a live hypothesis); (b) sized off the same 30 ms
    // tolerance oss_ring.h's kBeatCombAgreeMs already uses for
    // beat-multiple agreement ("|second_lag_ms - k*beat_period_ms| <
    // 30 ms," DSP-01b) -- reusing that already-vetted tolerance rather
    // than inventing a new one.
    double mht_dedup_agree_ms = 30.0;
};

struct DominantHypothesis {
    bool valid = false;   // true only when a dominant exists AND its
                           // existence >= mht_existence_actuate_threshold
    Estimate estimate;     // the dominant hypothesis's estimate at now_ns
    double existence = 0.0;
};

class HypothesisBank {
public:
    explicit HypothesisBank(const MhtConfig& cfg = {},
                            const EstimatorConfig& est_cfg = {});

    void set_output_latency_ms(double ms);
    void set_nudge_ms(double ms);
    void set_deadband_ms(double ms);

    // Forwarded to every live hypothesis and applied to future seeds.
    void on_player_state(int64_t position_ms, bool is_paused, uint64_t mono_ns);
    void on_local_seek(int64_t target_ms, uint64_t issued_mono_ns,
                       double command_latency_ms);

    // One ACCEPTED recognition fix -- the caller guarantees it already
    // passed the §7.3 self-match guard. This bank never sees rejected
    // fixes and holds NO self-match logic of any kind (see the header
    // comment's "HARD LIMIT" -- restated here per §2.16's "never touches
    // self-match"). beat/comb_ratio are the most recent referee
    // analysis-moment values (oss_ring.h's BeatEstimate, lag_window.h's
    // WindowLag::comb_ratio).
    void on_fix(int64_t match_offset_ms, uint64_t capture_mono_ns,
                double frequency_skew, float provider_confidence,
                const BeatEstimate& beat, double comb_ratio);

    bool active() const;        // >= 1 live hypothesis
    int active_count() const;
    DominantHypothesis dominant_at(uint64_t now_ns) const;
    void reset();               // epoch rule: drops all hypotheses/state

private:
    struct Hypothesis {
        bool active = false;
        SyncEstimator estimator;

        // IPDA existence probability. Mutated only on admit/gate-miss
        // events inside on_fix(); age-based decay is applied lazily (at
        // query time, via effective_existence()) rather than mutating
        // this field on a timer -- mirrors estimate_at()'s own
        // non-mutating confidence-age projection in estimator.cpp.
        double existence = 0.0;

        // Gate-only sidecar variance (see header comment's "Variance-for-
        // gating" design note). NEVER used for state estimation -- that
        // stays entirely `estimator`'s own posterior.
        double sidecar_p = 0.0;
        uint64_t sidecar_mono_ns = 0;

        // Anchor for existence's continuous age decay: the capture time
        // of this hypothesis's last ADMITTED (corroborating) fix.
        uint64_t last_admit_mono_ns = 0;
    };

    // Mirrors SyncEstimator::projected_local_ms's formula exactly (see
    // estimator.cpp). Duplicated here, deliberately, because the bank
    // computes ONE local_audible value per fix -- common to every
    // hypothesis, since player state/output latency are forwarded
    // identically to all of them -- before handing each hypothesis its
    // own per-hypothesis innovation to the chi-square gate. Reading it off
    // an arbitrary live hypothesis's estimator would work too but would
    // break when the bank is empty (first-ever warranted fix), which is
    // exactly the case this helper exists to cover.
    double projected_local_ms(uint64_t now_ns) const;

    // P += q*dt, gate-only sidecar prediction. No-op if t_ns is not after
    // the sidecar's own last-predicted time (mirrors predict_to's own
    // t_ns <= state_mono_ns_ guard in estimator.cpp).
    void predict_sidecar(Hypothesis& h, uint64_t t_ns) const;

    // §2.16 Design's WARRANT condition (governs SEEDING only -- see
    // on_fix's own comment for why an unwarranted fix still updates
    // already-active hypotheses).
    bool warranted(const BeatEstimate& beat, double comb_ratio) const;

    // existence scaled by the continuous age-decay term at now_ns. Never
    // mutates `h`.
    double effective_existence(const Hypothesis& h, uint64_t now_ns) const;

    // Returns a slot ready to receive a new hypothesis: the first free
    // (inactive) slot within [0, max_hyp_); or, if the bank is already at
    // mht_max_hypotheses, the slot with the lowest effective existence at
    // now_ns IF AND ONLY IF that existence is strictly below
    // mht_existence_birth -- otherwise -1 ("no qualifying victim, do not
    // seed"). See hypothesis_bank.cpp's DESIGN CHOICE comment on this
    // function for why a plain "evict the lowest, no floor" reading of
    // §2.16's wording causes same-pass seeding to evict its own siblings
    // (and even the fix's own k=0 candidate).
    int find_seed_slot(uint64_t now_ns);

    void seed_one(int64_t offset_ms, uint64_t capture_mono_ns,
                 double frequency_skew, float provider_confidence);

    MhtConfig cfg_;
    EstimatorConfig est_cfg_;      // unmodified; sidecar math's own source
                                    // of q_error_ms2_per_s/meas_noise_ms2/
                                    // seek_exec_var_ms2.
    EstimatorConfig hyp_est_cfg_;  // est_cfg_ with outlier_gate_max_p00
                                    // forced to 0 -- see hypothesis_bank.cpp
                                    // constructor comment.
    int max_hyp_ = 4;              // cfg_.mht_max_hypotheses, clamped.

    double output_latency_ms_ = 0.0;
    double nudge_ms_ = 0.0;
    double deadband_ms_ = 25.0;

    // Bank-level mirror of the latest player state, forwarded to every
    // live hypothesis and applied to future seeds (see on_player_state).
    bool has_player_ = false;
    int64_t player_position_ms_ = 0;
    bool player_paused_ = false;
    uint64_t player_mono_ns_ = 0;

    Hypothesis hyps_[kMhtMaxHypothesesCap];
    int active_count_ = 0;
};

}  // namespace synccore

#endif  // SYNCCORE_ESTIMATOR_HYPOTHESIS_BANK_H
