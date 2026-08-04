# DSP-03a review — volume-duck C ABI, worker dip detector & policy verdict · 2026-08-03

**Status: implemented and verified (this session).** Spec
`technical-requirements.md` §2.12 (composing with the shipped §2.9 pause
probe, CTL-01a), ticket DSP-03a in backlog Epic 9. Adds the `SC_EVT_ACTIVE_DUCK`
C ABI surface, a worker-side matched-filter capture-energy dip detector, and
the `CorrectionPolicy` verdict/escalation machinery that composes with the
already-landed pause-probe sentinel. No existing test's expected output
changed; `SC_EVT_ACTIVE_PROBE`/`sc_evt_active_probe_t`/`sc_notify_probe_executed`
are byte-untouched.

## ABI additions (`core/include/synccore/synccore.h`)

Append-only, exactly per §2.12:

```c
SC_EVT_ACTIVE_PROBE,      /* payload: sc_evt_active_probe_t */
SC_EVT_ACTIVE_DUCK        /* payload: sc_evt_active_duck_t  -- NEW, appended last */

typedef struct { int32_t duck_ms; } sc_evt_active_duck_t;

sc_status_t sc_notify_duck_executed(sc_session_t*, int32_t achieved_deci_db);
```

`abi_c_check.c` gained a `case SC_EVT_ACTIVE_DUCK:` in the exhaustive
`event_is_known` switch (so a future missing enumerator still fails
`-Wswitch`), a field init/read check on `sc_evt_active_duck_t`, and a call to
`sc_notify_duck_executed` on a fresh session (no duck outstanding — still
`SC_OK`), mirroring the file's existing `SC_EVT_ACTIVE_PROBE` coverage
exactly.

## The tier switch (R1) and why

§2.12's own text says both "triggers arm duck FIRST" and "CTL-01a's existing
tests pass unmodified" — those conflict unless the promotion is gated. Per
the orchestrator's ruling, `PolicyConfig::duck_tier_first` (default `false`)
resolves it:

