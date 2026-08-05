# FT9 Fixes Implementation Review — GRD-01 / IDC-01 / CTL-04 · 2026-08-04

**Scope.** Implements GitHub issues #32 (GRD-01, §2.13), #33 (IDC-01, §2.14),
and #34 (CTL-04, §2.15) against `technical-requirements.md` (commit d919c7e)
and `docs/to-spec-ft9-review.md`. #33's signed-off behavior change (aim-failure
now forces the same LOST→LISTENING→MATCHING re-bootstrap `onTrackLost()`
performs) is implemented as specified. No other behavior changes were made.

---

## #32 — GRD-01: self-play expected-URI latch (`SessionViewModel.kt`)

**Mechanism.** `startPlayback`'s `play(uri)` branch (not `resume()`) calls a
new `latchSelfPlay(uri)` before issuing the call. `handlePlayerState` checks
`consumeSelfPlayLatch(state.trackUri)` before the existing
`state.trackUri != commanded` test; a hit consumes the entry and skips the
guardian regardless of what `_syncState.track` currently holds; a miss falls
through unchanged.

**Constants** (top-level `private const val`, matching this file's existing
convention for `MAX_AIM_ATTEMPTS`/`AIM_VERIFY_DELAY_MS`/etc. — not a literal
Kotlin `companion object`, since `SessionViewModel` has none and the spec's
own cross-cutting note says the Kotlin realization is "a `SCREAMING_SNAKE_CASE`
constant," which this file already does at the top level):
`SELF_PLAY_LATCH_WINDOW_MS = 5000L`, `SELF_PLAY_LATCH_MAX_ENTRIES = 4`.

**Implementation choice — per-entry expiry job, not a nanotime comparison.**
Each latch entry expires via its own `scope.launch(dispatcher) { delay(...) }`
job (removed on a hit or an eviction) rather than a `System.nanoTime()`
timestamp checked lazily. This surfaced a real bug during implementation
(see Deviations/pitfalls below) and the fix is the job-based design: it's
virtual-time-friendly for the one test that wants the window to elapse, and
the *other* tests that don't want it to fire early use `runCurrent()` instead
of `advanceUntilIdle()` so they never drain a job scheduled 5 s out.

**State machine.** Unchanged. `reset()` now also cancels and clears
`selfPlayLatch` (epoch rule, matching `autoAdvanceHandled`'s existing
session-scoped clearing).

**Tests (`SessionViewModelTest.kt`, 5, all passing):**
- `selfPlayLatchSuppressesLateConfirmationAfterNewerResolutionSupersedesIt`
- `selfPlayLatchMissStillFiresGenuineAutoAdvance`
- `selfPlayLatchExpiredEntryFallsThroughToOrdinaryGuardianCheck`
- `selfPlayLatchBoundedAtMaxEntriesOldestEvicted`
- `ft9ThreeRestartReproductionProducesZeroGuardianFirings` (the exact FT9
  A→B→A sequence)

None of these tests emit into `FakeSpotifyController.playerStates` — every
existing test in this file already models a player-state confirmation via
`playerStateWatcher()`'s own SEED step, and reusing that (setting
`lastKnownPlayerState` immediately before the next resolution) reproduces
"a late confirmation lands once `_syncState.track` has already moved on"
deterministically, without the multi-collector fan-out ambiguity a live
`MutableSharedFlow` emission would introduce if two watchers were ever
simultaneously subscribed.

---

## #33 — IDC-01: post-lost/aim-failure identity corroboration (`SessionViewModel.kt`)

**(a) Aim-failure re-bootstrap (signed-off behavior change).**
`aimUntilLanded`'s give-up branch (after `MAX_AIM_ATTEMPTS`) now calls
`onTrackLost()` directly — literally reusing the same function
`SC_EVT_TRACK_LOST` invokes, rather than re-implementing the bootstrap. This
also means an aim-failure now counts toward the existing "3 consecutive
losses → error" rule and shares `onTrackLost()`'s re-arm cadence, which
matches the ticket's "force the SAME... re-bootstrap `onTrackLost()` already
performs" instruction literally.

