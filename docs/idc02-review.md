# IDC-02 Review — GitHub #37 · 2026-08-13

**Scope.** Implements the two PM behavior decisions recorded on issue #37
(comment, 2026-08-13): (1) the fast-switch (`room changed songs → re-aim`)
path now requires a second agreeing fix before actuating, instead of a
single wildly-off fix; (2) the natural-end / auto-advance re-listen tail
(`stopFollowingAndRelisten`) now arms a reduced 2-fix identity corroboration
gate, distinct from the full 3-fix gate `onTrackLost` keeps.

---

## Decision 1 — fast-switch corroboration

### Pending-candidate state: a separate slot, not a reuse of the ident streak

`SessionViewModel.kt` gains three new private fields:

```kotlin
private var fastSwitchPendingUri: String? = null
private var fastSwitchPendingOffsetMs: Long = 0L
private var fastSwitchPendingCaptureMonoNs: Long = 0L
```

and one function, `evaluateFastSwitchCandidate(fix)`, which runs the same
agreement math `identCorroborate` uses (same URI, offset delta tracking the
wall-clock delta within `IDENT_CONFIRM_OFFSET_AGREE_MS`) but against a
single previous-fix slot rather than an N-deep streak.

**Why a separate slot instead of reusing/parametrizing `identStreak*`:**
the existing streak fields are scoped to the *armed* corroboration gate
(`identCorrobArmed`, `armIdentCorroboration`/`clearIdentCorroboration`/
`identCorroborate`), whose lifecycle is orthogonal to the fast-switch path:

- The gate arms only on a **cold re-bootstrap into MATCHING**
  (`onTrackLost`, and now `stopFollowingAndRelisten`) and is asserted
  unarmed the rest of the time. The fast-switch path fires from an
  **active, non-MATCHING tracking session** (AIMING/CONVERGING/LOCKED/
  DRIFTING) that must stay armed=false and undisturbed throughout.
- Sharing one set of fields would mean a fast-switch candidate could
  corrupt an in-flight armed streak (or vice versa) if both were ever live
  at once, and would force the streak's now-parametrized threshold
  (`identCorrobMinFixes`, decision 2) to also apply here — when decision 1
  only ever needs exactly 2, not a parametrized N.
- With a fixed 2-fix requirement, "the streak's first entry" and "the
  pending candidate" are the same fix — a lone previous-fix slot is
  sufficient; a full streak counter would be over-built for what this
  decision asks for.

The task description offered either option as an implementer's call; this
is that call, documented at the field declaration site in the source
(`SessionViewModel.kt`, ~line 2085).

### Behavior

`runRecognitionPass`'s fast-switch branch (`SessionViewModel.kt`, the
`fix.spotifyUri != currentUri && isOffsetWildlyOff(fix)` branch) now calls
`evaluateFastSwitchCandidate(fix)` (inside `synchronized(sessionLock)`)
instead of actuating immediately:

- **No pending candidate, or a non-agreeing/different-URI fix**: the
  candidate is (re)opened at this fix; no switch; the session (phase,
  track) is undisturbed.
- **An agreeing second fix** (same URI as the pending candidate, offset
  delta tracking the wall-clock delta within 500 ms): confirmed — runs the
  same LOST→LISTENING→re-aim sequence as before, using this (the newest)
  fix's data, exactly like `identCorroborate`'s "act on the newest entry"
  convention.