- **`false` (default, every existing test's configuration):**
  `try_request_probe` falls straight through to the legacy pause-request
  gates/cooldown, byte-identical to shipped CTL-01a. `duck_request_due`
  never returns `true`.
- **`true` (this ticket's new tests only):** both triggers
  (`on_referee_window` starvation, `on_tick` Wittenmark turn-off) route to
  `try_request_duck` instead. The pause probe becomes reachable **only**
  through `on_duck_result`'s inconclusive-escalation path (R3) — never a
  second independent trigger.

Per §2.12's sequencing note, flipping this default on-device is explicitly a
**future** change gated on the CTL-01 pause probe being field-proven there;
this ticket lands the mechanism, not the promotion (DSP-03b doesn't touch it
either).

## Deferred-detector timing (R2)

The dip window is `[echo_ns − 250 ms, echo_ns + duck_ms + 750 ms]` — audio
*past* the echo hasn't been captured yet when the shell's echo (`kDuckExecuted`)
is processed. So the worker defers:

```
kDuckExecuted:  stamp wk.duck_echo_ns = wk.now_ns
                store wk.duck_achieved_deci_db
                arm   wk.duck_analysis_pending = true

tick(), every iteration:
  if duck_analysis_pending and
     wk.now_ns >= duck_echo_ns + duck_ms + 750ms + 250ms(margin):
    run_duck_analysis()   # matched filter, calls policy.on_duck_result, apply()
    duck_analysis_pending = false
```

```
capture time  ───────────────────────────────────────────────────▶
              │←── 3s baseline ──→│←250ms→│←── duck_ms+750ms ──→│
              [ ...clean audio... ][ search window: echo lands here ]
                                          ▲
                                      echo_ns (kDuckExecuted processed)
                                                                  ▲
                                                    ready_ns = echo_ns + duck_ms + 1000ms
                                                    (tick() runs the analysis once now_ns ≥ this)
```

The extra 250 ms margin on top of the window's own +750 ms reach exists so
`tick()` only fires once the *last* hop of the search window has actually
drained into `sc_copy_recent_capture`'s history — a pure worker-poll-cycle
concern, not part of the window's own acoustic reach.

Pending-analysis state (`duck_analysis_pending`, `duck_echo_ns`,
`duck_achieved_deci_db`) is worker-local and cleared in `apply()`'s
`kTrackLost` branch alongside the OSS ring reset — a re-listen is a new
epoch, and pending analysis from before it must never resolve into it.

## Detector math (`sc_session::run_duck_analysis`)

Reuses `sc_copy_recent_capture`'s existing 12 s post-AEC history — no new
capture tap:

1. **Envelope:** 20 ms non-overlapping RMS hops (`kDuckHopFrames = 960`),
   `e(j) = 10·log10(mean(x²) + 1e-12)`, computed over the combined
   baseline+search span `[echo−3.25s, echo+duck_ms+750ms]`.
2. **Baseline:** the first `kDuckBaselineHops = 150` hops (the fixed
   preceding 3 s) — median (for `D`) and MAD about that median (for `z`),
   computed into two stack `std::array<double, 150>`s, no heap allocation.
3. **Matched filter:** a rectangular template `max(1, duck_ms/20)` hops wide
   (integer division; 150 → 7 hops) slides across every position in the
   search-window hops; at each position `D = baseline_median − mean(template)`;
   keep the max.
4. **Significance:** `z = D / (1.4826·MAD)`, guarded to `0` when `MAD == 0`.
5. Capture-time↔buffer-index mapping uses `sc_copy_recent_capture`'s
   documented `out_end_mono_ns` pairing (`buffer[n-1]` ⇔ `out_end_ns`, one
   sample period per preceding frame) — not an assumed alignment.

Insufficient history (session too young, or the copy doesn't reach back far
enough to cover the 3.25 s baseline) short-circuits to
`on_duck_result(0.0, 0.0, /*achieved_deci_db=*/0, now)` — **corrected by the
orchestrator during review**: the original code passed the real achieved
depth with `D = 0`, which does NOT take the inconclusive path — `0 ≤` the
scaled 1.5 dB room band, so it resolved room-dominant and CLEARED sentinel
suspicion off no evidence at all. Passing achieved `0` triggers
`on_duck_result`'s explicit no-depth guard, which forces the inconclusive
resolution (escalate once, never silently drop, never falsely clear). The
achieved-0→inconclusive policy behavior is pinned by
`test_duck_verdict_scaling`'s second block. In practice these paths are
near-unreachable from legitimate triggers (starvation needs 45 s, the
turn-off dwell 20 s, so a real duck never fires into a <3.25 s session),
which is why no dedicated worker-level test pins them.

**Scratch reuse (steady-state zero-allocation):** a dedicated
`wk.duck_scratch` (12 s float history copy, sized once) and `wk.duck_hops`
(cleared/reused, not reallocated once the hop count — constant per session,
since `duck_ms` never changes at runtime — stabilizes) are used, deliberately
**siblings** of `residual_scratch` rather than a shared buffer: they're
called from `tick()`, not `process()`, and keeping them independent avoids
any aliasing risk if the two call sites are ever reordered or parallelized.
The baseline median/MAD scratch is fixed-size (`std::array<double,150>`),
avoiding heap allocation entirely for that piece.

## Verdict mechanism: why `on_duck_result` returns an `Action` directly

The pause probe's verdict (`on_probe_executed` → `on_estimate`) is
necessarily **deferred across multiple future estimates**, because a pause
perturbs the playback timeline and the verdict signal is *whether the
estimator's residual shifted* — something only visible over several
subsequent fixes. The duck's verdict signal is different in kind: capture
energy dip depth/significance, computed **once, entirely worker-side**, by
the time `on_duck_result` is called. There is nothing to wait for.

So `on_duck_result(dip_db, z, achieved_deci_db, now_ns)` decides immediately
and **returns an `Action`** — the exact same type/mechanism `on_estimate`
itself returns for `kTrackLost`. The worker's `run_duck_analysis` calls
`apply(wk.policy.on_duck_result(...))`, identically to how `process()` calls
`apply(wk.policy.on_estimate(...))`. This keeps worker plumbing uniform (one
`apply()` consumes every `Action`-producing decision) and needed no second,
parallel notification path. The ticket's own acceptance criteria ("`on_duck_result`
returns `kTrackLost`") independently confirm this was the intended shape.

One consequence: unlike `on_probe_executed`, there is **no
`on_duck_executed`-equivalent method in the policy** — the policy has
nothing to snapshot at echo time (no pre-duck error baseline is needed,
since the verdict never reads the estimator), so the worker's echo handling
is purely local bookkeeping (see the timing section above), and the policy
only ever hears about a duck via `try_request_duck` (arm) and
`on_duck_result` (verdict). This is documented in `policy.h`'s doc comment
on `on_duck_result`.

## Verdict bands and R4 scaling

At the nominal 6 dB commanded duck: `D ≥ 4 dB ∧ z ≥ 3` → self-dominant
(`kTrackLost`, via `reset()` — same recovery path a self-match pause verdict
uses); `D ≤ 1.5 dB` → room-dominant (cleared, `duck_escalated_` reset);
otherwise (including `z < 3`) → inconclusive → escalate.

When `achieved_deci_db ≠ 60`, the two `D` thresholds scale linearly by
`achieved_db / 6.0` (the mixture model's dip depth scales with commanded
depth); `duck_min_z` is **not** scaled — it's a noise-floor significance
test, not a depth-dependent quantity. `achieved_deci_db ≤ 0` forces both
`self_dominant`/`room_dominant` false regardless of `D`/`z` (a duck that
commanded no depth proves nothing) but still falls through to the
inconclusive/escalation branch — the episode/escalation still consumes
normally, per the ruling.

## Escalation & cooldown semantics (R3)

An inconclusive verdict escalates **at most once per duck episode**
(`duck_escalated_`, set at duck-request-arm time and belt-and-suspenders
alongside `duck_outstanding_` itself already blocking a stray second
`on_duck_result` call — since `on_duck_result` always clears
`duck_outstanding_` on its first, real call for an episode). The escalation:

- Arms the pause probe **immediately**, bypassing `try_request_probe`'s own
  settling/playback/cooldown gates — it continues the *same* probe episode
  the duck opened, not a fresh independent request.
- **Re-anchors** `last_probe_request_ns_` to the escalation time — kept
  correct for any later independent pause-probe trigger, though today that
  path is unreachable while `duck_tier_first` is set (`try_request_probe`
  always routes to the duck tier).
- Is **exempt from `probe_cooldown_ns`** (120 s, unchanged) by construction,
  since it never goes through `try_request_probe`'s cooldown check at all.

Duck cooldown (`duck_cooldown_ns = 60 s`, proposed/field-tunable, shorter
than the pause probe's 120 s because a −6 dB/150 ms duck is
near-inaudible) anchors at duck-*request* time (`last_duck_request_ns_`,
set in `try_request_duck`) and is unaffected by whether/how the episode
later resolves.

**Duck expiry** reuses §2.9's never-echoed pattern: if the episode hasn't
resolved (via `on_duck_result`) within `probe_verdict_window_ns` (20 s) of
the request, `on_tick` clears `duck_outstanding_` unfired. Because the duck
has no per-echo policy state (unlike the probe), one time-driven check
covers both "the shell never echoed" and "the echo landed but the worker's
analysis never resolved a verdict in time" — in practice the analysis
window (≤ ~1.4 s for the field-tunable 150–400 ms `duck_ms` range) sits far
inside the 20 s expiry, so this simplification has no practical effect but
is called out here as a deliberate one.

**Seek suppression:** `probe_suppresses_seeks` in `on_estimate` extends to
`probe_outstanding_ || duck_outstanding_` — a single flag name, extended
rather than duplicated, so every downstream use (instantaneous correction
path and the §2.7 persistence gate) treats a duck and a pause probe
identically.

## Test inventory

**`core/tests/test_policy.cpp`** (additive only, zero existing edits; all
new tests set `cfg.duck_tier_first = true` unless noted):

- `test_duck_first_starvation_trigger_arms_duck` / `test_duck_first_turnoff_trigger_arms_duck`
  — both triggers arm `duck_request_due`, never `probe_request_due`.
- `test_duck_tier_first_default_false_regression` — the exact
  `test_referee_sentinel_starvation_requests_probe` drive, at default
  config: pins the pause probe still arms and `duck_request_due` never
  fires.
- `test_duck_verdict_self_dominant_track_lost` — `on_duck_result(5.0, 4.0, 60, t)` → `kTrackLost`.
- `test_duck_verdict_room_dominant_clears_and_can_rearm` — `on_duck_result(1.0, 5.0, 60, t)`
  → cleared; after the 60 s cooldown, the still-active turn-off dwell
  re-arms a fresh duck request.
- `test_duck_verdict_inconclusive_escalates_once` — `on_duck_result(2.5, 4.0, 60, t)`
  → escalates exactly once; a second (stray) `on_duck_result` call fires no
  second escalation.
- `test_duck_verdict_z_gate_inconclusive_escalates` — deep-but-insignificant
  dip (`z=1.0`) is inconclusive, not self-dominant; still escalates.
- `test_duck_verdict_scaling` — achieved 30 (3 dB) halves the self-dominant
  threshold (2.2 dB, z=4.0 → `kTrackLost`); achieved 0 → inconclusive
  regardless of `D`, still escalates.
- `test_duck_cooldown_enforced` — second duck request blocked inside 60 s,
  allowed past it; `probe_cooldown_ns` (120 s) is untouched by this ticket
  and already regression-checked by every existing CTL-01a cooldown test.
- `test_seek_suppressed_while_duck_outstanding` — out-of-deadband estimate
  while a duck is outstanding → `kNone`; track-lost precedence still holds.
- `test_duck_state_cleared_on_reset` — `reset()` clears `duck_outstanding_`;
  a post-reset `on_duck_result` call is treated as a stray result (no
  verdict, no escalation) — deliberately built so a broken (no-op) reset
  would be caught.
- `test_stray_duck_result_ignored` — nothing outstanding → ignored.

**`core/tests/test_synccore.cpp`** (additive):

- `test_duck_executed_echo_contract` — null session rejected; a stray echo
  on a live session is harmless (`SC_OK`, no event, session works
  afterward) — mirrors `test_probe_executed_no_pending_is_safely_ignored`.
- `test_duck_deferred_detector_finds_dip` — ~7 s of constant-amplitude
  noise pushed through the real capture path, with a 150 ms segment scaled
  to 0.5 amplitude (a −6.02 dB *power* dip) placed so it falls entirely
  inside `[echo−250ms, echo]`, `sc_notify_duck_executed(60)`, more capture
  pushed past the analysis deadline, then `sc_test_get_duck_metrics`:
  **`dip_db=5.91` (within 1.5 dB of 6.0), `z=48.28`** (well above 3).
- `test_duck_deferred_detector_no_dip_reads_near_zero` — identical
  layout/timing with no dip segment: **`dip_db=0.07`, `z=0.74`**.

`abi_c_check.c` coverage described above.

## A bug caught during self-verification (test-only)

The first pass at the two deferred-detector tests read `dip_db=0.00,
z=0.00` — indistinguishable at a glance from "genuinely near zero," but
wrong for the dip test. Two independent bugs, found by instrumenting
`run_duck_analysis`/`tick` with temporary debug prints (removed before
finalizing):

1. **Floating-point truncation in the test's own timing constants.**
   `kDuckTestEchoS` was computed as `5.0 + 0.10 + 0.15 + 0.10` (a `double`),
   then `* 48000` cast to `size_t` — the accumulated binary rounding landed
   fractionally under `5.35`, and the cast **truncates**, not rounds, so
   the intended 256800-sample echo offset became 256799. Since
   `dsp_push_click_range`'s push loop only pushes whole 480-frame blocks
   (`while (pushed + 480 <= count)`), that single-sample shortfall dropped
   an entire trailing 10 ms block, desynchronizing the echo point from the
   dip's placement by 10 ms. **Fixed** by rewriting every segment boundary
   as an exact integer sample count (`kDuckTestBaselineSamples = 5 * 48000`,
   etc.) — eliminates the whole class of bug rather than papering over one
   instance.
2. **A genuine race between the test's second push and the worker's command
   queue.** `worker_loop()` swaps its pending-command snapshot, *then*
   drains whatever's currently sitting in the RT ring, *then* processes the
   swapped commands — all in one iteration. `sc_notify_duck_executed`
   returns immediately after enqueueing (non-blocking, per its documented
   contract), so the test's very next line (pushing the post-echo audio)
   could flood the ring well before the worker ever dequeued the
   `kDuckExecuted` command. When that happened, the *same* loop iteration's
   drain step swept in all of the post-echo audio too, advancing
   `wk.now_ns` past the intended echo point **before** the echo was
   stamped — `duck_echo_ns` ended up equal to the full pushed span (8.0 s)
   instead of the intended 6.35 s. **Fixed** by adding a 50 ms pause
   between the echo call and the second push (well over the 2 ms worker
   poll interval, with nothing new to drain in the meantime) — this is a
   test-harness fix only; it reflects how a real shell's echo always
   happens strictly after the duck it's reporting on, never racing ahead of
   audio that hasn't been captured yet.

Neither bug touched production code in `core/src/`; both were purely in the
new tests' own construction. Documented here because the failure mode (exact
`0.00/0.00` output) is easy to misread as "the detector legitimately found
nothing" rather than "the detector never got valid input to look at."

## Deviations / latitude taken

- **Verdict mechanism** (worker plumbing choice, ticket-sanctioned
  latitude): `on_duck_result` returns an `Action` rather than setting state
  for a later `on_estimate`/`on_tick` call to pick up — see "Verdict
  mechanism" above for the reasoning.
- **Scratch buffers**: dedicated `wk.duck_scratch`/`wk.duck_hops` siblings
  of `residual_scratch`, not a shared buffer — see "Detector math" above.
- **Analysis-when-no-request**: the detector only ever runs when
  `wk.duck_analysis_pending` is armed by a real `kDuckExecuted` echo; there
  is no "stray echo still runs analysis" path, since the worker has nothing
  to analyze without a stamped `duck_echo_ns`. A stray
  `sc_notify_duck_executed` call (no policy-level duck outstanding) still
  arms the worker's analysis pipeline **unconditionally** — the worker
  doesn't consult policy state before honoring the echo (mirrors
  `kProbeExecuted`'s own unconditional-processing shape) — but on a fresh
  session with no capture history this harmlessly resolves to the
  insufficient-history/inconclusive path, and `on_duck_result` itself still
  ignores it as a stray result at the policy layer. Confirmed exercised by
  `test_duck_executed_echo_contract`.
- **`template_hops` rounding**: `duck_ms / 20` uses C++ integer division
  (150 → 7, not the mathematical 7.5) — a simple, deterministic choice
  within the ticket's "max(1, duck_ms/20 ms)" wording.

## Orchestrator verification findings (two fixes applied after review)

1. **Insufficient-history paths resolved the wrong verdict band.** All three
   degenerate returns in `run_duck_analysis` passed the REAL achieved depth
   with `D = 0` — which lands room-dominant (0 ≤ the scaled 1.5 dB band) and
   would have CLEARED sentinel suspicion off no evidence, contradicting both
   the code's own comment ("the inconclusive path handles it") and ruling
   R2's intent. Fixed to pass `achieved_deci_db = 0`, which triggers the R4
   no-depth guard and forces the inconclusive resolution (escalate once).
   Near-unreachable from legitimate triggers (they require 20–45 s of
   session first), but a wrong comment beside wrong behavior is exactly the
   kind of latent trap the next reader steps into.
2. **Missing load-bearing negative test: duck expiry.** No test exercised
   `on_tick`'s duck-expiry block — it could be deleted and the whole suite
   stayed green, leaving a never-echoed duck outstanding forever (seeks
   suppressed indefinitely, no future duck able to arm). Added
   `test_duck_expires_when_never_echoed`: suppression holds while
   outstanding, then 21 s later (past `probe_verdict_window_ns`, still
   inside the 60 s cooldown so no re-arm can mask the check) the same
   out-of-deadband estimate must seek again. Proven to bite by temporarily
   disabling the expiry block (`if (false && ...)`) — it fails exactly on
   `freed.kind == kSeek` — then re-verified green with the block restored.
   Same class as CTL-02's originally-missing window-span pin and DSP-01a's
   frozen-ring pin.

Post-fix verification: full rebuild, 9/9 ctest, `synccore_tests` re-run
clean, and the five policy sim diagnostic lines byte-identical to the
pre-DSP-03a baseline (sawtooth 34/14/297/23.4 · vienna 1 seek/−50 ms ·
stability 0 · phantom 0 large/1.2 ms · genuine-jump 1 large/−22 ms).

## Build & verify (first-hand)

```
export PATH="C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin:C:/Users/RBILLC/tools/cmake/bin:$PATH"
cd build/core && cmake --build . && ctest --output-on-failure
```

```
Test project C:/Users/RBILLC/source/repos/JoinTheParty/build/core
1/9 Test #1: synccore_tests ...................   Passed   16.03 sec
2/9 Test #2: estimator_tests ..................   Passed    0.01 sec
3/9 Test #3: policy_tests .....................   Passed    0.01 sec
4/9 Test #4: correlate_tests ..................   Passed    1.05 sec
5/9 Test #5: input_level_tests ................   Passed    9.68 sec
6/9 Test #6: dsp_tests ........................   Passed    0.96 sec
7/9 Test #7: test_oss_ring ....................   Passed    0.35 sec
8/9 Test #8: lag_analyzer_selftest ............   Passed    0.06 sec
9/9 Test #9: synccore_abi_c_check .............   Passed    0.01 sec

100% tests passed, 0 tests failed out of 9
Total Test time (real) =  28.16 sec
```

`policy_tests.exe` run directly — the five closed-loop sim diagnostic lines
are **unchanged** from the pre-ticket baseline (byte-for-byte, confirmed by
running the suite before touching any file and again after):

```
sawtooth sim: 34 fixes, 14 seeks, learned latency 297 ms, worst |error| after 60 s = 23.4 ms
vienna persistence sim: 15 fixes, 1 seeks, converged at 21.0s, fired at 41.0s, final true error = -50.0 ms
stability sim: 13 fixes, 0 seeks (expect 0)
phantom-fix sim: 24 fixes, 7 seeks (0 of magnitude >=1000ms), final true |error| = 1.2 ms
genuine-jump sim: 19 fixes, 2 seeks (1 of magnitude >=1000ms), final true error = -22.0 ms
```

`synccore_tests.exe` re-run 3× back to back (DSP-01b precedent —
threading flakes hide in single runs): all three runs pass, and the duck
detector's own printed diagnostics are stable across all three
(`dip_db=5.91 z=48.28` / `dip_db=0.07 z=0.74`, identical every run).

## What's next

DSP-03b (Kotlin volume-duck actuator + JNI echo plumbing) is next in the
Epic 9 chain — it lands the shell-side mechanism only, per §2.12's
field-sequencing note the ticket itself restates: the duck becomes the
default probe tier on-device only after the CTL-01 pause probe is
field-proven there. `duck_tier_first` stays `false` in production config
until that separate, future promotion.
