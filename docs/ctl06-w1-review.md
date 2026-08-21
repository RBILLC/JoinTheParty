# CTL-06/W1 implementation review — §2.17 diagnostic instrumentation

**Ticket:** GitHub issue #42 (CTL-06/W1). **Spec:** technical-requirements.md
§2.17, citing §2.9/§2.12 for the additive-ABI precedent and §2.15 for
`settled_`. **Status:** implemented, all touched suites green.

---

## What shipped

Two new diagnostic events, appended to the end of `sc_event_type_t`
(`core/include/synccore/synccore.h`), after the existing `SC_EVT_ACTIVE_DUCK`:

1. **`SC_EVT_POLICY_STATE`** / `sc_evt_policy_state_t { bool settled; int32_t
   in_deadband_streak; }`. Emitted at the exact same two call sites as
   `SC_EVT_SYNC_ESTIMATE` (the periodic cadence in `tick()` and the
   accepted-fix path in `kRecognitionFix`) — no new timer, no new config.
   `settled` is `CorrectionPolicy`'s §2.15 (CTL-04) hysteresis state, exposed
   via a new `bool settled() const` accessor. `in_deadband_streak` is the
   §2.7 (CTL-02) persistence ring's current occupancy, exposed via a new
   `int32_t in_deadband_streak() const` accessor — the concrete state behind
   the spec's "`in_deadband_streak`-derived convergence context the policy
   already tracks" (no field of that literal name existed before; the
   persistence ring is the closest existing tracked state and required no
   new bookkeeping).

2. **`SC_EVT_FIX_DIAG`** / `sc_evt_fix_diag_t { int64_t match_offset_ms;
   sc_fix_diag_verdict_t verdict; bool tracks_room; bool tracks_cand; int64_t
   room_anchor_offset_ms; int64_t room_anchor_age_ms; double off; double
   predicted_room; double local_audible_ms; }`, verdict enum
   `sc_fix_diag_verdict_t { SC_FIX_DIAG_ACCEPTED, SC_FIX_DIAG_SELF_HEARING,
   SC_FIX_DIAG_LOW_CONFIDENCE, SC_FIX_DIAG_SETTLING }`. Emitted once per
   submitted recognition fix that reaches the CORE-06 self-match guard's
   arbitration in `synccore.cpp`'s `kRecognitionFix` handler — i.e. every
   `ACCEPTED`, `SELF_HEARING`-rejected, or `LOW_CONFIDENCE`-rejected fix —
   immediately after that arbitration, carrying its own already-computed
   inputs/outputs (`tracks_room`, `tracks_cand`, the live room-anchor
   offset+age, and the self-hearing comparison values `off`/`predicted_room`/
   `local_audible_ms`).

**Deliberate scope decision — `SC_REJECT_SETTLING` has no `SC_EVT_FIX_DIAG`
counterpart.** A fix rejected because `CorrectionPolicy::is_settling()` is
true never reaches guard arbitration at all — that check runs strictly
*before* `off`/`tracks_room`/`tracks_cand`/the self-hearing comparison are
even computed, by design (the settle-window suppression is a distinct, prior
mechanism, not part of the arbitration §2.17 asks to instrument). Its
existing `SC_EVT_FIX_REJECTED`/`SC_REJECT_SETTLING` event is unchanged and is
the complete record of that case. `SC_FIX_DIAG_SETTLING` exists in the
verdict enum only so it's a strict superset of `sc_reject_reason_t` for a
future change; this version never emits it. Documented in both the header
and this doc rather than silently narrowed.

## Design notes — where each event is emitted from and why

- **`emit_policy_state()`** (`core/src/synccore.cpp`) is a sibling of the
  existing `emit_estimate()`, added right next to it, and called from the
  same two sites `emit_estimate()` already is: `tick()`'s periodic cadence
  check and `kRecognitionFix`'s accepted-fix path. Both calls happen
  **before** `wk.policy.on_estimate(...)` runs for that same tick/fix — the
  same pre-decision snapshot timing `SC_EVT_SYNC_ESTIMATE` itself already
  has (it's built from `est`, computed before the policy decides what to do
  with it). This means a `settled_` transition set *inside* a given fix's
  own `on_estimate` call becomes visible only on the *next* policy-state
  emission, not the one tied to the fix that caused it — confirmed by
  `test_policy_state_cadence_and_settled_transition`, which needs a third
  fix to observe the transition for exactly this reason.
