# CTL-02 review — persistence gate + dynamic deadband · 2026-07-29

**Status: landed and verified in commit `5f03d08`.** One correction to the
resumption premise: the session did not die mid-implementation — the full
`/to-spec → /to-tickets → /implement` chain completed in one pass (Sonnet
subagents, orchestrator-verified per the project convention). This file is
the completion review that chain owed.

## What shipped

The mechanism specced in `technical-requirements.md` §2.7 and ticketed as
CTL-02a/CTL-02b (backlog Epic 8): a second, slower correction gate that
fixes field test 8's Vienna/Dreams class — a stable ~300 ms echo standing
forever inside the Android shell's 350 ms deadband — without reintroducing
the deadband-150 churn the live experiment measured.

While converged, `CorrectionPolicy` accumulates each accepted fix's
residual into a fixed-size ring (N=8, no heap). When ≥3 samples span
≥20 s, all agree within 60 ms of their mean, and |mean| exceeds the 125 ms
floor (RFC 5905's STEPT ambiguity — 125 in its Figure 27 table vs .128 in
its Appendix A code — resolved deliberately to the table's value), the
policy emits one correction from the **cluster mean** through the existing
drift-centered target formula. The ring clears on every seek, on any
non-converged estimate, and on `reset()` (epoch rule). While an above-floor
cluster is open, fix cadence tightens from 30 s to 10 s so corroboration
arrives in ~30 s. The instantaneous `deadband_ms` gate and the FT4
confidence floor are untouched; at core defaults (deadband 25 < floor 125)
the mechanism is structurally inert.

## Files touched

| File | Change |
|---|---|
| `core/src/policy/policy.h` | 4 new `PolicyConfig` fields (`confirm_min_fixes=3`, `confirm_window_ns=20 s`, `confirm_agree_ms=60`, `confirm_floor_ms=125`), ring storage + helpers |
| `core/src/policy/policy.cpp` | ring maintenance, corroboration-hungry cadence, persistence trigger as an `else if` after the (unchanged) instantaneous deadband path |
| `core/tests/test_policy.cpp` | 10 added tests, zero existing tests modified: Vienna-class, window-span-required, churn-never-fires, floor, confidence, two clearing tests, cadence, plus two closed-loop sims |
| `technical-requirements.md` | new §2.7 (the governing spec, literature-cited) |
| `backlog-tickets.md` | Epic 8 (CTL-02a/b), CTL-03 row (the old CTL-02 large-correction text, preserved), status table |
| `android/.../session/SessionGraph.kt` | `ENGINE_DEADBAND_MS` comment redirect only — no functional Kotlin change, constant stays 350 |

## Verification (run first-hand, not agent-claimed)

- Core: `cmake --build` + `ctest` — **8/8 suites pass**. `policy_tests`
  diagnostics: `vienna persistence sim: 15 fixes, 1 seeks, converged at
  21.0s, fired at 41.0s, final true error = −50.0 ms` (fires 20 s after
  convergence, well inside the ≤90 s bound, lands under the 125 ms floor);
  `stability sim: 13 fixes, 0 seeks` (±300 ms scatter never fires — the
  deadband-150 lesson holds at 350); existing sawtooth sim within all
  original bounds.
- Android: `:app:testDebugUnitTest --rerun-tasks` — **126/126 green**
  (~22 s, no virtual-time hang).
- Orchestrator review of the agent diff found and fixed two items: a
  missing negative test for the `confirm_window_ns` span condition (added
  as `test_persistence_gate_window_span_required` — without it, dropping
  the span term would have passed the whole suite) and a misleading
  "slower-cadence" phrase in the SessionGraph comment.

## What's next (user-confirmed order)

1. **CTL-03 — comb-flatness/ambiguity gate** (spec pass pending): expose
   `analyze_window`'s top-K peak-ratio score; corrections >1000 ms need a
   corroborating second fix or post-seek verification (FT8's 1259 ms
   single-fix overshoot).
2. **MHT** — parallel `SyncEstimator` bank for Billie Jean-class material
   (still needs a beat-period source — known research gap).
3. **CTL-01** — active probe + referee sentinel for the self-match bug.

Post-landing field check for CTL-02: the five-cycle repeatability protocol
(`field-test-8-results.md` addendum) — Vienna/Dreams should now correct to
near-floor within ~30–60 s of lock instead of holding the echo.
