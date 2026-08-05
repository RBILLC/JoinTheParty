# MHT-01 Implementation Review — §2.16 Multi-Hypothesis Tracking Bank (Issue #35)

Date: 2026-08-05
Pipeline: `/implement` (spec `d919c7e` §2.16 · Issue #35 · PM implement directive of 2026-08-05)
Orchestration: three Sonnet subagents in a pinned-contract fan-out under Fable orchestration —
Agent A (bank module), Agent B (synccore/policy wiring), Agent C (test suite). A ran first
(its header is the contract B and C compile against); B and C ran in parallel with disjoint
file ownership. Every agent claim below was re-verified first-hand by the orchestrator
(diffs read in full, builds and test suites rerun independently, counts recomputed).

## Scheduling reconciliation (read this first)

Issue #35 is written as a **design-gate ticket** ("NOT an implementation authorization")
with two blockers: DSP-01b field validation and the §2.11-style corpus gate. The PM's
implement directive scheduled it anyway. The two are reconciled through §2.16's own test
obligation (3), which forbids **on-device default changes** before the corpus gate — not
implementation itself:

- The bank ships behind **`MhtConfig::mht_enabled = false`** (a knob §2.16's table does not
  contain; added as the scheduling gate). Every `HypothesisBank` entry point is a true
  no-op while disabled, and the synccore wiring is byte-behavior-identical to pre-MHT-01
  in that state. Zero on-device behavior change lands with this commit.
- Obligation (2) — synthetic-fixture validation of admission/pruning/existence-decay
  before any corpus run — is what this pass's test suite delivers.
- Obligation (1) — zero interaction with the self-match guard/probe suites — holds:
  every existing test file is byte-unmodified except `test_policy.cpp`, which is
  append-only (+98/−0).
- Obligation (4) — ABI coverage for new events — is vacuous: **no C ABI change of any
  kind** was made (no new events, no header edits; `abi_c_check.c` untouched).
- **Issue #35 stays OPEN.** Both blockers stand. Promotion (flipping `mht_enabled`)
  requires DSP-01b field validation plus a corpus sweep with §2.11's promotion
  discipline: no regressions on healthy-lock windows, measurable win on the
  Billie Jean-class comb-ambiguous windows.

## What was built

### `core/src/estimator/hypothesis_bank.h/.cpp` (Agent A; 299 + ~330 lines)

`synccore::HypothesisBank` — pure logic, no threads/clocks, fixed-size storage
(`kMhtMaxHypothesesCap = 8` slots; `mht_max_hypotheses` clamped, default 4), zero heap
allocation after construction (pinned by test).

- **Warrant** (seeding only): `mht_enabled` ∧ `0 < comb_ratio ≤ 1.7` ∧ `beat.period_ms > 0`
  ∧ (`beat.stable` unless `mht_warrant_requires_stable_beat=false`). `comb_ratio ≤ 0` is
  `lag_window.h`'s "no competitor" sentinel and never warrants; Dreams' 4.3 never warrants.
  An unwarranted fix still updates an already-active bank — warrant governs seeding only.
- **Seeding**: candidates at the fix offset (k=0) plus `fix ± k·beat.period_ms`, k=1..3,
  seeded k=0 outward; 30 ms dedup gate (reusing `kBeatCombAgreeMs`'s vetted tolerance)
  against live hypotheses. Each hypothesis is one `SyncEstimator` reused **verbatim** —
  `estimator.h/.cpp` are byte-untouched.
- **Admission**: per-hypothesis 1-dof χ² Mahalanobis gate on the innovation,
  `mht_chi2_gate_1dof = 3.841` (χ²(1, 0.95), Grinberg Eq. 3.2), generalizing the estimator's
  fixed `outlier_gate_ms`/`outlier_gate_max_p00` pair. Each hypothesis's own estimator is
  constructed with `outlier_gate_max_p00 = 0`, which provably disables its internal fixed
  gate (`p00_ < 0` is never true — verified against `estimator.cpp:79`), so the χ² gate owns
  admission exclusively.
- **The variance problem, resolved without touching the estimator**: `SyncEstimator`'s
  posterior covariance is private and §2.16 lists the estimator "Unchanged." Each
  hypothesis carries a **gate-only sidecar** 1-state variance recursion
  (`P += q·dt`; `S = P + R`; on admit `P ← P·R/S`; seeks widen by `seek_exec_var_ms2`)
  built from `EstimatorConfig`'s own constants. It never feeds state estimation.
- **IPDA existence** (Mušicki & Evans via Grinberg §4): birth 0.5; saturating rise
  `+0.25·(1−x)` per admitted fix; multiplicative ×0.6 penalty per gate-miss; continuous
  age decay `exp(−age/45 s)` applied lazily at query time (reusing `conf_age_tau_s`'s
  idiom and default). Prune below 0.05. `dominant_at` returns the highest-existence
  hypothesis; `valid` requires existence ≥ 0.75 **and** a valid estimate. Never a soft
  blend (PDA Eq. 3.6 explicitly rejected per §2.16).
- **Self-match hard limit**: the bank holds no self-match logic; the header carries the
  §2.16 restatement, and the wiring feeds it strictly downstream of the §7.3 guard.

### `core/src/synccore.cpp` + `core/src/policy/*` (Agent B; +109/−3, +27, +24/−3)

- Worker state gains the bank (default-disabled, same `EstimatorConfig{}` as the shared
  estimator) plus `last_beat`/`last_comb_ratio`, captured unconditionally at the
  `kSampleLatencyResidual` analysis moment (§2.10's "one shared analysis moment";
  `WindowLag{}`'s comb=0 sentinel makes the n≤0 case correct with no special-casing).
- `wk.mht.on_fix(...)` sits immediately after `policy.on_fix_accepted(t)` — strictly
  downstream of every self-match early return; the placement comment marks it as the
  invariant. Player states, local seeks, nudge, output latency, and the shared deadband
  are all forwarded wherever the estimator gets them; `wk.mht.reset()` joins the
  `kTrackLost` epoch rule.
- **Actuation** at `decide_ns`: `dom = mht.dominant_at(decide_ns)`;
  `policy.set_mht_hold(mht.active() && !dom.valid)`; the estimate fed to
  `emit_estimate` + `on_estimate` is the **dominant's** when it clears the threshold,
  the plain estimator's otherwise. Disabled/empty bank ⇒ hold false, plain estimate —
  byte-identical behavior.
- **Policy hold**: one new boolean (`set_mht_hold`; no `PolicyConfig` fields — the
  threshold lives in `MhtConfig`, the worker computes dominance, the policy learns one
  bit). Suppression joins the existing `probe_suppresses_seeks` flag, the single vetted
  point already gating all four seek-firing branches — so held cycles are provably
  no-proposal cycles (no phantom `awaiting_verify_`, no ring/cooldown corruption), and
  both `kTrackLost` checks run before the flag is even computed. Cleared in `reset()`.
- `tick()` deliberately does **not** substitute the dominant: `on_tick` never seeks, and
  §2.16's actuate-on-dominant rule governs seek actuation only (documented inline).

### Tests (Agent C + Agent B)

- `core/tests/test_hypothesis_bank.cpp` (full replacement of A's stub): **17 test
  functions, 63 CHECK/CHECK_NEAR assertions**, house harness style, tolerances derived
  from code constants. Covers: disabled-bank true no-op; all six warrant gates
  (Dreams 4.3, 0/negative sentinels, unstable beat both ways, zero period);
  warrant-governs-seeding-only; first-pass seeding shape **with the k=0-survives
  eviction-churn regression pin**; dedup; χ² routing to the matching tooth; the core
  Billie Jean AC (runs of 4 true admits vs. interspersed ±516 ms teeth → true hypothesis
  peaks ≥ 0.75, teeth prune below 0.05, bank 4→1); corroborated-bank
  resists-then-succumbs displacement (hand-traced margins); the dominant-validity
  regression pin; seek forwarding + gate widening (parallel plain/seeked banks);
  reset epoch rule; operator-new zero-allocation guard.
- `core/tests/test_policy.cpp` (append-only): 5 new tests (65 functions total) pinning
  hold-suppresses-instantaneous, hold-suppresses-persistence-gate, kTrackLost-live-while-
  held, release-restores-firing-without-residue, reset-clears-hold.

## Orchestrator corrections (found in first-hand verification, then fixed)

1. **Same-pass eviction churn (Agent A, real defect, traced).** The original
   `find_seed_slot` evicted the strict-minimum-existence slot on a full bank. On the
   first warranted fix into an empty bank, all same-pass seeds tie at birth existence,
   so the far teeth (−2, ±3) serially evicted slot 0 — **the k=0 hypothesis, the fix's
   own offset, was churned out by its own far teeth**. Directed fix: eviction requires a
   victim strictly **below birth existence**; no qualifying victim ⇒ seeding stops (k=0
   outward order keeps the most plausible candidates). Documented inline as a deliberate
   refinement of §2.16's eviction wording, with the consequence that a corroborated bank
   resists displacement until it decays below birth — both halves pinned by tests.
2. **`dominant_at` validity guard (Agent A).** A hypothesis seeded before any player
   state keeps a permanently invalid estimator (per `estimator.cpp`'s `has_player_`
   guard) while its existence can rise — `dominant_at` could have reported `valid=true`
   over a garbage estimate. Fixed (`valid` now requires `estimate.valid`); regression-pinned.
3. **House-convention cleanup (orchestrator, direct edit).** Removed a passing-path
   `printf` trace from the AC6 convergence test — house tests print only failures.
   Also corrected Agent C's self-reported count (16 → actually 17 test functions).

## Known behavioral notes (not defects; for the future corpus pass)

- The bank sees only fixes the **shared estimator accepted**, so a single wild tooth fix
  beyond the estimator's 1200 ms outlier gate (e.g. ±2·period ≈ 1032 ms is inside, but
  ±3 ≈ 1548 ms is outside when confident) can be rejected upstream and never offered to
  the bank. This dampens far-tooth corroboration; likely desirable, but it is a real
  asymmetry the corpus sweep should see.
- Heavy 50/50 alternation between two offsets equilibrates existence near ~0.27 — **no
  hypothesis can actuate**. This is by design (an unresolved comb must hold fire); the
  actuate threshold is only reachable with runs of ~4+ consecutive corroborating fixes.
- The hold bit is recomputed on every accepted fix (the only path that calls
  `on_estimate`), so it can never act between fixes; a stale hold has no effect.

## Verification (orchestrator, first-hand)

- Unified build in the canonical `build/core` tree: clean, `-Wall -Wextra -Wpedantic`,
  zero warnings.
- `ctest --test-dir build/core`: **100% passed, 0 failed out of 10** (9 pre-existing
  suites + `hypothesis_bank_tests`). Agents' independent scratch-tree runs (since
  removed) also 10/10.
- Direct runs: `hypothesis_bank_tests: all tests passed`; `policy_tests: all tests passed`.
- Diff audit: 8 files total (3 new). Existing tests byte-unmodified except the
  append-only `test_policy.cpp`. No estimator, oss_ring, lag_window, ABI-header, or
  Android/iOS changes. `abi_c_check.c` untouched (nothing to append).

## Remaining before promotion (unchanged from Issue #35's blockers)

1. DSP-01b beat-tracker field validation on-device.
2. §2.11-style corpus gate over `docs/sync-test-results.md` + FT8/FT9 captures.
3. Only then: a future spec section promotes `mht_enabled` (and re-tunes the provisional
   IPDA knobs against corpus evidence — birth/gain/miss-decay/τ are all uncorroborated).
