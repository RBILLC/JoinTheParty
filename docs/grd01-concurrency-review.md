# GRD-01 Concurrency Fix Review — GitHub #32 (reopened) · 2026-08-12

**Scope.** Fixes the field-test-10 regression that reopened GitHub #32
(GRD-01): a FATAL `IndexOutOfBoundsException` in `SessionViewModel
.consumeSelfPlayLatch` plus a same-family double guardian fire in
`onSpotifyAutoAdvanced`, both real-multi-thread-only races invisible to the
JVM suite's single-threaded `StandardTestDispatcher`. Also implements the
two instrumentation/doc sub-items of #37 that live in this file: an
`identCorrob: streak N/3` log line, and the KDoc/comment fix for the
"aim-failure arms the gate via `aimUntilLanded`" discrepancy. No product
behavior change beyond eliminating the races and adding the log line.

---

## The defect

`SessionViewModel` runs its session coroutines on `dispatcher`, which
defaults to `Dispatchers.Default` (`SessionViewModel.kt:422`) — a genuinely
multi-threaded pool, not a single confined thread. Several of the
ViewModel's shared mutable session fields are plain `var`/`mutableListOf`
properties with no synchronization, mutated from coroutines that can land
on different worker threads:

- `selfPlayLatch` (a plain `mutableListOf`): `consumeSelfPlayLatch`'s
  `indexOfFirst` → `removeAt(idx)` window could race `latchSelfPlay`'s own
  per-entry expiry job (`removeAll`), a concurrent `latchSelfPlay` eviction
  (`removeAt(0)`), or another concurrent `consumeSelfPlayLatch` call. FT10
  crashed the app here: `IndexOutOfBoundsException: Index 0 out of bounds
  for length 0` at `ArrayList.remove` under real Billie Jean churn (63
  phase transitions, 14 LOST cycles in ~2 minutes).