- **Expiry**: before the agreement check, a pending candidate older than
  `IDENT_CORROB_MAX_AGE_MS` (measured from the candidate's own
  `captureMonoNs`, reactive against the next fix — no new timer, same
  reasoning as `identStreakStartCaptureMonoNs`'s KDoc) is cleared first,
  so the next fix becomes a fresh candidate rather than a confirmation.
- `engine.submitRecognitionFix` remains unconditional, before this branch —
  unchanged. Every pending or restarted fix still reaches the engine as a
  sync observation.
- Epoch rule: `reset()` clears `fastSwitchPendingUri` (mirrors the existing
  `selfPlayLatch`/`clearIdentCorroboration()` epoch clears).

### sessionLock discipline

`evaluateFastSwitchCandidate` is only ever called from inside
`synchronized(sessionLock) { evaluateFastSwitchCandidate(fix) }` in
`runRecognitionPass` — the function itself does not acquire the lock (it
assumes the caller holds it, documented in its KDoc), matching this file's
existing convention for small helpers called from one guarded site rather
than re-entering the monitor internally. The confirmed-switch's own
LOST→LISTENING→`onMatchInFlight()` triplet is a second, separate
`synchronized` block immediately after — unchanged from the pre-existing
code, and `resolveTrack(fix)` (suspending, backend I/O) stays outside both,
exactly as before.

---

## Decision 2 — natural-end / auto-advance re-listen: reduced 2-fix gate

### Parametrization

`armIdentCorroboration` now takes `minFixes: Int = IDENT_CONFIRM_MIN_FIXES`:

```kotlin
private fun armIdentCorroboration(minFixes: Int = IDENT_CONFIRM_MIN_FIXES) = synchronized(sessionLock) {
    identCorrobArmed = true
    identCorrobMinFixes = minFixes
    identStreakCount = 0
    identStreakUri = null
}
```

A new field, `identCorrobMinFixes`, holds the *active* threshold; the
existing hardcoded `IDENT_CONFIRM_MIN_FIXES` literal is gone from
`identCorroborate`'s escalation check and from the `identCorrob: streak
N/M` log line, both of which now read `identCorrobMinFixes` — per the
task's explicit instruction, the log line prints the threshold actually in
force, not a hardcoded 3.

A new named constant, following the file's `IDENT_*` naming convention:

```kotlin
private const val IDENT_RELISTEN_MIN_FIXES = 2
```

`onTrackLost`'s call site (`armIdentCorroboration()`, no argument) is
**unchanged** — it keeps the full 3-fix default. `stopFollowingAndRelisten`
now calls `armIdentCorroboration(IDENT_RELISTEN_MIN_FIXES)` in the same
position `onTrackLost` arms its own gate (immediately before
`onMatchInFlight()`/the re-bootstrap's first recognition pass), and only
inside the existing `if (recognition == null) return@synchronized` guard —
i.e. it never arms when there is no recognizer to feed it, matching
`onTrackLost`'s own guard shape.

`clearIdentCorroboration` resets `identCorrobMinFixes` back to
`IDENT_CONFIRM_MIN_FIXES` for hygiene (not required for correctness, since
every `armIdentCorroboration` call sets it explicitly, but keeps the field
never observably stale between epochs).

### sessionLock discipline

No new lock scope: `identCorrobMinFixes` is written only inside the
existing `armIdentCorroboration`/`clearIdentCorroboration`
`synchronized(sessionLock)` bodies, and read only inside
`identCorroborate`'s own `synchronized(sessionLock)` body — the same
guarding `identStreakCount`/`identStreakUri`/etc. already had.

---

## Cold-start / engine-submission guardrails (unchanged, verified)

- `startListening()`'s bootstrap never calls `armIdentCorroboration` —
  cold-start MATCHING is untouched, still single-fix.
- `engine.submitRecognitionFix` is called unconditionally in
  `runRecognitionPass`, before both the resolve-track and fast-switch
  branches — unchanged by either decision.
- The CTL-05 `fixdbg` line was not touched.

---

## Tests (append-only, `SessionViewModelTest.kt`)

6 new tests, all passing, none touching pre-existing tests:

**Fast-switch (decision 1):**
- `fastSwitchSingleWildlyOffFixDoesNotSwitch` — a single different-URI
  wildly-off fix opens a candidate but does not switch; phase/track
  undisturbed; the fix still reached `engine.submittedFixes`.
- `fastSwitchSecondAgreeingFixSwitchesCleanly` — a second agreeing fix
  confirms in one clean switch; asserts `identCorrobArmed` stays `false`
  throughout (proving the fast-switch path never touches the unrelated
  IDC-01 gate).
- `fastSwitchNonAgreeingSecondFixRestartsTheCandidate` — a different-URI
  second fix restarts the candidate (not accumulate); a third fix agreeing
  with the *restarted* candidate then confirms.
- `fastSwitchCandidateExpiresViaMaxAgeRule` — a candidate older than
  `IDENT_CORROB_MAX_AGE_MS` expires silently; the next fix opens a fresh
  candidate; a further agreeing fix against the fresh candidate confirms.

**Natural-end / auto-advance (decision 2):**
- `naturalEndRelistenRequiresTwoAgreeingFixesNotOne` — invokes the private
  `stopFollowingAndRelisten` via reflection (same seam
  `autoAdvanceGuardianFiresExactlyOnceUnderConcurrentDuplicateReports`
  already uses for `onSpotifyAutoAdvanced`); one fix does not resolve, a
  second agreeing fix does.
- `trackLostRelistenStillRequiresThreeFixesAfterIdc02` — regression guard:
  re-confirms `onTrackLost`'s own gate still requires 3 fixes after the
  parametrization, guarding against a wrong default sneaking into
  `armIdentCorroboration()`.

Fast-switch tests deliberately construct the `SessionViewModel` **without**
a `spotify` controller (`isOffsetWildlyOff` then reads `spotify?.
lastKnownPlayerState` = null → always "wildly off", exactly the condition
needed; `startPlayback` no-ops on a null `spotify`), the same seam
`aimFailureForcesLostListeningMatchingRebootstrapAndArmsCorroboration`
already established. The natural-end test uses the same no-`spotify`
construction for the ViewModel itself (a throwaway `FakeSpotifyController`
is passed only as `stopFollowingAndRelisten`'s own `controller` argument,
for its `.pause()` call) — otherwise a confirmed resolution's
`aimUntilLanded` would run its own real (and irrelevant to this test)
4-attempt aim-verification loop against a mic-less fake.

## Test evidence

```
./gradlew.bat :app:testDebugUnitTest
BUILD SUCCESSFUL

TEST-...SessionViewModelTest.xml: tests=94 skipped=0 failures=0 errors=0
(88 pre-existing + 6 new; whole module: 10 classes, 106 tests, 0 failures/errors)
```

## Existing-test conflicts

**None found.** Searched `SessionViewModelTest.kt` for any test that
constructs a `SessionViewModel` with a non-null `recognition` provider AND
either (a) drives the fast-switch branch (a different-URI, wildly-off fix
while not in MATCHING) or (b) reaches `stopFollowingAndRelisten` (via
`onSpotifyAutoAdvanced` or the end-of-track timer). Every pre-existing test
that exercises `onSpotifyAutoAdvanced`/`stopFollowingAndRelisten`
(`selfPlayLatchMissStillFiresGenuineAutoAdvance`,
`selfPlayLatchExpiredEntryFallsThroughToOrdinaryGuardianCheck`,
`selfPlayLatchBoundedAtMaxEntriesOldestEvicted`,
`ft9ThreeRestartReproductionProducesZeroGuardianFirings`,
`autoAdvanceGuardianFiresExactlyOnceUnderConcurrentDuplicateReports`)
constructs its `SessionViewModel` with `recognition` left at its default
`null`, so `stopFollowingAndRelisten`'s `if (recognition == null)
return@synchronized` returns before reaching the new
`armIdentCorroboration(IDENT_RELISTEN_MIN_FIXES)` call — those tests only
assert on `pause()` call counts, never on post-relisten resolution timing.
No pre-existing test drives a different-URI fix through the fast-switch
branch either (every `FakeRecognitionProvider`/`FakeQueuedRecognitionProvider`
scenario in the file either stays in cold-start MATCHING or drives the
3-fix `onTrackLost` gate). All 88 pre-existing tests pass byte-unmodified.

## Files touched

- `android/app/src/main/java/com/jointheparty/app/ui/session/SessionViewModel.kt`
  (+152/−20): new `IDENT_RELISTEN_MIN_FIXES` constant, new
  `fastSwitchPending*` fields + `evaluateFastSwitchCandidate`, parametrized
  `armIdentCorroboration`/`identCorroborate`, `stopFollowingAndRelisten`'s
  new arm call, `reset()`'s new epoch clear, updated `sessionLock` KDoc.
- `android/app/src/test/java/com/jointheparty/app/ui/session/SessionViewModelTest.kt`
  (+217/−0, append-only): 6 new tests, no existing test modified.
- `docs/idc02-review.md` (this file): new.
