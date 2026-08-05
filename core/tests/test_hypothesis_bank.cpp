// test_hypothesis_bank.cpp -- MHT-01 acceptance tests for the §2.16
// Multi-Hypothesis Tracking bank (core/src/estimator/hypothesis_bank.h/.cpp).
//
// World model, mirroring test_estimator.cpp's own: player timeline
// local(t) = 60'000 + t ms (t synthetic nanoseconds); a synthetic room
// applies a true offset error to that timeline the same way
// hypothesis_bank.cpp's on_fix measurement convention expects
// (z = local_audible - match_offset - nudge; error_ms = local ahead of
// external, per estimator.h's own §6.1 doc comment) -- so a fix constructed
// as `match_offset = local(t) - error_ms` makes a hypothesis seeded from it
// converge to `error_ms` almost exactly (single-fix Kalman collapse from
// the estimator's huge init_error_var_ms2 prior). Framework-free, house
// CHECK/CHECK_NEAR macros + main() runner, mirroring test_estimator.cpp;
// the allocation guard mirrors test_oss_ring.cpp's operator-new hook.

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>

#include "estimator/hypothesis_bank.h"

namespace {

int g_failures = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                               \
        }                                                                \
    } while (0)

#define CHECK_NEAR(val, target, tol)                                          \
    do {                                                                      \
        const double v = (val);                                               \
        if (std::abs(v - (target)) > (tol)) {                                 \
            std::printf("FAIL %s:%d: %s = %.6f, want %.6f +/- %.6f\n",        \
                        __FILE__, __LINE__, #val, v, (double)(target),        \
                        (double)(tol));                                       \
            ++g_failures;                                                     \
        }                                                                     \
    } while (0)

// ---- Allocation guard (mirrors test_oss_ring.cpp:32-47) ---------------
thread_local bool tl_forbid_alloc = false;
std::atomic<uint64_t> g_forbidden_allocs{0};

}  // namespace

void* operator new(std::size_t n) {
    if (tl_forbid_alloc) g_forbidden_allocs.fetch_add(1);
    if (void* p = std::malloc(n ? n : 1)) return p;
    throw std::bad_alloc{};
}
void operator delete(void* p) noexcept {
    if (tl_forbid_alloc) g_forbidden_allocs.fetch_add(1);
    std::free(p);
}
void operator delete(void* p, std::size_t) noexcept { ::operator delete(p); }

namespace {

constexpr uint64_t kSec = 1'000'000'000ull;

// FT9: Billie Jean's mic-measured beat_period was ~512-516 ms; 516.0 is
// used throughout as the fixture's stable beat period.
constexpr double kBeatPeriodMs = 516.0;
// FT9: comb_ratio "mostly 1.0-1.7" is Billie Jean's own measured ambiguous
// band -- the sizing basis for MhtConfig::mht_warrant_comb_ratio_max (1.7).
constexpr double kAmbiguousComb = 1.2;
// §2.16's "Correction to the task brief": 4.3 is Dreams' pre-seek plateau
// reading, explicitly the UNAMBIGUOUS single-copy case that must never
// warrant seeding a bank.
constexpr double kUnambiguousComb = 4.3;

synccore::MhtConfig enabled_cfg() {
    synccore::MhtConfig cfg;
    cfg.mht_enabled = true;  // every other knob stays at its provisional default.
    return cfg;
}

synccore::BeatEstimate make_beat(double period_ms, bool stable) {
    synccore::BeatEstimate b;
    b.period_ms = period_ms;
    b.stable = stable;
    return b;
}

// Drives a HypothesisBank with a synthetic world, mirroring
// test_estimator.cpp's World struct.
struct BankWorld {
    synccore::HypothesisBank bank;

    explicit BankWorld(const synccore::MhtConfig& cfg = enabled_cfg(),
                       const synccore::EstimatorConfig& est_cfg = {})
        : bank(cfg, est_cfg) {}

    // Local playback timeline: position == 60'000 + t (ms), advancing 1:1
    // (test_estimator.cpp's own World::push_fix formula).
    static double local_ms(uint64_t t_ns) {
        return 60'000.0 + static_cast<double>(t_ns) / 1e6;
    }