- **`SC_EVT_FIX_DIAG`** is dispatched via an `emit_fix_diag` lambda defined
  inside `kRecognitionFix`'s case block, right after `self_hearing_candidate`
  is computed — the same point the existing code already snapshots values
  "as they stood at entry" (see the pre-existing comment on
  `self_hearing_candidate` about not letting CTL-05 post-seek promotion
  retroactively change what a fix was judged against). The lambda captures
  `tracks_room`/`tracks_cand`/`off`/`predicted_room` by reference (all
  already-computed consts) plus a snapshot of the live room anchor
  (`wk.room_anchor_offset_ms`/`age`) and `wk.estimator.local_audible_ms(t)`,
  taken at that same pre-mutation point. It's called from all three
  applicable exit points (self-hearing reject, low-confidence reject,
  accept) — the settling-reject return above it is untouched and never
  calls it.

## Zero-behavior-change verification

- No existing call, branch, or mutation in `synccore.cpp`'s worker loop,
  `kRecognitionFix` arbitration, or `CorrectionPolicy` was reordered,
  removed, or made conditional on new state. Every addition is a new
  `const` read of already-computed values, a new accessor method, or a new
  `dispatch(...)` call appended after an existing one — none of it feeds
  back into `off`, `tracks_room`, `tracks_cand`, `predicted_room`,
  `room_anchor_*`, the estimator, or `CorrectionPolicy`'s decisions.
- `CorrectionPolicy::settled()`/`in_deadband_streak()` are `const` accessors
  with no side effects.
- All 11 existing ctest suites pass unmodified (see below) — house rule
  satisfied.

## Test inventory

**Core (`core/tests/test_synccore.cpp`, public C API + event pump only, per
the CTL-05 test pattern):**

- `test_policy_state_cadence_and_settled_transition` — asserts
  `SC_EVT_POLICY_STATE` fires in lockstep with `SC_EVT_SYNC_ESTIMATE`
  (`policy_state_events == estimates` after every synchronization point) and
  that `settled` transitions `false → true` across a fired correction, its
  ack, and the post-settle verify fix.
- `test_fix_diag_accepted_and_self_hearing` — reuses
  `test_self_hearing_guard`'s exact scenario/offsets; asserts `FIX_DIAG`
  values across the bootstrap accept (no anchor yet), the confirming accept
  (`tracks_room=true`, anchor = the bootstrap's), and the self-hearing
  rejection (`tracks_room=false`, `tracks_cand=false`, anchor/age/off/
  predicted_room/local_audible_ms all matching the investigation's own
  hand-derived arithmetic).
- `test_fix_diag_post_seek_corroboration` — reuses
  `test_post_seek_two_agreeing_fixes_reanchor`'s exact scenario; asserts
  `FIX_DIAG` across the seek, the two corroborating post-seek fixes (anchor
  reported pre-promotion each time), the fix that tracks the newly-promoted
  anchor, and the final self-hearing rejection against that new anchor.

**ABI (`core/tests/abi_c_check.c`):** `event_is_known` gained
`SC_EVT_POLICY_STATE`/`SC_EVT_FIX_DIAG` cases (exhaustive `-Wswitch`
coverage); a new `fix_diag_verdict_is_known` gives the same coverage for
`sc_fix_diag_verdict_t`; both new structs are constructed and field-checked
as valid C99 aggregates, mirroring the existing `sc_evt_active_probe_t`/
`sc_evt_active_duck_t` pattern.

**Android (JVM, `SessionViewModelTest.kt`):**

- `policyStateLogsOnlyOnSettledTransition` — asserts the `policy: settled →`
  line appears on the first observation and again only when the value
  actually flips, not on a same-value repeat.
- `fixDiagLogsOneLineWithEventValues` / `fixDiagAcceptedVerdictRenders` —
  assert the `fixdiag:` line's every field against the emitted event's
  values (`SELF_HEARING` and `ACCEPTED` verdicts respectively).

## Files touched (rough line counts, `git diff --stat`)

| File | +/- |
|---|---|
| `core/include/synccore/synccore.h` | +86/-0 |
| `core/src/policy/policy.h` | +11/-0 |
| `core/src/synccore.cpp` | +56/-0 |
| `core/tests/abi_c_check.c` | +44/-0 |
| `core/tests/test_synccore.cpp` | +290/-0 |
| `android/app/src/main/cpp/synccore_jni.cpp` | +51/-2 |
| `android/app/src/main/java/.../core/SyncCore.kt` | +90/-0 |
| `android/app/src/main/java/.../ui/session/SessionViewModel.kt` | +40/-0 |
| `android/app/src/test/java/.../ui/session/SessionViewModelTest.kt` | +90/-0 |

## New declared names

- `SC_EVT_POLICY_STATE`, `sc_evt_policy_state_t { settled, in_deadband_streak }`
- `SC_EVT_FIX_DIAG`, `sc_evt_fix_diag_t { match_offset_ms, verdict, tracks_room,
  tracks_cand, room_anchor_offset_ms, room_anchor_age_ms, off, predicted_room,
  local_audible_ms }`
- `sc_fix_diag_verdict_t { SC_FIX_DIAG_ACCEPTED, SC_FIX_DIAG_SELF_HEARING,
  SC_FIX_DIAG_LOW_CONFIDENCE, SC_FIX_DIAG_SETTLING }`
- `CorrectionPolicy::settled() const`, `CorrectionPolicy::in_deadband_streak() const`
- JNI: `BridgeHandle::on_policy_state`/`on_fix_diag` method IDs; Kotlin
  callback methods `onPolicyStateEvent(Boolean, Int)` / `onFixDiagEvent(Long,
  Int, Boolean, Boolean, Long, Long, Double, Double, Double)`
- Kotlin: `SyncCore.Event.PolicyState`, `SyncCore.Event.FixDiag`,
  `SyncCore.FixDiagVerdict`
- `SessionViewModel.onPolicyState`/`onFixDiag`, new log-line formats
  `policy: settled → true|false` (transition-only) and `fixdiag: off=<ms>
  verdict=<...> trackR=<0|1> trackC=<0|1> anchor=<ms>@<age_ms> pred=<ms>
  localAud=<ms>` (per fix)

## Deviation from the JNI forwarding mechanics (justified)

The existing JNI bridge forwards every event through one packed callback,
`onNativeEvent(type: Int, d0: Double, d1: Double, d2: Double, i0: Int, i1:
Int, l0: Long)` — 6 primitive slots. `sc_evt_fix_diag_t` alone has 9 fields.
Rather than lossily repacking (e.g. dropping a field, or overloading `l0` to
carry two different int64s), `event_trampoline` dispatches
`SC_EVT_POLICY_STATE`/`SC_EVT_FIX_DIAG` to two **new, dedicated** callback
methods (`onPolicyStateEvent(Z I)V`, `onFixDiagEvent(J I Z Z J J D D D)V`)
before it ever reaches the generic packed switch — every existing event's
dispatch path is untouched. This is shell-internal JNI/Kotlin plumbing, not
the C ABI the ticket's "additive ABI only" rule protects (that rule is about
`synccore.h`, which stays enum-append/new-struct-only); it does not require
justification under that rule, but is called out here as a deviation from
"reuse the existing forwarding shape" a reviewer might otherwise expect.