- `autoAdvanceHandled`: `onSpotifyAutoAdvanced`'s `autoAdvanceHandled ==
  actualUri` check and `stopFollowingAndRelisten`'s `autoAdvanceHandled =
  uri` write were an unsynchronized check-then-act. FT10 caught two threads
  (13606/13849) both pass the check for the same stale confirmation and
  both run the full pause/LOST/re-listen sequence.
- `transition()`'s `from`-read → `isLegalTransition` check → `_syncState
  .update` was the same check-then-act shape, at the single gate every
  phase change passes through — FT10's log shows duplicated
  LOST→LISTENING→MATCHING chains at 10:52:59.917–.922.
- The IDC-01 corroboration fields (`identCorrobArmed`, `identStreakCount`,
  `identStreakUri`, `identStreakLastOffsetMs`,
  `identStreakLastCaptureMonoNs`, `identStreakStartCaptureMonoNs`) and
  `consecutiveLosses` are read-modify-written from more than one coroutine
  (the single engine-event collector calling `onTrackLost`, and
  `aimUntilLanded`'s give-up path also calling `onTrackLost` from its own
  `startPlayback` coroutine; `resolveTrack`'s corroboration check running
  inside `runRecognitionPass`'s coroutine).
- `endOfTrackJob`, identified while reading the rest of the file: read/
  cancelled/reassigned from `scheduleEndOfTrackPause` (every
  `playerStateWatcher()` collector) and `stopFollowingAndRelisten` — two
  functions that CAN run concurrently, since nothing cancels an older
  `playerStateWatcher()` when a newer `startPlayback` call spawns a fresh
  one (e.g. overlapping re-resolutions).

The existing 5 GRD-01 JVM tests all passed because they run on
`StandardTestDispatcher`, which is single-threaded — this class of race is
only reachable when coroutines land on different real OS threads, which
only happens on `Dispatchers.Default` in production.

## Mechanism chosen: plain JVM `synchronized`, not a coroutine `Mutex`

A single `private val sessionLock = Any()` field, documented at its
declaration site (`SessionViewModel.kt`, next to `consecutiveLosses`).
Every function that reads-then-writes one of the fields above wraps its
critical section in `synchronized(sessionLock) { ... }`.

**Why not `kotlinx.coroutines.sync.Mutex`.** Most of the guarded call sites
— `transition`, every shell-driven intent (`startListening`,
`onTrackResolved`, `reset`, ...), `onSpotifyAutoAdvanced` — are ordinary
non-suspend functions called synchronously from the UI thread (Compose
callbacks call `vm.startListening()` etc. directly, not from a coroutine).
A `Mutex` requires `withLock { ... }`, which is `suspend`; adopting it would
have forced every one of those call sites to become `suspend`, rippling the
signature change into Compose's calling convention, for a critical section
that never itself suspends or blocks on I/O. There is no benefit to paying
that cost.

**Why plain `synchronized` is safe here.**
- It is reentrant per thread, so the nesting this file already has —
  `onTrackLost` → `transition` / `armIdentCorroboration`,
  `onSpotifyAutoAdvanced` → `stopFollowingAndRelisten` → `transition`,
  `resolveTrack`'s outer lock → `identCorroborate`'s own lock — is free.
- No guarded block ever suspends inside the monitor. `scope.launch(...)`
  calls inside a `synchronized` block are fine (launching is not
  suspending); the one genuinely suspending call in this area,
  `resolveTrack`'s `resolveTrackInfo(fix)` (backend I/O) and
  `runRecognitionPass`'s `resolveTrack(fix)` call, are deliberately kept
  **outside** every `synchronized` block — see the fast-switch branch in
  `runRecognitionPass`, where only the plain `transition`/`onMatchInFlight`
  triplet is locked and `resolveTrack(fix)` runs unlocked afterward.
- Under the JVM suite's single-threaded `StandardTestDispatcher`, the
  monitor is never contended (there is only ever one thread), so
  virtual-time scheduling (`advanceUntilIdle`/`runCurrent`) is completely
  unaffected. This is a purely real-multi-thread-only guard, which is
  exactly the property the task asked for.

A confined single-thread dispatcher for session mutations was considered
and rejected: it would have meant either (a) hardcoding a real dedicated
thread distinct from the test-injected `dispatcher`, breaking the tests'
ability to control scheduling deterministically via `StandardTestDispatcher`,
or (b) routing every mutation through `withContext(singleThreadDispatcher)`,
which is `suspend` — the same signature-ripple problem as `Mutex`. An actor
was considered and rejected for the same non-suspend-call-site reason, plus
added complexity (a channel + message types) disproportionate to the size
of the critical sections involved.

## Exactly what is guarded, and where

| State | Guarded in |
|---|---|
| `selfPlayLatch` | `latchSelfPlay` (eviction + append + the expiry job's `removeAll`), `consumeSelfPlayLatch`, `reset()` |
| `autoAdvanceHandled` | `onSpotifyAutoAdvanced`, `stopFollowingAndRelisten`, `reset()` |
| `identCorrobArmed`, `identStreakCount`, `identStreakUri`, `identStreakLastOffsetMs`, `identStreakLastCaptureMonoNs`, `identStreakStartCaptureMonoNs` | `armIdentCorroboration`, `clearIdentCorroboration`, `identCorroborate`, `resolveTrack`'s armed-check |
| `consecutiveLosses` | `transition` (reset on LOCKED), `onTrackLost` (increment), `reset()` |
| `transition()`'s from→to legality check + `_syncState.update` | `transition` itself |
| `endOfTrackJob` | `scheduleEndOfTrackPause`, `stopFollowingAndRelisten`, `reset()` |
| The `LOST`/`LISTENING`/`onMatchInFlight` chains outside `onTrackLost`/`stopFollowingAndRelisten` | `runRecognitionPass`'s fast-switch branch and sampling-cap branch, each wrapped as one atomic sequence |

**Deliberately left unguarded**, with reasoning:
- `firstEstimateSeen`/`samplingAttempts` are touched inside the now-locked
  `onTrackLost`/`stopFollowingAndRelisten`/`reset()` bodies (so those
  writes are guarded), but their reads/writes inside `runRecognitionPass`
  and `onSyncEstimate` are not. Torn reads of a `Boolean`/`Int` here cannot
  corrupt a collection or double-fire a side effect the way `selfPlayLatch`
  or `autoAdvanceHandled` can — worst case is one extra or one fewer
  recognition retry, which self-corrects on the next pass. Guarding them
  fully would mean locking `runRecognitionPass`'s entire hot path (network
  I/O adjacent) for no crash-preventing benefit, so this was left alone as
  a conscious scope boundary.
- `refereePendingRouteId`/`refereePendingResidualsMs` were re-examined and
  left alone: unlike `playerStateWatcher()`, `engine.events` has exactly
  one collector, started once in `init` and never re-launched, so the
  existing doc comment's "runs synchronously on the single events-collector
  coroutine" claim still holds and this state is not in the racy family.
- `lastEstimateErrorMs`/`lastEstimateConfidence` were already marked
  `@Volatile` before this change (a single-writer/multi-reader shape,
  adequate for a plain most-recent-value read) — left as is.

## #37 instrumentation + doc fixes (same file)

- `identCorroborate` now logs `identCorrob: streak N/3 (uri)` on every
  record/extend/restart, inside the same `synchronized` block as the streak
  mutation it describes (so the log line and the state it reports can never
  disagree).
- The KDoc on `identCorrobArmed` (previously ~:851–852) and the comment in
  `onTrackLost` at the `armIdentCorroboration()` call site (previously
  ~:2288–2289) both claimed `aimUntilLanded` arms the gate. Corrected: no
  arming call exists in `aimUntilLanded`; its give-up path arms the gate
  only indirectly, by calling `onTrackLost()` itself (the same re-bootstrap
  any other track-lost takes). `armIdentCorroboration()`'s only direct call
  site remains inside `onTrackLost`. No behavior change — `aimUntilLanded`
  was NOT given a new arming call, per the task's explicit instruction.

## Tests

**Existing suite:** all 88 tests in `SessionViewModelTest.kt` (85 pre-existing
+ 3 new below) pass unmodified — no existing test needed to change.
Whole-module `testDebugUnitTest`: 10 test classes, 100 tests total, 0
failures/errors.

**Pre-fix verification (manual, not part of the committed suite).** Before
writing the permanent tests, I built a throwaway standalone class in the
test source set mirroring the OLD unsynchronized `selfPlayLatch`/
`latchSelfPlay`/`consumeSelfPlayLatch` shape byte-for-byte (same
`mutableListOf`, same eviction, same per-entry `delay()`-based expiry job,
shortened to 50 ms so the repro runs fast), stress-tested with 20,000
concurrent latch+consume pairs on a real 8-thread pool. Result: **21,102
errors out of 40,000 concurrent consume attempts** —
`NullPointerException`/`ArrayList` corruption from the same "unsynchronized
`ArrayList` mutated from multiple real worker threads" root cause as FT10's
`IndexOutOfBoundsException`. The throwaway file was deleted immediately
after this one confirmation run; it was never committed and is not part of
the diff.

**New permanent tests** (`SessionViewModelTest.kt`, real multi-threaded
`Executors.newFixedThreadPool(8).asCoroutineDispatcher()`, not
`StandardTestDispatcher`):

1. `consumeSelfPlayLatchSurvivesConcurrentLatchAndConsumeChurn` — 20,000
   concurrent latch+consume pairs (reflection into the private
   `latchSelfPlay`/`consumeSelfPlayLatch`, bypassing the phase-transition
   gate so the primitives themselves are hammered directly) against the
   real, now-synchronized code. Zero errors.
2. `consumeSelfPlayLatchSurvivesConcurrentEntryExpiryRace` — fills the
   4-entry ring, then hammers `consumeSelfPlayLatch` continuously across a
   real ~5.5 s window so calls land both well before and right at/after
   each entry's own scheduled expiry `removeAll` fires — the specific
   mutation site FT10's crash trace named. Zero errors. (This is the one
   genuinely slow test in the new set — `SELF_PLAY_LATCH_WINDOW_MS` is a
   compile-time constant, not test-injectable, so there is no way to
   shorten the real wait without touching production code.)
3. `autoAdvanceGuardianFiresExactlyOnceUnderConcurrentDuplicateReports` —
   200 concurrent calls into the private `onSpotifyAutoAdvanced` reporting
   the SAME auto-advanced URI. Asserts exactly one `pause()` call reaches
   `FakeSpotifyController` — directly reproducing and closing FT10's
   two-thread double-fire.

All three passed on every run (verified 3x for the two fast tests plus one
run of the slow one) with no flakiness observed.

## Task output summary

```
./gradlew.bat :app:testDebugUnitTest
BUILD SUCCESSFUL

TEST-...SessionViewModelTest.xml: tests=88 skipped=0 failures=0 errors=0
(whole module: 100 tests across 10 classes, 0 failures, 0 errors)
```

## Behavior notes

No product behavior changes beyond eliminating the races and adding the
`identCorrob` log line. In particular:
- `aimUntilLanded` was NOT given a new arming call — only its documentation
  was corrected, per the task's explicit instruction not to add arming
  there.
- The fast-switch path (`runRecognitionPass`'s "room changed songs →
  re-aim" branch) and the natural-end re-listen path remain unarmed, as
  before — that coverage gap is issue #37's PM-facing question, out of
  scope for this pass.
- Under genuinely concurrent real-world events (e.g. an engine `TrackLost`
  racing an `aimUntilLanded` give-up), the exact WINNER of a race is still
  whichever thread's `transition()` call lands first — that non-determinism
  is inherent to two real concurrent events and is unchanged by this fix.
  What changes is that the loser's redundant chain now cleanly no-ops
  (idempotent `transition()`, deduped `autoAdvanceHandled`,
  `consumeSelfPlayLatch` never sees a torn list) instead of corrupting
  shared state or double-firing a side effect.