    void push_player(uint64_t t_ns, bool paused = false) {
        bank.on_player_state(60'000 + static_cast<int64_t>(t_ns / 1'000'000), paused,
                             t_ns);
    }

    // Sends a fix whose implied error (local_audible - match_offset, with
    // output_latency/nudge at their default 0) equals error_ms.
    void push_fix(uint64_t t_ns, double error_ms, const synccore::BeatEstimate& beat,
                 double comb_ratio, float conf = 0.9f, double skew = 0.0) {
        const int64_t offset =
            static_cast<int64_t>(std::llround(local_ms(t_ns) - error_ms));
        bank.on_fix(offset, t_ns, skew, conf, beat, comb_ratio);
    }
};

// =========================================================================
// 1. Disabled bank is a true no-op.
// =========================================================================

void test_disabled_bank_is_noop() {
    // WHY: §2.16 ships default-OFF (mht_enabled=false) pending its own
    // corpus gate -- no on-device default change is authorized by this
    // section. Every entry point must be a true no-op, not merely "never
    // seeds," pinned across repeated warranted-looking fixes and multiple
    // query times, forever.
    synccore::HypothesisBank bank;  // default MhtConfig: mht_enabled=false.
    CHECK(!bank.active());
    CHECK(bank.active_count() == 0);

    bank.set_output_latency_ms(50.0);
    bank.set_nudge_ms(10.0);
    bank.set_deadband_ms(30.0);

    const auto beat = make_beat(kBeatPeriodMs, true);
    uint64_t t = kSec;
    bank.on_player_state(60'000, false, t);
    for (int i = 0; i < 5; ++i) {
        t += 2 * kSec;
        bank.on_player_state(60'000 + static_cast<int64_t>((t - kSec) / 1'000'000),
                             false, t);
        // Warranted-looking: ambiguous comb + stable beat -- would seed a
        // bank of 4 if mht_enabled were true.
        bank.on_fix(59'500 - i * 10, t, 0.0, 0.9f, beat, kAmbiguousComb);
        CHECK(!bank.active());
        CHECK(bank.active_count() == 0);
        CHECK(!bank.dominant_at(t).valid);
        CHECK(!bank.dominant_at(t).estimate.valid);
    }
    CHECK(!bank.dominant_at(t + 1000 * kSec).valid);  // "forever"

    bank.on_local_seek(65'000, t, 0.0);  // also must be a no-op
    CHECK(bank.active_count() == 0);

    bank.reset();
    CHECK(!bank.active());
    CHECK(bank.active_count() == 0);
}

// =========================================================================
// 2. Warrant gating.
// =========================================================================

void test_warrant_rejects_unambiguous_comb_dreams_class() {
    // WHY: §2.16's "Correction to the task brief, load-bearing" -- comb
    // 4.3 is Dreams' UNAMBIGUOUS single-copy reading, not the ambiguous
    // Billie Jean band; it must never warrant seeding a bank.
    BankWorld w;
    w.push_player(kSec);
    w.push_fix(kSec, -580.0, make_beat(kBeatPeriodMs, true), kUnambiguousComb);
    CHECK(!w.bank.active());
    CHECK(w.bank.active_count() == 0);
}

void test_warrant_rejects_zero_comb_sentinel() {
    // WHY: lag_window.h's WindowLag::comb_ratio doc comment -- 0 is the
    // "no meaningful competitor" sentinel, not a claim of a real, tiny
    // ratio; hypothesis_bank.cpp's warranted() explicitly excludes it.
    BankWorld w;
    w.push_player(kSec);
    w.push_fix(kSec, -580.0, make_beat(kBeatPeriodMs, true), 0.0);
    CHECK(w.bank.active_count() == 0);
}

void test_warrant_rejects_negative_comb_sentinel() {
    // WHY: same sentinel family as comb_ratio==0 -- warranted() gates on
    // comb_ratio <= 0.0, not just == 0.0.
    BankWorld w;
    w.push_player(kSec);
    w.push_fix(kSec, -580.0, make_beat(kBeatPeriodMs, true), -1.5);
    CHECK(w.bank.active_count() == 0);
}

void test_warrant_requires_stable_beat_by_default() {
    // WHY: §2.16 Design -- "an unstable/unreliable beat_period_ms must not
    // seed hypotheses at all." mht_warrant_requires_stable_beat defaults
    // true.
    BankWorld w;
    w.push_player(kSec);
    w.push_fix(kSec, -580.0, make_beat(kBeatPeriodMs, /*stable=*/false),
              kAmbiguousComb);
    CHECK(w.bank.active_count() == 0);
}

void test_warrant_seeds_with_unstable_beat_when_not_required() {
    synccore::MhtConfig cfg = enabled_cfg();
    // Non-default: explicitly exercises the mht_warrant_requires_stable_beat
    // knob itself (the previous test already pins the true/default case).
    cfg.mht_warrant_requires_stable_beat = false;
    BankWorld w(cfg);
    w.push_player(kSec);
    w.push_fix(kSec, -580.0, make_beat(kBeatPeriodMs, /*stable=*/false),
              kAmbiguousComb);
    CHECK(w.bank.active_count() > 0);
}

void test_warrant_rejects_zero_beat_period() {
    // WHY: warranted() checks beat.period_ms <= 0.0 -- a fresh/never-
    // estimated BeatEstimate{} (period_ms defaults to 0) has nothing to
    // seed multiples off of, even if (degenerately) marked stable.
    BankWorld w;
    w.push_player(kSec);
    w.push_fix(kSec, -580.0, make_beat(0.0, true), kAmbiguousComb);
    CHECK(w.bank.active_count() == 0);
}

void test_warrant_governs_seeding_only_not_admission() {
    // WHY: §2.16 Design + on_fix's own comment -- "warrant governs SEEDING
    // only." An unwarranted fix arriving at an already-active bank must
    // still be offered to every live hypothesis's chi-square gate and
    // still move existence on admission.
    BankWorld w;
    const double kTrue = -580.0;
    const auto beat = make_beat(kBeatPeriodMs, true);
    const uint64_t t0 = kSec;
    w.push_player(t0);
    w.push_fix(t0, kTrue, beat, kAmbiguousComb);
    CHECK(w.bank.active_count() == 4);
    const double exist0 = w.bank.dominant_at(t0).existence;
    CHECK_NEAR(exist0, 0.5, 1e-9);  // mht_existence_birth, exact double literal

    // comb_ratio 4.3 (Dreams-class, unwarranted) at the SAME implied
    // offset: no new hypothesis, but the matching k=0 hypothesis still
    // admits and its existence still rises via the ordinary IPDA gain.
    const uint64_t t1 = t0 + 2 * kSec;
    w.push_player(t1);
    w.push_fix(t1, kTrue, beat, kUnambiguousComb);
    CHECK(w.bank.active_count() == 4);  // no growth: comb 4.3 never seeds
    const double exist1 = w.bank.dominant_at(t1).existence;
    CHECK(exist1 > exist0);  // existence moved: admission still happened
}

// =========================================================================
// 3. First-pass seeding shape.
// =========================================================================

void test_first_pass_seeding_fills_bank_and_k0_survives() {
    // WHY: hypothesis_bank.cpp's find_seed_slot DESIGN CHOICE comment
    // traces a churn bug where a naive "evict the lowest, no floor"
    // reading of §2.16's eviction wording lets a single warranted fix's
    // own k=2/k=3 far-tooth candidates evict the fix's own k=0 offset --
    // the single most plausible candidate of the batch. Pin: one
    // warranted fix into an empty, default-sized (mht_max_hypotheses=4)
    // bank fills it exactly, and a hypothesis survives whose estimate
    // matches the fix's own implied error.
    BankWorld w;
    const double kTrue = -580.0;
    const uint64_t t0 = kSec;
    w.push_player(t0);
    w.push_fix(t0, kTrue, make_beat(kBeatPeriodMs, true), kAmbiguousComb);

    CHECK(w.bank.active_count() == 4);  // mht_max_hypotheses default

    // All 4 same-pass siblings share existence==birth (0.5) and an
    // identical last_admit timestamp; dominant_at's strict '>' scan
    // (hypothesis_bank.cpp) resolves the tie to the FIRST slot filled,
    // which seed_one's k=0-first ordering guarantees is the fix's own
    // offset -- confirming k=0 was never evicted by its own far teeth.
    const auto dom = w.bank.dominant_at(t0);
    CHECK_NEAR(dom.existence, 0.5, 1e-9);
    CHECK_NEAR(dom.estimate.error_ms, kTrue, 2.0);  // near-exact single-fix collapse
    CHECK(!dom.valid);  // birth(0.5) < actuate_threshold(0.75)
}

// =========================================================================
// 4. Dedup.
// =========================================================================

void test_dedup_same_offset_fix_does_not_grow_bank() {
    // WHY: mht_dedup_agree_ms (30 ms, reusing oss_ring.h's own
    // kBeatCombAgreeMs tolerance) must prevent a repeat fix at
    // (approximately) the same implied offset from spawning duplicate
    // hypotheses.
    BankWorld w;
    const double kTrue = -580.0;
    const auto beat = make_beat(kBeatPeriodMs, true);
    const uint64_t t0 = kSec;
    w.push_player(t0);
    w.push_fix(t0, kTrue, beat, kAmbiguousComb);
    CHECK(w.bank.active_count() == 4);

    const uint64_t t1 = t0 + kSec;
    w.push_player(t1);
    w.push_fix(t1, kTrue, beat, kAmbiguousComb);
    CHECK(w.bank.active_count() == 4);  // no growth
}

// =========================================================================
// 5. Chi-square admission routing.
// =========================================================================

void test_chi2_admission_routes_to_matching_tooth() {
    // WHY: §2.16 Design's chi-square gate (Grinberg Eq. 3.2) must route an
    // incoming fix ONLY into hypotheses whose predicted offset it agrees
    // with; a fix at a beat-multiple tooth raises that tooth's existence
    // (IPDA corroboration) while every other live hypothesis -- including
    // the fix's own former k=0 seed -- takes the immediate gate-miss
    // penalty (x0.6). Observable via dominant_at flipping to the
    // now-highest-existence hypothesis.
    BankWorld w;
    const double kTrue = -580.0;
    const auto beat = make_beat(kBeatPeriodMs, true);
    const uint64_t t0 = kSec;
    w.push_player(t0);
    w.push_fix(t0, kTrue, beat, kAmbiguousComb);
    CHECK(w.bank.active_count() == 4);
    CHECK_NEAR(w.bank.dominant_at(t0).estimate.error_ms, kTrue, 2.0);  // slot0 dominant

    // A fix implying error kTrue+beat_period exactly matches the k=1
    // "down" seed (seed_one's down = match_offset - beat.period_ms =>
    // implied error kTrue + beat.period_ms).
    const uint64_t t1 = t0 + 2 * kSec;
    w.push_player(t1);
    w.push_fix(t1, kTrue + kBeatPeriodMs, beat, kUnambiguousComb);  // admission only

    const auto dom = w.bank.dominant_at(t1);
    CHECK_NEAR(dom.existence, 0.625, 1e-6);  // birth 0.5 + gain_admit*(1-0.5), exact
    CHECK_NEAR(dom.estimate.error_ms, kTrue + kBeatPeriodMs, 5.0);
    CHECK(w.bank.active_count() == 4);  // no prune yet (0.3 > floor 0.05)
}

// =========================================================================
// 6. False-teeth pruning + convergence to the true offset (core Billie
//    Jean AC).
// =========================================================================

void test_convergence_to_true_offset_with_tooth_pruning() {
    // THE core Billie Jean AC (§2.16, FT9 evidence): a realistic
    // comb-aliased stream -- mostly consistent true-offset fixes with
    // interspersed tooth outliers at exact beat-period multiples -- must
    // converge the true-offset hypothesis's existence past
    // mht_existence_actuate_threshold (0.75) while the false teeth decay
    // below mht_existence_prune_floor (0.05) and prune.
    //
    // By design (see the header's own gain_admit/gate_miss_decay
    // comments), heavy 50/50 alternation between two offsets equilibrates
    // around existence*gain/(gain+(1-decay))-ish math well under 0.75 --
    // an unresolved comb must hold fire, not tune-force an actuation. This
    // stream instead uses runs of 4 consecutive true-offset admits before
    // each single tooth miss/admit, so the true hypothesis's existence
    // oscillates well above 0.75 at each run's peak (birth 0.5 -> ~0.84
    // after 4 admits from the 0.25-gain recursion) rather than sitting at
    // a 50/50 equilibrium.
    //
    // comb_ratio is ambiguous (1.2, warranted) ONLY on the seed fix; every
    // later fix in the stream uses comb_ratio 4.3 (unambiguous) so warrant
    // governs seeding only (already pinned above) and this stream
    // exercises pure admission/gate-miss/prune dynamics on the
    // already-seeded bank without reseed churn muddying the trace.
    BankWorld w;
    const double kTrue = -580.0;
    const auto beat = make_beat(kBeatPeriodMs, true);
    const uint64_t t0 = kSec;

    w.push_player(t0);
    w.push_fix(t0, kTrue, beat, kAmbiguousComb);  // seeds kTrue, +/-516, -1032
    CHECK(w.bank.active_count() == 4);

    uint64_t t = t0;
    int min_active_count = w.bank.active_count();
    double true_peak_existence = 0.0;
    for (int block = 0; block < 10; ++block) {
        for (int i = 0; i < 4; ++i) {
            t += 2 * kSec;
            w.push_player(t);
            w.push_fix(t, kTrue, beat, kUnambiguousComb);
        }
        // Sampled right after the block's 4th consecutive true admit --
        // the run's peak, per the hand-derived oscillation above.
        const auto dom_true = w.bank.dominant_at(t);
        if (dom_true.estimate.valid &&
            std::abs(dom_true.estimate.error_ms - kTrue) < 50.0) {
            true_peak_existence = std::max(true_peak_existence, dom_true.existence);
        }
        min_active_count = std::min(min_active_count, w.bank.active_count());

        t += 2 * kSec;
        w.push_player(t);
        const double tooth =
            kTrue + ((block % 2 == 0) ? kBeatPeriodMs : -kBeatPeriodMs);
        w.push_fix(t, tooth, beat, kUnambiguousComb);
        min_active_count = std::min(min_active_count, w.bank.active_count());
    }

    CHECK(true_peak_existence >= 0.75);  // clears actuate threshold
    const auto final_dom = w.bank.dominant_at(t);
    CHECK(final_dom.estimate.valid);
    CHECK_NEAR(final_dom.estimate.error_ms, kTrue, 5.0);
    CHECK(min_active_count < 4);       // at least one false tooth pruned somewhere
    CHECK(w.bank.active_count() < 4);  // pruning persists to the end of the stream
}

// =========================================================================
// 7. Corroborated bank resists displacement, then succumbs once decayed.
// =========================================================================

void test_corroborated_bank_resists_then_succumbs_to_displacement() {
    // WHY: hypothesis_bank.h/.cpp's find_seed_slot DESIGN CHOICE -- a full
    // bank only gives up a slot to a fresh candidate if the weakest
    // occupant's effective existence is STRICTLY BELOW mht_existence_birth
    // (0.5). A corroborated (existence > birth) hypothesis must survive a
    // fresh warranted fix from an unrelated offset family; only once it
    // has decayed back below birth does displacement become possible.
    synccore::MhtConfig cfg = enabled_cfg();
    // Non-default: mht_max_hypotheses=1 isolates single-slot eviction
    // dynamics from the k=1..3 seeding fan-out (tests 3/5/6 above already
    // cover that fan-out at the default cap of 4).
    cfg.mht_max_hypotheses = 1;
    BankWorld w(cfg);

    const double kA = -580.0;
    const auto beat = make_beat(kBeatPeriodMs, true);
    const uint64_t t0 = kSec;
    w.push_player(t0);
    w.push_fix(t0, kA, beat, kAmbiguousComb);  // seeds hyp A, existence = birth 0.5
    CHECK(w.bank.active_count() == 1);

    // 6 more corroborating admits, 1 s apart: birth 0.5 -> ~0.911011 (the
    // same 0.25-gain recursion AC6 uses). Needed margin, derived from the
    // code: after ONE gate-miss (x0.6) and the age decay a 1 s query gap
    // applies (exp(-1/45) ~= 0.978, mht_existence_age_tau_s=45.0), the
    // result must still clear birth (0.5) for the "resists" half below:
    // 0.911011 * 0.6 * 0.978 ~= 0.5346 > 0.5, a ~7% margin.
    uint64_t t = t0;
    for (int i = 0; i < 6; ++i) {
        t += kSec;
        w.push_player(t);
        w.push_fix(t, kA, beat, kUnambiguousComb);  // admission only
    }
    const double exist_corroborated = w.bank.dominant_at(t).existence;
    CHECK(exist_corroborated > 0.85);  // sanity: matches the ~0.911011 hand trace

    // A fresh warranted fix at an unrelated offset family (+5000 ms, far
    // outside any beat-multiple aliasing) gate-misses A once. Per the
    // margin derived above, find_seed_slot must find NO qualifying victim
    // and refuse to seed -- bank composition unchanged, A survives.
    const double kB = kA + 5000.0;
    t += kSec;
    w.push_player(t);
    w.push_fix(t, kB, beat, kAmbiguousComb);
    CHECK(w.bank.active_count() == 1);
    CHECK_NEAR(w.bank.dominant_at(t).estimate.error_ms, kA, 5.0);  // A survives

    // A second fresh fix at the same new family gate-misses A again:
    // ~0.911011*0.6*0.6 further age-decayed over the elapsed 2 s since
    // its last admit (exp(-2/45) ~= 0.956) ~= 0.31, now clearly below
    // birth (0.5) -- find_seed_slot finds A a qualifying victim and B
    // displaces it.
    t += kSec;
    w.push_player(t);
    w.push_fix(t, kB, beat, kAmbiguousComb);
    CHECK(w.bank.active_count() == 1);
    CHECK_NEAR(w.bank.dominant_at(t).estimate.error_ms, kB, 5.0);  // B displaced A
}

// =========================================================================
// 8. Dominant-validity guard (defect-2 regression pin).
// =========================================================================

void test_dominant_invalid_without_player_state() {
    // WHY (defect-2 regression, re-affirmed by hypothesis_bank.cpp's
    // dominant_at comment): a hypothesis seeded before ANY on_player_state
    // call gets active=true/existence=birth from seed_one, but its
    // estimator's own has_player_ stays false forever (seed_one only
    // forwards player state when the BANK's has_player_ is already true)
    // -- so its estimator.on_fix always returns false and its Estimate
    // stays permanently invalid. dominant_at must never report valid=true
    // over that invalid estimate, no matter what existence does.
    BankWorld w;  // note: push_player() is never called anywhere below.
    const auto beat = make_beat(kBeatPeriodMs, true);
    const uint64_t t0 = kSec;
    // error_ms here is nominal only (BankWorld::local_ms doesn't require
    // player state) -- with no player state, on_fix's own local_audible
    // computation is 0 regardless of what offset is passed.
    w.push_fix(t0, -580.0, beat, kAmbiguousComb);
    CHECK(w.bank.active_count() > 0);  // hypotheses DO exist

    const auto dom0 = w.bank.dominant_at(t0);
    CHECK(!dom0.valid);
    CHECK(!dom0.estimate.valid);

    // A second, later fix: existence evolves (admit/gate-miss/age
    // machinery already pinned above still runs) but validity cannot,
    // since dominant_at gates strictly on estimate.valid, not existence.
    const uint64_t t1 = t0 + 5 * kSec;
    w.push_fix(t1, -600.0, beat, kAmbiguousComb);
    const auto dom1 = w.bank.dominant_at(t1);
    CHECK(!dom1.valid);
    CHECK(!dom1.estimate.valid);

    // Much later still (age decay runs existence toward 0) -- still false.
    CHECK(!w.bank.dominant_at(t1 + 100 * kSec).valid);
}

// =========================================================================
// 9. on_local_seek forwarding.
// =========================================================================

// Seeds and corroborates a single true-offset family with 3 further
// admits, 2 s apart -- shared setup for the plain-vs-seeked comparison
// below (isolates the seek's own effect from seeding mechanics already
// pinned by tests 3/5/6).
void corroborate_true_offset(BankWorld& w, double err, const synccore::BeatEstimate& beat,
                             uint64_t t0) {
    w.push_player(t0);
    w.push_fix(t0, err, beat, kAmbiguousComb);  // seeds 4 hyps incl. slot0 @ err
    for (int i = 1; i <= 3; ++i) {
        const uint64_t t = t0 + static_cast<uint64_t>(i) * 2 * kSec;
        w.push_player(t);
        w.push_fix(t, err, beat, kUnambiguousComb);  // admission only
    }
}

void test_local_seek_shifts_hypotheses_and_widens_gate() {
    // WHY: HypothesisBank::on_local_seek must forward the commanded jump
    // to every live hypothesis's estimator (mirrors estimator.cpp's own
    // on_local_seek: e_ += delta, p00_ += seek_exec_var_ms2) AND widen the
    // gate-only sidecar the same way (hypothesis_bank.cpp's own
    // on_local_seek: predict_sidecar then += seek_exec_var_ms2). A fix
    // that would have gate-missed pre-seek must admit post-seek because S
    // grew by seek_exec_var_ms2 (2500 ms^2, EstimatorConfig default),
    // widening sqrt(mht_chi2_gate_1dof * S) well past its pre-seek width.
    const double E0 = -580.0;
    const uint64_t t0 = kSec;
    const auto beat = make_beat(kBeatPeriodMs, true);

    // Two parallel banks built identically through corroboration; only
    // `seeked` gets the on_local_seek call, isolating its effect.
    BankWorld plain;
    BankWorld seeked;
    corroborate_true_offset(plain, E0, beat, t0);
    corroborate_true_offset(seeked, E0, beat, t0);

    const uint64_t t_seek = t0 + 8 * kSec;
    const double pre_error = plain.bank.dominant_at(t_seek).estimate.error_ms;
    CHECK_NEAR(pre_error, E0, 5.0);

    // Command a +2000 ms local jump (a scrub forward), landing immediately
    // (command_latency_ms=0).
    plain.push_player(t_seek);
    seeked.push_player(t_seek);
    const double target = seeked.local_ms(t_seek) + 2000.0;
    seeked.bank.on_local_seek(static_cast<int64_t>(std::llround(target)), t_seek,
                              /*command_latency_ms=*/0.0);

    const double post_error = seeked.bank.dominant_at(t_seek).estimate.error_ms;
    CHECK_NEAR(post_error, E0 + 2000.0, 5.0);  // dominant error reflects the jump

    // Existence-delta proxy for "admitted": gain_admit always RAISES
    // existence, gate_miss_decay always LOWERS it -- a clean binary signal
    // for chi-square admission without reading private sidecar state.
    const double plain_exist_before = plain.bank.dominant_at(t_seek).existence;
    const double seeked_exist_before = seeked.bank.dominant_at(t_seek).existence;

    const uint64_t t_after = t_seek + static_cast<uint64_t>(0.1 * kSec);
    // 50 ms off each bank's own current true offset: derived from the
    // code, after 3 corroborating admits the sidecar variance shrinks to
    // roughly 7-12 ms^2 (predict-then-Kalman-shrink recursion off R~30.86
    // at conf 0.9), giving a pre-seek gate width
    // sqrt(3.841*(~11+30.86)) ~= 13-16 ms -- 50 ms clearly misses it. The
    // same widened post-seek gate is sqrt(3.841*(~11+2500+30.86)) ~= 98 ms
    // -- 50 ms clearly clears it.
    plain.push_fix(t_after, E0 + 50.0, beat, kUnambiguousComb);
    seeked.push_fix(t_after, E0 + 2000.0 + 50.0, beat, kUnambiguousComb);

    const double plain_exist_after = plain.bank.dominant_at(t_after).existence;
    const double seeked_exist_after = seeked.bank.dominant_at(t_after).existence;

    CHECK(plain_exist_after < plain_exist_before);    // pre-seek: gate-missed
    CHECK(seeked_exist_after > seeked_exist_before);  // post-seek: admitted
}

// =========================================================================
// 10. reset() epoch rule.
// =========================================================================

void test_reset_epoch_clears_and_reseeds() {
    // WHY: hypothesis_bank.cpp's reset() epoch rule mirrors
    // SyncEstimator::reset() -- a fresh join or track-lost re-listen must
    // never carry forward stale hypotheses or player state.
    BankWorld w;
    const auto beat = make_beat(kBeatPeriodMs, true);
    const uint64_t t0 = kSec;
    w.push_player(t0);
    w.push_fix(t0, -580.0, beat, kAmbiguousComb);
    CHECK(w.bank.active_count() == 4);
    CHECK(w.bank.active());

    w.bank.reset();
    CHECK(!w.bank.active());
    CHECK(w.bank.active_count() == 0);
    CHECK(!w.bank.dominant_at(t0).valid);
    CHECK(!w.bank.dominant_at(t0).estimate.valid);

    // A fresh warranted fix, with player state re-established first,
    // seeds from a clean slate exactly like the very first test's setup.
    const uint64_t t1 = t0 + 10 * kSec;
    w.push_player(t1);
    w.push_fix(t1, -580.0, beat, kAmbiguousComb);
    CHECK(w.bank.active_count() == 4);
    CHECK_NEAR(w.bank.dominant_at(t1).estimate.error_ms, -580.0, 2.0);
}

// =========================================================================
// 11. Allocation guard.
// =========================================================================

void test_zero_allocation_after_construction() {
    // Mirrors test_oss_ring.cpp's operator-new hook (house convention):
    // HypothesisBank/SyncEstimator are fixed-size by design
    // (kMhtMaxHypothesesCap-length array of Hypothesis, each holding one
    // SyncEstimator by value, per hypothesis_bank.h's own doc comment) --
    // construction may allocate; nothing in a full
    // seed/admit/gate-miss/prune/reseed scenario past construction should.
    BankWorld w;  // construction happens before the guard below.
    const auto beat = make_beat(kBeatPeriodMs, true);
    const double kTrue = -580.0;
    const uint64_t t0 = kSec;

    g_forbidden_allocs.store(0);
    tl_forbid_alloc = true;

    w.push_player(t0);
    w.push_fix(t0, kTrue, beat, kAmbiguousComb);  // seed pass
    uint64_t t = t0;
    for (int i = 0; i < 20; ++i) {
        t += 2 * kSec;
        w.push_player(t);
        const bool tooth = (i % 5 == 4);
        const double err = tooth ? kTrue + (((i / 5) % 2 == 0) ? kBeatPeriodMs
                                                               : -kBeatPeriodMs)
                                 : kTrue;
        w.push_fix(t, err, beat, kUnambiguousComb);  // admission/gate-miss/prune only
    }
    (void)w.bank.dominant_at(t);
    (void)w.bank.active_count();

    tl_forbid_alloc = false;
    CHECK(g_forbidden_allocs.load() == 0);
}

}  // namespace

int main() {
    test_disabled_bank_is_noop();

    test_warrant_rejects_unambiguous_comb_dreams_class();
    test_warrant_rejects_zero_comb_sentinel();
    test_warrant_rejects_negative_comb_sentinel();
    test_warrant_requires_stable_beat_by_default();
    test_warrant_seeds_with_unstable_beat_when_not_required();
    test_warrant_rejects_zero_beat_period();
    test_warrant_governs_seeding_only_not_admission();

    test_first_pass_seeding_fills_bank_and_k0_survives();
    test_dedup_same_offset_fix_does_not_grow_bank();
    test_chi2_admission_routes_to_matching_tooth();
    test_convergence_to_true_offset_with_tooth_pruning();
    test_corroborated_bank_resists_then_succumbs_to_displacement();
    test_dominant_invalid_without_player_state();
    test_local_seek_shifts_hypotheses_and_widens_gate();
    test_reset_epoch_clears_and_reseeds();
    test_zero_allocation_after_construction();

    if (g_failures == 0) {
        std::printf("hypothesis_bank_tests: all tests passed\n");
        return 0;
    }
    std::printf("hypothesis_bank_tests: %d check(s) FAILED\n", g_failures);
    return 1;
}