**(b) Corroboration gate.** `armIdentCorroboration()` is called from inside
`onTrackLost()`'s re-bootstrap branch — the single call site both arming
paths ((a) above and a genuine `SC_EVT_TRACK_LOST`) now share, satisfying
"one set of thresholds for both" trivially (it's the same code path). The
gate lives in `resolveTrack`, which — unchanged from today — is invoked
unconditionally from `runRecognitionPass()` while `phase == MATCHING`,
*regardless* of what SyncCore's async `FixRejected`/`SyncEstimate` events
later say about that same fix. This is what makes AC4 (a SELF_HEARING-
rejected fix still recorded) true by construction: `resolveTrack` was never
gated on that verdict in the first place.

`resolveTrack` was split into `resolveTrackInfo` (the existing direct-URI/
backend-ISRC resolution, now returning `TrackInfo?` instead of dispatching)
and `resolveTrack` itself, which resolves the identity, then — only if
`identCorrobArmed` — runs it through `identCorroborate(uri, fix)` before
calling `resolvedWithAim`. When not armed, behavior is byte-identical to
before (resolve-on-first-fix).

**Constants:** `IDENT_CONFIRM_MIN_FIXES = 3`,
`IDENT_CONFIRM_OFFSET_AGREE_MS = 500L`, `IDENT_CORROB_MAX_AGE_MS = 30_000L`.

**Implementation choice — expiry clock is the fix's own `captureMonoNs`, not
a coroutine timer.** The streak's age is tracked via
`identStreakStartCaptureMonoNs` (the current streak's own first entry's
`captureMonoNs`) and compared against each *new* fix's `captureMonoNs` —
checked reactively inside `identCorroborate`, with no scheduled job at all.
This was a deliberate redesign after the first attempt (a `delay(30_000)`
job) proved untestable: `advanceUntilIdle()` — the dominant idiom throughout
this test file — drains *any* pending coroutine work regardless of how far
in the future it's scheduled, so a background expiry job gets silently
executed the moment any unrelated `advanceUntilIdle()` call runs, corrupting
streak state between fixes in every other test. `maybeSampleReferee`'s own
doc comment already documents this exact "free-running timer" pitfall for a
looping `delay()`; a one-shot job hits the identical failure mode the first
time a test calls `advanceUntilIdle()` after arming. Reusing `captureMonoNs`
(already the mechanism's own wall-clock reference for the offset-agreement
check) needs no timer, is exercised by ordinary `on_estimate`-style test
construction, and is the design actually shipped. On expiry, the streak
clears silently (no escalation) and the arriving fix becomes entry 1 of a
fresh streak — the mechanism keeps sampling indefinitely rather than ever
locking out corroboration permanently, since spec text says expiry means
"the session simply keeps sampling in MATCHING," not that the gate itself
disarms.

**State machine.** Unchanged (§2.4), matching the ticket's own "unchanged"
list. `reset()` now also calls `clearIdentCorroboration()`.

**Tests (`SessionViewModelTest.kt`, 5, all passing):**
- `identityCorroborationResolvesOnThirdAgreeingFixNotTheFirst`
- `identityCorroborationDisagreeingFixMidStreakRestartsInsteadOfAccumulating`
- `aimFailureForcesLostListeningMatchingRebootstrapAndArmsCorroboration`
- `selfHearingRejectedFixStillRecordedAndCountsTowardStreak`
- `corroborationStreakExpiresWithoutEscalatingToError`

Driven via `engine.emit(SyncCore.Event.RequestFix)` for the 2nd/3rd fix in a
streak rather than the shell's own `RECOGNITION_RETRY_MS` timer — `RequestFix`
is the same public trigger `onEngineEvent` already routes to
`runRecognitionPass()`, so this isn't a new seam, just using an existing one
on demand instead of waiting out a real timer.

**Open questions from the PM review doc** (§2.14's three "Open questions for
the PM") are NOT re-litigated here — this ticket implements the ONE decision
the orchestrator prompt states is already made ("one set of thresholds for
both arming paths"; "corroboration-window expiry stays silent"). The other
two ((1) distinct UI copy on expiry, (3) whether N-of-M is an acceptable
interim bar) remain open per the spec's own text and are out of this
ticket's scope.

---

## #34 — CTL-04: convergence settling hysteresis (`core/src/policy/policy.{h,cpp}`)

**New `PolicyConfig` fields:** `settle_enter_threshold_ms = 150.0`,
`settled_confirm_min_fixes = 5`, `settled_confirm_agree_ms = 40.0`. New
private state: `bool settled_ = false;`. No C ABI changes — `synccore.h`
untouched, as required.

**Mechanism, as shipped:**
- **Entry.** Inside the existing `awaiting_verify_` block (the post-settle
  verify fix), if `|e| <= settle_enter_threshold_ms`, `settled_ = true`. No
  new dwell timer, per spec.
- **Bypass.** `|e| >= large_correction_threshold_ms` unconditionally sets
  `settled_ = false` (checked immediately after the `lost_threshold_ms`
  check, which itself calls `reset()` and therefore already clears
  `settled_`).
- **While settled:** `effective_min_fixes`/`effective_agree_ms` resolve to
  `settled_confirm_min_fixes`/`settled_confirm_agree_ms` instead of
  `confirm_min_fixes`/`confirm_agree_ms`, and are used by BOTH the
  persistence-gate branch (`else if`) and a new settled-hold branch inserted
  between the large-hold and the plain-instantaneous-fire cases.
- **Settled-hold branch gating — `settled_ && |e| >= deadband_ms`, not
  `settled_ && (|e| >= deadband_ms || |predicted| >= deadband_ms)`.** See
  Deviations below — this exact condition is why the mechanism doesn't gate
  the drift-preemption path.
- **Ring population — `est.converged || settled_`, not `est.converged`
  alone** for the shared ring-append/clear at the top of `on_estimate`. See
  Deviations below — this is why the settled-hold's own evidence can
  accumulate at all.
- Every seek-firing branch (large-hold corroborated fire, plain
  instantaneous fire, settled-hold fire, persistence-gate fire) sets
  `settled_ = false` explicitly (redundant in the large-hold case, since the
  bypass already cleared it that same call — kept for the epoch rule's own
  literal "clears on any emitted seek" wording). `reset()` clears `settled_`.

### Deviations from the literal spec text, with justification

Both deviations below were discovered by actually building and running
`policy_tests`, not by inspection — the initial literal implementation
(gate the settled-hold on `est.converged`, exactly like §2.7's persistence
gate; hold ANY proposal, instantaneous or preemptive, while settled) passed
zero new tests written for it but **broke two existing, unmodified closed-
loop simulations**: `test_closed_loop_sawtooth_within_deadband` (worst
|error| after 60 s ballooned from ≤25 ms to 251 ms) and
`test_closed_loop_genuine_large_jump_corrects` (final true error stuck at
−54 ms instead of converging to ≤25 ms, permanently — see below).

1. **The settled-hold does not gate on `est.converged`, and the ring
   population itself gains an `|| settled_` clause.** `Estimate.converged`
   is defined by the estimator as `converged_ && |e| <= cfg_.deadband_ms`
   (`estimator.cpp:176`) — and the shell always sets the estimator's
   `deadband_ms` to the SAME value as the policy's (`synccore.cpp:1058-1060`).
   An out-of-deadband residual — exactly the population §2.15 targets
   (Billie Jean's 542/547 ms readings) — can therefore **never** be
   `converged` by construction. Gating the settled-hold's own ring
   accumulation on `est.converged` (as the persistence gate's *existing*,
   *unmodified* population rule does) made the bar structurally
   unsatisfiable: `test_closed_loop_genuine_large_jump_corrects`'s
   post-correction residual sat at a stable −54 ms forever, never
   accumulating a single ring sample because every one of those samples was,
   by definition, not converged. Fix: `if (est.converged || settled_)
   ring_append(...) else ring_clear();` — while settled, evidence
   accumulates on the samples' own mutual agreement (`ring_all_agree`), not
   on the estimator's separate convergence flag. When `settled_` is false
   (every existing test), this is byte-identical to the original rule.

2. **The settled-hold only engages when the *actual* error crosses
   `deadband_ms`, not when only the drift-*predicted* error does (§6.3 skew
   pre-emption).** `test_closed_loop_sawtooth_within_deadband` proves the
   sawtooth stays inside a 25 ms deadband under 0.05 % source skew
   specifically *because* pre-emptive corrections fire on a *predicted*
   future crossing while the actual `|e|` is still small — continuous,
   ongoing drift-tracking evidence, not a discrete residual bump like Billie
   Jean's. Holding those hostage to 5-sample agreement defeats pre-emption's
   entire purpose (by the time 5 samples agree, the drift they're meant to
   pre-empt has already grown past where pre-emption would have caught it),
   and is exactly what broke the sawtooth sim. Fix: the settled-hold branch
   condition is `settled_ && std::abs(e) >= cfg_.deadband_ms` (not
   `std::abs(predicted)`); a purely preemptive proposal still falls through
   to the ordinary immediate-fire branch even while settled.

Both fixes are documented inline in `policy.cpp` at the exact lines they
apply, including the discovered-via-regression provenance, so a future
reader doesn't have to rediscover this by re-breaking the sims.

**Orchestrator correction (post-review).** Verification found a third gap in
the same family: the plain-instantaneous fire branch (the final
`else if (!probe_suppresses_seeks)` in `on_estimate`, reached when
`settled_` is true but `|e|` itself stays inside `deadband_ms` — i.e. exactly
the preemptive case deviation 2 above deliberately keeps firing) did not
clear `settled_` on its own emitted seek. A badly-landed preemptive seek
could therefore leave `settled_ = true` against a fresh 150–1000 ms residual,
keeping the raised 5-fix bar in place exactly when spec obligation 4
("settled clears on ANY emitted seek — instantaneous, persistence-gate, or
settled-gate") says recovery must not be slowed. `settled_ = false;` was
added at the end of that branch (right after `ring_clear()`), and this is
now pinned by `test_settled_clears_on_preemptive_instantaneous_seek` below —
this was not caught by the original 5 tests because none of them drove a
*predicted*-only crossing (`|e|` inside the deadband, `|predicted|` outside
it) while settled.

**Tests (`test_policy.cpp`, 6, all passing; every pre-existing test in the
file passes byte-unmodified):**
- `test_settled_entry_on_floor_landing_and_verify` (AC1)
- `test_settled_raises_bar_for_instantaneous_path_until_corroborated` (AC2)
- `test_settled_large_or_lost_error_bypasses_immediately` (AC3)
- `test_settled_clears_on_emitted_seek_and_on_reset` (AC4)
- `test_settled_clears_on_preemptive_instantaneous_seek` (AC4's preemptive
  case — the orchestrator correction above)
- `test_settled_never_spuriously_enters_during_harmonic_ambiguous_churn`
  (AC5 — the Test 1 trim=0 reconstruction: 43–52 ms readings interleaved
  with 1257/1639 ms harmonic readings)

Since internal state (`settled_`) has no public accessor (matching this
file's existing convention of testing only through `on_estimate`'s returned
`Action`), each test proves entry/exit/clearing via the resulting BEHAVIORAL
contrast — e.g. AC1 proves entry by showing a subsequent single out-of-
deadband proposal is held where an identical one fired instantly before
settling.

---

## Verification (exact counts)

**Core (`cmake --build build/core` then `ctest --test-dir build/core`):**
```
100% tests passed, 0 tests failed out of 9
  synccore_tests, estimator_tests, policy_tests, correlate_tests,
  input_level_tests, dsp_tests, test_oss_ring, lag_analyzer_selftest,
  synccore_abi_c_check
```
`policy_tests` itself: 60 test functions (54 pre-existing + 6 new — 5 from
the original CTL-04 pass plus the orchestrator-correction pin), 196 `CHECK`
assertions, `policy_tests: all tests passed` (0 failures).

**Android (`.\gradlew.bat :app:testDebugUnitTest` then `:app:assembleDebug`,
run from `android\`):**
```
BUILD SUCCESSFUL — 146 tests, 0 failures, 0 errors (across all Android unit
test classes)
SessionViewModelTest: 85 tests (75 pre-existing + 10 new: 5 GRD-01 + 5
IDC-01), 0 failures
```
`:app:assembleDebug` succeeded, including the `arm64-v8a`/`armeabi-v7a`/
`x86_64` CMake native builds against the modified `policy.{h,cpp}` — i.e. the
core changes were also confirmed to build under the Android NDK toolchains,
not just the desktop test toolchain.

## Things not independently re-verified

- The Android instrumented/emulator path was not run (no device/emulator in
  this environment) — only `assembleDebug` (compiles + links, including the
  native `.so`s) and the JVM unit-test suite.
- `docs/to-spec-ft9-review.md`'s three open PM questions for §2.14 are
  unresolved by design (not this ticket's job); flagged again here so they
  aren't lost.
