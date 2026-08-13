# CTL-05 implementation review — self-match guard mis-anchoring fix

**Ticket:** [#36](https://github.com/RBILLC/JoinTheParty/issues/36) (`CTL-05`).
**Contract:** `docs/ctl05-investigation.md` §6 ("Proposed fix"). This
document records implementation choices against that design, where §6 left
latitude, and the test evidence.

## 1. What changed, in one paragraph

`core/src/synccore.cpp`'s `kRecognitionFix` handler (CORE-06 self-match
guard) now does two things it didn't before: (1) a fix rejected as
`SELF_HEARING` still updates a candidate-timeline bookkeeping slot, so a
real, mutually-consistent second timeline can promote itself once two of
its fixes agree with each other, instead of only after
`kMaxConsecutiveSelfRejects` fixes have been discarded outright; and (2) a
local corrective seek now arms a **post-seek anchor reconfirmation**
requirement — the live, arbitration-capable room anchor is frozen at its
pre-seek value until two post-seek fixes agree with each other, so a single
post-seek fix can never alone re-confirm the guard's authority the way
FT10's fix B did. `estimator.h/.cpp` are byte-untouched. `policy.cpp` is
untouched (the PM brief's "policy.cpp" pointer was investigated and is not
where the mechanism lives — see the CORRECTION note in the task brief,
confirmed by re-reading `docs/ctl05-investigation.md` §2, which traces the
whole cascade to `synccore.cpp`'s guard alone). One bounded Kotlin log-line
extension surfaces `frequency_skew`.

## 2. The anchor state machine (design choice — §6 left this to be decided)

Investigation §6.2 proposed "requiring the room-continuity anchor to be
corroborated by two agreeing fixes… in the `settle_ns`-adjacent window
after any seek" but left the exact mechanism open. Two designs were
considered and rejected before landing on the one shipped:

- **Reject-all-during-pending.** Suppress the self-hearing check entirely
  while a post-seek anchor is unconfirmed (mirroring "the very first fix of
  a session can't arbitrate" literally). Rejected: this fully disarms
  protection against a *genuinely* self-hearing fix landing in that window,
  which is exactly the failure mode CORE-06 exists to prevent, and nothing
  in the investigation asks for that trade.
- **Downgrade-confirmed-on-first-post-seek-fix.** Let the ordinary
  `tracks_room`/`tracks_cand` bookkeeping keep running unmodified, but have
  it set `room_anchor_confirmed = false` (instead of `true`) on the first
  post-seek fix that would otherwise re-confirm, requiring a second such
  fix to flip it back. Rejected: this still lets the anchor's *value*
  update immediately (to the first fix's own reading), and — worse — once
  that first fix downgrades `confirmed` to `false`, a genuinely
  self-hearing **second** fix sails through unguarded until some later fix
  happens to re-confirm, which is a materially longer and less predictable
  protection gap than the shipped design.

**Shipped design.** Three new `sc_session::wk` fields
(`anchor_pending_reconfirm`, `post_seek_cand_offset_ms`,
`post_seek_cand_ns`), all worker-thread-only, zero heap, cleared on
`kTrackLost` (epoch rule) exactly like `room_anchor_offset_ms` and
`cand_offset_ms` already are:

- **Arm.** `sc_notify_seek_issued` → `anchor_pending_reconfirm = true`,
  `post_seek_cand_offset_ms = -1`. `room_anchor_offset_ms` /
  `room_anchor_confirmed` are **not** touched — they stay at their pre-seek
  values and keep their full pre-seek arbitration authority.
- **While pending, every fix (accepted or about-to-be-rejected) checks a
  SEPARATE slot.** Same `kRoomContinuityGateMs` (500 ms) tolerance already
  trusted for "two fixes agree on a new room timeline" at the pre-existing
  `tracks_cand` branch — reused rather than inventing a new constant, per
  the investigation's own "mirroring" framing. If this fix agrees with the
  candidate left by the previous post-seek fix, it **promotes**: the live
  anchor jumps to this fix's own value, `room_anchor_confirmed = true`,
  pending clears. If not, this fix becomes the new candidate (the window
  slides forward one fix at a time — same idiom as the ordinary
  `cand_offset_ms` "hold aside" branch).
- **The verdict on the fix that triggers promotion is snapshotted BEFORE
  promotion runs**, and the ordinary `tracks_room`/`tracks_cand` bookkeeping
  is skipped for any fix processed while pending (`was_pending`). This is
  what stops a single fix that merely tracks the **stale, frozen** pre-seek
  anchor from silently re-confirming on its own — the literal FT10
  mechanism (fix B tracked the pre-seek anchor by a ~100 ms coincidence and
  instantly regained full authority). A fix that itself corroborates and
  promotes the anchor is judged, for its OWN accept/reject verdict, against
  the anchor as it stood at entry — promotion never retroactively un-rejects
  the fix that triggered it (§7.3: rejected fixes must not gain adoption
  power they didn't have).
- **Expiry / fallback.** No new timer was added. Two existing mechanisms
  already bound this: `kRoomPredictionMaxAgeNs` (90 s) ages out the frozen
  anchor exactly as it would have anyway, and `kMaxConsecutiveSelfRejects`
  (3) still drops the anchor if post-seek corroboration never happens
  (noisy/perpetually-aliased recognizer output) — in which case
  `anchor_pending_reconfirm` is now also explicitly cleared at the drop, so
  the subsequent fresh reseed (the pre-existing anti-poisoning path) isn't
  left permanently gated by a pending flag with nothing left to reconfirm.

## 3. How SELF_HEARING interacts with candidate bookkeeping now

Before: the self-hearing branch returned immediately on rejection, before
`synccore.cpp`'s "maintain room timeline" `tracks_cand`/`cand_offset_ms`
bookkeeping (the pre-existing mechanism for **accepted** fixes) ever ran.
Rejected fixes were invisible to arbitration.

After: the self-hearing verdict is computed and **snapshotted** first (so a
fix's own eventual promotion can't retroactively change its own verdict),
then — only while a post-seek anchor is pending — the corroboration check
above runs regardless of whether the verdict will be accept or reject, then
the (unchanged) rejection early-return fires if the snapshot says reject.
A rejected fix's `(offset, capture_time)` therefore always reaches the
post-seek candidate slot; its rejection status is untouched. This is scoped
**only** to the post-seek pending window, not globally — see §5 below for
why a global version was rejected.

## 4. §7.3 protections regression-pinned

- `test_self_hearing_guard`, `test_self_match_guard_recovers_from_bad_reference`,
  `test_self_match_guard_ignores_unconfirmed_reference` (pre-existing,
  `core/tests/test_synccore.cpp`) — **byte-unmodified**, all pass. None of
  these tests issue a seek, so `anchor_pending_reconfirm` is never armed in
  any of them and every new code path is a structural no-op — this is not
  incidental, it's why the design is safe: the new mechanism is additive
  and only engages after `sc_notify_seek_issued`.
- New `test_post_seek_single_fix_cannot_reanchor` — after exactly one
  post-seek fix that coincidentally tracks the stale anchor (FT10's fix-B
  shape), a genuinely self-hearing fix immediately after is still rejected
  `SELF_HEARING`. Proves the guard stays fully armed through the pending
  window, not weakened.
- New `test_post_seek_two_agreeing_fixes_reanchor` — after two post-seek
  fixes promote a new anchor, a fix that genuinely self-hears **relative to
  the new anchor** is still rejected. Proves promotion grants real
  arbitration authority, not just passive acceptance.

## 5. A residual, bounded, explicitly-flagged risk

Genuinely self-heard audio is, by construction, internally self-consistent
(it dead-reckons off the SAME local clock the guard's own
`local_audible_ms` extrapolates from), so two genuinely-self-hearing
rejected fixes in a row will generally also satisfy the new post-seek
corroboration check. This means: if a genuine self-match begins **exactly**
in the post-seek pending window and produces two agreeing fixes before the
window resolves any other way, it could promote — gaining an anchor pointed
at our own audio for that window. This is why the mechanism was scoped
strictly to the post-seek pending window rather than made global (an
earlier design draft that let ANY two agreeing rejected fixes promote,
anywhere, was rejected during design specifically because it broke
`test_self_match_guard_recovers_from_bad_reference`'s three mutually-
agreeing self-matches — see git history of this review's authoring notes).
Within the post-seek window, this residual risk is structurally identical
to, and no larger than, the pre-existing anti-poisoning rule that a
session's very first fix can't be arbitrated against (also two-fix-to-earn-
trust, also exploitable in principle by two coincidentally-agreeing
self-matches at session start) — an accepted, pre-existing trade-off this
change extends to one more (bounded, post-seek-only) situation rather than
introducing a new class of gap. §2.9's referee-sentinel/probe mechanism
(agreement-starvation detection, independent of this per-fix guard) remains
fully active as the backstop for exactly this class of failure per its own
design brief.

## 6. Test evidence

Build: `build/ctl05-fix` (own directory, never `build/core`), llvm-mingw
clang/clang++, Ninja, Release. `ctest --test-dir build/ctl05-fix
--output-on-failure`:

```
100% tests passed, 0 tests failed out of 11
```

All 11 pre-existing suites pass unmodified
(`synccore_tests`, `estimator_tests`, `hypothesis_bank_tests`,
`policy_tests`, `correlate_tests`, `input_level_tests`, `dsp_tests`,
`test_oss_ring`, `lag_analyzer_selftest`, `synccore_abi_c_check`,
`fixture_tests`). `fixture_tests` in particular exercises
`SyncEstimator`/`CorrectionPolicy` directly, bypassing `sc_session`'s
worker/guard entirely (confirmed by reading `test_fixture_suite.cpp`), so
it was never at risk from this change — noted here rather than assumed.

Three new tests added to `core/tests/test_synccore.cpp` (append-only, no
new file, no `CMakeLists.txt` change needed — the existing `synccore_tests`
target already compiles this file):

1. `test_post_seek_single_fix_cannot_reanchor`
2. `test_post_seek_two_agreeing_fixes_reanchor`
3. `test_ft10_cascade_repro_recovers_without_fourth_fix` — the FT10 shape
   reproduction: lock, seek, a fix that tracks the stale anchor (B), then
   two mutually-agreeing fixes from a different timeline (C, D, 40 ms
   apart from each other, matching the investigation's own C→D reading) —
   each individually within `kSelfMatchWindowMs` of the dead-reckoned local
   audible position, the exact false-positive shape §2 traces — followed by
   a third timeline fix (E).

`EventLog` (shared test infrastructure in the same file) gained one
additive field, `last_drift_ppm`, populated from the existing
`SC_EVT_SYNC_ESTIMATE` payload's `drift_ppm` — needed to assert the drift
clamp doesn't stay pegged; no existing field or assertion touched.

### Pre-fix verification (temporary local revert, not just reasoning)

`git stash push -- core/src/synccore.cpp` (test file kept in place),
rebuilt, reran. Result:

```
FAIL .../test_synccore.cpp:775: log.rejects.load() == 2
FAIL .../test_synccore.cpp:792: std::abs(drift_after_e) < 750.0
FAIL .../test_synccore.cpp:793: std::abs(drift_after_e - 800.0) > 300.0
synccore_tests: 3 check(s) FAILED
```

This confirms, against the actual pre-fix code (not just hand-reasoning):
the C/D/E sequence needed a **third** consecutive reject (E itself, not
just C and D) before `kMaxConsecutiveSelfRejects` dropped the anchor — i.e.
a fourth fix would still have been required for recovery, exactly
`docs/ctl05-investigation.md`'s traced mechanism — and drift stayed
**exactly** pegged at 800 ppm through E, since neither C's, D's, nor (in
this run) E's corrective skew ever reached the estimator. The two narrower
guard tests (`test_post_seek_single_fix_cannot_reanchor`,
`test_post_seek_two_agreeing_fixes_reanchor`) passed even against the
pre-fix code — expected and noted, not a defect in those tests: they assert
protection-preservation properties that happened to already hold for their
specific (accept-path-only, or single-candidate) shapes; only the full
cascade test exercises the reject-path bookkeeping gap that is CTL-05's
actual subject, so it's the one that discriminates pre/post-fix.

`git stash pop` restored the fix; the full suite (11/11) was rebuilt and
rerun clean afterward.

### A note on the drift assertion's magnitude

The cascade test's `drift_after_e` check is `< 750.0` (below the 800 ppm
clamp with margin), not tighter. Hand-derivation before running predicted
roughly −46 ppm (assuming the skew Kalman update alone, isolated); the
actual measured value is ≈ −685 ppm. The gap is `predict_to`'s covariance
growth over the 15 s between the last *accepted* fix (B) and E (C and D are
rejected, so `on_fix` — and therefore `predict_to` — never runs for them):
`p01` grows from 0 via `p01_ += dt·p11_` during that gap, coupling the
position update back into the drift state on E's own `on_fix` call in a way
that isolated hand-arithmetic on the skew branch alone misses. The threshold
was set from the actual measured, deterministic value (this test uses only
synthetic `capture_mono_ns` timestamps — no wall-clock race) with margin,
not backed into an arbitrary pass. The load-bearing property — materially
off the clamp, not pegged at it — holds with a wide margin either way.

## 7. Deviations from the task brief

- The brief's PM-quoted directive to touch `policy.cpp` was investigated
  per the brief's own correction and confirmed unnecessary — `policy.cpp`
  is untouched.
- `estimator.h/.cpp` are byte-untouched, as required.
- No C ABI changes: no new events, no header changes, `abi_c_check` passes
  unmodified. All CTL-05 state lives in `sc_session::wk` (internal, not
  part of the public struct either).
- `core/CMakeLists.txt` was **not** touched — new tests were appended to
  the existing `test_synccore.cpp` / `synccore_tests` target instead of a
  new file, since the house convention already supports this (the file's
  own existing self-match tests are the precedent).
- Kotlin change is a single log line's string interpolation extended by
  one field (`skew=${fix.frequencySkew}`) in
  `SessionViewModel.kt`'s existing `fixdbg:` `DebugLog.log` call — nothing
  else in the file touched, per the brief's bound.

## 8. Files touched

| File | +/- |
|---|---|
| `core/src/synccore.cpp` | +114 / -20 |
| `core/tests/test_synccore.cpp` | +300 / -0 (append-only) |
| `android/app/.../ui/session/SessionViewModel.kt` | +9 / -1 (one log line) |
| `docs/ctl05-implementation-review.md` | new (this file) |

New tests: 3. `ctest` result: **11/11 suites, 100% passed** (including the
3 new tests inside `synccore_tests`). No fixture files or fixture
assertions changed.