## Commands run and results

```
# Core build (build/ctl06-w1, per this ticket's dedicated build dir)
C:/Users/RBILLC/tools/cmake/bin/cmake.exe -S core -B build/ctl06-w1 -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_MAKE_PROGRAM=C:/Users/RBILLC/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe \
  -DCMAKE_C_COMPILER=C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin/clang.exe \
  -DCMAKE_CXX_COMPILER=C:/Users/RBILLC/tools/llvm-mingw-20260616-ucrt-x86_64/bin/clang++.exe
C:/Users/RBILLC/tools/cmake/bin/cmake.exe --build build/ctl06-w1
# -> 13/13 targets built clean.

# ctest (PATH prepended with the llvm-mingw bin dir per the DLL rule; run via
# PowerShell — the Bash tool's exported PATH did not propagate to the child
# process's DLL search on this box, PowerShell's did)
cd build/ctl06-w1; ctest --output-on-failure
# -> 100% tests passed, 0 tests failed out of 11 (34.6s total)
#    synccore_tests, estimator_tests, hypothesis_bank_tests, policy_tests,
#    correlate_tests, input_level_tests, dsp_tests, test_oss_ring,
#    lag_analyzer_selftest, synccore_abi_c_check, fixture_tests — all green.
#    synccore_tests itself printed "synccore_tests: all tests passed" only.

# Android JVM suite
cd android; ./gradlew.bat :app:testDebugUnitTest            # BUILD SUCCESSFUL
./gradlew.bat :app:testDebugUnitTest --rerun-tasks           # BUILD SUCCESSFUL
# Authoritative count via JUnit XML sum (build-environment.md's documented
# trap: gradle's own summary can under-report on a warm run):
#   files=10 tests=167 failures=0 errors=0 skipped=0
# New tests confirmed present and passing in
# TEST-com.jointheparty.app.ui.session.SessionViewModelTest.xml:
#   policyStateLogsOnlyOnSettledTransition, fixDiagLogsOneLineWithEventValues,
#   fixDiagAcceptedVerdictRenders

# Native JNI compiles for all ABIs (assembleDebug triggers the NDK/CMake
# build synccore_jni.cpp is part of)
./gradlew.bat :app:assembleDebug                             # BUILD SUCCESSFUL
# libsynccore_jni.so under merged_native_libs/debug/.../lib/{arm64-v8a,
# armeabi-v7a,x86_64}/ confirmed newer than synccore_jni.cpp's source mtime.
```

These results will be independently re-verified with `--rerun-tasks`; the
counts and pass/fail states above are exactly what was observed on this run,
not a projection.
